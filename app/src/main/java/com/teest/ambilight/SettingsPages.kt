package com.teest.ambilight

/** All settings pages: title and XML file. Shared by the TV screens and the remote page. */
object SettingsPages {
    val ALL = listOf(
        "WLED-Verbindung" to R.xml.pref_connection,
        "LED-Layout" to R.xml.pref_layout,
        "Aufnahme" to R.xml.pref_capture,
        "Farbe & Glättung" to R.xml.pref_color,
        "Schwarze Balken" to R.xml.pref_bars,
        "Schwarzes Bild" to R.xml.pref_black,
        "Standby" to R.xml.pref_standby,
        "System" to R.xml.pref_system,
        "App" to R.xml.pref_app,
    )

    /** Pages offered on the remote page (the update options of the "App" page stay on the TV). */
    val REMOTE = ALL.filter { it.second != R.xml.pref_app }
}
