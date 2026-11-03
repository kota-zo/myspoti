package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The action catalogue, its manifests (new and old), the native blocks and the protected surfaces. */
class ActionsTest {
    private val track = TrackInfo("abc", "spotify:track:abc", "Song", "Artist", listOf("Artist"), "Album", 180_000)

    private fun manifest(vararg actions: ActionRequest) =
        Manifest("io.github.test.act", "Act", "1.0", author = "me", actions = actions.toList())

    private fun a(name: String, why: String = "Shows what you need", hosts: List<String> = emptyList()) = ActionRequest(name, why, hosts)

    private class QuietHost : Host {
        val logs = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        val posted = mutableListOf<String>()
        override fun log(extensionId: String, message: String) { logs += message }
        val store = MemoryStorage()
        override fun storage(extensionId: String): Storage = store
        override fun setting(extensionId: String, key: String): String? = null
        override fun refresh(extensionId: String, surface: String) { refreshed += surface }
        override fun postToPage(extensionId: String, json: String) { posted += json }
    }

    // ── The catalogue ──

    @Test fun theCatalogueIsClosedAndComplete() {
        assertEquals(25, Action.entries.size)
        assertEquals(Action.entries.size, Action.entries.map { it.key }.toSet().size)
        assertEquals(
            setOf("audio.source", "download.source", "library.write", "playlists.write", "spotify.raw", "webview.page", "webview.auth", "playback.mirror"),
            Action.entries.filter { it.level == Level.CRITICAL }.map { it.key }.toSet(),
        )
        Action.entries.forEach { act ->
            assertTrue(act.key, act.icon.isNotBlank() && act.label.isNotBlank() && act.label.length <= 28 && act.api.isNotBlank())
        }
        // Every area has at least one action, and every hook but tap is unlocked by one.
        Area.entries.forEach { area -> assertTrue(area.key, Action.entries.any { it.area == area }) }
        Hook.entries.filter { it != Hook.TAP && !it.free }.forEach { h -> assertTrue(h.key, h.action != null) }
    }

    // ── Sign-in pages and work that waits for the network ──

    private class AuthHost : Host {
        val opened = mutableListOf<AuthRequest>()
        var online = 0
        override fun log(extensionId: String, message: String) {}
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = null
        override fun openAuth(extensionId: String, request: AuthRequest): String { opened += request; return "auth-${opened.size}" }
        override fun whenOnline(extensionId: String) { online++ }
    }

    private fun hooked(body: String, host: Host, vararg actions: ActionRequest): String? =
        ExtensionRuntime(manifest(a("lyrics.provide"), *actions), "myspoti.provide('lyrics', function () { try { return { v: $body }; } catch (e) { return { v: 'error: ' + e.message }; } });", host, (actions.map { it.name } + "lyrics.provide").toSet())
            .use { it.start(); it.call(Hook.LYRICS, track.toJson(), 3_000) }

    @Test fun authOpensOnlyWithItsActionAndSafeAddresses() {
        val host = AuthHost()
        assertEquals("""{"v":"auth-1"}""", hooked("myspoti.auth.open({ url: 'https://id.example.com/authorize?x=1', callback: 'myext://done' })", host, a("webview.auth")))
        assertTrue(hooked("myspoti.auth.open({ url: 'https://id.example.com/', callback: 'myext://done' })", host)!!.contains("webview.auth"))
        for ((url, cb) in listOf(
            "http://id.example.com/" to "myext://done",
            "https://accounts.spotify.com/login" to "myext://done",
            "https://id.example.com/" to "javascript:alert(1)",
            "https://id.example.com/" to "http://cb.example.com/",
            "https://id.example.com/" to "https://open.spotify.com/cb",
            "https://id.example.com/" to "intent://x",
        )) assertTrue("$url $cb", hooked("myspoti.auth.open({ url: '$url', callback: '$cb' })", host, a("webview.auth"))!!.startsWith("""{"v":"error:"""))
        assertEquals(1, host.opened.size)
        // webview.page gives no sign-in page.
        assertTrue(hooked("myspoti.auth.open({ url: 'https://id.example.com/', callback: 'myext://done' })", host, a("webview.page"))!!.contains("webview.auth"))
    }

