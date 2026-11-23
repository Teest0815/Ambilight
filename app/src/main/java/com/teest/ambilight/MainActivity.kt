package com.teest.ambilight

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private lateinit var testButton: Button
    private lateinit var prefs: SharedPreferences
    private var testJob: Job? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updatePreviewLayout()
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
            } else {
                Toast.makeText(this, "Ohne Berechtigung zur Bildschirmaufnahme geht es nicht", Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        statusView = findViewById(R.id.status)
        toggleButton = findViewById(R.id.btnToggle)
        testButton = findViewById(R.id.btnTest)

        updatePreviewLayout()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        toggleButton.setOnClickListener {
            if (AmbilightState.running.value) stopCapture() else requestCapture()
        }
        testButton.setOnClickListener { runLayoutTest() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AmbilightState.running.collect { running ->
                        toggleButton.text = if (running) "Ambilight stoppen" else "Ambilight starten"
                    }
                }
                launch { AmbilightState.status.collect { statusView.text = it } }
                launch { AmbilightState.colors.collect { preview.setColors(it) } }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun handleAutostart(intent: Intent?) {
        val i = intent ?: return
        if (i.getBooleanExtra(EXTRA_AUTOSTART, false) && !AmbilightState.running.value) {
            i.removeExtra(EXTRA_AUTOSTART)
            requestCapture()
        }
    }

    private fun updatePreviewLayout() {
        preview.setLayoutModel(LedLayout(Config.load(prefs).layout))
    }

    private fun requestCapture() {
        if (Config.load(prefs).host.isEmpty()) {
            Toast.makeText(this, "Bitte zuerst die WLED-Adresse eingeben", Toast.LENGTH_LONG).show()
            return
        }
        testJob?.cancel()
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        stopService(Intent(this, CaptureService::class.java))
    }

    /** Lights the sides in different colours and runs a white dot along the strip, to check order and direction. */
    private fun runLayoutTest() {
        if (AmbilightState.running.value) {
            Toast.makeText(this, "Bitte zuerst das Ambilight stoppen", Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = Config.load(prefs)
        if (cfg.host.isEmpty()) {
            Toast.makeText(this, "Bitte zuerst die WLED-Adresse eingeben", Toast.LENGTH_LONG).show()
            return
        }
        testJob?.cancel()
        testJob = lifecycleScope.launch(Dispatchers.IO) {
            val layout = LedLayout(cfg.layout)
            if (layout.count == 0) return@launch
            val sender = WledSender()
            val out = ByteArray(layout.totalLeds * 3)
            val colors = IntArray(layout.count)
            try {
                WledSender.postState(cfg.host, WledSender.onJson(true))
                // Side colours: top red, right green, bottom blue, left yellow.
                val base = IntArray(layout.count) { k ->
                    val r = layout.regions[k]
                    when {
                        r.dotY < 0f -> Color.rgb(100, 0, 0)
                        r.dotX > 1f -> Color.rgb(0, 100, 0)
                        r.dotY > 1f -> Color.rgb(0, 0, 100)
                        else -> Color.rgb(100, 100, 0)
                    }
                }
                val stepDelay = max(15L, 4000L / layout.count)
                for (head in 0 until layout.count) {
                    for (k in 0 until layout.count) {
                        colors[k] = when {
                            k == head -> Color.WHITE
                            k < head && head - k <= 3 -> Color.rgb(200, 200, 200)
                            else -> base[k]
                        }
                    }
                    writeColors(out, colors, cfg.layout.skipLeading)
                    sender.send(cfg, out)
                    AmbilightState.colors.value = colors.copyOf()
                    delay(stepDelay)
                }
                for (k in 0 until layout.count) colors[k] = base[k]
                writeColors(out, colors, cfg.layout.skipLeading)
                repeat(30) {
                    sender.send(cfg, out)
                    delay(50)
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    java.util.Arrays.fill(out, 0.toByte())
                    repeat(2) { sender.send(cfg, out) }
                    sender.close()
                    AmbilightState.colors.value = null
                }
            }
        }
    }

    private fun writeColors(out: ByteArray, colors: IntArray, startPixel: Int) {
        java.util.Arrays.fill(out, 0.toByte())
        for (k in colors.indices) {
            val d = (startPixel + k) * 3
            if (d + 2 >= out.size) break
            out[d] = Color.red(colors[k]).toByte()
            out[d + 1] = Color.green(colors[k]).toByte()
            out[d + 2] = Color.blue(colors[k]).toByte()
        }
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}
