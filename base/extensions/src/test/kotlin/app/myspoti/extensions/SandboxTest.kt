package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SandboxTest {
    private val track = TrackInfo("abc", "spotify:track:abc", "Song", "Artist", listOf("Artist"), "Album", 180_000)

    private class FakeHost : Host {
        val requests = mutableListOf<HttpRequest>()
        val logs = mutableListOf<String>()
        val store = MemoryStorage()
        var answer = HttpResponse(200, mapOf("content-type" to "application/json"), """{"ok":1}""")
        override fun http(extensionId: String, request: HttpRequest): HttpResponse { requests += request; return answer }
        override fun log(extensionId: String, message: String) { logs += message }
        override fun storage(extensionId: String): Storage = store
        override fun setting(extensionId: String, key: String): String? = if (key == "lang") "\"fr\"" else null
        override fun nowPlaying(): String = """{"title":"Now"}"""
    }

    private fun manifest(
        provides: List<String> = listOf("lyrics"),
        permissions: List<PermissionRequest> = listOf(PermissionRequest("network", listOf("api.example.com", "*.cdn.example.org"))),
    ) = Manifest("io.github.test.ext", "Test", "1.0", author = "me", provides = provides, permissions = permissions,
        settings = listOf(Setting("depth", "number", "Depth", default = kotlinx.serialization.json.JsonPrimitive(3))))

    private fun runtime(script: String, m: Manifest = manifest(), host: Host = FakeHost(), granted: Set<String> = m.asks().map { it.key }.toSet()) =
        ExtensionRuntime(m, script, host, granted).also { it.start() }

    /** What the lyrics hook answered when it returns the expression [expr]. */
    private fun eval(expr: String, host: Host = FakeHost(), m: Manifest = manifest(), granted: Set<String>? = null): String? =
        runtime("myspoti.provide('lyrics', function (t) { return { v: (function () { $expr })() }; });", m, host, granted ?: m.asks().map { it.key }.toSet())
            .use { it.call(Hook.LYRICS, track.toJson(), 3_000) }

    @Test fun lyricsHookAnswersAndIsParsed() {
        val out = runtime("""
            myspoti.provide('lyrics', function (t) {
              return { provider: 'Test', lines: [{ time: 0, text: t.title }, { time: 1500, text: 'by ' + t.artist, words: [{ time: 1500, text: 'by' }] }] };
            });
        """).use { it.call(Hook.LYRICS, track.toJson(), 3_000) }
        val l = LyricsResult.parse(out!!, "x")!!
        assertEquals("Test", l.provider)
        assertEquals(listOf("Song", "by Artist"), l.lines.map { it.text })
        assertTrue(l.synced)
        assertTrue(l.wordSynced)
    }

    @Test fun noHookMeansNoAnswer() {
        assertNull(runtime("// provides nothing yet").use { it.call(Hook.LYRICS, track.toJson(), 1_000) })
    }

    @Test fun modernSyntaxRuns() {
        assertEquals("""{"v":"a-2-x"}""", eval("""
            const f = (a, b) => a + '-' + b;
            let { x } = { x: 'x' };
            const [first] = ['a'];
            return f(first, `${'$'}{1 + 1}`) + '-' + x;
        """))
    }

    @Test fun noJavaNoStringCode() {
        assertEquals("""{"v":"undefined undefined undefined"}""", eval("return typeof java + ' ' + typeof Packages + ' ' + typeof JavaImporter;"))
        for (attempt in listOf("eval('1+1')", "Function('return 1')()", "(function () {}).constructor('return 1')()", "new Function('return 1')()")) {
            val out = eval("try { $attempt; return 'ran'; } catch (e) { return String(e.message); }")
            assertTrue("$attempt: $out", out!!.contains("not allowed"))
        }
        assertEquals("""{"v":true}""", eval("return (function () {}) instanceof Function && typeof Function.prototype.call === 'function';"))
    }

    @Test fun builtInObjectsCannotBeReplaced() {
        assertEquals("""{"v":"kept"}""", eval("try { myspoti = null; } catch (e) {} try { myspoti.http = null; } catch (e) {} return typeof myspoti.http.get === 'function' ? 'kept' : 'lost';"))
        assertEquals("""{"v":"undefined"}""", eval("return typeof __host;"))
    }

    @Test fun aSlowLoadIsATimeoutAndAnErrorIsNot() {
        val m = manifest()
        val slow = ExtensionRuntime(m, "while (true) {}", FakeHost(), m.asks().map { it.key }.toSet(), ExtensionRuntime.Limits(loadMs = 300))
        try { slow.start(); fail("loaded") } catch (e: ExtensionException) { assertTrue(e is ExtensionTimeout) } finally { slow.close() }
        val broken = ExtensionRuntime(m, "throw new Error('no')", FakeHost(), m.asks().map { it.key }.toSet())
        try { broken.start(); fail("loaded") } catch (e: ExtensionException) { assertFalse(e is ExtensionTimeout) } finally { broken.close() }
    }

    @Test fun aCallWaitingBehindASlowOneHasItsOwnTime() {
        // The first call takes 1.2 s; the second, 0.5 s allowed, waits for it then runs at once.
        val rt = runtime("""myspoti.provide('lyrics', function (t) {
            if (t.title === 'slow') { var end = Date.now() + 1200; while (Date.now() < end) {} }
            return { plain: t.title };
        });""")
        val slow = Thread { rt.call(Hook.LYRICS, track.copy(title = "slow").toJson(), 3_000) }.apply { start() }
        Thread.sleep(100)
        val quick = rt.call(Hook.LYRICS, track.copy(title = "quick").toJson(), 500)
        assertTrue(quick!!.contains("quick"))
        slow.join()
        rt.close()
    }

    @Test fun anEndlessLoopIsStopped() {
        val rt = runtime("myspoti.provide('lyrics', function () { while (true) {} });")
        val started = System.currentTimeMillis()
        try {
            rt.call(Hook.LYRICS, track.toJson(), 500)
            fail("an endless loop answered")
        } catch (e: ExtensionException) {
            assertTrue(e.message!!.contains("took longer"))
            assertTrue("a time limit is told apart from the script's own errors", e is ExtensionTimeout)
        }
        assertTrue(System.currentTimeMillis() - started < 3_000)
        // The extension's thread is free again for the next call.
        rt.close()
    }

    @Test fun aCallCutShortByCloseIsNotTheScriptsFailure() {
        val rt = runtime("myspoti.provide('lyrics', function () { while (true) {} });")
        Thread { Thread.sleep(200); rt.close() }.start()
        try {
            rt.call(Hook.LYRICS, track.toJson(), 5_000)
            fail("a closed extension answered")
        } catch (e: ExtensionException) {
            assertEquals("stopped", e.message)
        }
        assertTrue(rt.closed)
        assertNull(rt.lastError)
    }

    @Test fun networkGoesOnlyToTheListedHosts() {
        val host = FakeHost()
        assertEquals("""{"v":1}""", eval("return myspoti.http.get('https://api.example.com/x?' + myspoti.query({ q: 'a b', n: null })).json().ok;", host))
        assertEquals("https://api.example.com/x?q=a%20b", host.requests.single().url)
        assertEquals("""{"v":true}""", eval("return myspoti.http.get('https://img.cdn.example.org/a').ok;", host))
        for (url in listOf("https://evil.com/", "https://api.example.com.evil.com/", "http://127.0.0.1/", "https://localhost/", "http://cdn.example.org/")) {
            val out = eval("try { myspoti.http.get('$url'); return 'sent'; } catch (e) { return e.message; }", host)
            assertFalse("$url: $out", out!!.contains("sent"))
        }
        assertEquals(2, host.requests.size)
    }

    /** Answers each request with its url as the body, after [delayMs]; fails on a url with "broken". */
    private class SlowHost(val delayMs: Long) : Host {
        val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
        override fun http(extensionId: String, request: HttpRequest): HttpResponse {
            requests += request
            Thread.sleep(delayMs)
            if ("broken" in request.url) throw java.io.IOException("connection reset")
            return HttpResponse(200, emptyMap(), request.url)
        }
        override fun log(extensionId: String, message: String) {}
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = null
    }

    @Test fun httpAllSendsTogetherAndAnswersInOrder() {
        val host = SlowHost(400)
        val started = System.nanoTime()
        val out = eval("""
            return myspoti.http.all([1, 2, 3, 4].map(function (n) { return { url: 'https://api.example.com/' + n }; }))
              .map(function (r) { return r.ok + ':' + r.body; }).join(' ');
        """, host)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals("""{"v":"true:https://api.example.com/1 true:https://api.example.com/2 true:https://api.example.com/3 true:https://api.example.com/4"}""", out)
        assertTrue("4 requests of 400 ms took $ms ms", ms < 1_400)
        assertEquals(4, host.requests.size)
    }

    @Test fun httpAllIsolatesAFailedRequest() {
        val host = SlowHost(0)
        val out = eval("""
            var r = myspoti.http.all([{ url: 'https://api.example.com/a' }, { url: 'https://evil.com/' }, { url: 'https://api.example.com/broken' }, { url: 'https://img.cdn.example.org/b', method: 'POST', body: { x: 1 } }]);
            return [r[0].body, r[1].error, r[2].error, r[3].body, typeof r[1].ok].join('|');
        """, host)!!
        assertTrue(out, out.startsWith("""{"v":"https://api.example.com/a|http: evil.com is not in this extension's network hosts|connection reset|https://img.cdn.example.org/b|undefined"""))
        // The refused one never left; the body that is not text went as JSON.
        assertEquals(3, host.requests.size)
        assertEquals("""{"x":1}""", host.requests.first { it.method == "POST" }.body)
        assertEquals("application/json", host.requests.first { it.method == "POST" }.headers["Content-Type"])
    }

    @Test fun httpAllHasItsLimits() {
        val five = eval("try { myspoti.http.all([1, 2, 3, 4, 5].map(function (n) { return { url: 'https://api.example.com/' + n }; })); return 'sent'; } catch (e) { return e.message; }", SlowHost(0))
        assertEquals("""{"v":"http.all: at most 4 requests at once"}""", five)
        assertEquals("""{"v":0}""", eval("return myspoti.http.all([]).length;", SlowHost(0)))
        // Counted in the requests per minute like http.request.
        val host = SlowHost(0)
        val m = manifest()
        val rt = ExtensionRuntime(m, """
            myspoti.provide('lyrics', function () {
              var r = myspoti.http.all([1, 2, 3].map(function (n) { return { url: 'https://api.example.com/' + n }; }));
              return { v: r.map(function (x) { return x.error || x.status; }).join(',') };
            });
        """, host, m.asks().map { it.key }.toSet(), ExtensionRuntime.Limits(requestsPerMinute = 2)).also { it.start() }
        rt.use { assertEquals("""{"v":"200,200,more than 2 requests in a minute"}""", it.call(Hook.LYRICS, track.toJson(), 3_000)) }
        // Without the network right, every request is refused in place.
        val none = eval("return myspoti.http.all([{ url: 'https://api.example.com/a' }])[0].error;", SlowHost(0), granted = setOf("lyrics"))
        assertTrue(none!!, none.contains("not in this extension's network hosts"))
    }

    @Test fun featuresNameWhatThisAppUnderstands() {
        assertEquals("""{"v":"audio.manifest,audio.expires,audio.quality,audio.source,audio.ahead,http.all,status,raw.base64,results"}""", eval("return myspoti.features.join(',');"))
    }

    @Test fun localMuteFeatureIsListedOnlyWhenMirrorIsGranted() {
        val m = Manifest("io.github.test.ext", "Test", "1.0", author = "me",
            actions = listOf(ActionRequest("lyrics.provide", "x"), ActionRequest("playback.mirror", "Mirrors a stream silently")))
        val js = "myspoti.provide('lyrics', function (t) { return { v: myspoti.features.indexOf('player.localMute') >= 0 }; });"
        assertEquals("""{"v":true}""", runtime(js, m, granted = setOf("lyrics.provide", "playback.mirror")).use { it.call(Hook.LYRICS, track.toJson(), 3_000) })
        assertEquals("""{"v":false}""", runtime(js, m, granted = setOf("lyrics.provide")).use { it.call(Hook.LYRICS, track.toJson(), 3_000) })
    }

    @Test fun statusNeedsNoActionAndSaysWhetherTheSourceAnswers() {
        // Provided with only the lyrics action granted: status asks for nothing more.
        val m = manifest()
        assertTrue(runtime("", m, granted = emptySet()).use { it.provides(Hook.STATUS) })
        val down = runtime("myspoti.provide('status', function () { return { live: false, detail: '  ' + 'x'.repeat(300) }; });", m)
        down.use {
            val s = ExtensionStatus.probe(it, 3_000)
            assertFalse(s.live)
            assertEquals(ExtensionStatus.MAX_DETAIL, s.detail!!.length)
        }
        assertEquals(ExtensionStatus(true, "ok"), runtime("myspoti.provide('status', function () { return { live: true, detail: 'ok' }; });", m).use { ExtensionStatus.probe(it, 3_000) })
        // No status hook: live.
        assertEquals(ExtensionStatus.LIVE, runtime("myspoti.provide('lyrics', function () { return null; });", m).use { ExtensionStatus.probe(it, 3_000) })
    }

    @Test fun aStatusThatThrowsIsLateOrIsNotOneIsUnknownAndStaysInUse() {
        val m = manifest()
        val threw = runtime("myspoti.provide('status', function () { throw new Error('server down'); });", m).use { ExtensionStatus.probe(it, 3_000) }
        assertTrue(threw.live)
        assertEquals("unknown", threw.state)
        assertTrue(threw.detail!!, threw.detail!!.contains("server down"))
        val late = runtime("myspoti.provide('status', function () { while (true) {} });", m).use { ExtensionStatus.probe(it, 300) }
        assertEquals("unknown", late.state)
        assertTrue(late.detail!!, late.detail!!.contains("longer than 300 ms"))
        assertEquals("unknown", runtime("myspoti.provide('status', function () { return 'yes'; });", m).use { ExtensionStatus.probe(it, 3_000) }.state)
        assertEquals("unknown", runtime("myspoti.provide('status', function () { return { detail: 'no live' }; });", m).use { ExtensionStatus.probe(it, 3_000) }.state)
        // Only an explicit live: false is inactive.
        assertEquals("inactive", runtime("myspoti.provide('status', function () { return { live: false }; });", m).use { ExtensionStatus.probe(it, 3_000) }.state)
    }

    @Test fun theStatusBookKeepsWhoIsInactiveAndWhenItWasAsked() {
        var now = 0L
        val book = StatusBook { now }
        assertFalse(book.inactive("a"))
        assertTrue(book.stale("a", 1_000))
        assertTrue(book.record("a", ExtensionStatus(false, "down")))
        assertTrue(book.inactive("a"))
        assertFalse(book.stale("a", 1_000))
        assertEquals(mapOf("a" to ExtensionStatus(false, "down")), book.inactiveNow())
        // Inactive again: no change; live again: active at once.
        now = 1_000
        assertTrue(book.stale("a", 1_000))
        assertFalse(book.record("a", ExtensionStatus(false, "still down")))
        assertTrue(book.record("a", ExtensionStatus.LIVE))
        assertFalse(book.inactive("a"))
        assertTrue(book.inactiveNow().isEmpty())
        // A probe without an answer is no proof: in use, a change of state all the same.
        assertTrue(book.record("a", ExtensionStatus.unknown("took longer than 5000 ms")))
        assertFalse(book.inactive("a"))
        assertTrue(book.inactiveNow().isEmpty())
        assertEquals("unknown", book.last("a")!!.state)
        book.forget("a")
        assertTrue(book.stale("a", 1_000))
    }

    @Test fun anyHostNeedsItsOwnGrant() {
        val m = manifest(permissions = listOf(PermissionRequest("network", listOf("*"), "Looks the song up on many sites")))
        assertTrue(m.isCritical())
        val granted = eval("return myspoti.http.get('https://anything.net/').status;", m = m)
        assertEquals("""{"v":200}""", granted)
        val refused = eval("try { myspoti.http.get('https://anything.net/'); return 'sent'; } catch (e) { return e.message; }", m = m, granted = setOf("lyrics"))
        assertTrue(refused!!.contains("not in this extension's network hosts"))
    }

    @Test fun sensitiveRightsAreChecked() {
        val notAsked = eval("try { return myspoti.player.current(); } catch (e) { return e.message; }")
        assertTrue(notAsked!!.contains("not in this extension's manifest"))
        val m = manifest(permissions = listOf(PermissionRequest("player.read", why = "Shows the song in a widget")))
        assertEquals("""{"v":"Now"}""", eval("return myspoti.player.current().title;", m = m))
        val notGranted = eval("try { return myspoti.player.current(); } catch (e) { return e.message; }", m = m, granted = setOf("lyrics"))
        assertTrue(notGranted!!.contains("did not grant"))
    }

    @Test fun storageSettingsAndUtil() {
        val host = FakeHost()
        assertEquals("""{"v":[2,"fr",3,null]}""", eval("""
            myspoti.storage.set('n', { a: 2 });
            const n = myspoti.storage.get('n').a;
            myspoti.storage.remove('n');
            return [n, myspoti.settings.get('lang'), myspoti.settings.get('depth'), myspoti.storage.get('n') === undefined ? null : 1];
        """, host))
        assertEquals("""{"v":"aGk= 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}""",
            eval("return myspoti.util.base64Encode('hi') + ' ' + myspoti.util.sha256('hello');"))
    }

    @Test fun storageIsCapped() {
        val m = manifest()
        val rt = ExtensionRuntime(m, "myspoti.provide('lyrics', function () { var s = new Array(2000).join('x'); for (var i = 0; i < 1000; i++) myspoti.storage.set('k' + i, s); });",
            FakeHost(), m.asks().map { it.key }.toSet(), ExtensionRuntime.Limits(storageChars = 100_000)).also { it.start() }
        try { rt.call(Hook.LYRICS, track.toJson(), 3_000); fail("stored past the cap") } catch (e: ExtensionException) { assertTrue(e.message!!.contains("storage full")) }
        rt.close()
    }

    @Test fun aLoadCountsTheTopLevelNotTheCompiling() {
        val m = manifest()
        // Compiling a long script is not its time: thousands of functions load within 300 ms of running.
        val big = (1..4_000).joinToString("\n") { "function f$it(a) { return a + $it; }" } + "\nmyspoti.provide('lyrics', function () { return null; });"
        ExtensionRuntime(m, big, FakeHost(), m.asks().map { it.key }.toSet(), ExtensionRuntime.Limits(loadMs = 300)).also { it.start() }.close()
        val loop = ExtensionRuntime(m, "while (true) {}", FakeHost(), m.asks().map { it.key }.toSet(), ExtensionRuntime.Limits(loadMs = 300))
        try { loop.start(); fail("an endless top level loaded") } catch (e: ExtensionException) { assertTrue(e.message!!.contains("took longer than 300 ms")) }
        loop.close()
    }

    @Test fun aScriptThatThrowsReportsWhere() {
        try {
            runtime("var a = 1;\nnull.boom;")
            fail("loaded")
        } catch (e: ExtensionException) {
            assertTrue(e.message, e.message!!.contains("main.js:2"))
        }
    }

    @Test fun provideOnlyWhatTheManifestSays() {
        try {
            runtime("myspoti.provide('audio-source', function () {});")
            fail("provided a hook it did not declare")
        } catch (e: ExtensionException) {
            assertTrue(e.message!!.contains("needs the audio.source action"))
        }
    }

    @Test fun trackEventsReachListenersWithPlayerRead() {
        val host = FakeHost()
        val m = manifest(provides = emptyList(), permissions = listOf(PermissionRequest("player.read", why = "Scrobbles what you listen to")))
        runtime("myspoti.on('track', function (t) { myspoti.log('now ' + t.title + ' ' + t.origin + ' ' + t.providerId); });", m, host).use {
            it.emit("track", PlaybackEvent.trackEventJson(track, PlaybackEvent.ORIGIN_DOWNLOAD, "io.github.x.dl"))
            it.emit("track", PlaybackEvent.trackEventJson(track, PlaybackEvent.ORIGIN_SPOTIFY, null))
        }
        assertEquals(listOf("now Song download io.github.x.dl", "now Song spotify null"), host.logs)
    }

    @Test fun anEventWaitingBehindALongCallIsDroppedQuietly() {
        val host = FakeHost()
        val m = manifest(permissions = listOf(PermissionRequest("player.read", why = "Scrobbles")))
        val script = """
            myspoti.provide('lyrics', function () { var t = Date.now(); while (Date.now() - t < 1500) {} return null; });
            myspoti.on('track', function (t) { myspoti.log('now ' + t.title); });
        """
        runtime(script, m, host).use { rt ->
            val call = Thread { rt.call(Hook.LYRICS, track.toJson(), 5_000) }.apply { start() }
            Thread.sleep(200)
            rt.emit("track", PlaybackEvent.trackEventJson(track, PlaybackEvent.ORIGIN_SPOTIFY, null), timeoutMs = 300)
            call.join()
            assertNull(rt.lastError)
            assertTrue(host.logs.toString(), host.logs.none { it.startsWith("failed") || it.startsWith("now") })
            // Free again: the next event is delivered.
            rt.emit("track", PlaybackEvent.trackEventJson(track, PlaybackEvent.ORIGIN_SPOTIFY, null))
        }
        assertEquals(listOf("now Song"), host.logs)
    }

    @Test fun playbackEventsReachListenersWithPlayerReadOnly() {
        val host = FakeHost()
        val m = manifest(provides = emptyList(), permissions = listOf(PermissionRequest("player.read", why = "Scrobbles what you listen to")))
        val e = PlaybackEvent("skip", track, 40_000, 180_000, 1_700_000_000_000, PlaybackEvent.LOCAL, "spotify:album:x", true, "forward", 40_000)
        val script = "myspoti.on('playback', function (e) { myspoti.log(e.type + ' ' + e.reason + ' ' + e.playedMs + ' ' + e.source + ' ' + e.track.title); });"
        runtime(script, m, host).use {
            assertTrue(it.listens("playback"))
            assertFalse(it.listens("track"))
            it.emit("playback", e.toJson())
        }
        assertEquals(listOf("skip forward 40000 local Song"), host.logs)
        // Every field is there, defaults included.
        val start = PlaybackEvent("start", track, 0, 180_000, 1, PlaybackEvent.SPOTIFY).toJson()
        for (k in listOf("\"reason\":null", "\"shuffle\":false", "\"playedMs\":0", "\"contextUri\":\"\"", "\"origin\":\"spotify\"", "\"providerId\":null")) assertTrue(k, start.contains(k))
        val fromExt = PlaybackEvent("start", track, 0, 180_000, 1, PlaybackEvent.LOCAL, origin = PlaybackEvent.ORIGIN_AUDIO_SOURCE, providerId = "io.github.x.audio").toJson()
        assertTrue(fromExt, fromExt.contains("\"origin\":\"audio-source\"") && fromExt.contains("\"providerId\":\"io.github.x.audio\""))
        // Not granted: nothing is delivered, and the app is told nobody listens.
        val quiet = FakeHost()
        runtime(script, m, quiet, granted = emptySet()).use { assertFalse(it.listens("playback")); it.emit("playback", e.toJson()) }
        assertTrue(quiet.logs.isEmpty())
        // Without player.read in the manifest, on() itself throws.
        try {
            runtime(script, manifest(provides = listOf("lyrics"), permissions = emptyList()))
            fail("listened without player.read")
        } catch (e: ExtensionException) {
            assertTrue(e.message!!.contains("playback.events"))
        }
    }

    @Test fun downloadSourceAnswersAreChecked() {
        val m = manifest(provides = listOf("download-source"), permissions = listOf(PermissionRequest("download-source", why = "Finds where each song can be downloaded")))
        assertTrue(m.problems().toString(), m.problems().isEmpty())
        val out = runtime("""
            myspoti.provide('download-source', function (t) {
              if (t.quality !== 'hi-res' || t.isrc !== 'USUM71703861') return null;
              return [ { url: 'https://cdn.example.org/a.flac', format: 'flac', bitDepth: 24, sampleRate: 96000, sizeHint: 90000000, provider: 'Example', isrcMatched: true, headers: { Referer: 'r', Cookie: 'no' } },
                       { manifest: 'PE1QRD4=', format: 'dash-flac', bitDepth: 24 } ];
            });""", m).use { it.call(Hook.DOWNLOAD_SOURCE, DownloadQuery.toJson(track.copy(isrc = "USUM71703861"), "hi-res"), 3_000) }
        val files = DownloadFile.parseList(out!!)
        assertEquals(2, files.size)
        assertEquals(DownloadFile("https://cdn.example.org/a.flac", null, mapOf("Referer" to "r"), "flac", 24, 96_000, 90_000_000, "Example", true), files[0])
        assertEquals("dash-flac", files[1].format)
        assertTrue(files[1].lossless && files[1].url == null)
        // Single object and { files } forms; nothing is no file.
        assertEquals(1, DownloadFile.parseList("""{"url":"https://x.example.org/a.m4a","format":"m4a"}""").size)
        assertEquals(1, DownloadFile.parseList("""{"files":[{"url":"https://x.example.org/a.mp3","format":"mp3"}]}""").size)
        assertTrue(DownloadFile.parseList("""[]""").isEmpty())
        for (bad in listOf("""[{"url":"https://x.example.org/a"}]""", """[{"url":"https://x.example.org/a","format":"ogg"}]""",
                """[{"url":"http://10.0.0.2/a.flac","format":"flac"}]""", """[{"format":"dash-flac"}]""", "\"text\"")) {
            try { DownloadFile.parseList(bad); fail(bad) } catch (_: ExtensionException) {}
        }
        try { DownloadQuery.toJson(track, "best"); fail("unknown quality") } catch (_: IllegalArgumentException) {}
    }

    @Test fun audioStreamIsChecked() {
        val a = AudioStream.parse("""{"url":"https://cdn.example.org/a.m4a","headers":{"Referer":"x","Cookie":"no"},"mimeType":"audio/mp4"}""")!!
        assertEquals(mapOf("Referer" to "x"), a.headers)
        assertNull(a.source)
        // The label the player shows: one line, 80 characters at most, blank as none.
        assertEquals("FLAC · 16-bit · 44.1 kHz", AudioStream.parse("""{"url":"https://cdn.example.org/a","source":" FLAC ·\n16-bit · 44.1 kHz "}""")!!.source)
        assertEquals(80, AudioStream.parse("""{"url":"https://cdn.example.org/a","source":"${"x".repeat(200)}"}""")!!.source!!.length)
        assertNull(AudioStream.parse("""{"url":"https://cdn.example.org/a","source":"  "}""")!!.source)
        assertNull(AudioStream.parse("""{"url":"https://cdn.example.org/a","source":5}""")!!.source)
        assertNull(AudioStream.parse("""{}"""))
        for (bad in listOf("""{"url":"file:///sdcard/x"}""", """{"url":"http://192.168.1.2/a"}""", """{"url":"content://x"}""")) {
            try { AudioStream.parse(bad); fail(bad) } catch (_: ExtensionException) {}
        }
        assertNull(a.qualityLabel())
        assertEquals(10 * 60_000L, a.keepMs(10 * 60_000L))
    }

    @Test fun audioStreamLifetimeAndQuality() {
        val a = AudioStream.parse("""{"url":"https://cdn.example.org/a.flac","expiresInMs":90000,"bitDepth":24,"sampleRate":96000,"codec":"FLAC"}""")!!
        assertEquals(90_000L, a.expiresInMs)
        assertEquals(90_000L, a.keepMs(600_000))
        assertEquals("FLAC · 24-bit · 96 kHz", a.qualityLabel())
        // At most the app's own time; a past or negative one is asked again at once.
        assertEquals(600_000L, AudioStream.parse("""{"url":"https://x.example.org/a","expiresInMs":86400000}""")!!.keepMs(600_000))
        assertEquals(0L, AudioStream.parse("""{"url":"https://x.example.org/a","expiresInMs":-5}""")!!.keepMs(600_000))
        assertEquals("16-bit · 44.1 kHz", AudioStream.parse("""{"url":"https://x.example.org/a","bitDepth":16,"sampleRate":44100}""")!!.qualityLabel())
        // Out of range or unreadable: left out, never made up.
        val odd = AudioStream.parse("""{"url":"https://x.example.org/a","bitDepth":64,"sampleRate":"fast","codec":"<b>flac</b>"}""")!!
        assertNull(odd.bitDepth); assertNull(odd.sampleRate); assertNull(odd.codec); assertNull(odd.qualityLabel())
        // The same fields with a manifest.
        val mpd = java.util.Base64.getEncoder().encodeToString("<MPD><SegmentTemplate initialization=\"https://x.example.org/0.mp4\" media=\"https://x.example.org/${'$'}Number${'$'}.mp4\"/></MPD>".toByteArray())
        val d = AudioStream.parse("""{"manifest":"$mpd","expiresInMs":30000,"codec":"flac","bitDepth":24}""")!!
        assertEquals(30_000L, d.expiresInMs); assertEquals("FLAC · 24-bit", d.qualityLabel())
    }

    @Test fun anAudioSourceKnowsWhenItIsAskedAhead() {
        val out = runtime("myspoti.provide('audio-source', function (t) { return { url: 'https://cdn.example.org/' + (t.ahead ? 'ahead' : 'now') + '.flac' }; });",
            manifest(provides = listOf("audio-source"))).use { rt ->
            listOf(true, false).map { AudioStream.parse(rt.call(Hook.AUDIO_SOURCE, AudioQuery.toJson(track, it), 3_000)!!)!!.url }
        }
        assertEquals(listOf("https://cdn.example.org/ahead.flac", "https://cdn.example.org/now.flac"), out)
    }

    @Test fun plainLyrics() {
        val l = LyricsResult.parse("""{"plain":"one\ntwo"}""", "Ext")!!
        assertFalse(l.synced)
        assertEquals("Ext", l.provider)
        assertNotNull(l.lines.firstOrNull { it.text == "two" })
    }
}