    @Test fun aHiddenSignInIsAskedForByAGenericOptionOnly() {
        val host = AuthHost()
        assertEquals("""{"v":"auth-1"}""", hooked("myspoti.auth.open({ url: 'https://id.example.com/check', callback: 'myext://done', hidden: true })", host, a("webview.auth")))
        assertEquals("""{"v":"auth-2"}""", hooked("myspoti.auth.open({ url: 'https://id.example.com/check', callback: 'myext://done' })", host, a("webview.auth")))
        // Only a real true hides it; and hidden or not, the addresses are checked the same way.
        assertEquals("""{"v":"auth-3"}""", hooked("myspoti.auth.open({ url: 'https://id.example.com/check', callback: 'myext://done', hidden: 'yes' })", host, a("webview.auth")))
        assertTrue(hooked("myspoti.auth.open({ url: 'http://id.example.com/', callback: 'myext://done', hidden: true })", host, a("webview.auth"))!!.startsWith("""{"v":"error:"""))
        assertEquals(listOf(true, false, false), host.opened.map { it.hidden })
        assertEquals(AuthRequest("https://id.example.com/check", "myext://done", hidden = true), host.opened[0])
    }

    @Test fun theCallbackIsReachedByItsPrefixOnly() {
        val r = AuthRequest("https://id.example.com/", "https://cb.example.com/done")
        assertTrue(r.reached("https://cb.example.com/done?code=1"))
        assertTrue(r.reached("https://cb.example.com/done"))
        assertFalse(r.reached("https://cb.example.com/doneX?code=1"))
        assertFalse(r.reached("https://cb.example.com.evil.net/done"))
        assertTrue(AuthRequest("https://id.example.com/", "myext://done").reached("myext://done#token=1"))
    }

    @Test fun authAndOnlineEventsNeedTheirActions() {
        val host = AuthHost()
        val script = "myspoti.on('auth', function (e) { myspoti.storage.set('auth', e); }); myspoti.on('online', function () { myspoti.storage.set('online', true); }); myspoti.work.whenOnline();"
        ExtensionRuntime(manifest(a("webview.auth"), a("network", hosts = listOf("api.example.com")), a("storage")), script, host, setOf("webview.auth", "network", "storage")).use { rt ->
            rt.start()
            assertEquals(1, host.online)
            assertTrue(rt.listens(ExtensionRuntime.EVENT_AUTH) && rt.listens(ExtensionRuntime.EVENT_ONLINE))
            rt.emit(ExtensionRuntime.EVENT_AUTH, ExtJson.encodeToString(AuthResult.serializer(), AuthResult("auth-1", url = "myext://done?code=1")))
            rt.emit(ExtensionRuntime.EVENT_ONLINE, "{}")
        }
        assertTrue(ExtensionRuntime(manifest(a("storage")), "myspoti.on('auth', function () {});", host, setOf("storage")).runCatching { use { it.start() } }.isFailure)
        assertTrue(ExtensionRuntime(manifest(a("storage")), "myspoti.work.whenOnline();", host, setOf("storage")).runCatching { use { it.start() } }.isFailure)
        assertEquals(1, host.online)
    }

    @Test fun aButtonSettingHasNoDefaultAndTheSettingEventNeedsSettingsPage() {
        val ok = Manifest("io.github.test.act", "Act", "1.0", author = "me", actions = listOf(a("settings.page")),
            settings = listOf(Setting("pair", "button", "Pair again"), Setting("quality", "choice", "Quality", options = listOf("FLAC", "MP3"))))
        assertEquals(emptyList<String>(), ok.problems())
        val bad = ok.copy(settings = listOf(Setting("pair", "button", "Pair again", default = kotlinx.serialization.json.JsonPrimitive(true))))
        assertTrue(bad.problems().any { "button has no default" in it })

        val host = QuietHost()
        ExtensionRuntime(ok.copy(actions = listOf(a("settings.page"), a("storage"))), "myspoti.on('setting', function (e) { myspoti.storage.set('last', e); });", host, setOf("settings.page", "storage")).use { rt ->
            rt.start()
            assertTrue(rt.listens(ExtensionRuntime.EVENT_SETTING))
            rt.emit(ExtensionRuntime.EVENT_SETTING, """{"key":"pair","pressed":true}""")
        }
        assertEquals("""{"key":"pair","pressed":true}""", host.store.get("last"))
        assertTrue(ExtensionRuntime(manifest(a("storage")), "myspoti.on('setting', function () {});", host, setOf("storage")).runCatching { use { it.start() } }.isFailure)
    }

