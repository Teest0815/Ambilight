package com.teest.ambilight

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

data class FoundWled(val ip: String, val name: String, val ledCount: Int?)

/**
 * Looks for WLED devices in the local network.
 * Two methods run in parallel: mDNS (WLED announces itself as _wled._tcp) and a scan of the local
 * subnet. Many TV boxes block multicast, so the scan is the safety net.
 */
@Suppress("DEPRECATION")
class WledDiscovery(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val found = LinkedHashMap<String, FoundWled>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val pool = Executors.newFixedThreadPool(64)

    @Volatile
    private var cancelled = false
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null

    /** onDone runs on the main thread (not at all when cancel() was called). */
    fun start(onDone: (List<FoundWled>) -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        startMdns()
        Thread {
            scanSubnet()
            val remaining = MIN_DURATION_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0 && !cancelled) {
                try {
                    Thread.sleep(remaining)
                } catch (e: InterruptedException) {
                    // fall through
                }
            }
            stopMdns()
            pool.shutdown()
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                // fall through
            }
            if (!cancelled) {
                val list = synchronized(lock) { found.values.toList() }
                main.post { if (!cancelled) onDone(list) }
            }
        }.start()
    }

    fun cancel() {
        cancelled = true
        stopMdns()
        pool.shutdownNow()
    }

    // ---------- mDNS ----------

    private fun startMdns() {
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = mgr
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { pending.addLast(serviceInfo) }
                resolveNext()
            }
        }
        listener = l
        try {
            mgr.discoverServices("_wled._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    private fun stopMdns() {
        val mgr = nsd
        val l = listener
        listener = null
        if (mgr != null && l != null) {
            try {
                mgr.stopServiceDiscovery(l)
            } catch (e: Exception) {
                // already stopped
            }
        }
    }

    /** NsdManager resolves one service at a time. */
    private fun resolveNext() {
        val mgr = nsd ?: return
        val next = synchronized(lock) {
            if (resolving) return
            val n = pending.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        try {
            mgr.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val ip = (serviceInfo.host as? Inet4Address)?.hostAddress
                    synchronized(lock) { resolving = false }
                    if (ip != null) submitProbe(ip)
                    resolveNext()
                }
            })
        } catch (e: Exception) {
            synchronized(lock) { resolving = false }
        }
    }

    private fun submitProbe(ip: String) {
        try {
            pool.execute {
                if (!cancelled) probe(ip, 1200)?.let { add(it) }
            }
        } catch (e: RejectedExecutionException) {
            // search already finished
        }
    }

    // ---------- subnet scan ----------

    private fun scanSubnet() {
        val ips = subnetAddresses() ?: return
        val tasks = ips.map { ip ->
            Callable {
                if (!cancelled) probe(ip, 600)?.let { add(it) }
            }
        }
        try {
            pool.invokeAll(tasks, 8, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // interrupted or cancelled
        }
    }

    /** All addresses of the own subnet (at least /24 is scanned, never more than that). */
    private fun subnetAddresses(): List<String>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        val la = props.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        } ?: return null
        val b = la.address.address
        val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val prefix = la.prefixLength.coerceIn(24, 30)
        val mask = -1 shl (32 - prefix)
        val first = (ip and mask) + 1
        val last = (ip and mask) or mask.inv()
        val out = ArrayList<String>()
        var a = first
        while (a < last) {
            if (a != ip) {
                out.add("${(a ushr 24) and 255}.${(a ushr 16) and 255}.${(a ushr 8) and 255}.${a and 255}")
            }
            a++
        }
        return out
    }

    // ---------- helpers ----------

    private fun add(w: FoundWled) {
        synchronized(lock) { found[w.ip] = w }
    }

    /** Asks /json/info; returns null if nothing answers or it is not WLED. */
    private fun probe(ip: String, timeoutMs: Int): FoundWled? {
        return try {
            val conn = URL("http://$ip/json/info").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs * 2
                if (conn.responseCode != 200) return null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val isWled = json.optString("brand") == "WLED" || (json.has("ver") && json.has("leds"))
                if (!isWled) return null
                val count = json.optJSONObject("leds")?.optInt("count", -1)?.takeIf { it > 0 }
                FoundWled(ip, json.optString("name", ip).ifBlank { ip }, count)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** mDNS answers need a moment even if the scan was fast. */
        private const val MIN_DURATION_MS = 4000L
    }
}
