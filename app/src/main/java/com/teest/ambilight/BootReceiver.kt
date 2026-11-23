package com.teest.ambilight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

/** Optionally opens the app after boot so the screen-capture permission can be confirmed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("auto_start", false)) return
        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_AUTOSTART, true)
        }
        try {
            context.startActivity(open)
        } catch (e: Exception) {
            // Some devices block starting activities from the background.
        }
    }
}