    @Test fun oldManifestsMapToActions() {
        val old = Manifest("io.github.old.ext", "Old", "1.0", author = "me", provides = listOf("lyrics", "download-source"),
            permissions = listOf(
                PermissionRequest("download-source", why = "Finds the files on my own server, nowhere else at all"),
                PermissionRequest("network", listOf("api.example.com")),
                PermissionRequest("player.read", why = "Scrobbles"),
                PermissionRequest("player.control"),
                PermissionRequest("notify"),
            ))
        assertTrue(old.isLegacy)
        assertEquals(emptyList<String>(), old.problems())
        assertEquals(
            listOf("lyrics.provide", "download.source", "network", "playback.events", "playback.control", "notify", "storage"),
            old.declared().map { it.name },
        )
        assertEquals("Finds the files on my own server, nowhere else at all", old.asks().first { it.key == "download.source" }.why)
        assertEquals(listOf("api.example.com"), old.hosts())
        // What an old install was granted becomes its actions; new keys are kept as they are.
        assertEquals(
            setOf("lyrics.provide", "network", "playback.events", "library.read", "storage", "settings.page"),
            Action.migrateGranted(setOf("lyrics", "network", "spotify.library.read")),
        )
        assertEquals(setOf("lyrics.provide", "network.any"), Action.migrateGranted(setOf("lyrics.provide", "network.any")))
        // An old install keeps working: granted with old keys, its hook and its storage answer.
        val host = QuietHost()
        ExtensionRuntime(old, "myspoti.storage.set('a', 1); myspoti.provide('lyrics', function () { return { plain: 'la ' + myspoti.storage.get('a') }; });",
            host, setOf("lyrics", "network", "download-source")).use { rt ->
            rt.start()
            assertEquals("""{"plain":"la 1"}""", rt.call(Hook.LYRICS, track.toJson(), 3_000))
        }
    }

    @Test fun everyActionSaysWhyIn80Characters() {
        assertEquals(emptyList<String>(), manifest(a("lyrics.provide"), a("network", hosts = listOf("lrclib.net"))).problems())
        val missing = manifest(a("lyrics.provide", why = " ")).problems()
        assertTrue(missing.toString(), missing.single().contains("action \"lyrics.provide\": say why"))
        val long = manifest(a("lyrics.provide", why = "x".repeat(81))).problems()
        assertTrue(long.toString(), long.single().contains("81 characters, 80 at most"))
        assertTrue(manifest(a("lyrics.provide", why = "x".repeat(80))).problems().isEmpty())
        // Unknown names, twice the same, hosts out of place, settings without their page, both formats at once.
        val p = manifest(a("everything"), a("notify"), a("notify"), a("storage", hosts = listOf("x.org")), a("network")).copy(
            settings = listOf(Setting("k", label = "K")), provides = listOf("lyrics"),
        ).problems().joinToString("\n")
        for (want in listOf("action \"everything\": unknown", "an action listed twice", "only network takes hosts", "network: list the hosts",
                "settings.page action", "cannot be mixed")) assertTrue("$want in\n$p", p.contains(want))
        assertTrue(manifest().problems().single().startsWith("actions: at least one"))
    }

    @Test fun theRuntimeChecksActionsNotNames() {
        val m = manifest(a("home.sections"), a("menu.items"), a("storage"))
        val host = QuietHost()
        val script = """
            myspoti.provide('home', function () { return [{ type: 'section', title: 'Mine', blocks: [{ type: 'text', text: 'hi' }] }]; });
            myspoti.provide('tap', function (e) { myspoti.log('tap ' + e.action); myspoti.ui.refresh('home'); });
        """
        ExtensionRuntime(m, script, host, m.asks().map { it.key }.toSet(), guard = SurfaceGuard()).use { rt ->
            rt.start()
            assertTrue(rt.provides(Hook.HOME) && rt.provides(Hook.TAP) && !rt.provides(Hook.LYRICS))
            val blocks = Blocks.parse(rt.call(Hook.HOME, "{}", 3_000)!!, Surface.HOME)
            assertEquals(listOf(Block.Section("Mine", null, listOf(Block.Text("hi", TextStyle.BODY)))), blocks)
            rt.call(Hook.TAP, """{"action":"go","surface":"home"}""", 3_000)
        }
        assertEquals(listOf("tap go"), host.logs)
        assertEquals(listOf("home"), host.refreshed)
        // A hook whose action is not declared cannot even be provided.
        try {
            ExtensionRuntime(m, "myspoti.provide('player', function () { return []; });", host, emptySet()).use { it.start() }
            fail("provided player without player.buttons")
        } catch (e: ExtensionException) { assertTrue(e.message, e.message!!.contains("player.buttons")) }
        // Declared but not granted: the app does not ask it.
        ExtensionRuntime(m, script, host, setOf("menu.items"), guard = SurfaceGuard()).use { rt ->
            rt.start()
            assertFalse(rt.provides(Hook.HOME))
            assertNull(rt.call(Hook.HOME, "{}", 3_000))
        }
    }

