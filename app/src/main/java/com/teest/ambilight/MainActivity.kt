package com.teest.ambilight

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.graphics.drawable.ColorDrawable
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
        for (page in PAGES) PreferenceManager.setDefaultValues(this, page.xml, false)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        setContentView(R.layout.activity_main)
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#121212")))

        preview = findViewById(R.id.preview)
        statusView = findViewById(R.id.status)
        toggleButton = findViewById(R.id.btnToggle)
        testButton = findViewById(R.id.btnTest)

        updatePreviewLayout()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        toggleButton.setOnClickListener {
            if (AmbilightState.running.value) stopCapture() else requestCapture()
        }
        testButton.setOnClickListener {
            val running = testJob
            if (running != null && running.isActive) running.cancel() else showTestDialog()
        }
        buildTiles()

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

    override fun onStart() {
        super.onStart()
        AmbilightState.previewVisible = true
        updatePreviewLayout() // settings pages may have changed the layout
    }

    override fun onStop() {
        AmbilightState.previewVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    /** One tile per settings page; each opens its own screen. */
    private fun buildTiles() {
        val grid = findViewById<ViewGroup>(R.id.tiles)
        val subtitleColor = Color.parseColor("#B5B5B5")
        for (page in PAGES) {
            val tile = layoutInflater.inflate(R.layout.item_tile, grid, false) as Button
            val text = SpannableStringBuilder()
            text.append(page.title)
            text.setSpan(StyleSpan(Typeface.BOLD), 0, page.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(1.25f), 0, page.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("\n")
            val start = text.length
            text.append(page.subtitle)
            text.setSpan(ForegroundColorSpan(subtitleColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.85f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tile.text = text
            tile.setOnClickListener {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_TITLE, page.title)
                        .putExtra(SettingsActivity.EXTRA_XML, page.xml),
                )
            }
            grid.addView(tile)
        }
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
            Toast.makeText(this, "Bitte zuerst die WLED-Adresse eingeben oder unter Einstellungen „WLED automatisch suchen“ drücken", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Bitte zuerst die WLED-Adresse eingeben oder unter Einstellungen „WLED automatisch suchen“ drücken", Toast.LENGTH_LONG).show()
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
        testJob?.let { watchTest(it) }
    }

    private fun showTestDialog() {
        val items = arrayOf(
            "Layout (laufender Punkt, Seitenfarben)",
            "Dimmen (Weiß, Rot, Grün, Blau)",
            "Farbsprünge (ruckartig wechselnde Farben)",
            "RGB-Rad (Farbkreis um den Fernseher)",
            "Alles nacheinander (Dimmen, Farbsprünge, RGB-Rad)",
        )
        AlertDialog.Builder(this)
            .setTitle("LEDs testen")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runLayoutTest()
                    1 -> runPatternTest(TestKind.DIM)
                    2 -> runPatternTest(TestKind.JUMP)
                    3 -> runPatternTest(TestKind.WHEEL)
                    else -> runPatternTest(TestKind.ALL)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** The test button turns into a stop button while a test runs. */
    private fun watchTest(job: Job) {
        testButton.text = "Test stoppen"
        job.invokeOnCompletion { runOnUiThread { testButton.text = "LEDs testen" } }
    }

    /** Dimming, abrupt colour changes and an RGB wheel on the whole strip, independent of the screen. */
    private fun runPatternTest(kind: TestKind) {
        if (AmbilightState.running.value) {
            Toast.makeText(this, "Bitte zuerst das Ambilight stoppen", Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = Config.load(prefs)
        if (cfg.host.isEmpty()) {
            Toast.makeText(this, "Bitte zuerst die WLED-Adresse eingeben oder unter Einstellungen „WLED automatisch suchen“ drücken", Toast.LENGTH_LONG).show()
            return
        }
        testJob?.cancel()
        testJob = lifecycleScope.launch(Dispatchers.IO) {
            val layout = LedLayout(cfg.layout)
            if (layout.count == 0) return@launch
            val sender = WledSender()
            val out = ByteArray(layout.totalLeds * 3)
            val packed = IntArray(layout.count)
            val colors = IntArray(layout.count)
            val bri = cfg.brightness / 100f // the global brightness limit applies to the test too
            try {
                WledSender.postState(cfg.host, WledSender.onJson(true))
                val start = SystemClock.elapsedRealtime()
                val duration = TestPatterns.durationMs(kind)
                while (isActive) {
                    val t = SystemClock.elapsedRealtime() - start
                    if (t >= duration) break
                    TestPatterns.render(kind, t, layout.count, packed)
                    for (k in colors.indices) {
                        val c = packed[k]
                        colors[k] = Color.rgb(
                            (((c shr 16) and 0xFF) * bri + 0.5f).toInt(),
                            (((c shr 8) and 0xFF) * bri + 0.5f).toInt(),
                            ((c and 0xFF) * bri + 0.5f).toInt(),
                        )
                    }
                    writeColors(out, colors, cfg.layout.skipLeading)
                    sender.send(cfg, out)
                    AmbilightState.colors.value = colors.copyOf()
                    AmbilightState.status.value = "Test: ${TestPatterns.label(kind, t)}"
                    delay(33)
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    java.util.Arrays.fill(out, 0.toByte())
                    repeat(2) { sender.send(cfg, out) }
                    sender.close()
                    AmbilightState.colors.value = null
                    AmbilightState.status.value = "Gestoppt"
                }
            }
        }
        testJob?.let { watchTest(it) }
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

    private class Page(val title: String, val subtitle: String, val xml: Int)

    companion object {
        const val EXTRA_AUTOSTART = "autostart"

        private val PAGES = listOf(
            Page("WLED-Verbindung", "Adresse, automatische Suche, Protokoll", R.xml.pref_connection),
            Page("LED-Layout", "LEDs pro Seite, Startecke, Richtung", R.xml.pref_layout),
            Page("Aufnahme", "Bildrate, Auflösung, Farbberechnung", R.xml.pref_capture),
            Page("Farbe & Glättung", "Helligkeit, Sättigung, Gamma, Weißabgleich", R.xml.pref_color),
            Page("Schwarze Balken", "Automatische Erkennung bei Filmen", R.xml.pref_bars),
            Page("Schwarzes Bild", "Was passiert z. B. bei Netflix?", R.xml.pref_black),
            Page("Standby", "Was passiert bei Bildschirm aus?", R.xml.pref_standby),
            Page("System", "Start mit dem Fernseher", R.xml.pref_system),
        )
    }
}
