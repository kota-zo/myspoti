// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
package com.music.spotui.connect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.myspoti.host.android.Logbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Live state shared between the TV/device QR screen and the local pairing HTTP server. */
sealed interface QrServerState {
    data object Waiting : QrServerState
    data object Checking : QrServerState
    data object SignedIn : QrServerState
    data class Failed(val message: String) : QrServerState
}

/**
 * Process-wide handoff when a phone opens a `myspoti://pair?...` link or scans a TV QR code:
 * survives activity restart if the user needs to sign in on the phone first before sending.
 */
object QrPairHandoff {
    private val _target = MutableStateFlow<QrPairTarget?>(null)
    val target: StateFlow<QrPairTarget?> = _target

    private val _manualOpen = MutableStateFlow(false)
    val manualOpen: StateFlow<Boolean> = _manualOpen

    fun offer(t: QrPairTarget) { _manualOpen.value = false; _target.value = t }
    fun clear() { _target.value = null }
    fun openManual() { _manualOpen.value = true }
    fun closeManual() { _manualOpen.value = false }
}

/**
 * Small ephemeral HTTP server bound only to the device's LAN IPv4/IPv6 address while QR sign-in is
 * open. Protected by a 96-bit random token in the QR code (and a 6-digit PIN for manual browser
 * entry, rate-limited against guessing). Cookies are validated in memory and never logged.
 */
