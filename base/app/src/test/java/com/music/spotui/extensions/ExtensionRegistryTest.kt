package com.music.spotui.extensions

import app.myspoti.extensions.Action
import app.myspoti.extensions.ActionRequest
import app.myspoti.extensions.Area
import app.myspoti.extensions.Clash
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** What is granted, kept and asked again, and what the registry and the stores keep on disk. */
class ExtensionRegistryTest {
    private val why = "Explains why it needs this right"

    private fun manifest(
        versionCode: Int = 1,
        provides: List<String> = listOf("lyrics"),
        permissions: List<PermissionRequest> = listOf(PermissionRequest("network", listOf("api.example.com"))),
    ) = Manifest("io.github.test.ext", "Test", "1.$versionCode", versionCode = versionCode, author = "me", provides = provides, permissions = permissions)

    private fun installed(m: Manifest, granted: Set<String>) =
        InstalledExtension(m.id, m.name, m.version, m.versionCode, m.author, "sha", granted)

    // ── Grants ──

    private val base = setOf("lyrics.provide", "network", "storage")

    @Test fun installingGrantsEveryActionTheManifestLists() {
        val m = manifest(permissions = listOf(PermissionRequest("network", listOf("api.example.com")), PermissionRequest("notify")))
        assertEquals(setOf("lyrics.provide", "network", "notify", "storage"), Grants.keys(m))
        val audio = manifest(provides = listOf("audio-source"), permissions = listOf(PermissionRequest("audio-source", why = why), PermissionRequest("network", listOf("cdn.example.com"))))
        assertEquals(setOf("audio.source", "network", "storage"), Grants.keys(audio))
    }

    @Test fun actionsUntickedBeforeAreRefusedUntilTheUserAcceptsThemAll() {
        val m = manifest(permissions = listOf(PermissionRequest("network", listOf("api.example.com")), PermissionRequest("notify")))
        assertEquals(setOf("notify"), Grants.refused(installed(m, base), m))
        assertTrue(Grants.refused(installed(m, Grants.keys(m)), m).isEmpty())
        assertEquals(base, Grants.replacing(m, setOf("notify")))
        assertEquals(Grants.keys(m), Grants.replacing(m, emptySet()))
    }

    // ── Install plans and the re-ask on update ──

    @Test fun notInstalledIsAFreshInstall() {
        assertTrue(InstallPlan.of(manifest(), null, null) is InstallPlan.Fresh)
    }

    @Test fun theSameOrAnOlderVersionIsRefused() {
        val m = manifest(versionCode = 2)
        assertTrue(InstallPlan.of(m, installed(m, base), m) is InstallPlan.NotNewer)
        assertTrue(InstallPlan.of(manifest(versionCode = 1), installed(m, base), m) is InstallPlan.NotNewer)
    }

    @Test fun anUpdateWithTheSameRightsKeepsWhatWasGrantedWithoutAsking() {
        val old = manifest(1, permissions = listOf(PermissionRequest("network", listOf("api.example.com")), PermissionRequest("notify")))
        val new = old.copy(versionCode = 2, version = "1.2")
        val all = Grants.keys(old)
        val plan = InstallPlan.of(new, installed(old, all), old) as InstallPlan.Update
        assertFalse(plan.reAsk)
        assertTrue(plan.refused.isEmpty())
        // One unticked back when the prompt allowed it: the prompt lists it again.
        val partial = InstallPlan.of(new, installed(old, base), old) as InstallPlan.Update
        assertTrue(partial.reAsk)
        assertEquals(setOf("notify"), partial.refused)
    }

