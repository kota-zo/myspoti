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
    @Test fun findsTheReleaseApkOfThisChannelOnly() {
        // An older release: one APK named after the version, whichever channel the release is.
        val legacy = org.json.JSONObject("""{"tag_name":"v2.0.0-beta.4","assets":[
            {"name":"checksums.txt","browser_download_url":"https://x/c.txt"},
            {"name":"myspoti-2.0.0-beta.4.apk","browser_download_url":"https://x/myspoti.apk"}]}""")
        assertEquals("https://x/myspoti.apk", UpdateChecker.apkAsset(legacy, beta = true, abis = arm64))
        assertEquals("https://x/myspoti.apk", UpdateChecker.apkAsset(legacy, beta = false, abis = arm64))
        val both = org.json.JSONObject("""{"tag_name":"v2.2.1","assets":[
            {"name":"myspoti-stable-2.2.1.apk","browser_download_url":"https://x/stable.apk"},
            {"name":"myspoti-beta-2.2.1.apk","browser_download_url":"https://x/beta.apk"}]}""")
        assertEquals("https://x/beta.apk", UpdateChecker.apkAsset(both, beta = true, abis = arm64))
        assertEquals("https://x/stable.apk", UpdateChecker.apkAsset(both, beta = false, abis = arm64))
        // Only the other channel's APK, or an unrelated .apk: no link, never the wrong build.
        val other = org.json.JSONObject("""{"tag_name":"v2.2.1","assets":[
            {"name":"myspoti-stable-2.2.1.apk","browser_download_url":"https://x/stable.apk"},
            {"name":"something-else.apk","browser_download_url":"https://x/else.apk"}]}""")
        assertEquals("", UpdateChecker.apkAsset(other, beta = true, abis = arm64))
        assertEquals("", UpdateChecker.apkAsset(org.json.JSONObject("{}"), beta = true, abis = arm64))
    }
    @Test fun findsTheApkOfThisPhonesAbi() {
        val split = org.json.JSONObject("""{"tag_name":"v2.3.0-beta.1","assets":[
            {"name":"myspoti-beta-2.3.0-beta.1-armeabi-v7a.apk","browser_download_url":"https://x/beta-v7a.apk"},
            {"name":"myspoti-beta-2.3.0-beta.1-arm64-v8a.apk","browser_download_url":"https://x/beta-arm64.apk"},
            {"name":"myspoti-stable-2.3.0-beta.1-arm64-v8a.apk","browser_download_url":"https://x/stable-arm64.apk"}]}""")
        // A 64-bit phone that also runs armeabi-v7a takes arm64-v8a, whatever the assets' order.
        assertEquals("https://x/beta-arm64.apk", UpdateChecker.apkAsset(split, beta = true, abis = arm64))
        assertEquals("https://x/beta-v7a.apk", UpdateChecker.apkAsset(split, beta = true, abis = listOf("armeabi-v7a", "armeabi")))
        assertEquals("https://x/stable-arm64.apk", UpdateChecker.apkAsset(split, beta = false, abis = arm64))
        // The other channel's APK for this ABI only: no link.
        assertEquals("", UpdateChecker.apkAsset(split, beta = false, abis = listOf("armeabi-v7a")))
        // An ABI the releases do not carry (x86 emulator, Chromebook): no link, the release page opens.
        assertEquals("", UpdateChecker.apkAsset(split, beta = true, abis = listOf("x86_64", "x86")))
        assertEquals("", UpdateChecker.apkAsset(split, beta = true, abis = emptyList()))
        // A release from before the ABI split: the channel's APK without a suffix, as before.
        val single = org.json.JSONObject("""{"tag_name":"v2.2.1","assets":[
            {"name":"myspoti-stable-2.2.1.apk","browser_download_url":"https://x/stable.apk"}]}""")
        assertEquals("https://x/stable.apk", UpdateChecker.apkAsset(single, beta = false, abis = listOf("armeabi-v7a")))
    }
}
