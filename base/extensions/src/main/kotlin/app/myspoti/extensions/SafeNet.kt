package app.myspoti.extensions

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Network safety for everything an extension makes the app fetch: a host is refused when it is, or
 * resolves to, a local address (loopback, link-local, private ranges, CGNAT, unique-local IPv6,
 * IPv4 written in decimal, hex, octal or short forms, IPv4 inside IPv6). The check on the name is
 * only a first filter: the real one is [SafeDns], made on the addresses the connection will use.
 */
object SafeNet {
    /** Whether [host] (a name, or an IP literal in any form) is a local address. */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        literal(h)?.let { return isPrivateAddress(it) ?: true }
        return false
    }

    /** The address [host] spells out as an IP literal (IPv6 with or without brackets; IPv4 in any form `inet_aton` reads), or null for a name. */
    fun literal(host: String): ByteArray? {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.contains(':')) return v6(h.substringBefore('%'))
        return v4(h)
    }

    private fun v4(h: String): ByteArray? {
        val parts = h.split('.')
        if (parts.size !in 1..4 || parts.any { it.isEmpty() }) return null
        val nums = parts.map { p ->
            val n = when {
                p.startsWith("0x") || p.startsWith("0X") -> p.substring(2).takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }?.toLongOrNull(16)
                p.length > 1 && p.startsWith("0") -> p.takeIf { it.all { c -> c in '0'..'7' } }?.toLongOrNull(8)
                else -> p.takeIf { it.all { c -> c.isDigit() } }?.toLongOrNull()
            } ?: return null
            if (n < 0) return null
            n
        }
        // inet_aton: the last part fills the remaining bytes.
        val last = nums.last()
        val limit = 1L shl (8 * (5 - nums.size))
        if (last >= limit) return null
        if (nums.dropLast(1).any { it > 255 }) return null
        var value = last
        nums.dropLast(1).forEachIndexed { i, n -> value += n shl (8 * (3 - i)) }
        return ByteArray(4) { i -> (value shr (8 * (3 - i))).toByte() }
    }

    private fun v6(h: String): ByteArray? = try {
        // A literal with a colon is never resolved by name.
        InetAddress.getByName(h).address.let { b ->
            // Java folds ::ffff:a.b.c.d into 4 bytes; keep it, the IPv4 rules apply.
            b
        }
    } catch (_: Exception) { null }

    /** Whether the raw [address] (4 or 16 bytes) is local; null when it is not an address. */
    fun isPrivateAddress(address: ByteArray): Boolean? {
        fun u(i: Int) = address[i].toInt() and 0xff
        if (address.size == 4) {
            val a = u(0); val b = u(1)
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 192 && b == 0 && u(2) == 0) || (a == 198 && b in 18..19)
        }
        if (address.size != 16) return null
        if ((0..9).all { address[it].toInt() == 0 }) {
            // ::, ::1, ::a.b.c.d (compatible) and ::ffff:a.b.c.d (mapped)
            val mapped = u(10) == 0xff && u(11) == 0xff
            val compat = u(10) == 0 && u(11) == 0
            if (mapped || compat) return isPrivateAddress(address.copyOfRange(12, 16))
        }
        val first = (u(0) shl 8) or u(1)
        // NAT64 (64:ff9b::/96) and 6to4 (2002::/16) carry an IPv4 address.
        if (first == 0x0064 && u(2) == 0xff && u(3) == 0x9b) return isPrivateAddress(address.copyOfRange(12, 16))
        if (first == 0x2002) return isPrivateAddress(address.copyOfRange(2, 6))
        return (first and 0xfe00) == 0xfc00 ||   // fc00::/7
            (first and 0xffc0) == 0xfe80 ||      // fe80::/10
            (first and 0xffc0) == 0xfec0 ||      // fec0::/10 (site-local, deprecated)
            (first and 0xff00) == 0xff00 ||      // multicast
            first == 0x0100 && (0..5).all { address[2 + it].toInt() == 0 } // 100::/64 discard
    }

    /** A host that is, or resolves to, a local address (the connection is never made). */
    class LocalAddress(message: String) : UnknownHostException(message)

    /** Resolves names with [upstream] and refuses the whole answer when any address is local (no mix to pick from). */
    class CheckedDns(private val upstream: Dns = Dns.SYSTEM) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (isPrivateHost(hostname)) throw LocalAddress("$hostname is a local address")
            val all = upstream.lookup(hostname)
            for (a in all) {
                if (a is Inet4Address || a is Inet6Address) {
                    if (isPrivateAddress(a.address) != false) throw LocalAddress("$hostname resolves to a local address")
                }
            }
            return all
        }
    }

    /** The system resolver, checked. */
    val SafeDns: Dns = CheckedDns()

    /**
     * An OkHttp client that never connects to a local address, follows no redirect (the caller
     * decides, and checks the next address with [checkUrlHost]), keeps no cookie and no cache.
     * Literal IP addresses skip the [Dns] in OkHttp: the interceptor checks them.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(SafeDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                checkUrlHost(chain.request().url.host)
                chain.proceed(chain.request())
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Throws when [host] is a local address (the name, or an IP literal). */
    fun checkUrlHost(host: String) {
        if (isPrivateHost(host)) throw LocalAddress("$host is a local address")
    }

    /** Whether [e], or something it was caused by, is a refusal of a local address. */
    fun isRefusal(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is LocalAddress) return true
            t = t.cause
        }
        return false
    }

    /** [url] as it is written in a log: no user info, no query, no fragment (they carry tokens). */
    fun redact(url: HttpUrl): String = "${url.scheme}://${url.host}${if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""}${url.encodedPath}"
}