    @Test fun aNewRightIsAskedAgainWithTheOnesUntickedBefore() {
        val old = manifest(1, permissions = listOf(PermissionRequest("network", listOf("api.example.com")), PermissionRequest("notify")))
        val new = manifest(2, permissions = old.permissions + PermissionRequest("spotify.write", why = why))
        val plan = InstallPlan.of(new, installed(old, base), old) as InstallPlan.Update
        assertTrue(plan.reAsk)
        assertEquals(listOf("library.write", "playlists.write", "playback.control", "playback.queue"), plan.newAsks.map { it.key })
        assertEquals(setOf("notify"), plan.refused)
        // The prompt installs with all it lists.
        assertEquals(base + setOf("notify", "library.write", "playlists.write", "playback.control", "playback.queue"), Grants.keys(new))
    }

    @Test fun moreHostsOrAnyHostIsAskedAgain() {
        val old = manifest(1)
        val more = manifest(2, permissions = listOf(PermissionRequest("network", listOf("api.example.com", "other.example.com"))))
        assertEquals(listOf("network"), (InstallPlan.of(more, installed(old, base), old) as InstallPlan.Update).newAsks.map { it.key })
        val fewer = manifest(2, permissions = listOf(PermissionRequest("network", listOf("api.example.com"))))
        assertFalse((InstallPlan.of(fewer, installed(old, base), old) as InstallPlan.Update).reAsk)
        val any = manifest(2, permissions = listOf(PermissionRequest("network", listOf("*"), why = why)))
        val plan = InstallPlan.of(any, installed(old, base), old) as InstallPlan.Update
        assertEquals(listOf("network.any"), plan.newAsks.map { it.key })
        assertFalse("network" in Grants.keys(any))
    }

    @Test fun aNewTakeoverIsAskedAndARightDroppedByTheUpdateIsForgotten() {
        val old = manifest(1, permissions = listOf(PermissionRequest("network", listOf("api.example.com")), PermissionRequest("notify")))
        val new = manifest(2, provides = listOf("lyrics", "audio-source"),
            permissions = listOf(PermissionRequest("network", listOf("api.example.com")), PermissionRequest("audio-source", why = why)))
        val plan = InstallPlan.of(new, installed(old, base + "notify"), old) as InstallPlan.Update
        assertEquals(listOf("audio.source"), plan.newAsks.map { it.key })
        assertFalse("notify" in plan.refused)
    }

    @Test fun anotherReasonIsAskedAgain() {
        val old = manifest(1, permissions = listOf(PermissionRequest("network", listOf("api.example.com"), why = "Reads lyrics")))
        val new = manifest(2, permissions = listOf(PermissionRequest("network", listOf("api.example.com"), why = "Sends what you play")))
        assertEquals(listOf("network"), (InstallPlan.of(new, installed(old, base), old) as InstallPlan.Update).newAsks.map { it.key })
    }

    @Test fun theGitHubRecordIsKeptOnDisk() {
        val m = manifest(2)
        val e = installed(m, setOf("lyrics.provide", "storage")).copy(
            origin = app.myspoti.extensions.Origin("me/ext", "v1.2", tag = true, commit = "0123456789abcdef0123456789abcdef01234567"),
            accepted = app.myspoti.extensions.Snapshot.of(manifest(1), "me/ext"),
            previous = KeptVersion("1.1", 1, "old", null),
            pendingUpdate = PendingUpdate(KeptVersion("1.3", 3, "new"), listOf(app.myspoti.extensions.Change(app.myspoti.extensions.Change.Kind.HOST, "x.example"))),
            declinedVersionCode = 3, sourceGone = true,
        )
        val f = Files.createTempFile("registry", ".json").toFile()
        Registry.write(f, Registry(listOf(e)))
        assertEquals(e, Registry.read(f).find(m.id))
        assertEquals("v1.2 · 0123456", e.origin!!.label())
        f.delete()
    }

    // ── Before actions, areas, clashes ──

