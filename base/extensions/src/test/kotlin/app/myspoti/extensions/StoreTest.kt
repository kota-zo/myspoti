package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The store's fields in the index and the manifest, a row's button, categories and search. */
class StoreTest {
    private fun entry(
        versionCode: Int = 2,
        minApp: String? = null,
        apiVersion: Int = API_VERSION,
        provides: List<String> = listOf("lyrics"),
        permissions: List<String> = listOf("network"),
        category: String? = null,
        tags: List<String> = emptyList(),
    ) = Catalog.Entry(
        id = "io.github.x.ext", name = "Ext", version = "1.$versionCode", versionCode = versionCode, apiVersion = apiVersion,
        author = "x", description = "Synced lyrics", provides = provides, permissions = permissions.map { PermissionRequest(it, listOf("a.example.com")) },
        url = "e.zip", sha256 = "00", minAppVersion = minApp, category = category, tags = tags,
    )

    // ── The index ──

    @Test fun indexReadsTheStoreFields() {
        val c = Catalog.parse("""
            { "format": 1, "name": "Repo", "extensions": [ {
              "id": "a.b.c", "name": "ABC", "version": "1.1", "versionCode": 4, "author": "me",
              "url": "pkgs/abc.zip", "sha256": "ff",
              "icon_url": "icons/abc.png", "updated_at": "2026-10-23", "category": "spotify",
              "tags": ["playlists", "sort"], "min_app_version": "2.0.2", "changelog": "Faster.",
              "somethingLater": 1 } ] }
        """)
        val e = c.extensions.single()
        assertEquals("icons/abc.png", e.iconAddress())
        assertEquals("2026-10-23", e.updatedAt)
        assertEquals(listOf("playlists", "sort"), e.tags)
        assertEquals("2.0.2", e.minAppVersion)
        assertEquals("Faster.", e.changelog)
        // "spotify", a key of the first store, reads as Library; an extension with no library action is filed under Other.
        assertEquals(Category.OTHER, e.category())
        assertEquals("https://h.org/r/icons/abc.png", Catalog.resolve("https://h.org/r/index.json", e.iconAddress()!!))
        // Written back under the same names.
        val text = Catalog.encode(c)
        listOf("\"icon_url\"", "\"updated_at\"", "\"min_app_version\"", "\"changelog\"").forEach { assertTrue(text, it in text) }
        assertEquals(e, Catalog.parse(text).extensions.single())
    }

    @Test fun anOldIndexStillReads() {
        val e = Catalog.parse("""{ "name": "Old", "extensions": [ { "id": "a.b.c", "name": "ABC", "version": "1", "versionCode": 1,
            "author": "me", "url": "a.zip", "sha256": "ff", "icon": "https://h.org/i.png" } ] }""").extensions.single()
        assertEquals("https://h.org/i.png", e.iconAddress())
        assertNull(e.minAppVersion)
        assertTrue(e.tags.isEmpty())
        assertTrue(e.compatible("1.0.0"))
    }

    // ── A store row's button ──

    @Test fun aRowSaysInstallUpdateInstalledOrIncompatible() {
        val app = "2.0.2-beta.4-dev"
        assertEquals(StoreAction.INSTALL, Store.action(entry(), null, app))
        assertEquals(StoreAction.UPDATE, Store.action(entry(versionCode = 3), 2, app))
        assertEquals(StoreAction.INSTALLED, Store.action(entry(versionCode = 2), 2, app))
        assertEquals(StoreAction.INSTALLED, Store.action(entry(versionCode = 1), 2, app))
        assertEquals(StoreAction.INCOMPATIBLE, Store.action(entry(minApp = "2.1.0"), null, app))
        assertEquals(StoreAction.INCOMPATIBLE, Store.action(entry(versionCode = 3, minApp = "2.0.2"), 2, app))
        assertEquals(StoreAction.INSTALLED, Store.action(entry(minApp = "9.0"), 2, app))
        assertEquals(StoreAction.INCOMPATIBLE, Store.action(entry(apiVersion = API_VERSION + 1), null, app))
        assertEquals(StoreAction.INSTALL, Store.action(entry(minApp = "2.0.2-beta.4"), null, app))
    }

    @Test fun updatesCountOnlyWhatCanBeInstalled() {
        val a = Catalog(name = "A", extensions = listOf(entry(versionCode = 3), entry(versionCode = 5, minApp = "3.0").copy(id = "x.y.z")))
        val b = Catalog(name = "B", extensions = listOf(entry(versionCode = 4)))
        val installed = mapOf("io.github.x.ext" to 2, "x.y.z" to 1)
        assertEquals(setOf("io.github.x.ext"), Store.updates(listOf("a" to a, "b" to b), installed, "2.0.2"))
        assertEquals("b" to 4, Store.newest(listOf("a" to a, "b" to b))["io.github.x.ext"]!!.let { it.first to it.second.versionCode })
    }

