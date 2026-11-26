package com.teest.ambilight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Foreground service: captures the screen at low resolution, derives LED colours and streams them to WLED.
 */
class CaptureService : Service() {

    private lateinit var prefs: SharedPreferences

    @Volatile
    private var config: Config = Config.load(EmptyPrefs)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
        config = Config.load(p)
    }

    /** True while the screen is off (TV in standby) or a screensaver runs. */
    @Volatile
    private var paused = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> paused = true
                Intent.ACTION_SCREEN_ON -> paused = false
                Intent.ACTION_DREAMING_STARTED -> if (config.pauseOnDream) paused = true
                Intent.ACTION_DREAMING_STOPPED -> paused = false
            }
        }
    }
    private var receiverRegistered = false

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var readerWidth = 0
    private var dispW = 0
    private var dispH = 0
    private var dpi = 0

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loopJob: Job? = null

    // WLED commands run one after the other in a fixed order, so e.g. "hand over" can never overtake "take back".
    private val postExecutor = Executors.newSingleThreadExecutor()

    private fun postAsync(host: String, json: String) {
        try {
            postExecutor.execute { WledSender.postState(host, json) }
        } catch (e: RejectedExecutionException) {
            // service is shutting down
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        config = Config.load(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        if (prefs.getBoolean(RemoteActivity.PREF_AUTO, false)) RemoteServer.acquire(this, "service")

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loopJob?.isActive == true) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        try {
            startCapture(resultCode, data)
        } catch (e: Exception) {
            AmbilightState.status.value = "Fehler beim Start: ${e.message}"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = "ambilight"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Ambilight", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Ambilight läuft")
            .setContentText("Der Bildschirm wird für WLED aufgenommen")
            .setContentIntent(openIntent)
            .addAction(0, "Stoppen", stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Keine Berechtigung für die Bildschirmaufnahme")
        // Android 14 requires a callback before the virtual display is created.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        dispW = dm.widthPixels.coerceAtLeast(1)
        dispH = dm.heightPixels.coerceAtLeast(1)
        dpi = dm.densityDpi

        paused = false
        AmbilightState.running.value = true
        AmbilightState.status.value = "Warte auf Bild …"
        loopJob = scope.launch {
            try {
                runLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AmbilightState.status.value = "Fehler: ${e.message}"
                stopSelf()
            }
        }
    }

    /** (Re)creates the image reader if the capture resolution changed. Must run on the loop thread. */
    private fun ensureReader(width: Int) {
        val w = width.coerceAtLeast(32)
        if (reader != null && w == readerWidth) return
        val h = (w.toLong() * dispH / dispW).toInt().coerceAtLeast(18)
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection!!.createVirtualDisplay(
                "Ambilight", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, null,
            )
        } else {
            vd.resize(w, h, dpi)
            vd.surface = newReader.surface
        }
        reader?.close()
        reader = newReader
        readerWidth = w
    }

    private suspend fun runLoop() {
        val job = coroutineContext[Job]!!
        val sender = WledSender()
        val engine = EffectEngine()
        val bars = BarTracker()
        val sampler = LedSampler()

        var layout = LedLayout(config.layout)
        var processor = ColorProcessor(layout.count)
        var target = FloatArray(layout.count * 3)
        var lastGood = FloatArray(layout.count * 3)
        var out = ByteArray(layout.totalLeds * 3)

        var frame = ByteArray(0)
        var fw = 0
        var fh = 0
        var fStride = 0
        var haveFrame = false

        val measured = IntArray(4)
        var lastBarCheck = 0L
        var frameBlack = false
        var blackSince = 0L
        var inBlack = false
        var handover = false
        var snapNext = true
        var lastStatus = 0L
        var lastPreview = 0L
        val startTime = SystemClock.elapsedRealtime()
        var last = startTime
        var cfg = config

        // adaptive performance
        var effFps = cfg.fps
        var effWidth = cfg.captureWidth
        var seenFps = cfg.fps
        var seenWidth = cfg.captureWidth
        var busyEma = 0f
        var lastAdapt = startTime

        // standby and reconnect
        var wasPaused = false
        var prevError: String? = null

        // two alternating buffers for the preview, so no new array is allocated for every update
        var previewA = IntArray(0)
        var previewB = IntArray(0)
        var previewFlip = false

        // transitions: what is shown on the strip right now, and the fade that leads there
        var shown = FloatArray(layout.count * 3)
        var crossfade = Crossfade(layout.count)
        var mode = MODE_AMBIENT
        var pendingHandover = false // fading to black before WLED takes over
        var pendingFadeMs = -1 // fade to start with the first frame after standby
        var standbyDone = false

        if (cfg.wledOnStart) {
            val host = cfg.host
            postAsync(host, WledSender.onJson(true))
        }

        try {
            while (job.isActive) {
                val loopStart = SystemClock.elapsedRealtime()
                cfg = config
                val dt = (loopStart - last).coerceIn(1L, 250L).toFloat()
                last = loopStart

                if (cfg.layout != layout.spec) {
                    layout = LedLayout(cfg.layout)
                    processor = ColorProcessor(layout.count)
                    target = FloatArray(layout.count * 3)
                    lastGood = FloatArray(layout.count * 3)
                    out = ByteArray(layout.totalLeds * 3)
                    shown = FloatArray(layout.count * 3)
                    crossfade = Crossfade(layout.count)
                    bars.reset()
                    snapNext = true
                }

                // ---- standby: no capture, the chosen standby behaviour runs instead ----
                if (paused) {
                    if (!wasPaused) {
                        wasPaused = true
                        standbyDone = false
                        pendingHandover = false
                        crossfade.begin(shown, loopStart, cfg.standbyFadeMs)
                        snapNext = true
                        mode = MODE_STANDBY
                        if (cfg.standbyAction == StandbyAction.STATIC || cfg.standbyAction == StandbyAction.EFFECT) {
                            val host = cfg.host
                            postAsync(host, WledSender.onJson(true))
                        }
                        AmbilightState.colors.value = null
                        AmbilightState.status.value = "Pausiert (Standby): " + when (cfg.standbyAction) {
                            StandbyAction.OFF -> "WLED aus"
                            StandbyAction.WLED -> "WLED-Effekt"
                            StandbyAction.STATIC -> "feste Farbe"
                            StandbyAction.EFFECT -> "App-Effekt"
                        }
                    }
                    try {
                        reader?.acquireLatestImage()?.close() // do not keep stale frames
                    } catch (e: Exception) {
                        // ignore
                    }

                    // Source colours of the standby behaviour. OFF and WLED fade down to black first.
                    when (cfg.standbyAction) {
                        StandbyAction.OFF, StandbyAction.WLED -> target.fill(0f)
                        StandbyAction.STATIC -> {
                            val f = cfg.standbyBrightness / 100f
                            val r = Color.red(cfg.standbyColor) * f
                            val g = Color.green(cfg.standbyColor) * f
                            val bl = Color.blue(cfg.standbyColor) * f
                            for (i in 0 until layout.count) {
                                target[i * 3] = r
                                target[i * 3 + 1] = g
                                target[i * 3 + 2] = bl
                            }
                        }
                        StandbyAction.EFFECT -> engine.render(
                            cfg.standbyEffect,
                            loopStart - startTime,
                            dt,
                            layout.count,
                            cfg.standbyColor,
                            cfg.standbyEffectSpeed,
                            cfg.standbyBrightness / 100f,
                            target,
                        )
                    }
                    val effect = cfg.standbyAction == StandbyAction.EFFECT
                    processor.process(target, cfg, dt, if (effect) min(cfg.smoothingMs, 120).toFloat() else 0f, snapNext)
                    snapNext = false
                    crossfade.apply(processor.cur, loopStart, shown)

                    val dark = cfg.standbyAction == StandbyAction.OFF || cfg.standbyAction == StandbyAction.WLED
                    if (dark && !standbyDone && !crossfade.active) {
                        standbyDone = true
                        shown.fill(0f)
                        val host = cfg.host
                        if (cfg.standbyAction == StandbyAction.OFF) {
                            java.util.Arrays.fill(out, 0.toByte())
                            repeat(2) { sender.send(cfg, out) }
                            postAsync(host, WledSender.onJson(false))
                        } else {
                            // WLED shows its own preset/effect as soon as our data stops.
                            val json = WledSender.handoverJson(
                                cfg.standbyPreset, cfg.standbyFx, cfg.standbySx, cfg.standbyIx, cfg.standbyPal,
                                cfg.standbyFadeMs,
                            )
                            postAsync(host, json)
                        }
                    }
                    if (!(dark && standbyDone)) {
                        java.util.Arrays.fill(out, 0.toByte())
                        processor.writeTo(shown, out, cfg.layout.skipLeading)
                        sender.send(cfg, out) // repeated, so WLED stays in live mode
                    }
                    val busy = crossfade.active || effect
                    delay(if (busy) maxOf(1L, 33L - (SystemClock.elapsedRealtime() - loopStart)) else 400L)
                    continue
                }
                if (wasPaused) {
                    wasPaused = false
                    haveFrame = false
                    frameBlack = false
                    blackSince = 0L
                    inBlack = false
                    handover = false
                    snapNext = true
                    mode = MODE_AMBIENT
                    pendingFadeMs = cfg.standbyFadeMs
                    bars.reset()
                    last = loopStart
                    // switches WLED on and lifts a possible live override of an earlier hand-over
                    postAsync(cfg.host, WledSender.onJson(true))
                    AmbilightState.status.value = "Warte auf Bild …"
                }

                // ---- adaptive performance: user settings are the upper limit ----
                if (cfg.fps != seenFps || cfg.captureWidth != seenWidth) {
                    seenFps = cfg.fps
                    seenWidth = cfg.captureWidth
                    effFps = cfg.fps
                    effWidth = cfg.captureWidth
                    busyEma = 0f
                }
                if (!cfg.adaptive) {
                    effFps = cfg.fps
                    effWidth = cfg.captureWidth
                }
                ensureReader(effWidth)

                // ---- grab the newest frame, if there is one ----
                var newFrame = false
                val img = try {
                    reader?.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (img != null) {
                    try {
                        val plane = img.planes[0]
                        val buffer = plane.buffer
                        val stride = plane.rowStride
                        val need = stride * img.height
                        if (frame.size < need) frame = ByteArray(need)
                        val len = min(buffer.remaining(), frame.size)
                        buffer.get(frame, 0, len)
                        fw = img.width
                        fh = img.height
                        fStride = stride
                        newFrame = len >= stride * (fh - 1) + fw * 4
                    } finally {
                        img.close()
                    }
                    if (newFrame) haveFrame = true
                }

                // ---- analyse a new frame ----
                if (newFrame) {
                    val mean = FrameAnalyzer.meanLuma(frame, fw, fh, fStride)
                    val thr = cfg.blackThreshold.toFloat()
                    frameBlack = if (inBlack) mean < thr * 1.6f + 1f else mean < thr

                    if (cfg.barDetect) {
                        if (!frameBlack && loopStart - lastBarCheck >= 100) {
                            lastBarCheck = loopStart
                            FrameAnalyzer.detectBars(
                                frame, fw, fh, fStride, cfg.barThreshold, cfg.barMaxPercent / 100f, measured,
                            )
                            bars.update(measured, loopStart, cfg.barDelayMs.toLong(), cfg.barSymmetric)
                        }
                    } else {
                        bars.reset()
                    }
                }

                // ---- black screen state machine (runs every tick, also for static pictures) ----
                val wasBlack = inBlack
                if (haveFrame && frameBlack) {
                    if (blackSince == 0L) blackSince = loopStart
                    if (!inBlack && loopStart - blackSince >= cfg.blackDelayMs) inBlack = true
                } else {
                    blackSince = 0L
                    inBlack = false
                }
                // ---- every change between ambient light and black screen is faded ----
                val modeNow = if (inBlack) MODE_BLACK else MODE_AMBIENT
                if (haveFrame && modeNow != mode) {
                    mode = modeNow
                    crossfade.begin(shown, loopStart, cfg.blackFadeMs)
                    snapNext = true
                }
                if (haveFrame && pendingFadeMs >= 0) {
                    crossfade.begin(shown, loopStart, pendingFadeMs)
                    pendingFadeMs = -1
                    snapNext = true
                }

                // WLED takes over only after the picture faded to black, so the effect does not pop in.
                if (inBlack && !wasBlack && cfg.blackAction == BlackAction.WLED) {
                    if (cfg.blackFadeMs > 0) {
                        pendingHandover = true
                    } else {
                        handover = true
                        shown.fill(0f)
                        val host = cfg.host
                        val json = WledSender.handoverJson(cfg)
                        postAsync(host, json)
                    }
                }
                if (!inBlack) pendingHandover = false
                if (pendingHandover && !crossfade.active) {
                    pendingHandover = false
                    handover = true
                    shown.fill(0f)
                    val host = cfg.host
                    val json = WledSender.handoverJson(cfg)
                    postAsync(host, json)
                }
                if (!inBlack && handover) {
                    handover = false
                    snapNext = true
                    // Without this WLED keeps ignoring our packets and stays on its effect.
                    postAsync(cfg.host, WledSender.onJson(true))
                }

                // ---- decide the target colours ----
                if (haveFrame) {
                    if (!inBlack) {
                        if (newFrame) {
                            val b = bars.applied
                            var x0 = b[2]
                            var x1 = fw - b[3]
                            var y0 = b[0]
                            var y1 = fh - b[1]
                            if (x1 - x0 < fw / 5 || y1 - y0 < fh / 5) {
                                x0 = 0; x1 = fw; y0 = 0; y1 = fh
                            }
                            sampler.update(layout, x0, y0, x1, y1)
                            sampler.colors(frame, fStride, cfg.vibrant, target)
                            if (!frameBlack) target.copyInto(lastGood)
                        }
                    } else {
                        when (cfg.blackAction) {
                            BlackAction.OFF -> target.fill(0f)
                            BlackAction.HOLD -> lastGood.copyInto(target)
                            BlackAction.STATIC -> {
                                val f = cfg.blackBrightness / 100f
                                val r = Color.red(cfg.blackColor) * f
                                val g = Color.green(cfg.blackColor) * f
                                val bl = Color.blue(cfg.blackColor) * f
                                for (i in 0 until layout.count) {
                                    target[i * 3] = r
                                    target[i * 3 + 1] = g
                                    target[i * 3 + 2] = bl
                                }
                            }
                            BlackAction.EFFECT -> engine.render(
                                cfg.blackEffect,
                                loopStart - startTime,
                                dt,
                                layout.count,
                                cfg.blackColor,
                                cfg.effectSpeed,
                                cfg.blackBrightness / 100f,
                                target,
                            )
                            BlackAction.WLED -> if (pendingHandover) target.fill(0f)
                        }
                    }
                }

                // ---- smooth and send ----
                if (haveFrame && !handover) {
                    val tau = if (inBlack && cfg.blackAction == BlackAction.EFFECT) {
                        min(cfg.smoothingMs, 120).toFloat()
                    } else {
                        cfg.smoothingMs.toFloat()
                    }
                    processor.process(target, cfg, dt, tau, snapNext)
                    snapNext = false
                    crossfade.apply(processor.cur, loopStart, shown)
                    java.util.Arrays.fill(out, 0.toByte())
                    processor.writeTo(shown, out, cfg.layout.skipLeading)
                    sender.send(cfg, out)

                    // WLED was unreachable and is back: make sure it is switched on again.
                    val err = sender.error
                    if (prevError != null && err == null && cfg.wledOnStart) {
                        val host = cfg.host
                        postAsync(host, WledSender.onJson(true))
                    }
                    prevError = err
                }

                // ---- tell the UI (preview colours only while the screen is visible) ----
                if (AmbilightState.previewVisible && loopStart - lastPreview >= 80) {
                    lastPreview = loopStart
                    val n = layout.count
                    if (previewA.size != n) {
                        previewA = IntArray(n)
                        previewB = IntArray(n)
                    }
                    val buf = if (previewFlip) previewA else previewB
                    previewFlip = !previewFlip
                    val cur = shown
                    for (i in 0 until n) {
                        buf[i] = Color.rgb(
                            cur[i * 3].toInt().coerceIn(0, 255),
                            cur[i * 3 + 1].toInt().coerceIn(0, 255),
                            cur[i * 3 + 2].toInt().coerceIn(0, 255),
                        )
                    }
                    AmbilightState.colors.value = buf
                }
                if (loopStart - lastStatus >= 500) {
                    lastStatus = loopStart
                    AmbilightState.status.value = buildStatus(
                        cfg, effFps, effWidth, haveFrame, inBlack, handover, bars.applied, sender.error,
                    )
                }

                // ---- adapt frame rate / resolution if this device cannot keep up ----
                val spent = SystemClock.elapsedRealtime() - loopStart
                busyEma = if (busyEma == 0f) spent.toFloat() else busyEma * 0.9f + spent * 0.1f
                if (cfg.adaptive && loopStart - lastAdapt >= 3000) {
                    lastAdapt = loopStart
                    val budget = 1000f / effFps
                    if (busyEma > budget * 0.85f) {
                        if (effFps > MIN_ADAPTIVE_FPS) {
                            effFps = maxOf(MIN_ADAPTIVE_FPS, (effFps * 0.8f).toInt())
                        } else {
                            val smaller = WIDTH_STEPS.lastOrNull { it < effWidth }
                            if (smaller != null) effWidth = smaller
                        }
                        busyEma = 0f
                    } else if (effFps < cfg.fps && busyEma < 1000f / (effFps + 5) * 0.6f) {
                        effFps = min(cfg.fps, effFps + 5)
                    }
                }

                delay(maxOf(1L, 1000L / effFps - spent))
            }
        } finally {
            // Leave the strip dark (unless WLED is showing its own effect right now).
            try {
                if (!handover) {
                    java.util.Arrays.fill(out, 0.toByte())
                    repeat(2) { sender.send(cfg, out) }
                }
                if (cfg.wledOffStop) {
                    val host = cfg.host
                    postAsync(host, WledSender.onJson(false))
                }
            } catch (e: Exception) {
                // ignore, we are shutting down
            }
            sender.close()
            release()
            AmbilightState.running.value = false
            AmbilightState.colors.value = null
            AmbilightState.status.value = "Gestoppt"
        }
    }

    private fun buildStatus(
        cfg: Config,
        effFps: Int,
        effWidth: Int,
        haveFrame: Boolean,
        inBlack: Boolean,
        handover: Boolean,
        bars: IntArray,
        error: String?,
    ): String {
        if (error != null) return "Läuft, aber: $error"
        if (!haveFrame) return "Warte auf Bild …"
        val sb = StringBuilder("Läuft · $effFps FPS")
        if (effFps != cfg.fps || effWidth != cfg.captureWidth) {
            sb.append(" (automatisch angepasst, ${effWidth} px)")
        }
        if (inBlack) {
            sb.append(" · Schwarzes Bild: ")
            sb.append(
                when (cfg.blackAction) {
                    BlackAction.OFF -> "LEDs aus"
                    BlackAction.HOLD -> "Farben gehalten"
                    BlackAction.STATIC -> "feste Farbe"
                    BlackAction.EFFECT -> "App-Effekt"
                    BlackAction.WLED -> if (handover) "WLED-Effekt" else "WLED"
                },
            )
        } else if (cfg.barDetect) {
            val v = bars[0] + bars[1]
            val h = bars[2] + bars[3]
            sb.append(if (v == 0 && h == 0) " · keine Balken" else " · Balken erkannt (oben/unten ${bars[0]}/${bars[1]}, links/rechts ${bars[2]}/${bars[3]} px)")
        }
        return sb.toString()
    }

    @Synchronized
    private fun release() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null
        readerWidth = 0
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
    }

    override fun onDestroy() {
        RemoteServer.release("service")
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
            }
            receiverRegistered = false
        }
        val job = loopJob
        job?.cancel()
        if (job != null) {
            // The loop cleans up after itself; give it a moment.
            runBlocking { withTimeoutOrNull(1000) { job.join() } }
        }
        release()
        postExecutor.shutdown() // queued WLED commands still finish
        scope.cancel()
        dispatcher.close()
        AmbilightState.running.value = false
        AmbilightState.colors.value = null
        if (!AmbilightState.status.value.startsWith("Fehler")) {
            AmbilightState.status.value = "Gestoppt"
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.teest.ambilight.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1
        private const val MIN_ADAPTIVE_FPS = 12
        private const val MODE_AMBIENT = 0
        private const val MODE_BLACK = 1
        private const val MODE_STANDBY = 2
        private val WIDTH_STEPS = intArrayOf(80, 120, 160, 240, 320)
    }
}

/** Read-only, always empty preferences: makes Config.load return its built-in defaults. */
private object EmptyPrefs : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = false
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
