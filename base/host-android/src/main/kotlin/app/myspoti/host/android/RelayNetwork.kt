package app.myspoti.host.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.myspoti.spotify.bridge.IpFamily
import app.myspoti.spotify.bridge.RelayRoutes
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/*
 * 402 on some free accounts (see RelayRoutes): what a tester's diagnostic must carry to tell the
 * paths apart — which address family the relayed requests went out on, from and to which
 * (masked) addresses, and what the phone's network offers. Logged when it changes, not per call.
 */

/** Filled by [RelayEvents] for one relayed request: the connection it went out on. */
internal class RelayTag {
    @Volatile var family: IpFamily? = null
}

internal object RelayEvents : EventListener() {
    @Volatile private var lastPath: String? = null

    override fun connectionAcquired(call: Call, connection: Connection) {
        val socket = runCatching { connection.socket() }.getOrNull() ?: return
        val remote = socket.inetAddress
        call.request().tag(RelayTag::class.java)?.family = IpFamily.of(remote)
        val path = "${RelayRoutes.mask(socket.localAddress)} -> ${RelayRoutes.mask(remote)} (${connection.protocol()})"
        if (path != lastPath) {
            lastPath = path
            Logbook.log("relay path: $path")
        }
    }
}

/** The system resolver, keeping one address family only (see [RelayRoutes.only]). */
internal class FamilyDns(private val family: IpFamily) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = RelayRoutes.only(family, hostname, Dns.SYSTEM.lookup(hostname))
}

internal object NetworkReport {
    @Volatile private var logged = false

    /** Once per session: the active network's families, CLAT (464XLAT), VPN and Private DNS. */
    fun logOnce(context: Context) {
        if (logged) return
        logged = true
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork
            val link = cm.getLinkProperties(network)
            val caps = cm.getNetworkCapabilities(network)
            val addresses = link?.linkAddresses.orEmpty().map { it.address }
            val v6 = addresses.any { it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }
            val v4 = addresses.any { it is Inet4Address }
            // The CLAT interface ("v4-rmnet…") carries IPv4 over an IPv6-only network: the WebView's
            // IPv4 requests then leave from a translated address.
            val clat = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { it.name.startsWith("v4-") && it.isUp }
            }.getOrDefault(false)
            val transport = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val privateDns = when {
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P -> "off"
                link?.isPrivateDnsActive != true -> "off"
                link.privateDnsServerName != null -> "strict"
                else -> "opportunistic"
            }
            fun yn(b: Boolean) = if (b) "yes" else "no"
            val line = "$transport, IPv4 ${yn(v4)}, IPv6 ${yn(v6)}, CLAT ${yn(clat)}, VPN ${yn(vpn)}, private DNS $privateDns"
            Logbook.log("network: $line")
            Logbook.fact("network", line)
        }.onFailure { Logbook.log("network: not read (${it.message})") }
    }
}

/** Told when the phone starts or stops ringing or being in a call (Android 12+), on a thread of its own. */
object PhoneCall {
    @Volatile var listener: ((busy: Boolean) -> Unit)? = null
}

/**
 * Calls in the timeline: the phone's audio mode (ringing, in a call, back to normal), the moments
 * a report of "the music did not come back after a call" turns on. Android 12+.
 */
internal object AudioModeWatch {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed || android.os.Build.VERSION.SDK_INT < 31) return
        installed = true
        runCatching {
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            audio.addOnModeChangedListener(java.util.concurrent.Executors.newSingleThreadExecutor()) { mode ->
                val name = when (mode) {
                    android.media.AudioManager.MODE_NORMAL -> "normal"
                    android.media.AudioManager.MODE_RINGTONE -> "ringing"
                    android.media.AudioManager.MODE_IN_CALL -> "in a call"
                    android.media.AudioManager.MODE_IN_COMMUNICATION -> "in a voice/video call (app)"
                    android.media.AudioManager.MODE_CALL_SCREENING -> "call screening"
                    else -> "mode $mode"
                }
                Logbook.log("audio mode: $name")
                PhoneCall.listener?.invoke(mode != android.media.AudioManager.MODE_NORMAL)
            }
        }
    }
}
