/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.api

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The last Myspoti update offers Orpheon, the app that replaces it: which release of the shared
 * list is Orpheon's for this channel and phone, Myspoti's own update first, "Later" for a day,
 * and an Orpheon already installed opened instead of installed again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OrpheonOfferTest {
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val v7 = listOf("armeabi-v7a", "armeabi")
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
        AppUpdater.reset()
    }

    private fun release(tag: String, prerelease: Boolean, vararg apks: String, draft: Boolean = false) = JSONObject().apply {
        put("tag_name", tag); put("prerelease", prerelease); put("draft", draft)
        put("html_url", "https://example.invalid/releases/$tag")
        put("body", "- **New**: a [note](https://example.invalid)\n\n**Full Changelog**: https://example.invalid/compare")
        put("assets", org.json.JSONArray(apks.map { JSONObject().put("name", it).put("browser_download_url", "https://example.invalid/$tag/$it") }))
    }

    // GitHub's list, newest first: Myspoti's betas and releases next to Orpheon's.
    private val list = listOf(
        release("v2.4.1-beta.1", true, "Myspoti-Beta-2.4.1-beta.1-ARMv8.apk", "Myspoti-Beta-2.4.1-beta.1-ARMv7.apk"),
        release("v1.0.0-beta.2", true, "Orpheon-Beta-1.0.0-beta.2-ARMv8.apk", "Orpheon-Beta-1.0.0-beta.2-universal.apk"),
        release("v1.0.0-beta.1", true, "Orpheon-Beta-1.0.0-beta.1-ARMv8.apk", "Orpheon-Beta-1.0.0-beta.1-ARMv7.apk"),
        release("v2.4.0", false, "Myspoti-2.4.0-ARMv8.apk", "Myspoti-2.4.0-ARMv7.apk"),
    )

    @Test fun aBetaTakesTheNewestOrpheonPreReleaseForItsAbi() {
        val offer = UpdateChecker.newestOrpheon(list, beta = true, abis = arm64)!!
        assertEquals("1.0.0-beta.2", offer.version)
        assertEquals("https://example.invalid/v1.0.0-beta.2/Orpheon-Beta-1.0.0-beta.2-ARMv8.apk", offer.apkUrl)
        assertEquals("https://example.invalid/releases/v1.0.0-beta.2", offer.pageUrl)
        assertEquals("• New: a note", offer.notes)
        // beta.2 has no ARMv7 APK: a 32-bit phone gets beta.1, which has one.
        val old = UpdateChecker.newestOrpheon(list, beta = true, abis = v7)!!
        assertEquals("1.0.0-beta.1", old.version)
        assertEquals("https://example.invalid/v1.0.0-beta.1/Orpheon-Beta-1.0.0-beta.1-ARMv7.apk", old.apkUrl)
        // An ABI the releases do not carry: nothing (the universal APK is not offered).
        assertNull(UpdateChecker.newestOrpheon(list, beta = true, abis = listOf("x86_64")))
    }

    @Test fun aStableIsOfferedNoOrpheonPreRelease() {
        assertNull(UpdateChecker.newestOrpheon(list, beta = false, abis = arm64))
        // A stable Orpheon, once there: the stable APK, never the beta one.
        val withStable = list + release("v1.0.0", false, "Orpheon-1.0.0-ARMv8.apk", "Orpheon-Beta-1.0.0-ARMv8.apk")
        val offer = UpdateChecker.newestOrpheon(withStable, beta = false, abis = arm64)!!
        assertEquals("1.0.0", offer.version)
        assertEquals("https://example.invalid/v1.0.0/Orpheon-1.0.0-ARMv8.apk", offer.apkUrl)
        // A beta is not handed the stable release either.
        assertEquals("1.0.0-beta.2", UpdateChecker.newestOrpheon(withStable, beta = true, abis = arm64)!!.version)
    }

    @Test fun myspotiReleasesDraftsAndReleasesWithoutAnOrpheonApkAreNotOrpheon() {
        val noApk = listOf(
            release("v2.4.1-beta.1", true, "Myspoti-Beta-2.4.1-beta.1-ARMv8.apk"),
            release("v1.0.0-beta.3", true, "checksums.txt"),
            release("v1.0.0-beta.4", true, "Orpheon-Beta-1.0.0-beta.4-ARMv8.apk", draft = true),
            release("v1.0.0-beta.5", true),
        )
        assertNull(UpdateChecker.newestOrpheon(noApk, beta = true, abis = arm64))
        assertFalse(UpdateChecker.isOrpheon(noApk[0]))
        assertTrue(UpdateChecker.isOrpheon(list[1]))
    }

    @Test fun orpheonReleasesAreNeverAMyspotiUpdate() {
        assertEquals("2.4.1-beta.1", UpdateChecker.newest(list, beta = true, abis = arm64)!!.version)
        assertEquals("2.4.0", UpdateChecker.newest(list, beta = false, abis = arm64)!!.version)
        // Even numbered above Myspoti, an Orpheon release stays Orpheon's.
        val later = listOf(release("v3.0.0-beta.1", true, "Orpheon-Beta-3.0.0-beta.1-ARMv8.apk")) + list
        assertEquals("2.4.1-beta.1", UpdateChecker.newest(later, beta = true, abis = arm64)!!.version)
    }

    private fun store(myspoti: String? = null) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putString("version", myspoti).putString("url", myspoti?.let { "https://example.invalid/releases/v$it" })
            .putString("orpheon_version", "1.0.0-beta.1").putString("orpheon_url", "https://example.invalid/releases/v1.0.0-beta.1")
            .putString("orpheon_notes", "• New").putString("orpheon_apk", "https://example.invalid/Orpheon-Beta-1.0.0-beta.1-ARMv8.apk")
            .commit()
    }

    @Test fun aMyspotiUpdateComesFirst() {
        store(myspoti = "99.0.0")
        assertNotNull(UpdateChecker.known(context))
        assertNull(UpdateChecker.orpheon(context))
        // Settings > About still has it.
        assertEquals("1.0.0-beta.1", UpdateChecker.orpheon(context, manual = true)!!.version)
        // The Myspoti update put aside with "Later": Orpheon's turn.
        UpdateChecker.dismiss(context, UpdateChecker.known(context)!!)
        assertEquals("1.0.0-beta.1", UpdateChecker.orpheon(context)!!.version)
    }

    @Test fun laterOffersItAgainADayLater() {
        store()
        val now = 1_000_000_000_000L
        assertNotNull(UpdateChecker.orpheon(context, now = now))
        UpdateChecker.orpheonLater(context, now)
        assertNull(UpdateChecker.orpheon(context, now = now + 1))
        assertNull(UpdateChecker.orpheon(context, now = now + UpdateChecker.ORPHEON_LATER_MS - 1))
        assertNotNull(UpdateChecker.orpheon(context, manual = true, now = now + 1))
        assertNotNull(UpdateChecker.orpheon(context, now = now + UpdateChecker.ORPHEON_LATER_MS))
    }

    @Test fun nothingKnownNothingOffered() {
        assertNull(UpdateChecker.orpheon(context))
        assertNull(UpdateChecker.orpheon(context, manual = true))
    }

    @Test fun anInstalledOrpheonIsOpenedNotInstalled() {
        val pkg = UpdateChecker.orpheonPackage(beta = true)
        assertEquals("app.orpheon.beta", pkg)
        assertEquals("app.orpheon", UpdateChecker.orpheonPackage(beta = false))
        assertFalse(AppUpdater.isInstalled(context, pkg))
        assertEquals(app.myspoti.engine.R.string.orpheon_install, com.music.spotui.orpheonButton(false, AppUpdater.State.Idle))
        assertFalse(AppUpdater.open(context, pkg))

        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = pkg })
        val main = ComponentName(pkg, "$pkg.MainActivity")
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        assertTrue(AppUpdater.isInstalled(context, pkg))
        assertFalse(AppUpdater.isInstalled(context, UpdateChecker.orpheonPackage(beta = false)))
        assertEquals(app.myspoti.engine.R.string.orpheon_open, com.music.spotui.orpheonButton(true, AppUpdater.State.Idle))
        assertTrue(AppUpdater.open(context, pkg))
        assertEquals(pkg, shadowOf(context as Application).nextStartedActivity.component?.packageName)
    }

    @Test fun theInstallButtonFollowsTheInstall() {
        assertEquals(app.myspoti.engine.R.string.update_allow, com.music.spotui.orpheonButton(false, AppUpdater.State.NeedsPermission))
        assertEquals(app.myspoti.engine.R.string.update_retry, com.music.spotui.orpheonButton(false, AppUpdater.State.Failed("x")))
    }
}
