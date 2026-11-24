package com.teest.ambilight

import android.os.Bundle
import android.text.InputType
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
        private const val ARG_XML = "xml"

        fun newInstance(xmlRes: Int) = SettingsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_XML, xmlRes) }
        }
    }
}
