package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    @Test fun comparesNumericallyNotAsText() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"))
        assertTrue(UpdateChecker.isNewer("0.9.1", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
    }
    @Test fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.8.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0-beta", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "2.0.0-beta.3"))
        assertTrue(UpdateChecker.isNewer("2.0.0-beta.2", "2.0.0-beta.1"))
        assertFalse(UpdateChecker.isNewer("2.0.0-beta.1", "2.0.0-beta.1"))
        assertTrue(UpdateChecker.isNewer("2.0.1-beta.1", "2.0.0"))
    }
    @Test fun ordersBetasAndReleases() {
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.7", "2.0.2-beta.6"))
        assertFalse(UpdateChecker.isNewer("2.0.2-beta.6", "2.0.2-beta.7"))
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.10", "2.0.2-beta.9"))
        assertTrue(UpdateChecker.isNewer("2.0.2", "2.0.2-beta.7"))
        assertFalse(UpdateChecker.isNewer("2.0.2-beta.7", "2.0.2"))
        assertTrue(UpdateChecker.isNewer("2.0.3-beta.1", "2.0.2"))
        assertTrue(UpdateChecker.isNewer("2.1.0-beta.1", "2.0.9"))
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.1", "2.0.1-beta.9"))
    }
    @Test fun notesKeepTheChangelogSectionAsPlainLines() {
        val body = "- **Local Files**: a folder\n  - nested [link](https://x.y)\n\n**Full Changelog**: https://github.com/a/b/compare/v1...v2"
        assertEquals("• Local Files: a folder\n  • nested link", UpdateChecker.cleanNotes(body))
    }
    @Test fun findsThisAppsApkForThisChannelAndAbi() {
        val r = org.json.JSONObject("""{"tag_name":"v2.5.0","assets":[
            {"name":"Orpheon-2.5.0-TV.apk","browser_download_url":"https://x/tv.apk"},
            {"name":"Orpheon-2.5.0-universal.apk","browser_download_url":"https://x/universal.apk"},
            {"name":"Orpheon-2.5.0-ARMv7.apk","browser_download_url":"https://x/v7.apk"},
            {"name":"Orpheon-2.5.0-ARMv8.apk","browser_download_url":"https://x/v8.apk"}]}""")
        // A 64-bit phone that also runs armeabi-v7a takes ARMv8, whatever the assets' order.
        assertEquals("https://x/v8.apk", UpdateChecker.apkAsset(r, beta = false, abis = arm64, name = "Orpheon"))
        assertEquals("https://x/v7.apk", UpdateChecker.apkAsset(r, beta = false, abis = listOf("armeabi-v7a", "armeabi"), name = "Orpheon"))
        // A stable release has no beta APK; an ABI the releases do not carry gets no link (the page opens).
        assertEquals("", UpdateChecker.apkAsset(r, beta = true, abis = arm64, name = "Orpheon"))
        assertEquals("", UpdateChecker.apkAsset(r, beta = false, abis = listOf("x86_64", "x86"), name = "Orpheon"))
        assertEquals("", UpdateChecker.apkAsset(r, beta = false, abis = emptyList(), name = "Orpheon"))
        assertEquals("", UpdateChecker.apkAsset(org.json.JSONObject("{}"), beta = false, abis = arm64, name = "Orpheon"))
        val b = org.json.JSONObject("""{"tag_name":"v2.5.0-beta.2","assets":[
            {"name":"Orpheon-Beta-2.5.0-beta.2-ARMv8.apk","browser_download_url":"https://x/b8.apk"}]}""")
        assertEquals("https://x/b8.apk", UpdateChecker.apkAsset(b, beta = true, abis = arm64, name = "Orpheon"))
        assertEquals("", UpdateChecker.apkAsset(b, beta = false, abis = arm64, name = "Orpheon"))
    }
    @Test fun anotherAppsApkIsNeverTaken() {
        // Myspoti's own releases (another package, Android cannot install it over this app), new
        // names or those of 2.3 and before: no link, so the release page opens.
        val myspoti = org.json.JSONObject("""{"tag_name":"v2.4.0","assets":[
            {"name":"Myspoti-2.4.0-ARMv8.apk","browser_download_url":"https://x/m8.apk"},
            {"name":"myspoti-stable-2.4.0-arm64-v8a.apk","browser_download_url":"https://x/legacy.apk"},
            {"name":"myspoti-2.4.0.apk","browser_download_url":"https://x/single.apk"}]}""")
        assertEquals("", UpdateChecker.apkAsset(myspoti, beta = false, abis = arm64, name = "Orpheon"))
        // And Myspoti's own build still finds its own.
        assertEquals("https://x/m8.apk", UpdateChecker.apkAsset(myspoti, beta = false, abis = arm64, name = "Myspoti"))
    }
    @Test fun onlyAVersionTagIsARelease() {
        assertTrue(UpdateChecker.isVersionTag("v2.4.0"))
        assertTrue(UpdateChecker.isVersionTag("2.5.0-beta.1"))
        assertFalse(UpdateChecker.isVersionTag("automix2-build205"))
        assertFalse(UpdateChecker.isVersionTag("mixlab-3150-private"))
        assertFalse(UpdateChecker.isVersionTag(""))
    }

    /** Myspoti's releases share the repository: never this app's update, however high their number. */
    @Test fun onlyThisAppsReleasesAreOffered() {
        fun rel(tag: String, pre: Boolean, vararg apks: String) = org.json.JSONObject().put("tag_name", tag).put("prerelease", pre)
            .put("html_url", "https://x/$tag").put("body", "")
            .put("assets", org.json.JSONArray(apks.map { org.json.JSONObject().put("name", it).put("browser_download_url", "https://x/$it") }))
        val arm64 = listOf("arm64-v8a", "armeabi-v7a")
        val list = listOf(
            rel("v2.4.0", false, "Myspoti-2.4.0-ARMv8.apk"),
            rel("v2.4.1-beta.1", true, "Myspoti-Beta-2.4.1-beta.1-ARMv8.apk"),
            rel("v1.0.0-beta.2", true, "Orpheon-Beta-1.0.0-beta.2-ARMv8.apk"),
            rel("v1.0.0-beta.1", true, "Orpheon-Beta-1.0.0-beta.1-ARMv8.apk"),
            rel("v1.0.1-beta.1", true),
        )
        assertEquals("1.0.0-beta.2", UpdateChecker.newest(list, beta = true, abis = arm64, name = "Orpheon")?.version)
        // No stable Orpheon yet: Myspoti's 2.4.0 is not one.
        assertEquals(null, UpdateChecker.newest(list, beta = false, abis = arm64, name = "Orpheon"))
        val stable = list + rel("v1.0.0", false, "Orpheon-1.0.0-ARMv8.apk")
        assertEquals("1.0.0", UpdateChecker.newest(stable, beta = false, abis = arm64, name = "Orpheon")?.version)
    }
}