class QrLoginServer(
    private val context: Context,
    private val isTv: Boolean,
    private val onCookiesReceived: (Map<String, String>) -> Unit,
) {
    private val rng = SecureRandom()
    private val pool = Executors.newCachedThreadPool { Thread(it, "qr-pair-http").apply { isDaemon = true } }
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val busy = AtomicInteger()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""
    @Volatile private var pin: String = ""
    @Volatile private var state: QrServerState = QrServerState.Waiting
    private val badPinAttempts = AtomicInteger(0)

    data class Endpoint(
        val url: String,
        val shortUrl: String,
        val host: String,
        val pin: String,
        val vpnBypassed: Boolean = false,
    )

    data class LanSelection(val address: InetAddress, val vpnActive: Boolean)

    @Synchronized
    fun start(): Endpoint? {
        stop()
        val selection = findLanSelection(context) ?: return null
        val addr = selection.address
        val bytes = ByteArray(12).also { rng.nextBytes(it) }
        token = bytes.joinToString("") { "%02x".format(it) }
        pin = "%06d".format(rng.nextInt(1_000_000))
        badPinAttempts.set(0)
        state = QrServerState.Waiting
        val s = runCatching { ServerSocket(0, 16, addr) }.getOrNull() ?: return null
        server = s
        val rawHost = addr.hostAddress?.substringBefore('%').orEmpty()
        val bracketed = if (':' in rawHost) "[$rawHost]" else rawHost
        val port = s.localPort
        Logbook.log("qr login: server listening on $rawHost:$port (vpn=${selection.vpnActive})")
        Thread({ acceptLoop(s) }, "qr-pair-accept").apply { isDaemon = true }.start()
        return Endpoint(
            url = QrLoginProtocol.buildPairUri(rawHost, port, token),
            shortUrl = "http://$bracketed:$port",
            host = rawHost,
            pin = pin,
            vpnBypassed = selection.vpnActive,
        )
    }

    fun setState(next: QrServerState) {
        state = next
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.close() }; Logbook.log("qr login: server stopped") }
        server = null
        token = ""
        pin = ""
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = runCatching { s.accept() }.getOrNull() ?: break
            if (!isAllowedClient(client.inetAddress) || busy.get() >= MAX_BUSY) {
                runCatching { client.close() }
                continue
            }
            sockets.add(client)
            busy.incrementAndGet()
            pool.execute {
                try {
                    handleClient(client, s)
                } finally {
                    busy.decrementAndGet()
                    sockets.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun isAllowedClient(addr: InetAddress?): Boolean =
        addr != null && (Zeroconf.isLan(addr) || addr.isLoopbackAddress)

    private fun handleClient(client: Socket, s: ServerSocket) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        val req = QrHttp.readRequest(client.getInputStream()) ?: return
        val out = client.getOutputStream()
        val localHost = s.inetAddress.hostAddress?.substringBefore('%').orEmpty()
        val port = s.localPort

        fun authorized(qToken: String?, qPin: String?): Boolean {
            if (!qToken.isNullOrBlank() && QrLoginProtocol.constantTimeEquals(qToken, token)) return true
            if (!qPin.isNullOrBlank()) {
                if (badPinAttempts.get() >= MAX_PIN_ATTEMPTS) return false
                if (QrLoginProtocol.constantTimeEquals(qPin.filter { it.isDigit() }, pin)) return true
                badPinAttempts.incrementAndGet()
            }
            return false
        }

        val response: ByteArray = when {
            req.method == "GET" && (req.path == "/" || req.path == "/pair") -> {
                val validQueryToken = req.query["t"]?.takeIf { QrLoginProtocol.constantTimeEquals(it, token) }
                val html = QrHttp.pairingHtml(deviceDisplayName(), localHost, port, validQueryToken)
                QrHttp.buildResponse(200, "OK", "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
            }
            req.method == "GET" && req.path == "/info" -> {
                if (!authorized(req.query["t"], req.query["pin"])) {
                    QrHttp.jsonResponse(403, "Forbidden", QrLoginProtocol.jsonObjectString("message" to "Invalid or expired code."))
                } else {
                    val body = buildJsonObject {
                        put("name", deviceDisplayName())
                        put("tv", isTv)
                    }.toString()
                    QrHttp.jsonResponse(200, "OK", body)
                }
            }
            req.method == "GET" && req.path == "/status" -> {
                if (!authorized(req.query["t"], req.query["pin"])) {
                    QrHttp.jsonResponse(403, "Forbidden", QrLoginProtocol.jsonObjectString("message" to "Invalid or expired code."))
                } else {
                    val body = when (val cur = state) {
                        QrServerState.Waiting -> QrLoginProtocol.jsonObjectString("state" to "waiting")
                        QrServerState.Checking -> QrLoginProtocol.jsonObjectString("state" to "checking")
                        QrServerState.SignedIn -> QrLoginProtocol.jsonObjectString("state" to "signed_in")
                        is QrServerState.Failed -> QrLoginProtocol.jsonObjectString("state" to "failed", "message" to cur.message)
                    }
                    QrHttp.jsonResponse(200, "OK", body)
                }
            }
            req.method == "POST" && req.path == "/submit" -> {
                val ct = req.headers["content-type"].orEmpty().lowercase()
                val obj = if ("application/json" in ct) QrHttp.parseJsonObject(req.body) else null
                if (obj == null) {
                    QrHttp.jsonResponse(400, "Bad Request", QrLoginProtocol.jsonObjectString("message" to "Expected JSON payload."))
                } else {
                    val reqToken = obj["token"]?.jsonPrimitive?.contentOrNull
                    val reqPin = obj["pin"]?.jsonPrimitive?.contentOrNull
                    if (!authorized(reqToken, reqPin)) {
                        val locked = badPinAttempts.get() >= MAX_PIN_ATTEMPTS
                        val msg = if (locked) "Too many wrong codes — refresh QR on device." else "Invalid pairing token or code."
                        QrHttp.jsonResponse(403, "Forbidden", QrLoginProtocol.jsonObjectString("message" to msg))
                    } else {
                        val cookies = extractCookiesFromPayload(obj)
                        if (cookies == null) {
                            QrHttp.jsonResponse(400, "Bad Request", QrLoginProtocol.jsonObjectString("message" to "Could not find a valid sp_dc cookie."))
                        } else {
                            Logbook.log("qr login: session cookies received")
                            state = QrServerState.Checking
                            onCookiesReceived(cookies)
                            QrHttp.jsonResponse(202, "Accepted", QrLoginProtocol.jsonObjectString("state" to "checking"))
                        }
                    }
                }
            }
            else -> QrHttp.jsonResponse(404, "Not Found", QrLoginProtocol.jsonObjectString("message" to "Not found"))
        }
        out.write(response)
        out.flush()
    }

    private fun extractCookiesFromPayload(obj: JsonObject): Map<String, String>? {
        (obj["cookies"] as? JsonObject)?.let { mapObj ->
            QrLoginProtocol.parseCookieInput(mapObj.toString())?.let { return it }
        }
        val text = obj["cookieText"]?.jsonPrimitive?.contentOrNull ?: return null
        return QrLoginProtocol.parseCookieInput(text)
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_BUSY = 8
        private const val MAX_PIN_ATTEMPTS = 8

        fun deviceDisplayName(): String {
            val mfg = Build.MANUFACTURER.orEmpty().trim()
            val model = Build.MODEL.orEmpty().trim()
            return when {
                model.isBlank() -> "Myspoti"
                mfg.isBlank() || model.startsWith(mfg, ignoreCase = true) -> model
                else -> "$mfg $model"
            }.take(48)
        }

        /** Finds the active LAN IPv4 address (Wi-Fi / Ethernet / Hotspot), skipping any VPN tunnel. */
        fun findLanAddress(context: Context): InetAddress? = findLanSelection(context)?.address

        fun findLanSelection(context: Context): LanSelection? {
            var vpnActive = false
            val candidates = mutableListOf<LanCandidate>()

            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                @Suppress("DEPRECATION")
                val networks = runCatching { cm.allNetworks }.getOrNull().orEmpty()
                for (net in networks) {
                    val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        vpnActive = true
                        continue
                    }
                    val hasLanTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    val isCellularOnly = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !hasLanTransport
                    if (isCellularOnly) continue
                    val lp = runCatching { cm.getLinkProperties(net) }.getOrNull() ?: continue
                    val ifaceName = lp.interfaceName.orEmpty()
                    val isVirtualIface = QrLoginProtocol.isVirtualOrVpnInterfaceName(ifaceName)
                    for (linkAddr in lp.linkAddresses) {
                        val addr = linkAddr.address ?: continue
                        candidates.add(
                            LanCandidate(
                                address = addr,
                                interfaceName = ifaceName,
                                isWifiOrEthernet = hasLanTransport,
                                isVpnOrVirtual = isVirtualIface,
                            ),
                        )
                    }
                }
            }

            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            for (iface in ifaces) {
                val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
                if (!up) continue
                val n = iface.name.orEmpty()
                val p2p = runCatching { iface.isPointToPoint }.getOrDefault(false)
                val virtual = runCatching { iface.isVirtual }.getOrDefault(false)
                val badName = QrLoginProtocol.isVirtualOrVpnInterfaceName(n)
                if (p2p || badName) {
                    if (n.lowercase().let { it.startsWith("tun") || it.startsWith("wg") || it.startsWith("ppp") || it.startsWith("utun") || it.startsWith("tailscale") }) {
                        vpnActive = true
                    }
                    continue
                }
                if (virtual) continue
                val addrs = runCatching { iface.inetAddresses.toList() }.getOrDefault(emptyList())
                for (a in addrs) {
                    candidates.add(
                        LanCandidate(
                            address = a,
                            interfaceName = n,
                            isWifiOrEthernet = n.lowercase().let { it.startsWith("wlan") || it.startsWith("eth") || it.startsWith("en") || it.startsWith("ap") || it.startsWith("swlan") },
                            isVpnOrVirtual = false,
                            isPointToPoint = false,
                        ),
                    )
                }
            }

            val picked = QrLoginProtocol.pickBestLanAddress(candidates) ?: return null
            return LanSelection(picked, vpnActive)
        }
    }
}

