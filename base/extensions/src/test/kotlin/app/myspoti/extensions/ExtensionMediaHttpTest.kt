package app.myspoti.extensions

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/** Address classification, and the redirect loop that fetches an extension's media with a fake transport. */
class ExtensionMediaHttpTest {
    private fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun v6(text: String) = InetAddress.getByName(text).address

    // ── Address classification ──

    @Test fun ipv4Boundaries() {
        val local = listOf(ip(0, 0, 0, 0), ip(10, 0, 0, 1), ip(127, 255, 255, 254), ip(100, 64, 0, 0), ip(100, 127, 255, 255), ip(169, 254, 0, 1), ip(172, 16, 0, 0), ip(172, 31, 255, 255),
            ip(192, 168, 255, 255), ip(192, 0, 0, 1), ip(198, 18, 0, 1), ip(198, 19, 255, 255), ip(224, 0, 0, 1), ip(239, 255, 255, 250), ip(255, 255, 255, 255))
        local.forEach { assertEquals(it.joinToString("."), true, SafeNet.isPrivateAddress(it)) }
        val public = listOf(ip(8, 8, 8, 8), ip(100, 63, 255, 255), ip(100, 128, 0, 0), ip(172, 15, 255, 255), ip(172, 32, 0, 0), ip(169, 253, 255, 255), ip(192, 167, 1, 1), ip(198, 17, 0, 1), ip(198, 20, 0, 1), ip(223, 255, 255, 255))
        public.forEach { assertEquals(it.joinToString("."), false, SafeNet.isPrivateAddress(it)) }
    }

