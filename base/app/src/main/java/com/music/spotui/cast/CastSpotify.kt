package com.music.spotui.cast

import androidx.compose.runtime.mutableStateOf
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

/**
 * Spotify's own songs on a Cast device, the way the Spotify app does it: the Spotify receiver
 * (CC32E753) is launched on the device, told who plays (getInfo, then addUser with a token
 * Spotify's device-auth issues for the account's web token), and the device then shows up in
 * the account's Spotify Connect devices, where the song is transferred to it like to any other.
 * Spotify plays it itself (DRM): nothing goes through the phone, and a free account is refused
 * by Spotify at the transfer, as for any Connect device.
 *
 * The idea is that of the Home Assistant integration Spotcast (Apache-2.0); the code is Myspoti's.
 */
object CastSpotify {
    /** The device being woken (the picker shows it connecting). Main thread. */
    val waking = mutableStateOf<CastDevice?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The connection kept to the device Spotify plays on: its receiver goes when the last sender leaves. */
    private class Held(val channel: CastChannel, val app: CastProtocol.App)
    @Volatile private var held: Held? = null
    private var requestId = 10

    /** Wakes [d] and moves the Spotify song to it (main thread). */
    fun start(d: CastDevice) {
        if (waking.value != null) return
        waking.value = d
        scope.launch {
            val device = runCatching { wake(d) }
            withContext(Dispatchers.Main) {
                waking.value = null
                device.onSuccess { SongPlayer.transferTo(it) }.onFailure {
                    log("failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}")
                    android.widget.Toast.makeText(Graph.app, Graph.app.getString(app.myspoti.engine.R.string.cast_spotify_failed, d.name), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun wake(d: CastDevice): SongPlayer.ConnectDevice {
        val id = CastProtocol.spotifyDeviceId(d.name)
        // Already one of the account's devices (the Spotify app woke it, or an earlier wake of ours).
        connectDevice(d, id)?.let { log("already a Spotify Connect device"); return it }
        release()
        val inbox = Channel<Pair<CastMessage, JSONObject>>(Channel.UNLIMITED)
        val c = CastChannel(d.host, d.port, onMessage = { m, j -> inbox.trySend(m to j) }, onClosed = { inbox.close(it) })
        withContext(Dispatchers.IO) { c.open(10_000) }
        try {
            log("connected, launching the Spotify receiver")
            c.send(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.launch(++requestId, CastProtocol.SPOTIFY_RECEIVER))
            val app = inbox.next(25_000) { m, j ->
                if (m.namespace != CastProtocol.NS_RECEIVER) return@next null
                when (j.optString("type")) {
                    "LAUNCH_ERROR" -> throw IOException("launch refused")
                    "RECEIVER_STATUS" -> CastProtocol.receiver(j, CastProtocol.SPOTIFY_RECEIVER).app?.takeIf { it.transportId.isNotBlank() }
                    else -> null
                }
            } ?: throw IOException("no Spotify receiver")
            c.send(app.transportId, CastProtocol.NS_CONNECTION, CastProtocol.connect())
            // The receiver may not listen yet: asked again until it answers.
            var info: CastProtocol.SpotifyReply.Info? = null
            for (k in 0 until 5) {
                c.send(app.transportId, CastProtocol.NS_SPOTIFY, CastProtocol.spotifyGetInfo(++requestId, d.name, id, d.model == CastProtocol.GROUP_MODEL))
                val r = inbox.next(3_000) { m, j -> if (m.namespace == CastProtocol.NS_SPOTIFY) CastProtocol.spotifyReply(j) else null }
                if (r is CastProtocol.SpotifyReply.Failed) throw IOException(r.type)
                info = r as? CastProtocol.SpotifyReply.Info
                if (info != null) break
            }
            val clientId = info?.clientId ?: throw IOException("no answer to getInfo")
            val answer = MyspotiRuntime.spotify.raw.request("POST", "https://spclient.wg.spotify.com/device-auth/v1/refresh",
                CastProtocol.deviceAuthBody(clientId, id).toByteArray(), mapOf("content-type" to "text/plain;charset=UTF-8"))
            // The token is never logged.
            val blob = (if (answer.status == 200) CastProtocol.deviceAuthToken(answer.body) else null) ?: throw IOException("device-auth ${answer.status}")
            c.send(app.transportId, CastProtocol.NS_SPOTIFY, CastProtocol.spotifyAddUser(++requestId, blob))
            val added = inbox.next(20_000) { m, j -> if (m.namespace == CastProtocol.NS_SPOTIFY) CastProtocol.spotifyReply(j) else null }
            if (added !is CastProtocol.SpotifyReply.UserAdded) throw IOException((added as? CastProtocol.SpotifyReply.Failed)?.type ?: "no answer to addUser")
            log("logged in on the device, waiting for it among the Spotify devices")
            held = Held(c, app)
            for (k in 0 until 14) {
                delay(1500)
                connectDevice(d, id)?.let { found -> watch(found.id); return found }
            }
            release()
            throw IOException("the device did not show up")
        } catch (t: Throwable) {
            if (held?.channel !== c) c.close()
            throw t
        }
    }

    /** The next message [pick] accepts within [ms] (null: none came in time). */
    private suspend fun <T : Any> Channel<Pair<CastMessage, JSONObject>>.next(ms: Long, pick: (CastMessage, JSONObject) -> T?): T? =
        withTimeoutOrNull(ms) {
            var found: T? = null
            while (found == null) { val (m, j) = receive(); found = pick(m, j) }
            found
        }

    /** [d] among the account's Spotify Connect devices: by the id Spotify gives it, else by name among the Cast ones. */
    private suspend fun connectDevice(d: CastDevice, id: String): SongPlayer.ConnectDevice? {
        val api = MyspotiRuntime.spotify.devices
        runCatching { api.refresh() }
        val all = api.devices.value.filter { !it.local }
        val x = all.firstOrNull { it.id == id } ?: all.firstOrNull { it.name == d.name && it.type.startsWith("CAST", ignoreCase = true) } ?: return null
        return SongPlayer.ConnectDevice(x.id, x.name, x.type, false, x.active)
    }

    /**
     * Keeps the connection while Spotify plays on the device; once it has not played there for 15 s
     * (back on the phone, another device) or never started in 2 min, the receiver is stopped.
     */
    private fun watch(connectId: String) {
        val mine = held ?: return
        scope.launch {
            val list = MyspotiRuntime.spotify.devices.devices
            fun playing() = list.value.any { it.id == connectId && it.active }
            withTimeoutOrNull(120_000) { list.first { _ -> playing() } }
            while (held === mine) {
                list.first { _ -> !playing() }
                delay(15_000)
                if (!playing()) break
            }
            if (held === mine) release()
        }
    }

    private fun release() {
        val h = held ?: return
        held = null
        runCatching { h.channel.send(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.stopApp(++requestId, h.app.sessionId)) }
        h.channel.close()
        log("released the Spotify receiver")
    }

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("cast: spotify: $line")
}