    // ── Native blocks ──

    @Test fun blocksAreParsedForTheirPlace() {
        val home = Blocks.parse("""[
            { "type": "card", "title": "Daily", "subtitle": "for you", "image": "https://i.example.org/a.jpg", "open": "spotify:playlist:37i9dQZF1" },
            { "type": "section", "title": "Picks", "blocks": [
              { "type": "tracks", "title": "Songs", "items": [{ "uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "title": "One" }] },
              { "type": "button", "label": "More", "icon": "nope", "action": "more" },
              { "type": "hologram", "text": "from a newer app" } ] }
        ]""", Surface.HOME)
        assertEquals(2, home.size)
        val picks = home[0] as Block.Section
        assertEquals("Picks", picks.title)
        assertEquals(Block.Button("More", Blocks.DEFAULT_ICON, "more"), picks.blocks[1])
        assertEquals(2, picks.blocks.size)
        assertEquals(Block.Section("", null, listOf(Block.Card("Daily", "for you", "https://i.example.org/a.jpg", Target("spotify:playlist:37i9dQZF1", null)))), home[1])

        val menu = Blocks.parse("""[{ "type": "menu", "label": "Find the sample", "icon": "search", "action": "sample" }]""", Surface.TRACK_MENU)
        assertEquals(listOf(Block.MenuEntry("Find the sample", "search", "sample")), menu)
        assertEquals(1, Blocks.parse("""{ "type": "button", "label": "Scrobble", "icon": "check", "action": "s" }""", Surface.PLAYER).size)

        for ((json, surface) in listOf(
            """[{ "type": "card", "title": "x" }]""" to Surface.PLAYER,                                      // not a button
            """[{ "type": "menu", "label": "x" }]""" to Surface.TRACK_MENU,                                  // no action
            """[{ "type": "card", "title": "x", "open": "https://evil.example.org" }]""" to Surface.HOME,     // not a spotify uri
            """[{ "type": "card", "title": "x", "open": "spotify:track:a", "action": "b" }]""" to Surface.HOME, // both
            """[{ "type": "image", "url": "http://i.example.org/a.jpg" }]""" to Surface.HOME,               // not https
            """[{ "type": "image", "url": "https://192.168.1.1/a.jpg" }]""" to Surface.HOME,                // local
            """[{ "type": "albums", "items": [{ "uri": "spotify:track:a", "title": "x" }] }]""" to Surface.SEARCH, // wrong kind
            """[{ "type": "section", "blocks": [{ "type": "section", "blocks": [] }] }]""" to Surface.HOME,  // nested
            """[{ "type": "button", "label": "x", "action": "a b" }]""" to Surface.HOME,                       // bad action id
            List(5) { """{ "type": "menu", "label": "x", "action": "a" }""" }.joinToString(",", "[", "]") to Surface.ARTIST_MENU, // too many
        )) {
            try { Blocks.parse(json, surface); fail("$surface accepted $json") } catch (_: ExtensionException) {}
        }
        assertEquals(PageContent("Act", "<p>hi</p>"), PageContent.parse("""{ "html": "<p>hi</p>" }""", "Act"))
    }