    @Test fun versionsCompareLikeSemver() {
        assertTrue(AppVersion.compare("2.0.2", "2.0.2-beta.4-dev") > 0)
        assertTrue(AppVersion.compare("2.0.2-beta.4-dev", "2.0.2-beta.4") > 0)
        assertTrue(AppVersion.compare("2.0.2-beta.10", "2.0.2-beta.9") > 0)
        assertTrue(AppVersion.compare("2.0.1", "2.0.2-beta.1") < 0)
        assertEquals(0, AppVersion.compare("2.1", "2.1.0"))
        assertTrue(AppVersion.atLeast("2.0.2", null))
        assertTrue(AppVersion.atLeast("dev", "9.9"))
        assertFalse(AppVersion.atLeast("2.0.1", "2.0.2"))
        assertNull(AppVersion.parse("two"))
    }

    @Test fun orpheonMeetsTheMinimumsOfMyspotiUpToItsLastVersion() {
        assertTrue(AppVersion.atLeast("1.0.0", "2.3.0"))
        assertTrue(AppVersion.atLeast("1.0.0-beta.1", "2.4.0"))
        assertFalse(AppVersion.atLeast("1.0.0", "2.5.0"))
        assertTrue(AppVersion.atLeast("1.2.0", "1.1.0"))
        assertFalse(AppVersion.atLeast("1.0.0", "1.1.0"))
    }

    // ── Categories and search ──

    @Test fun categoriesAreDeclaredAndCheckedAgainstTheActions() {
        assertEquals(Category.LYRICS, entry(category = "lyrics").category())
        assertEquals(Category.OTHER, entry().category())
        assertEquals(Category.OTHER, entry(category = "nonsense").category())
        // Declared without the action it needs: Other.
        assertEquals(Category.OTHER, entry(provides = listOf("audio-source"), category = "lyrics").category())
        assertEquals(Category.PLAYBACK, entry(provides = listOf("lyrics", "audio-source"), category = "playback").category())
        assertEquals(Category.LIBRARY, entry(provides = emptyList(), permissions = listOf("player.read", "spotify.write"), category = "library").category())
        assertEquals(Category.STATS, entry(provides = emptyList(), permissions = listOf("player.read"), category = "stats").category())
        assertEquals(Category.TOOLS, entry(provides = emptyList(), permissions = listOf("player.read"), category = "tools").category())
        // Downloads always go under Tools, whatever is declared; no category is named after them.
        assertEquals(Category.TOOLS, entry(provides = listOf("lyrics", "download-source"), category = "lyrics").category())
        assertTrue(Category.entries.none { "download" in it.key || "rip" in it.key })
        // The first store's keys still read.
        assertEquals(Category.TOOLS, Category.of("downloads"))
        assertEquals(Category.PLAYBACK, Category.of("audio-source"))
    }

    @Test fun aCategoryThatDoesNotFitIsAWarningNotARefusal() {
        val m = Manifest("a.b.c", "N", "1", author = "me", actions = listOf(ActionRequest("lyrics.provide", "Synced lyrics")), category = "library")
        assertTrue(m.problems().isEmpty())
        assertEquals(Category.OTHER, m.category())
        assertTrue(m.categoryWarning()!!, "library" in m.categoryWarning()!!)
        assertNull(m.copy(category = "lyrics").categoryWarning())
        assertTrue("unknown" in m.copy(category = "games").categoryWarning()!!)
    }

    @Test fun searchFindsNameTagsAndCategory() {
        val e = entry(tags = listOf("karaoke"))
        assertTrue(Store.matches(e, "", null))
        assertTrue(Store.matches(e.copy(category = "lyrics"), "KARA ext", Category.LYRICS))
        assertFalse(Store.matches(e, "", Category.TOOLS))
        assertFalse(Store.matches(e, "flac", null))
    }

    // ── The manifest ──

    @Test fun manifestStoreFieldsAreChecked() {
        val base = Manifest("a.b.c", "N", "1", author = "me", provides = listOf("lyrics"))
        assertEquals(emptyList<String>(), base.copy(icon = "logo.png", category = "lyrics", tags = listOf("synced", "word by word"), minAppVersion = "2.0.2-beta.4", changelog = "New").problems())
        val p = base.copy(icon = "img/logo.jpg", tags = listOf("UPPER"), minAppVersion = "soon", changelog = "x".repeat(2001)).problems().joinToString("\n")
        listOf("icon \"img/logo.jpg\"", "tag \"UPPER\"", "minAppVersion \"soon\"", "changelog").forEach { assertTrue(p, it in p) }
    }

    @Test fun aNamedIconTravelsInThePackage() {
        val dir = Files.createTempDirectory("ext").toFile()
        File(dir, "manifest.json").writeText("""{ "id": "a.b.c", "name": "N", "version": "1", "author": "me", "provides": ["lyrics"], "icon": "logo.png" }""")
        File(dir, "main.js").writeText("myspoti.provide('lyrics', function () { return null; });")
        File(dir, "logo.png").writeBytes(byteArrayOf(1, 2, 3))
        val p = ExtensionPackage.read(ExtensionPackage.pack(dir))
        assertEquals(listOf<Byte>(1, 2, 3), p.icon!!.toList())
        dir.deleteRecursively()
    }
}
