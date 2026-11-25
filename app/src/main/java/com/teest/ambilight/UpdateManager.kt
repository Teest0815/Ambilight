package com.teest.ambilight

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val notes: String,
    /** API address of the APK asset (works for private repositories with a token). */
    val assetUrl: String,
    val size: Long,
)

sealed class CheckResult {
    data class Available(val info: UpdateInfo) : CheckResult()
    data class UpToDate(val versionName: String) : CheckResult()
    data class Failed(val reason: String) : CheckResult()
}

/**
 * Self-update from GitHub Releases: every build publishes a release with the APK.
 * The version number is part of the file name ("Ambilight-1.3.12-code112.apk").
 */
object UpdateManager {
    const val DEFAULT_REPO = "Teest0815/Ambilight"
    private val CODE_REGEX = Regex("code(\\d+)")

    /** Reads the version number out of the APK file name, null if it has none. */
    fun parseVersionCode(assetName: String): Long? =
        CODE_REGEX.find(assetName)?.groupValues?.get(1)?.toLongOrNull()

    /** Name and number of the installed version. */
    fun installed(context: Context): Pair<String, Long> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return (info.versionName ?: "?") to PackageInfoCompat.getLongVersionCode(info)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Asks GitHub for the latest release. Blocking: never call on the main thread. */
    fun check(context: Context, prefs: SharedPreferences): CheckResult {
        val repo = (prefs.getString("update_repo", null) ?: DEFAULT_REPO).trim().ifEmpty { DEFAULT_REPO }
        val token = (prefs.getString("update_token", null) ?: "").trim()
        val (curName, curCode) = installed(context)
        return try {
            val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "Ambilight-Updater")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
                when (val code = conn.responseCode) {
                    200 -> {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val assets = json.optJSONArray("assets")
                        var apk: JSONObject? = null
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val a = assets.getJSONObject(i)
                                if (a.optString("name").endsWith(".apk")) {
                                    apk = a
                                    break
                                }
                            }
                        }
                        if (apk == null) return CheckResult.Failed("Das Release enthält keine APK-Datei.")
                        val name = apk.optString("name")
                        val remoteCode = parseVersionCode(name)
                            ?: return CheckResult.Failed("Der Dateiname der APK enthält keine Versionsnummer ($name).")
                        if (remoteCode > curCode) {
                            val tag = json.optString("tag_name").removePrefix("v").ifEmpty { name }
                            CheckResult.Available(
                                UpdateInfo(tag, remoteCode, json.optString("body"), apk.getString("url"), apk.optLong("size", -1)),
                            )
                        } else {
                            CheckResult.UpToDate(curName)
                        }
                    }
                    404 -> CheckResult.Failed(
                        if (token.isEmpty()) {
                            "Kein Zugriff auf $repo. Ist das Repository privat, trage unter „App-Update“ ein GitHub-Token ein."
                        } else {
                            "Kein Release gefunden. Wurde schon eines veröffentlicht? Sonst Repository und Token prüfen."
                        },
                    )
                    401 -> CheckResult.Failed("GitHub akzeptiert das Token nicht. Bitte neu eintragen.")
                    403 -> CheckResult.Failed("GitHub verweigert die Anfrage (Token ohne Rechte oder zu viele Anfragen).")
                    else -> CheckResult.Failed("GitHub antwortet mit Fehler $code.")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            CheckResult.Failed("Keine Verbindung zu GitHub (${e.message ?: "Netzwerkfehler"}).")
        } catch (e: JSONException) {
            CheckResult.Failed("Unerwartete Antwort von GitHub.")
        }
    }

    /**
     * Downloads the APK into the cache. Blocking. GitHub answers with a redirect to the file storage:
     * the token must only go to GitHub itself, so redirects are followed by hand.
     */
    @Throws(IOException::class)
    fun download(
        context: Context,
        info: UpdateInfo,
        token: String,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "ambilight-update.apk")
        var url = info.assetUrl
        var withAuth = true
        var hops = 0
        while (true) {
            if (cancelled()) throw IOException("Abgebrochen")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "Ambilight-Updater")
                conn.setRequestProperty("Accept", "application/octet-stream")
                if (withAuth && token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
                val code = conn.responseCode
                if (code in 300..399) {
                    url = conn.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel")
                    withAuth = false
                    if (++hops > 5) throw IOException("Zu viele Weiterleitungen")
                    continue
                }
                if (code != 200) throw IOException("Download fehlgeschlagen (HTTP $code)")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
                var done = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw IOException("Abgebrochen")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                break
            } finally {
                conn.disconnect()
            }
        }
        if (file.length() == 0L || (info.size > 0 && file.length() != info.size)) {
            throw IOException("Die heruntergeladene Datei ist unvollständig.")
        }
        return file
    }

    /** Null if the file is an update for this app, otherwise the reason why not. */
    fun verify(context: Context, apk: File): String? {
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: return "Die Datei ist keine gültige APK."
        if (archive.packageName != context.packageName) return "Die APK gehört zu einer anderen App."
        if (PackageInfoCompat.getLongVersionCode(archive) <= installed(context).second) {
            return "Die APK ist nicht neuer als die installierte Version."
        }
        return null
    }

    /** Hands the APK to Android's installer. The result arrives at [InstallReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: an update by the installing app itself may skip the confirmation
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("ambilight.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    fun cleanup(context: Context) {
        File(context.cacheDir, "update").deleteRecursively()
    }
}
