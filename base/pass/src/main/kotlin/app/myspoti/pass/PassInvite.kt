package app.myspoti.pass

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * What the receiving device's QR code says: where it listens on the local network, its public
 * key, a one-time token and its name, as a link that a phone's camera (Samsung's, Google Lens)
 * opens in the app straight on the confirmation:
 * `orpheon://pass?v=1&a=192.168.1.20:41234&k=…&t=…&n=Living+room+TV`. An app scheme and not an
 * https link: the project has no web domain of its own to verify an App Link with. The codes of
 * before (`orpheon-pass:1?a=…`, the desktop app's until it moves to this one) still read.
 */
data class PassInvite(val host: String, val port: Int, val key: ByteArray, val token: ByteArray, val name: String) {
    fun toText(): String = "$LINK?v=1&a=$host:$port&k=${b64(key)}&t=${b64(token)}&n=${URLEncoder.encode(name.take(40), "UTF-8")}"

    companion object {
        /** The app's own link scheme (the manifest's intent filter on MainActivity). */
        const val LINK_SCHEME = "orpheon"
        const val LINK_HOST = "pass"
        const val LINK = "$LINK_SCHEME://$LINK_HOST"
        /** The QR codes' text of before, still read by the app's scanner. */
        const val SCHEME = "orpheon-pass"
        private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        private fun unb64(s: String) = Base64.getUrlDecoder().decode(s)

        /**
         * The invitation in a scanned text or an opened link, or null when it is not one (another
         * QR code, another host or scheme, a missing or malformed part, an address outside the
         * local network).
         */
        fun parse(text: String): PassInvite? = runCatching {
            val t = text.trim()
            val query = when {
                t.startsWith("$SCHEME:1?") -> t.substringAfter('?')
                // The scheme and the host as the link gives them, any case; then nothing but the query
                // (orpheon://pass/x?… or orpheon://pass.evil?… are not this app's).
                t.length > LINK.length && t.regionMatches(0, LINK, 0, LINK.length, ignoreCase = true) -> {
                    val rest = t.substring(LINK.length).removePrefix("/")
                    if (!rest.startsWith("?")) return null
                    rest.substring(1).substringBefore('#')
                }
                else -> return null
            }
            val q = query.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            // A later version of the link that this app does not know: not guessed at.
            if (q["v"] != null && q["v"] != "1") return null
            val a = q["a"]!!
            val host = a.substringBeforeLast(':')
            // Only an address of a local network: a QR code cannot send the user's session elsewhere.
            require(isPrivate(host)) { "not a local address" }
            val port = a.substringAfterLast(':').toInt()
            require(port in 1..65535) { "no port" }
            val key = unb64(q["k"]!!)
            val token = unb64(q["t"]!!)
            // The receiver's key must be one (a P-256 public key) and the token whole.
            PassCrypto.publicKey(key)
            require(token.size == 16) { "token of ${token.size} bytes" }
            // The name is shown on the confirmation: no control or direction characters that could
            // make it read as something else.
            val name = q["n"].orEmpty().filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim().take(40)
            PassInvite(host, port, key, token, name)
        }.getOrNull()

        /** A private IPv4 address (10/8, 172.16/12, 192.168/16) or a link-local one. */
        fun isPrivate(host: String): Boolean {
            val p = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && it.all { n -> n in 0..255 } } ?: return false
            return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
        }

        /** This device's address on its local network (Wi-Fi or Ethernet), or null without one. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("docker") && !it.name.startsWith("veth") && !it.name.startsWith("br-") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .filter(::isPrivate)
                .sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.")) 1 else 2 }
                .firstOrNull()
        }.getOrNull()
    }
}
