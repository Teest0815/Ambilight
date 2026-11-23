package com.teest.ambilight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Foreground service: captures the screen at low resolution, derives LED colours and streams them to WLED.
 */
class CaptureService : Service() {

    private lateinit var prefs: SharedPreferences

    @Volatile
    private var config: Config = Config.load(emptyPrefsFallback())

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
        config = Config.load(p)
    }

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        config = Config.load(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
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
    private fun ensureReader(cfg: Config) {
        val w = cfg.captureWidth.coerceAtLeast(32)
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

        if (cfg.wledOnStart) {
            val host = cfg.host
            thread { WledSender.postState(host, WledSender.onJson(true)) }
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
                    bars.reset()
                    snapNext = true
                }

                ensureReader(cfg)

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
                if (inBlack && !wasBlack && cfg.blackAction == BlackAction.WLED) {
                    handover = true
                    val host = cfg.host
                    val json = WledSender.handoverJson(cfg)
                    thread { WledSender.postState(host, json) }
                }
                if (!inBlack && handover) {
                    handover = false
                    snapNext = true
                }
                if (wasBlack && !inBlack) snapNext = true

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
                            FrameAnalyzer.ledColors(
                                frame, fStride, x0, y0, x1, y1, layout, cfg.vibrant, target,
                            )
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
                            BlackAction.WLED -> Unit
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
                    java.util.Arrays.fill(out, 0.toByte())
                    processor.writeTo(out, cfg.layout.skipLeading)
                    sender.send(cfg, out)
                }

                // ---- tell the UI ----
                if (loopStart - lastPreview >= 80) {
                    lastPreview = loopStart
                    val cur = processor.cur
                    AmbilightState.colors.value = IntArray(layout.count) { i ->
                        Color.rgb(
                            cur[i * 3].toInt().coerceIn(0, 255),
                            cur[i * 3 + 1].toInt().coerceIn(0, 255),
                            cur[i * 3 + 2].toInt().coerceIn(0, 255),
                        )
                    }
                }
                if (loopStart - lastStatus >= 500) {
                    lastStatus = loopStart
                    AmbilightState.status.value = buildStatus(
                        cfg, haveFrame, inBlack, handover, bars.applied, sender.error,
                    )
                }

                val spent = SystemClock.elapsedRealtime() - loopStart
                delay(maxOf(1L, 1000L / cfg.fps - spent))
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
                    thread { WledSender.postState(host, WledSender.onJson(false)) }
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
        haveFrame: Boolean,
        inBlack: Boolean,
        handover: Boolean,
        bars: IntArray,
        error: String?,
    ): String {
        if (error != null) return "Läuft, aber: $error"
        if (!haveFrame) return "Warte auf Bild …"
        val sb = StringBuilder("Läuft · ${cfg.fps} FPS")
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
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        val job = loopJob
        job?.cancel()
        if (job != null) {
            // The loop cleans up after itself; give it a moment.
            runBlocking { withTimeoutOrNull(1000) { job.join() } }
        }
        release()
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

        /** Placeholder until onCreate runs: Config needs preferences, so use plain defaults. */
        private fun emptyPrefsFallback(): SharedPreferences = EmptyPrefs
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
