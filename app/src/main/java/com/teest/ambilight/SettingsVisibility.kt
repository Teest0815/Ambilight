package com.teest.ambilight

/** Which settings are shown depends on the chosen action. Used by the TV screens and the remote page alike. */
object SettingsVisibility {
    /** Key -> visible. [get] returns the stored value of a setting as text. */
    fun compute(get: (String) -> String?): Map<String, Boolean> {
        fun number(key: String): Int = get(key)?.trim()?.toIntOrNull() ?: 0
        val m = LinkedHashMap<String, Boolean>()

        val black = get("black_action") ?: "off"
        val blackColor = black == "static" || black == "effect"
        val blackWled = black == "wled"
        m["black_fade_ms"] = black != "hold"
        m["black_color"] = blackColor
        m["black_brightness"] = blackColor
        m["black_effect"] = black == "effect"
        m["effect_speed"] = black == "effect"
        m["wled_preset"] = blackWled
        for (key in listOf("wled_fx", "wled_sx", "wled_ix", "wled_pal")) {
            m[key] = blackWled && number("wled_preset") == 0
        }

        val standby = get("standby_action") ?: "off"
        val standbyColor = standby == "static" || standby == "effect"
        val standbyWled = standby == "wled"
        m["standby_color"] = standbyColor
        m["standby_brightness"] = standbyColor
        m["standby_effect"] = standby == "effect"
        m["standby_effect_speed"] = standby == "effect"
        m["standby_wled_preset"] = standbyWled
        for (key in listOf("standby_wled_fx", "standby_wled_sx", "standby_wled_ix", "standby_wled_pal")) {
            m[key] = standbyWled && number("standby_wled_preset") == 0
        }
        return m
    }
}
