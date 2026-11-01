package com.music.spotui.connect

import androidx.compose.runtime.mutableStateOf
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.IOException

/**
 * Logs a Spotify Connect device of the network in to the account, as the Spotify app does when a
 * speaker is tapped: Spotify's device-auth issues a token for the device's client id (from the web
 * token), `addUser` hands it to the device, the device joins the account's Connect devices, and
 * the song is transferred to it like to any other. Tried for every device that announces its ids
 * ([ZeroconfInfo.loginable]), Sonos included; the answer of each is in the log.
 */
object LanLogin {
    /** The device being logged in (the picker shows it connecting). Main thread. */
    val busy = mutableStateOf<LanDevice?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Logs [d] in and moves the song to it (main thread). */
    fun start(d: LanDevice) {
        if (busy.value != null) return
        busy.value = d
        scope.launch {
            val device = runCatching { login(d) }
            withContext(Dispatchers.Main) {
                busy.value = null
                device.onSuccess { SongPlayer.transferTo(it) }.onFailure {
                    log("failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}")
                    android.widget.Toast.makeText(Graph.app, Graph.app.getString(app.myspoti.engine.R.string.lan_failed, d.info.name.ifBlank { d.service }), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun login(d: LanDevice): SongPlayer.ConnectDevice {
        connectDevice(d)?.let { log("already a Spotify Connect device"); return it }
        val spotify = MyspotiRuntime.spotify
        val user = (spotify.raw.service("UserAPI", "getUser") as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty()
        val auth = spotify.raw.request("POST", "https://spclient.wg.spotify.com/device-auth/v1/refresh",
            JSONObject().put("clientId", d.info.clientId).put("deviceId", d.info.deviceId).toString().toByteArray(),
            mapOf("content-type" to "text/plain;charset=UTF-8"))
        // The token is never logged.
        val token = (if (auth.status == 200) runCatching { JSONObject(auth.body).optString("accessToken").ifBlank { null } }.getOrNull() else null)
            ?: throw IOException("device-auth ${auth.status}")
        val me = android.os.Build.MODEL.ifBlank { "Android" }
        val request = Zeroconf.addUserRequest(d.host.hostAddress.orEmpty(), d.port, d.cpath, user, token, d.info, me, controllerId(me))
        val answer = withContext(Dispatchers.IO) { LanDiscovery.call(d.host, d.port, request, 10_000) } ?: throw IOException("no answer to addUser")
        log("addUser answered ${answer.first}, ${Zeroconf.answerSummary(answer.second)} (device says ${d.info.tokenType.ifBlank { "?" }})")
        if (!Zeroconf.addUserAccepted(answer.first, answer.second)) throw IOException("addUser refused ${answer.first}")
        // It joins the account's devices a moment after (it logs in to Spotify first).
        for (k in 0 until 10) {
            delay(1500)
            connectDevice(d)?.let { log("joined the Spotify Connect devices"); return it }
        }
        throw IOException("the device did not show up")
    }

    /** [d] among the account's Connect devices: by the id it announced, else by its name. */
    private suspend fun connectDevice(d: LanDevice): SongPlayer.ConnectDevice? {
        val api = MyspotiRuntime.spotify.devices
        runCatching { api.refresh() }
        val all = api.devices.value.filter { !it.local }
        val x = all.firstOrNull { it.id == d.info.deviceId } ?: all.firstOrNull { d.info.name.isNotBlank() && it.name == d.info.name } ?: return null
        return SongPlayer.ConnectDevice(x.id, x.name, x.type, false, x.active)
    }

    /** A stable id for this phone as a controller: the MD5 of its name, as Spotify's own clients derive one. */
    private fun controllerId(name: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("lan: login: $line")
}
