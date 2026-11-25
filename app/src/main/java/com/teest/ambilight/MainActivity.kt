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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private lateinit var testButton: Button
    private lateinit var prefs: SharedPreferences
    private var pendingTest: TestKind? = null

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
                pendingTest?.let { launchTest(it, stopWhenDone = true) }
                pendingTest = null
            } else {
                pendingTest = null
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
        testButton.setOnClickListener { showTestDialog() }
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
            pendingTest = null
            Toast.makeText(this, "Bitte zuerst die WLED-Adresse eingeben oder unter Einstellungen „WLED automatisch suchen“ drücken", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        stopService(Intent(this, CaptureService::class.java))
    }

    private fun showTestDialog() {
        val items = arrayOf(
            "Layout (Farbränder und laufender Marker)",
            "Dimmen (Weiß, Rot, Grün, Blau)",
            "Farbsprünge (ruckartig wechselnde Farben)",
            "RGB-Rad (Farbkreis dreht sich)",
            "Alles nacheinander (Dimmen, Farbsprünge, RGB-Rad)",
        )
        val kinds = arrayOf(TestKind.LAYOUT, TestKind.DIM, TestKind.JUMP, TestKind.WHEEL, TestKind.ALL)
        AlertDialog.Builder(this)
            .setTitle("Test auf dem Bildschirm (beliebige Taste beendet ihn)")
            .setItems(items) { _, which -> startTest(kinds[which]) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** The picture must really be captured, so the Ambilight has to run. If it does not, it is started for the test. */
    private fun startTest(kind: TestKind) {
        if (AmbilightState.running.value) {
            launchTest(kind, stopWhenDone = false)
        } else {
            pendingTest = kind
            requestCapture() // after the permission dialog the service starts and the test opens
        }
    }

    private fun launchTest(kind: TestKind, stopWhenDone: Boolean) {
        startActivity(
            Intent(this, TestActivity::class.java)
                .putExtra(TestActivity.EXTRA_KIND, kind.name)
                .putExtra(TestActivity.EXTRA_STOP_SERVICE, stopWhenDone),
        )
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