    @Test fun aRegistryFromBeforeActionsIsMigrated() {
        val old = Registry(
            extensions = listOf(installed(manifest(), setOf("lyrics", "network", "player.read"))),
            priority = mapOf("lyrics" to listOf("a", "b"), "audio-source" to listOf("c"), "download-source" to listOf("d", "c")),
        )
        val now = old.migrated()
        assertEquals(setOf("lyrics.provide", "network", "playback.events", "storage", "settings.page"), now.extensions.single().granted)
        assertEquals(mapOf("lyrics" to listOf("a", "b"), "audio" to listOf("c", "d")), now.priority)
        // Read from disk, it comes back migrated.
        val file = java.io.File(Files.createTempDirectory("reg").toFile(), "registry.json")
        Registry.write(file, old)
        assertEquals(now, Registry.read(file))
    }

    @Test fun extensionsAreGroupedByAreaInTheUsersOrder() {
        fun m(id: String, vararg actions: String) = Manifest(id, id, "1", author = "me", actions = actions.map { ActionRequest(it, "Because") })
        val manifests = mapOf(
            "x.lyrics" to m("x.lyrics", "lyrics.provide", "network"),
            "x.both" to m("x.both", "lyrics.provide", "player.buttons"),
            "x.net" to m("x.net", "network", "storage"),
        )
        var r = Registry().put(installed(manifests.getValue("x.lyrics"), base).copy(id = "x.lyrics"))
            .put(installed(manifests.getValue("x.both"), base).copy(id = "x.both"))
            .put(installed(manifests.getValue("x.net"), base).copy(id = "x.net"))
        r = r.move(Area.LYRICS.key, listOf("x.lyrics", "x.both"), "x.both", -1)
        val g = Areas.group(r) { manifests[it] }
        assertEquals(Area.entries.toList(), g.keys.toList())
        assertEquals(listOf("x.both", "x.lyrics"), g.getValue(Area.LYRICS).map { it.id })
        assertEquals(listOf("x.both"), g.getValue(Area.MENUS).map { it.id })
        // Support actions alone: Other.
        assertEquals(listOf("x.net"), g.getValue(Area.OTHER).map { it.id })
        assertTrue(g.getValue(Area.PLAYBACK).isEmpty())
    }

    @Test fun aClashIsSettledAsTheUserChose() {
        val m = manifest()
        val r = Registry().put(installed(m, base).copy(id = "old.one")).put(installed(m, base).copy(id = "old.two"))
        val audio = Clash(Clash.Kind.EXCLUSIVE, "old.one", Action.AUDIO_SOURCE)
        val conflict = Clash(Clash.Kind.CONFLICT, "old.two")
        val (kept, off) = Clashes.apply(r, "new.one", listOf(audio, conflict)) { true }
        assertEquals("new.one", kept.active["audio.source"])
        assertFalse(kept.find("old.two")!!.enabled)
        assertTrue(kept.find("old.one")!!.enabled) // idle, not off
        assertFalse(off)
        val (refused, newOff) = Clashes.apply(r, "new.one", listOf(audio, conflict)) { false }
        assertEquals("old.one", refused.active["audio.source"])
        assertTrue(refused.find("old.two")!!.enabled)
        assertTrue(newOff)
        assertEquals(emptyMap<String, String>(), kept.remove("new.one").active)
    }

    // ── On disk ──

    @Test fun theRegistryKeepsOrderAndSurvivesARestart() {
        val dir = Files.createTempDirectory("ext").toFile()
        val file = java.io.File(dir, "registry.json")
        val a = installed(manifest(), base).copy(id = "io.a.one")
        val b = installed(manifest(), base).copy(id = "io.b.two")
        var r = Registry().put(a).put(b).copy(repositories = listOf("https://example.com/index.json"))
        r = r.put(b.copy(enabled = false, disabledReason = "failed"))
        Registry.write(file, r)
        val back = Registry.read(file)
        assertEquals(listOf("io.a.one", "io.b.two"), back.extensions.map { it.id })
        assertEquals("failed", back.find("io.b.two")?.disabledReason)
        assertEquals(listOf("https://example.com/index.json"), back.repositories)
        assertEquals(listOf("io.b.two"), back.remove("io.a.one").extensions.map { it.id })
        assertEquals(Registry(), Registry.read(java.io.File(dir, "missing.json")))
        dir.deleteRecursively()
    }

