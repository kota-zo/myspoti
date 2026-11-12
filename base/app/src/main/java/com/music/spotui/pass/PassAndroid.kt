package com.music.spotui.pass

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.pass.PassCookie
import app.myspoti.pass.PassExtension
import app.myspoti.pass.PassPayload
import app.myspoti.pass.PassPref
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

/**
 * The Android side of passing the setup between the user's devices (`:pass`): what this app sends
 * (its settings, its extensions, its Spotify session) and how it takes what another device sent.
 */
object PassAndroid {
    /** Preferences of this device only (its state, its ids, the web view's own), never sent. */
    private val NOT_SENT = setOf(
        "WebViewChromiumPrefs", "AwOriginVisitLoggerPrefs", "last_exit", "web_player", "library_outbox",
        "extension_answers", "extension_isrcs", "catalog_scripts", "myspoti_last_track", "support_reminder",
        "new_releases", "RecentItems",
    )
    private val NOT_SENT_PARTS = listOf("token", "device", "fingerprint", "profileinstaller", "android.app.", "cli", "pass")

    private fun prefsDir(c: Context) = File(c.applicationInfo.dataDir, "shared_prefs")
    private fun extensionsDir(c: Context) = File(c.filesDir, "extensions")

    /** Keys of a sent file that stay this device's own: whether its permissions were asked here. */
    private val OWN_KEYS = mapOf("myspoti_prefs" to setOf("SetupDone"))
    private fun sentKey(file: String, key: String) = key !in OWN_KEYS[file].orEmpty()

    private fun sent(name: String) = name !in NOT_SENT && NOT_SENT_PARTS.none { name.contains(it, ignoreCase = true) }

    /** Everything this app passes, read now. Off the main thread (the cookies are read on it). */
    suspend fun collect(c: Context): PassPayload {
        val prefs = prefsDir(c).listFiles { f -> f.name.endsWith(".xml") }.orEmpty().map { it.name.removeSuffix(".xml") }.filter(::sent).associateWith { name ->
            c.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
                k to when (v) {
                    is Boolean -> PassPref("b", v.toString())
                    is Int -> PassPref("i", v.toString())
                    is Long -> PassPref("l", v.toString())
                    is Float -> PassPref("f", v.toString())
                    is String -> PassPref("s", v)
                    is Set<*> -> PassPref("ss", set = v.filterIsInstance<String>())
                    else -> return@mapNotNull null
                }
            }.toMap()
        }.filterValues { it.isNotEmpty() }
        val root = extensionsDir(c)
        val b64 = Base64.getEncoder()
        val files = root.walkTopDown().filter { it.isFile && !it.relativeTo(root).path.startsWith("cache") && it.name != "log.txt" }
            .associate { it.relativeTo(root).path to b64.encodeToString(it.readBytes()) }
        val extensions = extensions(c)
        val cookies = onMain {
            MyspotiRuntime.host.cookies().getCookie(SPOTIFY).orEmpty().split(';').mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                if (name.isEmpty() || !part.contains('=')) null else PassCookie(name, part.substringAfter('=').trim())
            }
        }
        return PassPayload(from = android.os.Build.MODEL.orEmpty(), prefs = prefs, extensions = extensions, extensionFiles = files, cookies = cookies)
    }

    /**
     * Takes what another device sent: its settings over these, its extensions in place of these,
     * its session in this app's web view. The app then starts again ([restart]) to read it all.
     */
    /** The extensions installed here, each with its package, grants, state, store and settings. */
    fun extensions(c: Context): List<PassExtension> {
        val root = extensionsDir(c)
        val b64 = Base64.getEncoder()
        return runCatching {
            val registry = Json.parseToJsonElement(File(root, "registry.json").readText()).jsonObject
            (registry["extensions"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val zip = File(root, "$id/package.zip").takeIf { it.isFile } ?: return@mapNotNull null
                PassExtension(
                    id = id, packageZip = b64.encodeToString(zip.readBytes()),
                    granted = (o["granted"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                    enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    storage = File(root, "$id/storage.json").takeIf { it.isFile }?.readText(),
                    settings = File(root, "$id/settings.json").takeIf { it.isFile }?.readText(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun apply(c: Context, p: PassPayload): Boolean {
        for ((name, values) in p.prefs) {
            if (!sent(name)) continue
            val e = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for ((k, v) in values) if (sentKey(name, k)) when (v.t) {
                "b" -> e.putBoolean(k, v.v.toBoolean())
                "i" -> v.v.toIntOrNull()?.let { e.putInt(k, it) }
                "l" -> v.v.toLongOrNull()?.let { e.putLong(k, it) }
                "f" -> v.v.toFloatOrNull()?.let { e.putFloat(k, it) }
                "s" -> e.putString(k, v.v)
                "ss" -> e.putStringSet(k, v.set.toSet())
            }
            e.commit()
        }
        if (p.extensionFiles.isNotEmpty()) {
            val root = extensionsDir(c)
            val fresh = File(c.filesDir, "extensions.passing").apply { deleteRecursively(); mkdirs() }
            val d = Base64.getDecoder()
            for ((path, data) in p.extensionFiles) {
                val f = File(fresh, path)
                // Only inside the folder: a path that climbs out of it is not written.
                if (!f.canonicalPath.startsWith(fresh.canonicalPath + File.separator)) continue
                f.parentFile?.mkdirs(); f.writeBytes(d.decode(data))
            }
            root.deleteRecursively()
            fresh.renameTo(root)
        }
        if (p.cookies.isNotEmpty()) {
            val done = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    val jar = MyspotiRuntime.host.cookies()
                    for (cookie in p.cookies) jar.setCookie(SPOTIFY, "${cookie.name}=${cookie.value}; Domain=.spotify.com; Path=/; Max-Age=31536000; Secure")
                    jar.flush()
                }
                done.countDown()
            }
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        Logbook.log("pass: received from ${p.from.take(30)}: ${p.prefs.size} settings files, ${p.extensions.size} extensions, session ${if (p.cookies.isEmpty()) "no" else "yes"}")
        return true
    }

    /** Starts the app again, to read the settings, extensions and session it just took. */
    fun restart(c: Context) {
        // A TV build only has a Leanback entrance.
        val launch = (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: c.packageManager.getLeanbackLaunchIntentForPackage(c.packageName))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Handler(Looper.getMainLooper()).postDelayed({
            launch?.let(c::startActivity)
            Runtime.getRuntime().exit(0)
        }, 600)
    }

    private suspend fun <T> onMain(block: () -> T): T {
        val r = CompletableDeferred<T>()
        Handler(Looper.getMainLooper()).post { r.completeWith(runCatching(block)) }
        return r.await()
    }

    private const val SPOTIFY = "https://open.spotify.com"
}
