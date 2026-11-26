package com.teest.ambilight

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Small web server inside the app: open its address (or scan the QR code) on a phone or PC in the same network
 * and change the settings there. Every call needs the secret key that is part of the address.
 * It runs only while someone needs it (the remote screen is open, or Ambilight runs and the person allowed it).
 */
object RemoteServer {
    private const val MAX_BODY = 64 * 1024
    private val holders = mutableSetOf<String>()
    private var server: ServerSocket? = null
    private var appContext: Context? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun acquire(context: Context, who: String) {
        appContext = context.applicationContext
        holders.add(who)
        if (server != null) return
        val s = bind() ?: return
        server = s
        port = s.localPort
        Thread({ acceptLoop(s) }, "remote-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun release(who: String) {
        holders.remove(who)
        if (holders.isEmpty()) {
            try {
                server?.close()
            } catch (e: Exception) {
            }
            server = null
            port = 0
        }
    }

    val running: Boolean @Synchronized get() = server != null

    private fun bind(): ServerSocket? {
        for (candidate in (8080..8090) + 0) {
            try {
                return ServerSocket(candidate).apply { reuseAddress = true }
            } catch (e: Exception) {
            }
        }
        return null
    }

    /** Local network address of this device, null without a network. */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) {
        null
    }

    fun key(prefs: SharedPreferences): String {
        prefs.getString("remote_key", null)?.let { if (it.length >= 16) return it }
        return newKey(prefs)
    }

    fun newKey(prefs: SharedPreferences): String {
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val k = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("remote_key", k).apply()
        return k
    }

    fun url(prefs: SharedPreferences): String? {
        val ip = localIp() ?: return null
        val p = port
        if (p == 0) return null
        return "http://$ip:$p/?k=${key(prefs)}"
    }

    // ---- server ----

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try {
                s.accept()
            } catch (e: Exception) {
                break
            }
            pool.execute { handle(c) }
        }
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 5000
                val ctx = appContext ?: return
                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val req = readRequest(sock.getInputStream()) ?: return respond(sock, 400, "text/plain", "Bad request")
                val out = sock.getOutputStream()
                when {
                    req.method == "GET" && (req.path == "/" || req.path == "/index.html") ->
                        respond(sock, 200, "text/html; charset=utf-8", ctx.assets.open("remote.html").bufferedReader().use { it.readText() })
                    req.path.startsWith("/api/") -> {
                        val given = req.headers["x-key"] ?: req.query["k"] ?: ""
                        if (!MessageDigest.isEqual(given.toByteArray(), key(prefs).toByteArray())) {
                            respond(sock, 401, "application/json", """{"error":"Falscher Schlüssel. Bitte den QR-Code neu scannen."}""")
                        } else {
                            api(sock, ctx, prefs, req)
                        }
                    }
                    else -> respond(sock, 404, "text/plain", "Not found")
                }
                out.flush()
            } catch (e: SocketTimeoutException) {
            } catch (e: Exception) {
            }
        }
    }

    private fun api(sock: Socket, ctx: Context, prefs: SharedPreferences, req: Request) {
        when {
            req.method == "GET" && req.path == "/api/state" -> {
                val json = SettingsSchema.toJson(ctx, prefs)
                    .put("status", AmbilightState.status.value)
                    .put("running", AmbilightState.running.value)
                    .put("version", UpdateManager.installed(ctx).first)
                respond(sock, 200, "application/json", json.toString())
            }
            req.method == "POST" && req.path == "/api/set" -> {
                val body = try {
                    JSONObject(req.body)
                } catch (e: Exception) {
                    return respond(sock, 400, "application/json", """{"error":"Ungültige Anfrage"}""")
                }
                val error = SettingsSchema.apply(ctx, prefs, body.optString("key"), body.opt("value"))
                if (error != null) {
                    respond(sock, 422, "application/json", JSONObject().put("error", error).toString())
                } else {
                    val json = SettingsSchema.toJson(ctx, prefs)
                        .put("status", AmbilightState.status.value)
                        .put("running", AmbilightState.running.value)
                        .put("version", UpdateManager.installed(ctx).first)
                    respond(sock, 200, "application/json", json.toString())
                }
            }
            else -> respond(sock, 404, "application/json", """{"error":"Nicht gefunden"}""")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        if (last4 != 0x0D0A0D0A) return null
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = HashMap<String, String>()
        if (q >= 0) {
            for (kv in target.substring(q + 1).split("&")) {
                val e = kv.indexOf('=')
                if (e > 0) query[kv.substring(0, e)] = java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8")
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return Request(parts[0], path, query, headers, String(body, Charsets.UTF_8))
    }

    private fun respond(sock: Socket, code: Int, type: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 422 -> "Unprocessable Entity"; else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }
}