    @Test fun ipv6Forms() {
        listOf("::", "::1", "::ffff:127.0.0.1", "::ffff:10.1.2.3", "::ffff:192.168.0.9", "::127.0.0.1", "fc00::1", "fdff::1", "fe80::1", "febf::1", "ff02::1", "ff00::", "64:ff9b::a00:1", "2002:c0a8:101::1")
            .forEach { assertEquals(it, true, SafeNet.isPrivateAddress(v6(it))) }
        listOf("2001:4860:4860::8888", "2606:4700::1111", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::1", "fbff::1", "2a00:1450::1")
            .forEach { assertEquals(it, false, SafeNet.isPrivateAddress(v6(it))) }
    }

    @Test fun notAnAddress() {
        assertNull(SafeNet.isPrivateAddress(ByteArray(5)))
        assertNull(SafeNet.isPrivateAddress(ByteArray(0)))
    }

    @Test fun hostSpellings() {
        listOf("LOCALHOST.", "x.internal", "nas.lan", "router.home.arpa", "", "  ", "0x7f.0.0.1", "[::ffff:a00:1]").forEach { assertTrue("[$it]", SafeNet.isPrivateHost(it)) }
        listOf("cdn.example.org", "xn--bcher-kva.example", "1.2.3.4").forEach { assertFalse(it, SafeNet.isPrivateHost(it)) }
    }

    // ── DNS: every address of the answer counts ──

    private fun dnsReturning(vararg addresses: String) = SafeNet.CheckedDns(Dns { _ -> addresses.map { InetAddress.getByName(it) } })

    @Test fun dnsRefusesAMixedAnswer() {
        assertEquals(1, dnsReturning("93.184.216.34").lookup("cdn.example.org").size)
        listOf(arrayOf("93.184.216.34", "10.0.0.5"), arrayOf("::1"), arrayOf("fe80::1"), arrayOf("169.254.169.254"), arrayOf("::ffff:192.168.1.1"), arrayOf("100.64.1.1")).forEach {
            try { dnsReturning(*it).lookup("cdn.example.org"); fail(it.joinToString()) } catch (e: SafeNet.LocalAddress) { assertTrue(SafeNet.isRefusal(IOException(e))) }
        }
    }

    @Test fun dnsDoesNotAskForLocalNames() {
        val dns = SafeNet.CheckedDns(Dns { fail("asked"); emptyList() })
        try { dns.lookup("printer.local"); fail() } catch (_: SafeNet.LocalAddress) { /* refused before any lookup */ }
    }

    @Test fun redactedUrlHasNoQuery() {
        val u = "https://user:pw@cdn.example.org:8443/a/b.flac?token=SECRET#frag".toHttpUrl()
        assertEquals("https://cdn.example.org:8443/a/b.flac", SafeNet.redact(u))
    }

    // ── Redirects ──

    private class Fake(val routes: Map<String, (Request) -> Response>) {
        val seen = mutableListOf<Request>()
        val transport = ExtensionMediaHttp.Transport { r, _ ->
            seen += r
            (routes[r.url.toString()] ?: error("unexpected ${r.url}")).invoke(r)
        }
    }

    private fun reply(r: Request, code: Int, location: String? = null, body: String = "") =
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("m").apply { location?.let { header("Location", it) } }.body(body.toByteArray().toResponseBody()).build()

    private fun http(fake: Fake, logs: MutableList<String> = mutableListOf(), clock: () -> Long = { 0 }) = ExtensionMediaHttp(fake.transport, { logs += it }, clock)

    @Test fun followsRedirectsAndKeepsHeadersOnTheSameHost() {
        val fake = Fake(mapOf(
            "https://a.example.org/s" to { r -> reply(r, 302, "/t") },
            "https://a.example.org/t" to { r -> reply(r, 200, body = "ok") },
        ))
        val o = http(fake).open("https://a.example.org/s", headers = mapOf("Authorization" to "Bearer x", "X-Token" to "t"), fixed = mapOf("Range" to "bytes=5-"))
        assertEquals(200, o.response.code)
        assertEquals(1, o.redirects)
        assertEquals("https://a.example.org/t", o.url.toString())
        fake.seen.forEach {
            assertEquals("Bearer x", it.header("Authorization")); assertEquals("t", it.header("X-Token")); assertEquals("bytes=5-", it.header("Range")); assertEquals("identity", it.header("Accept-Encoding"))
        }
    }

    @Test fun dropsTheExtensionsHeadersOnAnotherHostOrPort() {
        val fake = Fake(mapOf(
            "https://a.example.org/s" to { r -> reply(r, 301, "https://cdn.example.net/f") },
            "https://cdn.example.net/f" to { r -> reply(r, 307, "https://cdn.example.net:8443/g") },
            "https://cdn.example.net:8443/g" to { r -> reply(r, 200) },
        ))
        http(fake).open("https://a.example.org/s", headers = mapOf("Authorization" to "Bearer x", "Cookie" to "a=b", "X-Token" to "t"), fixed = mapOf("Range" to "bytes=0-9", "User-Agent" to "app"))
        assertEquals("Bearer x", fake.seen[0].header("Authorization"))
        fake.seen.drop(1).forEach {
            assertNull(it.header("Authorization")); assertNull(it.header("Cookie")); assertNull(it.header("X-Token"))
            assertEquals("bytes=0-9", it.header("Range")); assertEquals("app", it.header("User-Agent"))
        }
        assertEquals(3, fake.seen.size)
    }

    @Test fun refusesAHopToALocalAddressAndLogsWithoutTheQuery() {
        val logs = mutableListOf<String>()
        for (target in listOf("http://169.254.169.254/latest?token=SECRET", "http://127.0.0.1:8080/x?k=SECRET", "http://localhost/x", "http://[::1]/x", "http://2130706433/x", "http://nas.local/x")) {
            val fake = Fake(mapOf("https://a.example.org/s?sig=SECRET" to { r -> reply(r, 302, target) }))
            try { http(fake, logs).open("https://a.example.org/s?sig=SECRET"); fail(target) } catch (_: ExtensionMediaHttp.Refused) { /* refused */ }
            assertEquals("no request to $target", 1, fake.seen.size)
        }
        assertEquals(6, logs.size)
        logs.forEach { assertTrue(it, it.startsWith("media: refused ")); assertFalse(it, it.contains("SECRET")); assertFalse(it, it.contains('?')) }
    }

    @Test fun refusesAStartAtALocalAddress() {
        val fake = Fake(emptyMap())
        try { http(fake).open("http://192.168.1.10/a.flac"); fail() } catch (_: ExtensionMediaHttp.Refused) { /* refused */ }
        assertTrue(fake.seen.isEmpty())
        for (bad in listOf("ftp://example.org/a", "file:///etc/passwd", "not a url")) {
            try { http(fake).open(bad); fail(bad) } catch (_: ExtensionMediaHttp.Refused) { /* refused */ }
        }
    }

    @Test fun refusesADowngradeAndAllowsAnUpgrade() {
        val down = Fake(mapOf("https://a.example.org/s" to { r -> reply(r, 302, "http://a.example.org/s") }))
        try { http(down).open("https://a.example.org/s"); fail() } catch (_: ExtensionMediaHttp.Refused) { /* refused */ }
        val up = Fake(mapOf("http://a.example.org/s" to { r -> reply(r, 301, "https://a.example.org/s") }, "https://a.example.org/s" to { r -> reply(r, 200) }))
        assertEquals(200, http(up).open("http://a.example.org/s", headers = mapOf("X-Token" to "t")).response.code)
        assertNull(up.seen[1].header("X-Token")) // another port: treated as another origin
    }

    @Test fun stopsAfterFiveRedirects() {
        val five = Fake((0..4).associate { i -> "https://a.example.org/$i" to { r: Request -> reply(r, 302, "/${i + 1}") } } + ("https://a.example.org/5" to { r: Request -> reply(r, 200) }))
        val o = http(five).open("https://a.example.org/0")
        assertEquals(5, o.redirects)
        val six = Fake((0..5).associate { i -> "https://a.example.org/$i" to { r: Request -> reply(r, 302, "/${i + 1}") } } + ("https://a.example.org/6" to { r: Request -> reply(r, 200) }))
        try { http(six).open("https://a.example.org/0"); fail() } catch (e: ExtensionMediaHttp.Refused) { assertTrue(e.message, e.message!!.contains("redirects")) }
        assertEquals(6, six.seen.size)
    }

    @Test fun redirectLoopEnds() {
        val fake = Fake(mapOf("https://a.example.org/l" to { r -> reply(r, 302, "/l") }))
        try { http(fake).open("https://a.example.org/l"); fail() } catch (_: ExtensionMediaHttp.Refused) { /* refused */ }
    }

    @Test fun aRedirectWithoutLocationIsTheAnswer() {
        val fake = Fake(mapOf("https://a.example.org/s" to { r -> reply(r, 302) }))
        assertEquals(302, http(fake).open("https://a.example.org/s").response.code)
    }

    @Test fun errorStatusesAreReturnedNotThrown() {
        val fake = Fake(mapOf("https://a.example.org/s" to { r -> reply(r, 403) }))
        assertEquals(403, http(fake).open("https://a.example.org/s").response.code)
    }

    @Test fun elapsedTimeCoversAllHops() {
        var t = 1_000L
        val fake = Fake(mapOf("https://a.example.org/s" to { r -> t += 40; reply(r, 302, "/t") }, "https://a.example.org/t" to { r -> t += 60; reply(r, 200) }))
        assertEquals(100L, http(fake, clock = { t }).open("https://a.example.org/s").elapsedMs)
    }

    @Test fun aRefusedConnectionIsLoggedOnce() {
        val logs = mutableListOf<String>()
        val transport = ExtensionMediaHttp.Transport { _, _ -> throw SafeNet.LocalAddress("cdn.example.org resolves to a local address") }
        try { ExtensionMediaHttp(transport, { logs += it }).open("https://cdn.example.org/a?sig=SECRET"); fail() } catch (_: java.net.UnknownHostException) { /* refused */ }
        assertEquals(listOf("media: refused https://cdn.example.org/a: cdn.example.org resolves to a local address"), logs)
    }

    @Test fun theAppsHeadersWinOnTheSameName() {
        val h = ExtensionMediaHttp.build(mapOf("range" to "bytes=1-", "X-A" to "1"), mapOf("Range" to "bytes=0-9"))
        assertEquals(listOf("bytes=0-9"), h.values("Range"))
        assertEquals("identity", h["Accept-Encoding"])
        assertEquals("gzip", ExtensionMediaHttp.build(mapOf("Accept-Encoding" to "gzip"), emptyMap())["Accept-Encoding"])
    }
}
