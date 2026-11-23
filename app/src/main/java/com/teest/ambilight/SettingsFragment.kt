package com.teest.ambilight

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val numeric = listOf(
            "leds_top", "leds_right", "leds_bottom", "leds_left",
            "skip_leading", "total_leds",
            "wled_preset", "wled_fx", "wled_sx", "wled_ix", "wled_pal",
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

        findPreference<EditTextPreference>("black_color")?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val ok = Config.isValidColor(newValue as String)
                if (!ok) {
                    Toast.makeText(requireContext(), "Ungültige Farbe, Beispiel: #FF6A00", Toast.LENGTH_LONG).show()
                }
                ok
            }

        findPreference<Preference>("read_led_count")?.setOnPreferenceClickListener {
            readLedCount()
            true
        }
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
}
