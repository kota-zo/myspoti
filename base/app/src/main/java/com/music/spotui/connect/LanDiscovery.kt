package com.music.spotui.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A Spotify Connect device found on the network, with what it said on its ZeroConf port. */
data class LanDevice(val service: String, val host: InetAddress, val port: Int, val cpath: String, val info: ZeroconfInfo)

/**
 * Finds the Spotify Connect devices of the local network (mDNS `_spotify-connect._tcp` through
 * NsdManager, which needs no permission; from targetSdk 37 on, Android asks ACCESS_LOCAL_NETWORK
 * for the connections to those addresses, implicitly granted below that), while the device picker is open. The Web Player only
 * lists the devices already logged in to the account; the Spotify app also looks on the network
 * and logs a speaker in when it is tapped. Each device found is asked `getInfo` once.
 */
object LanDiscovery {
    /** The devices that answered, newest last (main thread). */
    val devices = mutableStateListOf<LanDevice>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var users = 0
    /** Before Android 14 NsdManager resolves one service at a time: the others wait here. */
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** Starts looking (each [start] wants one [stop]). Main thread. */
    fun start(context: Context) {
        if (users++ > 0) return
        val m = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run { users = 0; return }
        nsd = m
        // Found anew each time: a device switched off since is not offered.
        devices.clear()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log("looking for Spotify Connect devices")
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = main.post {
                log("discovery failed to start (error $errorCode)")
                if (listener === this) { listener = null; users = 0 }
            }.let { }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = main.post { waiting.addLast(info); next() }.let { }
            override fun onServiceLost(info: NsdServiceInfo) = main.post { devices.removeAll { it.service == info.serviceName } }.let { }
        }
        listener = l
        runCatching { m.discoverServices(Zeroconf.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
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
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
                override fun onServiceResolved(s: NsdServiceInfo) { found(s); done() }
            })
        }.onFailure { done() }
    }

    @Suppress("DEPRECATION")
    private fun found(s: NsdServiceInfo) {
        val all = if (Build.VERSION.SDK_INT >= 34) s.hostAddresses else listOfNotNull(s.host)
        val host = all.filter { Zeroconf.isLan(it) }.let { a -> a.firstOrNull { it is Inet4Address } ?: a.firstOrNull() } ?: return
        val cpath = s.attributes["CPath"]?.let { String(it, Charsets.UTF_8) }
        scope.launch {
            val info = runCatching { getInfo(host, s.port, cpath) }.getOrNull()
            if (info == null) { log("a device on the network did not answer getInfo"); return@launch }
            // Brand, model and the kind of login only: no name, id or account.
            log("found ${info.brand.ifBlank { "?" }} ${info.model.ifBlank { "" }} (login ${info.tokenType.ifBlank { "?" }}, zeroconf ${info.version.ifBlank { "?" }}, " +
                "${if (info.activeUser.isBlank()) "no user" else "a user"} logged in)")
            val d = LanDevice(s.serviceName, host, s.port, Zeroconf.path(cpath), info)
            main.post {
                val at = devices.indexOfFirst { it.info.deviceId == info.deviceId }
                if (at >= 0) devices[at] = d else devices += d
            }
        }
    }

    suspend fun getInfo(host: InetAddress, port: Int, cpath: String?): ZeroconfInfo? =
        call(host, port, Zeroconf.infoRequest(host.hostAddress.orEmpty(), port, cpath))?.takeIf { it.first == 200 }?.let { Zeroconf.parseInfo(it.second) }

    /**
     * One plain HTTP exchange with a device. A socket, not OkHttp: the app does not allow cleartext
     * HTTP (`usesCleartextTraffic=false`), which is right for everything but a speaker's own port.
     */
    fun call(host: InetAddress, port: Int, request: ByteArray, timeoutMs: Int = 5_000): Pair<Int, String>? =
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(request); flush() }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                // Read to the end of the answer: its length, or the device closing (a timeout with an answer in hand is not a failure).
                while (out.size() < MAX_ANSWER && !Zeroconf.complete(out.toByteArray())) {
                    val n = try { s.getInputStream().read(buf) } catch (_: java.net.SocketTimeoutException) { -1 }
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                Zeroconf.parseResponse(out.toByteArray())
            }
        }.getOrNull()

    private const val MAX_ANSWER = 64 * 1024

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("lan: $line")
}
