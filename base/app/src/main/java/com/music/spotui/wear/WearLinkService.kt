package com.music.spotui.wear

import android.content.Context
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.host.android.NativeTokens
import app.myspoti.pass.PassCrypto
import app.myspoti.pass.PassExtension
import app.myspoti.pass.PassInvite
import app.myspoti.pass.PassLink
import app.myspoti.pass.PassPayload
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * What the watch's app asks the phone's: the account's session for the watch's own player (a
 * watch has no browser to sign in with), the playlists to pick from, a playlist's songs (for the
 * watch's own extensions to find their audio) and the extensions themselves. Answered only while
 * this app is signed in. Asked over the Wearable Data Layer ([WearLinkService]) or, when that link
 * is down, straight over the Wi-Fi ([WatchLan]).
 */
object WatchAnswers {
    const val SESSION = "/orpheon/session"
    const val LIBRARY = "/orpheon/library"
    const val TRACKS = "/orpheon/tracks"
    const val EXTENSIONS = "/orpheon/extensions"
    const val LISTEN = "/orpheon/listen"
    private const val MAX_LISTS = 60
    private const val MAX_TRACKS = 500

    private fun spotify() = MyspotiRuntime.takeIf { it.isStarted }?.spotify ?: error("the phone's app is not started")

    suspend fun answer(context: Context, path: String, body: String): String = when (path) {
        SESSION -> session(context, body == "fresh")
        LIBRARY -> library(context)
        TRACKS -> tracks(body)
        EXTENSIONS -> extensions(context)
        LISTEN -> { com.music.spotui.di.AccountSync.watchReport(body); "ok" }
        else -> error("unknown question")
    }

    private suspend fun session(context: Context, fresh: Boolean): String {
        val c = spotify().http.credentials(fresh)
        // The identity this install already shows the service (NativeTokens.config): the watch presents the same.
        val id = NativeTokens.config(spotify().http)
        // How the watch reaches this phone over the Wi-Fi when the Data Layer is down.
        val lan = WatchLan.ensure(context)
        Logbook.log("watch: session given (the web token ends in ${(c.accessExpiresAtMs - System.currentTimeMillis()) / 60_000} min)")
        return buildJsonObject {
            put("accessToken", c.accessToken); put("accessExpiresAtMs", c.accessExpiresAtMs); put("clientId", c.clientId)
            put("clientToken", c.clientToken); put("clientDeviceId", c.clientDeviceId); put("appVersion", c.appVersion)
            put("userAgent", id.userAgent); put("browserVersion", id.browserVersion); put("secChUa", id.clientHints["sec-ch-ua"].orEmpty())
            put("acceptLanguage", id.acceptLanguage)
            // "Count extension plays on your account", Spotify chosen: the watch mirrors its own extension plays.
            put("listenSync", com.music.spotui.data.preferences.ConnectMirrorPref.on &&
                com.music.spotui.data.preferences.AccountProviderPref.id == app.myspoti.api.account.AccountProviders.SPOTIFY)
            if (lan != null) {
                put("lanSecret", lan.secret); put("lanPort", lan.port); put("lanId", lan.id)
                PassInvite.localAddress()?.let { put("lanHost", it) }
            }
        }.toString()
    }

    private suspend fun library(context: Context): String {
        val lists = spotify().playlists.mine().filter { it.name.isNotBlank() }.take(MAX_LISTS)
        return buildJsonArray {
            add(buildJsonObject { put("uri", "spotify:collection:tracks"); put("name", context.getString(app.myspoti.engine.R.string.collection_liked_songs_title)); put("subtitle", "") })
            lists.forEach { p -> add(buildJsonObject { put("uri", p.uri); put("name", p.name); put("subtitle", p.owner.orEmpty()) }) }
        }.toString()
    }

    /** The songs of the list [uri] (Liked Songs: `spotify:collection:tracks`), at most [MAX_TRACKS]. */
    private suspend fun tracks(uri: String): String {
        val s = spotify()
        val tracks = buildList {
            var offset: Int? = 0
            while (offset != null && size < MAX_TRACKS) {
                val page = if (uri == "spotify:collection:tracks") s.library.likedTracks(offset, 50).let { p -> p.items.map { it.track } to p.nextOffset }
                    else s.playlists.tracks(uri, offset, 100).let { p -> p.items to p.nextOffset }
                addAll(page.first.filter { it.playable && it.uri.startsWith("spotify:track:") })
                offset = page.second
            }
        }
        return buildJsonArray {
            tracks.take(MAX_TRACKS).forEach { t ->
                add(buildJsonObject {
                    put("uri", t.uri); put("name", t.name); put("durationMs", t.durationMs)
                    put("artists", buildJsonArray { t.artists.forEach { a -> add(kotlinx.serialization.json.JsonPrimitive(a.name)) } })
                    put("album", t.album?.name.orEmpty()); t.album?.images?.firstOrNull()?.url?.let { put("cover", it) }
                })
            }
        }.toString()
    }

