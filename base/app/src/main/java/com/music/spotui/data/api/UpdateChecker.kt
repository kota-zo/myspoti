package com.music.spotui.data.api

import android.content.Context
import app.myspoti.engine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * New version check against the GitHub releases of [BuildConfig.UPDATE_REPO]
 * (set by the CI build; empty in local builds, which never check).
 * Automatic checks at most once an hour (launch/resume) and every 6 h while open. The release's
 * APK is downloaded and installed in the app ([AppUpdater]).
 */
object UpdateChecker {
    /** [apkUrl]: the release's signed APK; blank when the release has none (then its page opens). */
    data class Release(val version: String, val pageUrl: String, val notes: String = "", val apkUrl: String = "")

    /** Launch / resume check: not more often than this (releases can come several a day). */
    const val INTERVAL_MS = 3_600_000L
    /** While the app stays open, the check runs again this often. */
    const val PERIODIC_MS = 6 * 3_600_000L
    /** After a failed automatic check (offline, rate limit), wait this long before trying again. */
    private const val RETRY_MS = 10 * 60_000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).addInterceptor(com.music.spotui.data.DataSaver.interceptor).build() }

    /** Answer of [refresh]: the check always asks GitHub, whatever was cached. */
    sealed interface Outcome {
        data class Found(val release: Release) : Outcome
        data object UpToDate : Outcome
        /** [rateLimited]: GitHub refused for too many requests (about an hour on a shared network). */
        data class Failed(val rateLimited: Boolean) : Outcome
    }

    private sealed interface Fetch {
        data class Ok(val release: Release?) : Fetch
        data class Err(val rateLimited: Boolean) : Fetch
    }

    /** When the last successful check ran (epoch ms), 0 if never. */
    fun lastChecked(context: Context): Long =
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("checked_at", 0L)

    /**
     * Automatic check (launch, resume, periodic). [manual]: a release dismissed with "Later" is
     * offered again (the automatic check stays quiet about it). Skipped when the last
     * successful check is less than [INTERVAL_MS] old; a failed one is retried after [RETRY_MS]
     * and never erases the release already known.
     */
    suspend fun check(context: Context, manual: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return@withContext null
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked_at", 0L) < INTERVAL_MS || now - prefs.getLong("tried_at", 0L) < RETRY_MS) return@withContext pending(prefs, manual)
        store(prefs, fetch(repo, force = false), now)
        pending(prefs, manual)
    }

    /**
     * "Check now" from Settings: bypasses the throttle, the stored answer and every HTTP/CDN
     * cache, and says what happened.
     */
    suspend fun refresh(context: Context): Outcome = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return@withContext Outcome.UpToDate
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val result = fetch(repo, force = true)
        store(prefs, result, System.currentTimeMillis())
        when (result) {
            is Fetch.Err -> Outcome.Failed(result.rateLimited)
            is Fetch.Ok -> pending(prefs, manual = true)?.let { Outcome.Found(it) } ?: Outcome.UpToDate
        }
    }

    private fun store(prefs: android.content.SharedPreferences, result: Fetch, now: Long) {
        val e = prefs.edit()
        when (result) {
            // A failure keeps the last answer: "up to date" must not come from a failed request.
            is Fetch.Err -> e.putLong("tried_at", now)
            is Fetch.Ok -> e.putLong("checked_at", now).putLong("tried_at", 0L)
                .putString("version", result.release?.version).putString("url", result.release?.pageUrl)
                .putString("notes", result.release?.notes).putString("apk", result.release?.apkUrl)
        }
        e.apply()
    }

    private fun fetch(repo: String, force: Boolean): Fetch = try {
        // Stable builds follow "latest", which never includes pre-releases. A beta build
        // reads the list: the newest beta, compared with [isNewer].
        val bust = if (force) "${if (isBeta) "&" else "?"}_=${System.currentTimeMillis()}" else ""
        val url = (if (isBeta) "https://api.github.com/repos/$repo/releases?per_page=10" else "https://api.github.com/repos/$repo/releases/latest") + bust
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .apply { if (force) { header("Cache-Control", "no-cache, no-store, max-age=0"); header("Pragma", "no-cache") } }.build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return@use Fetch.Err(r.code == 429 || (r.code == 403 && r.header("x-ratelimit-remaining") == "0"))
            val text = r.body.string()
            val list = if (isBeta) org.json.JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } else listOf(JSONObject(text))
            // Betas and releases are two apps (different package names, one cannot update the
            // other): each one is offered its own channel only.
            Fetch.Ok(list.filter { !it.optBoolean("draft") && (!isBeta || it.optBoolean("prerelease")) }
                .map { Release(it.getString("tag_name").removePrefix("v"), it.getString("html_url"), cleanNotes(it.optString("body")), apkAsset(it)) }
                .reduceOrNull { best, next -> if (isNewer(next.version, best.version)) next else best })
        }
    } catch (e: Exception) { Fetch.Err(false) }

    /** The newer release last seen, dismissed or not (no request): what Settings > About shows. */
    fun known(context: Context): Release? =
        pending(context.getSharedPreferences("updates", Context.MODE_PRIVATE), true)

    /** The latest release known, if newer than this build and (unless [manual]) not dismissed. */
    private fun pending(prefs: android.content.SharedPreferences, manual: Boolean = false): Release? {
        val version = prefs.getString("version", null) ?: return null
        val url = prefs.getString("url", null) ?: return null
        if (!manual && prefs.getString("dismissed", null) == version) return null
        return Release(version, url, prefs.getString("notes", null).orEmpty(), prefs.getString("apk", null).orEmpty()).takeIf { isNewer(version, BuildConfig.VERSION_NAME) }
    }

    /** The ABIs a release carries one APK for (`myspoti-<channel>-<version>-<abi>.apk`): no x86, no universal APK. */
    internal val RELEASE_ABIS = listOf("arm64-v8a", "armeabi-v7a")

    /**
     * The release's APK download link for this app's channel and this phone: the CI uploads one
     * `myspoti-<beta|stable>-<version>-<abi>.apk` per ABI of [RELEASE_ABIS], and the first of [abis]
     * (the phone's, most preferred first) that has one wins, so a 64-bit phone that also runs
     * armeabi-v7a takes arm64-v8a. Older releases carry one APK without an ABI,
     * `myspoti-<beta|stable>-<version>.apk` or `myspoti-<version>.apk` (the app's own channel, since [fetch]
     * only offers a release of it), taken as before. Only this channel's APK: a release carrying both
     * must not hand a phone the other channel's build (a different package, it could not update this one),
     * and a name that matches none (or no APK for this phone's ABIs) gives no link.
     */
    internal fun apkAsset(release: JSONObject, beta: Boolean = isBeta, abis: List<String> = deviceAbis): String {
        val assets = release.optJSONArray("assets") ?: return ""
        val version = release.optString("tag_name").removePrefix("v")
        val channel = "myspoti-${if (beta) "beta" else "stable"}-$version"
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.optString("name") }
        val want = abis.filter { it in RELEASE_ABIS }.map { "$channel-$it.apk" } + listOf("$channel.apk", "myspoti-$version.apk")
        return want.firstNotNullOfOrNull { byName[it] }?.optString("browser_download_url").orEmpty()
    }

    /** This phone's ABIs, most preferred first. */
    private val deviceAbis: List<String> get() = android.os.Build.SUPPORTED_ABIS.toList()

    fun dismiss(context: Context, release: Release) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putString("dismissed", release.version).apply()
    }

    /**
     * The release description as plain lines for the update window: the CHANGELOG section,
     * without GitHub's generated part, Markdown emphasis or link syntax.
     */
    fun cleanNotes(body: String): String = body
        .substringBefore("**Full Changelog**").substringBefore("## What's Changed")
        .lines().map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .joinToString("\n") { line ->
            line.replace("**", "").replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("^(\\s*)[-*] "), "$1• ")
        }
        .trim()

    /** This build is a beta ("2.0.0-beta.1"). */
    val isBeta get() = BuildConfig.VERSION_NAME.contains("-beta")

    /**
     * "1.10.0" > "1.9.2". A release is newer than its betas ("2.0.0" > "2.0.0-beta.3"), and
     * betas compare by number ("2.0.0-beta.2" > "2.0.0-beta.1").
     */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // Same version number: release > beta, then the higher beta.
        fun beta(v: String) = if (!v.contains('-')) Int.MAX_VALUE else Regex("(\\d+)$").find(v)?.value?.toIntOrNull() ?: 0
        return beta(candidate) > beta(current)
    }
}