    @Test fun aPageLoadsOnlyFromItsHosts() {
        val hosts = listOf("api.example.com", "*.cdn.example.org")
        assertTrue(PageNetwork.allows("https://api.example.com/a.png", "GET", hosts, networkGranted = true, anyGranted = false))
        assertTrue(PageNetwork.allows("https://img.cdn.example.org/a.png", "GET", hosts, true, false))
        for (url in listOf("http://api.example.com/a", "https://other.org/a", "https://open.spotify.com/", "https://10.0.0.1/a", "file:///sdcard/a"))
            assertFalse(url, PageNetwork.allows(url, "GET", hosts, true, false))
        assertFalse(PageNetwork.allows("https://api.example.com/a", "POST", hosts, true, false))
        assertFalse("not granted", PageNetwork.allows("https://api.example.com/a", "GET", hosts, false, false))
        assertTrue(PageNetwork.allows("https://any.org/a", "GET", listOf("*"), false, true))
        assertFalse(PageNetwork.allows("https://api.spotify.com/v1/me", "GET", listOf("*"), false, true))
        val csp = PageNetwork.csp(hosts + "*.spotify.com", true, false)
        assertTrue(csp, csp.contains("connect-src https://api.example.com https://*.cdn.example.org;") && !csp.contains("spotify") && csp.contains("frame-src 'none'"))
        assertTrue(PageNetwork.wrap("<p>x</p>", csp).startsWith("<meta http-equiv=\"Content-Security-Policy\""))
    }

    // ── Protected surfaces ──

    @Test fun noActionReachesAProtectedSurface() {
        val protectedKeys = ProtectedSurface.entries.map { it.key }.toSet()
        // The places extensions draw and the protected ones never meet, by name or by action.
        assertTrue(Surface.entries.none { it.key in protectedKeys || it.key.substringBefore('.') in protectedKeys })
        assertTrue(Action.entries.none { it.key in protectedKeys || it.key.substringBefore('.') in protectedKeys })
        assertTrue(Hook.entries.none { it.key in protectedKeys })
        // No event about them exists, and an extension cannot listen for one.
        assertTrue(ExtensionRuntime.EVENTS.keys.none { it in protectedKeys })
        val m = manifest(a("playback.events"))
        for (event in protectedKeys) {
            try {
                ExtensionRuntime(m, "myspoti.on('$event', function () {});", QuietHost(), m.asks().map { it.key }.toSet()).use { it.start() }
                fail("listened to $event")
            } catch (e: ExtensionException) { assertTrue(e.message!!.contains("unknown event")) }
        }
        // A block can only open Spotify content or call its own extension back.
        for (uri in listOf("myspoti:support", "myspoti:about", "myspoti:update", "spotify:user:me", "intent://x")) {
            try { Blocks.parse("""[{ "type": "card", "title": "x", "open": "$uri" }]""", Surface.HOME); fail(uri) } catch (_: ExtensionException) {}
        }
    }

    @Test fun extensionUiWaitsWhileAProtectedSurfaceShows() {
        val guard = SurfaceGuard()
        val m = manifest(a("home.sections"), a("webview.page"), a("lyrics.provide"))
        val host = QuietHost()
        val script = """
            myspoti.provide('home', function () { return [{ type: 'text', text: 'hi' }]; });
            myspoti.provide('lyrics', function () { return { plain: 'la' }; });
            myspoti.provide('page', function () { return { html: 'x' }; });
            myspoti.provide('message', function (d) { myspoti.page.post({ echo: d }); return 'ok'; });
            myspoti.provide('tap', function () { myspoti.log('tapped'); });
        """
        ExtensionRuntime(m, script, host, m.asks().map { it.key }.toSet(), guard = guard).use { rt ->
            rt.start()
            assertTrue(rt.call(Hook.HOME, "{}", 3_000) != null)
            guard.shown(ProtectedSurface.SUPPORT)
            guard.shown(ProtectedSurface.SUPPORT) // shown twice (two sheets), hidden once: still there
            guard.hidden(ProtectedSurface.SUPPORT)
            assertTrue(guard.suspended)
            assertEquals(setOf(ProtectedSurface.SUPPORT), guard.state.value)
            for (h in listOf(Hook.HOME, Hook.PAGE, Hook.MESSAGE, Hook.TAP)) assertNull(h.key, rt.call(h, "{}", 3_000))
            // What is not drawn goes on: lyrics are no UI over the app.
            assertEquals("""{"plain":"la"}""", rt.call(Hook.LYRICS, track.toJson(), 3_000))
            guard.hidden(ProtectedSurface.SUPPORT)
            assertFalse(guard.suspended)
            assertEquals("\"ok\"", rt.call(Hook.MESSAGE, "\"hi\"", 3_000))
        }
        assertTrue(host.logs.none { it == "tapped" })
        assertEquals(listOf("""{"echo":"hi"}"""), host.posted)
    }
}