    @Test fun aStoreIsWrittenThroughAndCounted() {
        val dir = Files.createTempDirectory("ext").toFile()
        val f = java.io.File(dir, "x/storage.json")
        FileStore(f).apply { set("k", "[1,2]"); set("s", "\"v\""); remove("s") }
        val again = FileStore(f)
        assertEquals("[1,2]", again.get("k"))
        assertNull(again.get("s"))
        assertEquals(1 + 5, again.size())
        dir.deleteRecursively()
    }

    @Test fun theLogKeepsItsLastLines() {
        val dir = Files.createTempDirectory("ext").toFile()
        val log = ExtensionLog(java.io.File(dir, "log.txt"), maxLines = 3)
        (1..5).forEach { log.add("line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), log.lines())
        assertEquals(listOf("line 3", "line 4", "line 5"), ExtensionLog(java.io.File(dir, "log.txt"), maxLines = 3).lines())
        dir.deleteRecursively()
    }

    @Test fun threeFailuresInARowTurnAnExtensionOff() {
        val c = FailureCounter(limit = 3)
        assertFalse(c.failed("a")); assertFalse(c.failed("a"))
        c.succeeded("a")
        assertFalse(c.failed("a")); assertFalse(c.failed("a"))
        assertTrue(c.failed("a"))
        assertFalse(c.failed("b"))
    }

    // ── Priority ──

    @Test fun priorityIsInstallationOrderUntilTheUserMovesOne() {
        val ids = listOf("a", "b", "c")
        var r = Registry()
        assertEquals(ids, r.byPriority("lyrics", ids) { it })
        r = r.move("lyrics", ids, "c", -1)
        assertEquals(listOf("a", "c", "b"), r.byPriority("lyrics", ids) { it })
        r = r.move("lyrics", ids, "c", -5)
        assertEquals(listOf("c", "a", "b"), r.byPriority("lyrics", ids) { it })
        // Another area keeps installation order; one not listed (not a fresh install) comes last.
        assertEquals(ids, r.byPriority("audio", ids) { it })
        assertEquals(listOf("c", "a", "b", "d"), r.byPriority("lyrics", ids + "d") { it })
        // Moving past the end, or an unknown id, changes nothing.
        assertEquals(r, r.move("lyrics", ids, "b", 3))
        assertEquals(r, r.move("lyrics", ids, "zz", 1))
    }

    @Test fun eachSourceHasItsOwnOrderStartingFromTheOneActiveBefore() {
        val ids = listOf("tidal", "qobuz", "deezer")
        // Sources were exclusive: the one the user had made active is asked first.
        var r = Registry(active = mapOf("audio.source" to "deezer"))
        assertEquals(listOf("deezer", "tidal", "qobuz"), r.bySourceOrder("audio.source", "audio", ids) { it })
        assertEquals(ids, r.bySourceOrder("download.source", "audio", ids) { it })
        // Moving one in the audio order leaves the download order alone.
        r = r.move("audio.source", r.bySourceOrder("audio.source", "audio", ids) { it }, "qobuz", -2)
        assertEquals(listOf("qobuz", "deezer", "tidal"), r.bySourceOrder("audio.source", "audio", ids) { it })
        assertEquals(ids, r.bySourceOrder("download.source", "audio", ids) { it })
        // A source not listed (it became one with an update) is asked last.
        assertEquals(listOf("qobuz", "deezer", "tidal", "new"), r.bySourceOrder("audio.source", "audio", ids + "new") { it })
        // Spotify is last until moved; moved first, it stays first and a new source goes after it.
        val line = ids + SPOTIFY_SOURCE
        r = Registry()
        assertEquals(line, r.bySourceOrder("audio.source", "audio", line) { it })
        r = r.move("audio.source", line, SPOTIFY_SOURCE, -3)
        assertEquals(listOf(SPOTIFY_SOURCE) + ids, r.bySourceOrder("audio.source", "audio", line) { it })
        assertEquals(listOf(SPOTIFY_SOURCE) + ids + "new", r.bySourceOrder("audio.source", "audio", ids + "new" + SPOTIFY_SOURCE) { it })
    }

    @Test fun aFreshInstallIsAskedFirstWhereverItActs() {
        fun m(id: String, vararg actions: String) = Manifest(id, id, "1", author = "me", actions = actions.map { ActionRequest(it, "Because") })
        val ids = listOf("tidal", "qobuz", "deezer")
        // No order set yet: the new one first, the others behind it in installation order.
        var r = Registry().firstOnInstall("new", m("new", "lyrics.provide", "audio.source", "network"))
        assertEquals(listOf("new") + ids, r.byPriority("lyrics", ids + "new") { it })
        assertEquals(listOf("new") + ids + SPOTIFY_SOURCE, r.bySourceOrder("audio.source", "audio", ids + "new" + SPOTIFY_SOURCE) { it })
        // What it does not give keeps its order: downloads, home.
        assertEquals(ids, r.bySourceOrder("download.source", "audio", ids) { it })
        assertNull(r.priority["download.source"])
        assertNull(r.priority["home"])
        // The user's order: the new one above it all, Spotify moved first and the one active before included.
        r = Registry(active = mapOf("download.source" to "deezer"))
            .move("audio.source", ids + SPOTIFY_SOURCE, SPOTIFY_SOURCE, -3)
            .move("lyrics", ids, "deezer", -2)
            .firstOnInstall("dl", m("dl", "audio.source", "download.source", "lyrics.provide"))
        assertEquals(listOf("dl", SPOTIFY_SOURCE) + ids, r.bySourceOrder("audio.source", "audio", ids + "dl" + SPOTIFY_SOURCE) { it })
        assertEquals(listOf("dl", "deezer", "tidal", "qobuz"), r.bySourceOrder("download.source", "audio", ids + "dl") { it })
        assertEquals(listOf("dl", "deezer", "tidal", "qobuz"), r.byPriority("lyrics", ids + "dl") { it })
        // Moved down by the user, it stays there: only a fresh install puts it first, never twice listed.
        r = r.move("lyrics", ids + "dl", "dl", 3)
        assertEquals(listOf("deezer", "tidal", "qobuz", "dl"), r.byPriority("lyrics", ids + "dl") { it })
        assertEquals(listOf("dl", "deezer", "tidal", "qobuz"), r.firstOnInstall("dl", m("dl", "lyrics.provide")).priority["lyrics"])
        // Support actions alone: first of Other.
        assertEquals(mapOf("other" to listOf("x.net")), Registry().firstOnInstall("x.net", m("x.net", "network", "storage")).priority)
    }

    @Test fun priorityIsKeptOnDiskAndForgetsAnUninstalledExtension() {
        val m = manifest()
        val a = installed(m, base).copy(id = "a.b.a")
        val b = installed(m, base).copy(id = "a.b.b")
        var r = Registry().put(a).put(b).move("lyrics", listOf("a.b.a", "a.b.b"), "a.b.b", -1)
        val file = java.io.File(Files.createTempDirectory("reg").toFile(), "registry.json")
        Registry.write(file, r)
        r = Registry.read(file)
        assertEquals(listOf("a.b.b", "a.b.a"), r.byPriority("lyrics", r.extensions) { it.id }.map { it.id })
        r = r.remove("a.b.b")
        assertEquals(mapOf("lyrics" to listOf("a.b.a")), r.priority)
        assertEquals(emptyMap<String, List<String>>(), r.remove("a.b.a").priority)
    }
}
