package com.teest.ambilight

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** The update that was found last, null if the app is up to date or nothing was checked yet. */
object UpdateState {
    val available = MutableStateFlow<UpdateInfo?>(null)
}

private const val AUTO_CHECK_INTERVAL_MS = 30 * 60 * 1000L

/** Quiet check at app start (can be switched off): only sets [UpdateState], no dialog. */
fun AppCompatActivity.autoCheckForUpdate() {
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    if (!prefs.getBoolean("update_auto", true)) return
    if (System.currentTimeMillis() - prefs.getLong("update_last_check", 0L) < AUTO_CHECK_INTERVAL_MS) return
    checkForUpdate(manual = false)
}

/** manual = true tells the person the result (dialog or message), otherwise only the update hint is set. */
fun AppCompatActivity.checkForUpdate(manual: Boolean) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    val app = applicationContext
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { UpdateManager.check(app, prefs) }
        prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply()
        when (result) {
            is CheckResult.Available -> {
                UpdateState.available.value = result.info
                if (manual) showUpdateDialog(result.info)
            }
            is CheckResult.UpToDate -> {
                UpdateState.available.value = null
                if (manual) {
                    Toast.makeText(
                        this@checkForUpdate,
                        "Du hast die neueste Version (${result.versionName}).",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            is CheckResult.Failed -> if (manual) {
                AlertDialog.Builder(this@checkForUpdate)
                    .setTitle("Update-Suche fehlgeschlagen")
                    .setMessage(result.reason)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}

fun AppCompatActivity.showUpdateDialog(info: UpdateInfo) {
    val (current, _) = UpdateManager.installed(this)
    val notes = info.notes.trim().ifEmpty { "Keine Änderungsliste vorhanden." }
    AlertDialog.Builder(this)
        .setTitle("Update ${info.versionName} verfügbar")
        .setMessage("Installiert: $current\n\nNeu in dieser Version:\n$notes")
        .setPositiveButton("Jetzt installieren") { _, _ -> startUpdate(info) }
        .setNegativeButton("Später", null)
        .show()
}

private fun AppCompatActivity.startUpdate(info: UpdateInfo) {
    if (!UpdateManager.canInstall(this)) {
        AlertDialog.Builder(this)
            .setTitle("Installation erlauben")
            .setMessage(
                "Android muss der App erlauben, Updates zu installieren. Öffne die Einstellungen und schalte " +
                    "den Schalter für Ambilight ein. Danach mit „Zurück“ hierher und das Update noch einmal starten.",
            )
            .setPositiveButton("Einstellungen öffnen") { _, _ -> openInstallSettings() }
            .setNegativeButton("Abbrechen", null)
            .show()
        return
    }

    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    val token = prefs.getString("update_token", "")?.trim().orEmpty()
    val app = applicationContext
    var job: Job? = null
    val dialog = AlertDialog.Builder(this)
        .setTitle("Update ${info.versionName}")
        .setMessage("Lade herunter … 0 %")
        .setCancelable(false)
        .setNegativeButton("Abbrechen") { _, _ -> job?.cancel() }
        .show()

    job = lifecycleScope.launch {
        try {
            val file = withContext(Dispatchers.IO) {
                val f = UpdateManager.download(
                    app, info, token,
                    onProgress = { percent -> runOnUiThread { dialog.setMessage("Lade herunter … $percent %") } },
                    cancelled = { !isActive },
                )
                UpdateManager.verify(app, f)?.let { throw IOException(it) }
                f
            }
            dialog.setMessage("Starte die Installation …")
            UpdateManager.install(this@startUpdate, file)
            dialog.dismiss()
        } catch (e: CancellationException) {
            dialog.dismiss()
            throw e
        } catch (e: Exception) {
            dialog.dismiss()
            AlertDialog.Builder(this@startUpdate)
                .setTitle("Update fehlgeschlagen")
                .setMessage(e.message ?: "Unbekannter Fehler")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}

private fun AppCompatActivity.openInstallSettings() {
    try {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            this,
            "Bitte in den Android-Einstellungen unter Apps → Spezieller App-Zugriff → Unbekannte Apps installieren → Ambilight erlauben.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
