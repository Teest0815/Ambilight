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
import android.graphics.drawable.GradientDrawable
import android.view.View
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
    private lateinit var statusDot: View
    private lateinit var updateButton: Button
    private lateinit var versionText: TextView
    private val tileButtons = mutableListOf<Pair<Page, Button>>()
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
        window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.bg)))

        preview = findViewById(R.id.preview)
        statusView = findViewById(R.id.status)
        toggleButton = findViewById(R.id.btnToggle)
        testButton = findViewById(R.id.btnTest)
        statusDot = findViewById(R.id.statusDot)
        updateButton = findViewById(R.id.btnUpdate)
        versionText = findViewById(R.id.versionText)
        versionText.text = "Version " + UpdateManager.installed(this).first
        updateButton.setOnClickListener { UpdateState.available.value?.let { showUpdateDialog(it) } }

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
                launch {
                    AmbilightState.status.collect {
                        statusView.text = it
                        statusDot.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(statusColor(it))
                        }
                    }
                }
                launch {
                    UpdateState.available.collect { info ->
                        updateButton.visibility = if (info == null) View.GONE else View.VISIBLE
                        refreshTiles()
                        if (info != null) updateButton.text = "Update ${info.versionName} verfügbar – jetzt installieren"
                    }
                }
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
        autoCheckForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    override fun onStart() {
        super.onStart()
        AmbilightState.previewVisible = true
        updatePreviewLayout() // settings pages may have changed the layout
        refreshTiles()
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
        for (page in PAGES) {
            val tile = layoutInflater.inflate(R.layout.item_tile, grid, false) as Button
            tileButtons.add(page to tile)
            tile.setOnClickListener {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_TITLE, page.title)
                        .putExtra(SettingsActivity.EXTRA_XML, page.xml),
                )
            }
            grid.addView(tile)
        }
        refreshTiles()
    }

    /** Tile texts show the current values; only the text changes, so the focus stays where it is. */
    private fun refreshTiles() {
        val cfg = Config.load(prefs)
        val subtitleColor = ContextCompat.getColor(this, R.color.text_secondary)
        for ((page, tile) in tileButtons) {
            val text = SpannableStringBuilder()
            text.append(page.title)
            text.setSpan(StyleSpan(Typeface.BOLD), 0, page.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(1.25f), 0, page.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("\n")
            val start = text.length
            text.append(page.summary(cfg, UpdateState.available.value))
            text.setSpan(ForegroundColorSpan(subtitleColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.85f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tile.text = text
        }
    }

    private fun statusColor(status: String): Int = Color.parseColor(
        when {
            status.startsWith("Läuft, aber") || status.startsWith("Warte") -> "#FFB020"
            status.startsWith("Läuft") -> "#3DDC84"
            status.startsWith("Pausiert") -> "#4DA3FF"
            else -> "#80868B"
        },
    )

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

    private class Page(
        val title: String,
        val xml: Int,
        val summary: (Config, UpdateInfo?) -> String,
    )

    companion object {
        const val EXTRA_AUTOSTART = "autostart"

        private fun action(blackFade: Int, name: String) = if (blackFade > 0) "$name, ${blackFade} ms Übergang" else name

        private val PAGES = listOf(
            Page("WLED-Verbindung", R.xml.pref_connection) { c, _ ->
                (if (c.host.isEmpty()) "Noch keine Adresse – hier suchen" else c.host) + " · " + c.protocol.name
            },
            Page("LED-Layout", R.xml.pref_layout) { c, _ ->
                val l = c.layout
                "${l.top + l.right + l.bottom + l.left} LEDs (oben ${l.top}, rechts ${l.right}, unten ${l.bottom}, links ${l.left})"
            },
            Page("Aufnahme", R.xml.pref_capture) { c, _ ->
                "${c.fps} FPS · ${c.captureWidth} px" + if (c.adaptive) " · automatisch" else ""
            },
            Page("Farbe & Glättung", R.xml.pref_color) { c, _ ->
                "Helligkeit ${c.brightness} % · Sättigung ${c.saturation} % · Glättung ${c.smoothingMs} ms"
            },
            Page("Schwarze Balken", R.xml.pref_bars) { c, _ ->
                if (c.barDetect) "Automatische Erkennung an" else "Aus"
            },
            Page("Schwarzes Bild", R.xml.pref_black) { c, _ ->
                val name = when (c.blackAction) {
                    BlackAction.OFF -> "LEDs aus"
                    BlackAction.HOLD -> "Letzte Farbe halten"
                    BlackAction.STATIC -> "Feste Farbe"
                    BlackAction.EFFECT -> "Effekt der App"
                    BlackAction.WLED -> "WLED-Effekt"
                }
                action(c.blackFadeMs, name)
            },
            Page("Standby", R.xml.pref_standby) { c, _ ->
                val name = when (c.standbyAction) {
                    StandbyAction.OFF -> "LEDs aus"
                    StandbyAction.WLED -> "WLED-Effekt"
                    StandbyAction.STATIC -> "Feste Farbe"
                    StandbyAction.EFFECT -> "Effekt der App"
                }
                action(c.standbyFadeMs, name)
            },
            Page("System", R.xml.pref_system) { c, _ ->
                if (c.autoStart) "Start mit dem Fernseher: an" else "Start mit dem Fernseher: aus"
            },
            Page("App", R.xml.pref_app) { _, u ->
                if (u != null) "Update ${u.versionName} verfügbar" else "Design, Updates, Version"
            },
        )
    }
}