    /** The extensions installed on the phone, as the pass carries them (packages, grants, state, store, settings). */
    private fun extensions(context: Context): String =
        PassPayload.JSON.encodeToString(ListSerializer(PassExtension.serializer()), com.music.spotui.pass.PassAndroid.extensions(context))
}

/** The Data Layer side of [WatchAnswers]: small answers by message, the extensions (large) through a channel. */
class WearLinkService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path !in setOf(WatchAnswers.SESSION, WatchAnswers.LIBRARY, WatchAnswers.TRACKS)) return null
        val out = TaskCompletionSource<ByteArray>()
        scope.launch {
            val answer = runCatching { withTimeout(ANSWER_MS) { WatchAnswers.answer(applicationContext, path, data.decodeToString()) } }
                .getOrElse { Logbook.log("watch: $path not answered (${it.message})"); "error: ${it.message ?: it.javaClass.simpleName}" }
            out.setResult(answer.encodeToByteArray())
        }
        return out.task
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchAnswers.EXTENSIONS) return
        scope.launch {
            val client = Wearable.getChannelClient(applicationContext)
            runCatching {
                val text = runCatching { WatchAnswers.answer(applicationContext, channel.path, "") }.getOrElse { "error: ${it.message}" }
                client.getOutputStream(channel).await().use { it.write(text.encodeToByteArray()) }
            }.onFailure { Logbook.log("watch: extensions not sent (${it.message})") }
            runCatching { client.close(channel).await() }
        }
    }

    private companion object {
        const val ANSWER_MS = 25_000L
    }
}

/**
 * The phone answering its watch straight over the Wi-Fi ([PassLink], sealed with a secret the
 * watch got over the Data Layer), for when that link is down: Bluetooth off or out of range, the
 * phone's Google services unreachable. Runs while this app's process does, once a watch has asked
 * for the session; announced on the network (NSD, `_orpheon._tcp`) so the watch finds the phone
 * again when its address changed.
 */
object WatchLan {
    class Link(val secret: String, val port: Int, val id: String)

    private const val PREFS = "pass_watch_link"
    private var server: PassLink.Server? = null
    private var registered: android.net.nsd.NsdManager.RegistrationListener? = null

    /** Starts the server when a watch was ever linked (the app's start). */
    fun startIfLinked(context: Context) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("secret")) Thread { runCatching { ensure(context) } }.start()
    }

    /** The link's secret, port and id, the server started; null when it could not start. */
    @Synchronized fun ensure(context: Context): Link? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val secret = prefs.getString("secret", null) ?: b64.encodeToString(PassCrypto.randomBytes(PassLink.SECRET_BYTES)).also { prefs.edit().putString("secret", it).apply() }
        val id = prefs.getString("id", null) ?: b64.encodeToString(PassCrypto.randomBytes(6)).also { prefs.edit().putString("id", it).apply() }
        val running = server ?: run {
            val app = context.applicationContext
            val raw = Base64.getUrlDecoder().decode(secret)
            // The same port as before when free: the watch keeps the last one it was told.
            val s = runCatching { PassLink.Server(raw, prefs.getInt("port", 0), answer = answerer(app)) }.getOrElse { PassLink.Server(raw, 0, answer = answerer(app)) }.start()
            prefs.edit().putInt("port", s.port).apply()
            server = s
            announce(app, s.port, id)
            Logbook.log("watch: Wi-Fi link open on port ${s.port}")
            s
        }
        Link(secret, running.port, id)
    }.onFailure { Logbook.log("watch: Wi-Fi link not open (${it.message})") }.getOrNull()

    private fun answerer(context: Context): (String, String) -> String = { path, body ->
        runBlocking { withTimeout(25_000) { WatchAnswers.answer(context, path, body) } }
    }

    private fun announce(context: Context, port: Int, id: String) = runCatching {
        val nsd = context.getSystemService(android.net.nsd.NsdManager::class.java) ?: return@runCatching
        val info = android.net.nsd.NsdServiceInfo().apply { serviceName = "Orpheon-$id"; serviceType = "_orpheon._tcp"; setPort(port) }
        val listener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) {}
            override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, code: Int) { Logbook.log("watch: not announced on the network ($code)") }
            override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, code: Int) {}
        }
        nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
        registered = listener
    }
}
