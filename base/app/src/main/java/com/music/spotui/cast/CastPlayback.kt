package com.music.spotui.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.Graph
import com.music.spotui.di.LocalPlayer
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Myspoti's own player on a Cast device: the songs [LocalPlayer] plays (extension streams,
 * downloads, the phone's files) go to the Default Media Receiver of the chosen device, fetched
 * from the phone's [CastServer]. Spotify's songs are not cast here: Spotify Connect does that.
 *
 * [LocalPlayer] keeps the queue and its rules (shuffle, repeat, song radio); while casting its
 * ExoPlayer is stopped, and each song it moves to is loaded on the device ([follow]). The
 * device's status (position, play/pause, end of song) comes back to it, so the player screens,
 * the media notification and the controls go on as for the phone. No crossfade while casting.
 *
 * All the protocol work runs on one thread ([worker]); the public calls come from the main thread.
 */
object CastPlayback {
    /** The device the songs go to (or being connected to); null while they play on the phone. */
    val device = mutableStateOf<CastDevice?>(null)
    /** Connecting to [device], the songs still on the phone. */
    val connecting = mutableStateOf(false)
    /** The device's volume, 0 to 1. */
    val volume = mutableFloatStateOf(1f)
    /** The songs play on the device: LocalPlayer's own player is stopped and routes its controls here. */
    @Volatile var active = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "cast").apply { isDaemon = true } }

    private enum class Phase { NONE, LAUNCHING, RECONNECTING, READY }

    // ── Worker-thread state ──
    private var channel: CastChannel? = null
    private var phase = Phase.NONE
    private var session: CastProtocol.App? = null
    private var requestId = 0
    private var loadRequest = -1
    private var mediaSessionId = -1
    private var finishedFor = -1
    /** After a load: the play state the user wants is set once the device has the song. */
    private var settleAfterLoad = false
    /** The song the device has (its LOAD sent). */
    private var loaded: LocalTrack? = null
    /** Songs whose direct address the device could not play: proxied by the phone instead. */
    private val directFailed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<LocalTrack, Boolean>())
    private var lastLoadDirect = false
    private var failures = 0
    private var reconnects = 0
    private var launchRequest = -1
    private var launchAnswered = false
    /** The media server restarted on another address at the last connection. */
    private var serverMoved = false
    /** The generation whose status polling runs. */
    private var polling = -1

    // ── Read from the main thread ──
    /** Each start and end makes a new generation: what an older one left running stops. */
    @Volatile private var generation = 0
    /** The song the device has or is about to get. */
    @Volatile var track: LocalTrack? = null
        private set
    /** Where to start [track] when its load is still to be sent. */
    @Volatile private var startMs = 0L
    @Volatile private var wantPlaying = false
    @Volatile private var playerState = "IDLE"
    @Volatile private var posMs = 0L
    @Volatile private var posAt = 0L

    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // ── Calls from the app (main thread) ──

    /** Sends the songs to [d] (only Myspoti's own player: [LocalPlayer] must be playing). */
    fun start(d: CastDevice) {
        if (!LocalPlayer.active.value) { log("not cast: a Spotify song plays (Spotify Connect casts those)"); return }
        if (device.value?.id == d.id) return
        val gen = ++generation
        device.value = d
        connecting.value = true
        hold(true)
        log("connecting to a ${d.model.ifBlank { "Cast device" }}")
        worker.execute { connect(gen, d, Phase.LAUNCHING) }
        // Not ready in time: back to the phone (it never stopped playing there unless the device was ready).
        worker.schedule({ if (gen == generation && phase != Phase.READY) fail(gen, "no answer from the device in 15 s") }, 15, TimeUnit.SECONDS)
    }

    /** Back to the phone where the device was ([back] false: the songs stop, Spotify takes over). */
    fun stop(back: Boolean = true) {
        if (device.value == null && !active) return
        val wasActive = active
        val at = position()
        val playing = wantPlaying
        val gen = ++generation
        active = false
        device.value = null
        connecting.value = false
        track = null
        hold(false)
        log(if (back) "stopped casting: back on the phone at ${at / 1000} s" else "stopped casting")
        worker.execute { teardown(stopApp = true); if (gen == generation) CastServer.stop() }
        if (back && wasActive) LocalPlayer.castEnded(at, playing)
    }

    /** [t] is the current song of [LocalPlayer] (from [atMs] when it is a new one; [playing]: also the play state wanted). */
    fun follow(t: LocalTrack?, atMs: Long, playing: Boolean? = null) {
        if (!active || t == null) return
        if (playing != null) wantPlaying = playing
        if (t === track) return
        track = t
        startMs = atMs.coerceAtLeast(0)
        posMs = startMs; posAt = now(); playerState = "BUFFERING"
        val gen = generation
        worker.execute { load(gen, t) }
    }

    /** The same song again from its start (repeat one, a list of one song). */
    fun replay() {
        val t = track ?: return
        track = null
        follow(t, 0, true)
    }

    fun play() {
        wantPlaying = true
        worker.execute { command("PLAY") }
    }

    fun pause() {
        wantPlaying = false
        if (playerState == "PLAYING") { posMs = position(); posAt = now() }
        worker.execute { command("PAUSE") }
    }

    fun seek(ms: Long) {
        posMs = ms.coerceAtLeast(0); posAt = now()
        if (loaded !== track) startMs = posMs
        worker.execute {
            val id = mediaSessionId
            if (phase == Phase.READY && id >= 0 && loaded === track) send(CastProtocol.NS_MEDIA, CastProtocol.seek(++requestId, id, ms))
        }
    }

    fun setVolume(level: Float) {
        volume.floatValue = level.coerceIn(0f, 1f)
        worker.execute { if (phase == Phase.READY) send(CastProtocol.NS_RECEIVER, CastProtocol.setVolume(++requestId, level.toDouble()), CastProtocol.RECEIVER) }
    }

    fun position(): Long = if (playerState == "PLAYING") posMs + (now() - posAt) else posMs
    fun isPlaying() = playerState == "PLAYING"
    fun playWhenReady() = wantPlaying
    fun isBuffering() = playerState == "BUFFERING" || (active && loaded !== track)
    fun state(): String = "cast ${playerState.lowercase()}"

    // ── Worker thread ──

    private fun connect(gen: Int, d: CastDevice, then: Phase) {
        if (gen != generation) return
        // A new device (or the first): the receiver of the one before is stopped.
        if (then == Phase.LAUNCHING) teardown(stopApp = true) else { channel?.close(); channel = null }
        val c = CastChannel(d.host, d.port,
            onMessage = { m, json -> worker.execute { if (gen == generation) heard(gen, m, json) } },
            onClosed = { why -> worker.execute { if (gen == generation && why != null) lost(gen, d, why) } })
        try {
            c.open()
            serverMoved = CastServer.start(c.localAddress)
        } catch (t: Throwable) {
            c.close()
            log("connection failed: ${t.javaClass.simpleName}")
            if (then == Phase.RECONNECTING) retry(gen, d) else fail(gen, "connection failed")
            return
        }
        channel = c
        phase = then
        if (then == Phase.LAUNCHING) {
            log("connected, launching the Default Media Receiver")
            launch()
        } else {
            log("connected again, asking the device what it plays")
            send(CastProtocol.NS_RECEIVER, CastProtocol.type("GET_STATUS").put("requestId", ++requestId), CastProtocol.RECEIVER)
        }
    }

    private fun heard(gen: Int, m: CastMessage, json: JSONObject) {
        val type = json.optString("type")
        when (m.namespace) {
            CastProtocol.NS_RECEIVER -> when (type) {
                "RECEIVER_STATUS" -> receiverStatus(gen, CastProtocol.receiver(json), json.optInt("requestId", 0))
                "LAUNCH_ERROR" -> fail(gen, "the device refused to launch the receiver (${json.optString("reason")})")
            }
            CastProtocol.NS_CONNECTION -> if (type == "CLOSE" && m.source == session?.transportId && phase == Phase.READY) {
                log("the receiver closed the connection")
                endFromDevice(gen)
            }
            CastProtocol.NS_MEDIA -> when (type) {
                "MEDIA_STATUS" -> mediaStatus(gen, json)
                "LOAD_FAILED" -> loadFailed(gen, "load failed")
                "LOAD_CANCELLED" -> log("load cancelled (a newer one replaced it)")
                "INVALID_REQUEST" -> log("invalid request: ${json.optString("reason")}")
            }
        }
    }

    private fun receiverStatus(gen: Int, r: CastProtocol.Receiver, request: Int) {
        r.volume?.let { v -> main.post { if (gen == generation) volume.floatValue = v.toFloat() } }
        when (phase) {
            Phase.LAUNCHING -> {
                // The answer to the launch (a receiver another phone had running may show before it).
                if (request == launchRequest) launchAnswered = true
                else if (request != 0 || !launchAnswered) return
                val a = r.app?.takeIf { it.transportId.isNotBlank() } ?: return
                session = a
                joinApp()
                log("receiver running")
                ready(gen, reload = active)
            }
            Phase.RECONNECTING -> {
                val a = r.app
                if (a != null && a.sessionId == session?.sessionId) {
                    joinApp()
                    reconnects = 0
                    log("reconnected to the same receiver session")
                    // The phone's address changed (another network): the song's address died with it.
                    if (serverMoved) ready(gen, reload = true)
                    else send(CastProtocol.NS_MEDIA, CastProtocol.type("GET_STATUS").put("requestId", ++requestId))
                } else {
                    log("the receiver session is gone: launching it again")
                    session = null
                    phase = Phase.LAUNCHING
                    launch()
                }
            }
            Phase.READY -> if (r.app == null || r.app.sessionId != session?.sessionId) {
                log("the receiver stopped on the device or another app took it")
                endFromDevice(gen)
            }
            Phase.NONE -> Unit
        }
    }

    private fun launch() {
        launchRequest = ++requestId
        launchAnswered = false
        send(CastProtocol.NS_RECEIVER, CastProtocol.launch(launchRequest), CastProtocol.RECEIVER)
    }

    /** The virtual connection to the receiver app, needed before any media message. */
    private fun joinApp() {
        val a = session ?: return
        phase = Phase.READY
        send(CastProtocol.NS_CONNECTION, CastProtocol.connect(), a.transportId)
    }

    /** The receiver runs: the songs move to it (first time), or the song is loaded again (it restarted). */
    private fun ready(gen: Int, reload: Boolean) {
        schedulePoll(gen)
        if (reload) {
            loaded = null
            val t = track ?: return
            startMs = position()
            load(gen, t)
            return
        }
        main.post {
            if (gen != generation) return@post
            active = true
            connecting.value = false
            log("casting")
            LocalPlayer.castStarted()
        }
    }

    private fun load(gen: Int, t: LocalTrack) {
        if (gen != generation || track !== t || phase != Phase.READY) return
        val a = session ?: return
        val entry = try {
            CastServer.probe(t.uri, t.uri.lastPathSegment.orEmpty())
        } catch (e: Throwable) {
            log("could not open the song for the device: ${e.javaClass.simpleName}")
            if (track === t) loadFailed(gen, "song not readable")
            return
        }
        // Skipped again while the song was opened (an extension may take seconds).
        if (gen != generation || track !== t || phase != Phase.READY) return
        val direct = directUrl(t)
        val url = direct ?: CastServer.publish(entry)
        val cover = coverUrl(t)
        loadRequest = ++requestId
        mediaSessionId = -1
        settleAfterLoad = true
        loaded = t
        lastLoadDirect = direct != null
        val at = startMs
        send(CastProtocol.NS_MEDIA, CastProtocol.load(loadRequest, a.sessionId, url, entry.mime, t.durationMs,
            t.title, t.artist, t.album, cover, at, wantPlaying))
        log("load sent: ${entry.mime}, ${if (entry.length >= 0) "${entry.length / 1024} KB" else "length unknown"}, " +
            "${if (direct != null) "direct address" else "through the phone"}, from ${at / 1000} s${if (wantPlaying) "" else ", paused"}")
    }

    /** An extension's plain address (no headers, no DASH) the device can fetch itself, or null. */
    private fun directUrl(t: LocalTrack): String? {
        if (t.uri.scheme != com.music.spotui.extensions.ExtensionAudio.SCHEME || t in directFailed) return null
        val s = runCatching { com.music.spotui.extensions.ExtensionAudio.resolve(t.uri) }.getOrNull() ?: return null
        return s.url.takeIf { s.manifest == null && s.headers.isEmpty() && (it.startsWith("https://") || it.startsWith("http://")) }
    }

    private fun coverUrl(t: LocalTrack): String? {
        val art = t.artUri.toString().takeIf { it.isNotBlank() } ?: return null
        val large = runCatching { app.myspoti.host.android.Covers.large(art) }.getOrDefault(art)
        if (large.startsWith("https://") || large.startsWith("http://")) return large
        val u = Uri.parse(large)
        return if (u.scheme == "content" || u.scheme == "file" || u.scheme == "android.resource")
            runCatching { CastServer.publish(CastServer.Entry(u, "", -1, cover = true)) }.getOrNull() else null
    }

    private fun mediaStatus(gen: Int, json: JSONObject) {
        val s = CastProtocol.media(json) ?: return
        val forLoad = json.optInt("requestId", 0).let { it != 0 && it == loadRequest }
        when {
            forLoad -> mediaSessionId = s.mediaSessionId
            // Waiting for the load's answer: the song before may still speak.
            mediaSessionId < 0 || s.mediaSessionId < mediaSessionId -> return
            // Loaded by another sender meanwhile: followed.
            s.mediaSessionId > mediaSessionId -> mediaSessionId = s.mediaSessionId
        }
        playerState = s.playerState
        posMs = s.positionMs; posAt = now()
        if (s.playerState == "PLAYING") failures = 0
        if (settleAfterLoad && s.playerState != "IDLE") {
            settleAfterLoad = false
            if (wantPlaying && s.playerState == "PAUSED") command("PLAY")
            else if (!wantPlaying && s.playerState != "PAUSED") command("PAUSE")
        } else if (!settleAfterLoad) {
            // Paused or played from the device itself (or another phone): the app shows it.
            if (s.playerState == "PAUSED") wantPlaying = false
            if (s.playerState == "PLAYING") wantPlaying = true
        }
        if (s.playerState == "IDLE") when (s.idleReason) {
            "FINISHED" -> if (finishedFor != s.mediaSessionId && loaded === track) {
                finishedFor = s.mediaSessionId
                log("song ended on the device")
                main.post { if (gen == generation && active) LocalPlayer.castFinished() }
            }
            "ERROR" -> loadFailed(gen, "the device could not play the song")
        }
        main.post { if (gen == generation && active) LocalPlayer.castChanged() }
    }

    private fun loadFailed(gen: Int, why: String) {
        val t = loaded ?: track ?: return
        if (lastLoadDirect && t === track) {
            log("$why from its direct address: through the phone instead")
            directFailed += t
            loaded = null
            startMs = position()
            load(gen, t)
            return
        }
        failures++
        playerState = "IDLE"
        log("$why (failure $failures in a row)")
        main.post {
            if (gen != generation || !active) return@post
            if (failures < 3) LocalPlayer.next()
            else { wantPlaying = false; LocalPlayer.castChanged(); toast(app.myspoti.engine.R.string.cast_failed) }
        }
    }

    private fun command(type: String) {
        val id = mediaSessionId
        if (phase != Phase.READY || id < 0 || loaded !== track) return
        send(CastProtocol.NS_MEDIA, CastProtocol.media(type, ++requestId, id))
    }

    /** The device does not say where it is while it plays: asked every 5 s (the position between is counted here). */
    private fun schedulePoll(gen: Int) {
        if (polling == gen) return
        polling = gen
        poll(gen)
    }

    private fun poll(gen: Int) {
        worker.schedule({
            if (gen != generation) return@schedule
            if (phase == Phase.READY && mediaSessionId >= 0 && playerState != "IDLE")
                send(CastProtocol.NS_MEDIA, CastProtocol.type("GET_STATUS").put("mediaSessionId", mediaSessionId).put("requestId", ++requestId))
            poll(gen)
        }, 5, TimeUnit.SECONDS)
    }

    /** The connection dropped (Wi-Fi, device restart): connected again, at most 4 tries over about 15 s. */
    private fun lost(gen: Int, d: CastDevice, why: Throwable) {
        log("connection lost: ${why.javaClass.simpleName}")
        channel = null
        if (phase == Phase.NONE) return
        phase = Phase.RECONNECTING
        retry(gen, d)
    }

    private fun retry(gen: Int, d: CastDevice) {
        if (gen != generation) return
        if (++reconnects > 4) {
            log("could not connect again: back on the phone")
            main.post { if (gen == generation) { toast(app.myspoti.engine.R.string.cast_lost); stop(back = true) } }
            return
        }
        val delay = longArrayOf(1, 2, 4, 8)[reconnects - 1]
        log("connecting again in $delay s (try $reconnects)")
        worker.schedule({ if (gen == generation) connect(gen, d, Phase.RECONNECTING) }, delay, TimeUnit.SECONDS)
    }

    private fun fail(gen: Int, why: String) {
        log("cast failed: $why")
        teardown(stopApp = false)
        main.post {
            if (gen != generation) return@post
            toast(app.myspoti.engine.R.string.cast_failed)
            stop(back = true)
        }
    }

    /** The device ended the session itself: the songs come back to the phone, paused where they were. */
    private fun endFromDevice(gen: Int) {
        phase = Phase.NONE
        wantPlaying = false
        main.post { if (gen == generation) stop(back = true) }
    }

    private fun teardown(stopApp: Boolean) {
        val c = channel
        if (c != null && stopApp && phase == Phase.READY) session?.let { a ->
            runCatching { c.send(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.stopApp(++requestId, a.sessionId)) }
        }
        c?.close()
        channel = null
        phase = Phase.NONE
        session = null
        loaded = null
        mediaSessionId = -1; loadRequest = -1; finishedFor = -1
        reconnects = 0; failures = 0
        directFailed.clear()
        playerState = "IDLE"
    }

    private fun send(namespace: String, json: JSONObject, destination: String? = null) {
        val c = channel ?: return
        val to = destination ?: session?.transportId ?: return
        try { c.send(to, namespace, json) } catch (e: Throwable) { log("send failed: ${e.javaClass.simpleName}") }
    }

    /** Keeps the Wi-Fi and the CPU awake while the device fetches from the phone. */
    @Suppress("DEPRECATION")
    private fun hold(on: Boolean) {
        runCatching {
            if (on) {
                if (wifiLock == null) wifiLock = (Graph.app.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "myspoti:cast").apply { setReferenceCounted(false) }
                if (wakeLock == null) wakeLock = (Graph.app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "myspoti:cast").apply { setReferenceCounted(false) }
                wifiLock?.acquire(); wakeLock?.acquire(6 * 60 * 60 * 1000L)
            } else {
                wifiLock?.takeIf { it.isHeld }?.release(); wakeLock?.takeIf { it.isHeld }?.release()
            }
        }
    }

    private fun toast(res: Int) {
        val name = device.value?.name.orEmpty()
        android.widget.Toast.makeText(Graph.app, Graph.app.getString(res, name), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("cast: $line")
}
