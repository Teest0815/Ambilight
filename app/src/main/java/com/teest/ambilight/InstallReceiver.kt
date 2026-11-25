package com.teest.ambilight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Receives the result of the installation from Android's PackageInstaller. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a confirmation ("Do you want to update this app?")
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Die Installation konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by Android
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, "Update fehlgeschlagen. $reason".trim(), Toast.LENGTH_LONG).show()
            }
        }
    }
}