/**
 * Phone-side LAN client that confirms the TV's identity and delivers Spotify session cookies over raw
 * TCP socket (works alongside `android:usesCleartextTraffic="false"` and strictly enforces private LAN IPs).
 */
object QrPairSender {
    suspend fun fetchDeviceName(target: QrPairTarget): Result<String> = withContext(Dispatchers.IO) {
        if (!QrLoginProtocol.isPrivateLanHost(target.host) || target.port !in 1024..65535) {
            return@withContext Result.failure(IllegalArgumentException("Only local Wi-Fi addresses are allowed."))
        }
        runCatching {
            val (code, body) = callSocket(
                target.host,
                target.port,
                QrHttp.buildHttpGet(target.host, target.port, "/info?${target.authQuery}"),
            ) ?: error("Could not reach device on Wi-Fi")
            val obj = QrHttp.parseJsonObject(body.toByteArray(Charsets.UTF_8))
            if (code != 200) error(obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Device rejected code ($code)")
            obj?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Myspoti"
        }
    }

    suspend fun sendCookiesAndWait(target: QrPairTarget, cookies: Map<String, String>): Result<Unit> = withContext(Dispatchers.IO) {
        if (!QrLoginProtocol.isPrivateLanHost(target.host) || target.port !in 1024..65535) {
            return@withContext Result.failure(IllegalArgumentException("Only local Wi-Fi addresses are allowed."))
        }
        if (cookies["sp_dc"].isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("No active Spotify session found on this device."))
        }
        runCatching {
            val payload = buildJsonObject {
                if (target.token.isNotBlank()) put("token", target.token)
                if (target.pin.isNotBlank()) put("pin", target.pin)
                put("cookies", buildJsonObject {
                    cookies.forEach { (k, v) -> if (k in QrLoginProtocol.ALLOWED_COOKIES) put(k, v) }
                })
            }.toString()
            val (code, body) = callSocket(
                target.host,
                target.port,
                QrHttp.buildHttpJsonPost(target.host, target.port, "/submit", payload),
            ) ?: error("Could not reach device on Wi-Fi")
            if (code !in 200..299) {
                val msg = QrHttp.parseJsonObject(body.toByteArray(Charsets.UTF_8))?.get("message")?.jsonPrimitive?.contentOrNull
                error(msg ?: "Pairing failed (HTTP $code)")
            }
            val deadline = System.currentTimeMillis() + 65_000L
            while (System.currentTimeMillis() < deadline) {
                delay(750)
                val statusRes = callSocket(
                    target.host,
                    target.port,
                    QrHttp.buildHttpGet(target.host, target.port, "/status?${target.authQuery}"),
                ) ?: continue
                val obj = QrHttp.parseJsonObject(statusRes.second.toByteArray(Charsets.UTF_8)) ?: continue
                when (obj["state"]?.jsonPrimitive?.contentOrNull) {
                    "signed_in" -> return@runCatching
                    "failed" -> error(obj["message"]?.jsonPrimitive?.contentOrNull ?: "Device could not sign in with this session.")
                }
            }
            error("Timed out waiting for the other device to finish signing in.")
        }
    }

    private fun callSocket(host: String, port: Int, request: ByteArray): Pair<Int, String>? =
        runCatching {
            val addr = InetAddress.getByName(host)
            if (!Zeroconf.isLan(addr)) return null
            Socket().use { s ->
                s.connect(InetSocketAddress(addr, port), 5_000)
                s.soTimeout = 10_000
                s.getOutputStream().apply { write(request); flush() }
                QrHttp.readResponse(s.getInputStream())
            }
        }.getOrNull()
}
