package com.teest.ambilight

import android.app.Application
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

object AppTheme {
    fun apply(mode: String?) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }

    fun apply(p: SharedPreferences) = apply(p.getString("theme_mode", "dark"))
}

class AmbilightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppTheme.apply(PreferenceManager.getDefaultSharedPreferences(this))
    }
}
