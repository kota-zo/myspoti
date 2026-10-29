package com.music.spotui.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.net.Inet4Address
import java.net.InetAddress

/** A Cast device found on the network. [id] is the device's own (TXT `id`), stable across addresses. */
data class CastDevice(val id: String, val name: String, val model: String, val host: InetAddress, val port: Int, val service: String)

/**
 * Finds the Cast devices of the local network (mDNS / DNS-SD `_googlecast._tcp`, through
 * Android's NsdManager, which needs no extra permission), while the device picker is open.
 * Groups of speakers are listed too (they answer on their own port).
 */
object CastDiscovery {
    private const val TYPE = "_googlecast._tcp"
    /** The devices found, newest last (main thread). */
    val devices = mutableStateListOf<CastDevice>()
    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var users = 0
    /** Before Android 14 NsdManager resolves one service at a time: the others wait here. */
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** Starts looking (each [start] wants one [stop]). Main thread. */
    fun start(context: Context) {
        if (users++ > 0) return
        // No discovery on this phone: counted as not started, so a later start tries again.
        val m = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run { users = 0; return }
        nsd = m
        // Found anew each time: a device switched off since is not offered.
        devices.clear()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log("looking for Cast devices")
            override fun onDiscoveryStopped(serviceType: String) = log("stopped looking for Cast devices")
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = main.post {
                log("discovery failed to start (error $errorCode)")
                // Not running: a later start begins it again.
                if (listener === this) { listener = null; users = 0 }
            }.let { }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = main.post { waiting.addLast(info); next() }.let { }
            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                if (devices.removeAll { it.service == info.serviceName }) log("a Cast device left (${devices.size} left)")
            }.let { }
        }
        listener = l
        runCatching { m.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { log("discovery not available: ${it.javaClass.simpleName}"); listener = null; users = 0 }
    }

    fun stop() {
        if (users == 0 || --users > 0) return
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        waiting.clear()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || listener == null) return
        val info = waiting.removeFirstOrNull() ?: return
        val m = nsd ?: return
        resolving = true
        val done = { main.post { resolving = false; next() }; Unit }
        runCatching {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    log("a Cast device could not be resolved (error $errorCode)")
                    done()
                }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    found(s)
                    done()
                }
            })
        }.onFailure { done() }
    }

    @Suppress("DEPRECATION")
    private fun found(s: NsdServiceInfo) {
        val host = if (Build.VERSION.SDK_INT >= 34) s.hostAddresses.let { a -> a.firstOrNull { it is Inet4Address } ?: a.firstOrNull() } else s.host
        host ?: return
        fun attr(k: String) = s.attributes[k]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        val id = attr("id").ifBlank { s.serviceName }
        val d = CastDevice(id, attr("fn").ifBlank { s.serviceName }, attr("md"), host, s.port, s.serviceName)
        main.post {
            val at = devices.indexOfFirst { it.id == d.id }
            if (at >= 0) devices[at] = d else { devices += d; log("found a Cast device: ${d.model.ifBlank { "unknown model" }} (${devices.size} in all)") }
        }
    }

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("cast: $line")
}
