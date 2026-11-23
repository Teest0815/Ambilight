package com.teest.ambilight

import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Sends LED data to WLED (DDP or UDP realtime) and talks to its JSON API. Not thread-safe: use from one thread. */
class WledSender {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var resolvedHost = ""
    private var lastResolveFail = 0L
    private var seq = 0

    /** Last error as text, null if everything is fine. */
    var error: String? = null
        private set

    private fun ensureTarget(host: String): Boolean {
        if (host.isEmpty()) {
            error = "Keine WLED-Adresse eingestellt"
            return false
        }
        if (address != null && host == resolvedHost) return true
        val now = System.currentTimeMillis()
        if (host == resolvedHost && now - lastResolveFail < 5000) return false
        return try {
            address = InetAddress.getByName(host)
            resolvedHost = host
            if (socket == null) socket = DatagramSocket()
            error = null
            true
        } catch (e: IOException) {
            resolvedHost = host
            address = null
            lastResolveFail = now
            error = "WLED nicht gefunden: $host"
            false
        }
    }

    /** rgb = r,g,b for every pixel of the strip. */
    fun send(cfg: Config, rgb: ByteArray) {
        if (!ensureTarget(cfg.host)) return
        try {
            when (cfg.protocol) {
                Protocol.DDP -> sendDdp(rgb)
                Protocol.UDP -> sendDnrgb(rgb)
            }
            error = null
        } catch (e: IOException) {
            error = "Senden fehlgeschlagen: ${e.message}"
        }
    }

    private fun sendDdp(rgb: ByteArray) {
        val sock = socket ?: return
        val addr = address ?: return
        val maxData = 480 * 3
        var offset = 0
        seq = if (seq >= 15) 1 else seq + 1
        while (offset < rgb.size) {
            val len = minOf(maxData, rgb.size - offset)
            val last = offset + len >= rgb.size
            val packet = ByteArray(10 + len)
            packet[0] = (0x40 or (if (last) 0x01 else 0x00)).toByte() // version 1, push on the last chunk
            packet[1] = seq.toByte()
            packet[2] = 0x01 // RGB
            packet[3] = 0x01 // default output device
            packet[4] = (offset ushr 24).toByte()
            packet[5] = (offset ushr 16).toByte()
            packet[6] = (offset ushr 8).toByte()
            packet[7] = offset.toByte()
            packet[8] = (len ushr 8).toByte()
            packet[9] = len.toByte()
            System.arraycopy(rgb, offset, packet, 10, len)
            sock.send(DatagramPacket(packet, packet.size, addr, 4048))
            offset += len
        }
    }

    /** UDP realtime protocol 4 (DNRGB): works for strips with more than 490 LEDs. */
    private fun sendDnrgb(rgb: ByteArray) {
        val sock = socket ?: return
        val addr = address ?: return
        val pixels = rgb.size / 3
        val perPacket = 489
        var start = 0
        while (start < pixels) {
            val count = minOf(perPacket, pixels - start)
            val packet = ByteArray(4 + count * 3)
            packet[0] = 4
            packet[1] = 2 // seconds until WLED leaves realtime mode when packets stop
            packet[2] = (start ushr 8).toByte()
            packet[3] = start.toByte()
            System.arraycopy(rgb, start * 3, packet, 4, count * 3)
            sock.send(DatagramPacket(packet, packet.size, addr, 21324))
            start += count
        }
    }

    fun close() {
        socket?.close()
        socket = null
        address = null
    }

    companion object {
        /** POST to the WLED JSON API. Blocking: never call on the main thread. */
        fun postState(host: String, json: String): Boolean {
            if (host.isEmpty()) return false
            return try {
                val conn = URL("http://$host/json/state").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(json.toByteArray()) }
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                false
            }
        }

        /** Reads the LED count from /json/info, null on failure. Blocking. */
        fun readLedCount(host: String): Int? {
            if (host.isEmpty()) return null
            return try {
                val conn = URL("http://$host/json/info").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(text).getJSONObject("leds").getInt("count")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }

        fun onJson(on: Boolean) = if (on) """{"on":true}""" else """{"on":false}"""

        /** Hands the strip over to WLED's own preset or effect while the screen is black. */
        fun handoverJson(cfg: Config): String =
            if (cfg.wledPreset > 0) {
                """{"on":true,"lor":1,"ps":${cfg.wledPreset}}"""
            } else {
                """{"on":true,"lor":1,"seg":[{"id":0,"fx":${cfg.wledFx},"sx":${cfg.wledSx},"ix":${cfg.wledIx},"pal":${cfg.wledPal}}]}"""
            }
    }
}
