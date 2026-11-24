package com.teest.ambilight

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(requireArguments().getInt(ARG_XML), rootKey)

        val numeric = listOf(
            "leds_top", "leds_right", "leds_bottom", "leds_left",
            "skip_leading", "total_leds",
            "wled_preset", "wled_fx", "wled_sx", "wled_ix", "wled_pal",
            "standby_wled_preset", "standby_wled_fx", "standby_wled_sx", "standby_wled_ix", "standby_wled_pal",
        )
        for (key in numeric) {
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_NUMBER
                edit.setSelection(edit.text.length)
            }
        }
        findPreference<EditTextPreference>("led_offset")?.setOnBindEditTextListener { edit ->
            edit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            edit.setSelection(edit.text.length)
        }
        findPreference<EditTextPreference>("host")?.setOnBindEditTextListener { edit ->
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            edit.setSingleLine()
            edit.setSelection(edit.text.length)
        }

        for (key in listOf("black_color", "standby_color")) {
            findPreference<EditTextPreference>(key)?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    val ok = Config.isValidColor(newValue as String)
                    if (!ok) {
                        Toast.makeText(requireContext(), "Ungültige Farbe, Beispiel: #FF6A00", Toast.LENGTH_LONG).show()
                    }
                    ok
                }
        }

        findPreference<Preference>("read_led_count")?.setOnPreferenceClickListener {
            readLedCount()
            true
        }
        findPreference<Preference>("find_wled")?.setOnPreferenceClickListener {
            findWled()
            true
        }
    }

    // ---- show only the settings that belong to the chosen action ----

    private val visibilityListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in VISIBILITY_KEYS) updateVisibility()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(visibilityListener)
        updateVisibility()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(visibilityListener)
        super.onPause()
    }

    /** Works for every settings page: preferences that do not exist on the current page are simply skipped. */
    private fun updateVisibility() {
        val p = preferenceManager.sharedPreferences ?: return
        fun show(key: String, visible: Boolean) {
            findPreference<Preference>(key)?.isVisible = visible
        }
        fun number(key: String): Int = (p.all[key] as? String)?.trim()?.toIntOrNull() ?: 0

        // Black screen
        val black = p.getString("black_action", "off") ?: "off"
        val blackColor = black == "static" || black == "effect"
        val blackWled = black == "wled"
        show("black_fade_ms", black != "hold")
        show("black_color", blackColor)
        show("black_brightness", blackColor)
        show("black_effect", black == "effect")
        show("effect_speed", black == "effect")
        show("wled_preset", blackWled)
        for (key in listOf("wled_fx", "wled_sx", "wled_ix", "wled_pal")) {
            show(key, blackWled && number("wled_preset") == 0)
        }

        // Standby
        val standby = p.getString("standby_action", "off") ?: "off"
        val standbyColor = standby == "static" || standby == "effect"
        val standbyWled = standby == "wled"
        show("standby_color", standbyColor)
        show("standby_brightness", standbyColor)
        show("standby_effect", standby == "effect")
        show("standby_effect_speed", standby == "effect")
        show("standby_wled_preset", standbyWled)
        for (key in listOf("standby_wled_fx", "standby_wled_sx", "standby_wled_ix", "standby_wled_pal")) {
            show(key, standbyWled && number("standby_wled_preset") == 0)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Opaque background and no overscroll effect: some TV GPUs otherwise leave old frames on screen while scrolling.
        view.setBackgroundColor(Color.parseColor("#121212"))
        listView.setBackgroundColor(Color.parseColor("#121212"))
        listView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun onDestroyView() {
        discovery?.cancel()
        discovery = null
        searchDialog?.dismiss()
        searchDialog = null
        super.onDestroyView()
    }

    private var discovery: WledDiscovery? = null
    private var searchDialog: AlertDialog? = null

    private fun findWled() {
        val ctx = requireContext()
        discovery?.cancel()
        val d = WledDiscovery(ctx.applicationContext)
        discovery = d
        searchDialog?.dismiss()
        searchDialog = AlertDialog.Builder(ctx)
            .setTitle("WLED suchen")
            .setMessage("Suche im Netzwerk … (dauert etwa 5 Sekunden)")
            .setNegativeButton("Abbrechen") { _, _ -> d.cancel() }
            .setOnCancelListener { d.cancel() }
            .show()
        d.start { list ->
            searchDialog?.dismiss()
            searchDialog = null
            if (!isAdded) return@start
            showFound(list)
        }
    }

    private fun showFound(list: List<FoundWled>) {
        val ctx = requireContext()
        when {
            list.isEmpty() -> AlertDialog.Builder(ctx)
                .setTitle("Kein WLED gefunden")
                .setMessage("Läuft WLED im selben WLAN wie der Fernseher? Sonst kannst du die IP-Adresse von Hand eintragen.")
                .setPositiveButton("OK", null)
                .show()
            list.size == 1 -> applyFound(list[0])
            else -> {
                val labels = list.map {
                    val leds = it.ledCount?.let { n -> " · $n LEDs" } ?: ""
                    "${it.name} (${it.ip})$leds"
                }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle("WLED auswählen")
                    .setItems(labels) { _, which -> applyFound(list[which]) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
    }

    private fun applyFound(w: FoundWled) {
        findPreference<EditTextPreference>("host")?.text = w.ip
        if (w.ledCount != null) {
            findPreference<EditTextPreference>("total_leds")?.text = w.ledCount.toString()
        }
        val leds = w.ledCount?.let { " mit $it LEDs" } ?: ""
        Toast.makeText(requireContext(), "${w.name} (${w.ip})$leds übernommen", Toast.LENGTH_LONG).show()
    }

    private fun readLedCount() {
        val host = preferenceManager.sharedPreferences?.getString("host", "")?.trim().orEmpty()
        if (host.isEmpty()) {
            Toast.makeText(requireContext(), "Bitte zuerst die WLED-Adresse eingeben", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(requireContext(), "Frage WLED ab …", Toast.LENGTH_SHORT).show()
        Thread {
            val count = WledSender.readLedCount(host)
            activity?.runOnUiThread {
                val ctx = context ?: return@runOnUiThread
                if (count == null) {
                    Toast.makeText(ctx, "WLED unter $host nicht erreichbar", Toast.LENGTH_LONG).show()
                } else {
                    findPreference<EditTextPreference>("total_leds")?.text = count.toString()
                    Toast.makeText(ctx, "WLED meldet $count LEDs, als Gesamtzahl übernommen", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        private val VISIBILITY_KEYS = setOf("black_action", "wled_preset", "standby_action", "standby_wled_preset")
        private const val ARG_XML = "xml"

        fun newInstance(xmlRes: Int) = SettingsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_XML, xmlRes) }
        }
    }
}
