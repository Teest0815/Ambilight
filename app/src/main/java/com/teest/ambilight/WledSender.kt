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

    // Packet buffers are reused, so sending does not allocate memory for every frame.
    private val ddpBuf = ByteArray(DDP_HEADER + DDP_MAX_DATA)
    private var ddpPacket: DatagramPacket? = null
    private val udpBuf = ByteArray(4 + UDP_MAX_PIXELS * 3)
    private var udpPacket: DatagramPacket? = null

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
            ddpPacket = null
            udpPacket = null
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

    /** Drops socket and address so the next send resolves the host again (e.g. after the network was lost). */
    private fun resetTarget() {
        socket?.close()
        socket = null
        address = null
        resolvedHost = ""
        ddpPacket = null
        udpPacket = null
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
            resetTarget()
        }
    }

    private fun sendDdp(rgb: ByteArray) {
        val sock = socket ?: return
        val addr = address ?: return
        val packet = ddpPacket ?: DatagramPacket(ddpBuf, ddpBuf.size, addr, 4048).also { ddpPacket = it }
        var offset = 0
        seq = if (seq >= 15) 1 else seq + 1
        while (offset < rgb.size) {
            val len = minOf(DDP_MAX_DATA, rgb.size - offset)
            val last = offset + len >= rgb.size
            ddpBuf[0] = (0x40 or (if (last) 0x01 else 0x00)).toByte() // version 1, push on the last chunk
            ddpBuf[1] = seq.toByte()
            ddpBuf[2] = 0x01 // RGB
            ddpBuf[3] = 0x01 // default output device
            ddpBuf[4] = (offset ushr 24).toByte()
            ddpBuf[5] = (offset ushr 16).toByte()
            ddpBuf[6] = (offset ushr 8).toByte()
            ddpBuf[7] = offset.toByte()
            ddpBuf[8] = (len ushr 8).toByte()
            ddpBuf[9] = len.toByte()
            System.arraycopy(rgb, offset, ddpBuf, DDP_HEADER, len)
            packet.setData(ddpBuf, 0, DDP_HEADER + len)
            sock.send(packet)
            offset += len
        }
    }

    /** UDP realtime protocol 4 (DNRGB): works for strips with more than 490 LEDs. */
    private fun sendDnrgb(rgb: ByteArray) {
        val sock = socket ?: return
        val addr = address ?: return
        val packet = udpPacket ?: DatagramPacket(udpBuf, udpBuf.size, addr, 21324).also { udpPacket = it }
        val pixels = rgb.size / 3
        var start = 0
        while (start < pixels) {
            val count = minOf(UDP_MAX_PIXELS, pixels - start)
            udpBuf[0] = 4
            udpBuf[1] = 2 // seconds until WLED leaves realtime mode when packets stop
            udpBuf[2] = (start ushr 8).toByte()
            udpBuf[3] = start.toByte()
            System.arraycopy(rgb, start * 3, udpBuf, 4, count * 3)
            packet.setData(udpBuf, 0, 4 + count * 3)
            sock.send(packet)
            start += count
        }
    }

    fun close() {
        resetTarget()
    }

    companion object {
        private const val DDP_HEADER = 10
        private const val DDP_MAX_DATA = 480 * 3
        private const val UDP_MAX_PIXELS = 489

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

        /**
         * "lor":0 lifts a live-data override: after a hand-over ("lor":1) WLED ignores our packets for as long as
         * they keep arriving, so it would stay on its own effect if the picture came back quickly.
         */
        fun onJson(on: Boolean) = if (on) """{"on":true,"lor":0}""" else """{"on":false}"""

        /** Hands the strip over to WLED's own preset or effect while the screen is black. */
        fun handoverJson(cfg: Config): String =
            handoverJson(cfg.wledPreset, cfg.wledFx, cfg.wledSx, cfg.wledIx, cfg.wledPal, cfg.blackFadeMs)

        /**
         * Preset > 0 selects a WLED preset, otherwise effect fx with speed, intensity and palette.
         * fadeMs > 0 asks WLED for a one-time transition ("tt", in units of 100 ms).
         */
        fun handoverJson(preset: Int, fx: Int, sx: Int, ix: Int, pal: Int, fadeMs: Int): String {
            val tt = if (fadeMs > 0) ""","tt":${fadeMs / 100}""" else ""
            return if (preset > 0) {
                """{"on":true,"lor":1$tt,"ps":$preset}"""
            } else {
                """{"on":true,"lor":1$tt,"seg":[{"id":0,"fx":$fx,"sx":$sx,"ix":$ix,"pal":$pal}]}"""
            }
        }
    }
}
