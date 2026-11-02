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
 * ([ZeroconfInfo.loginable]); the answer of each is in the log. A device that wants a code (Sonos)
 * gets one minted for it ([ConnectCode]) instead of the token, with its own retries.
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
                    val message = when {
                        it is ConnectCode.Failure && it.grouped -> app.myspoti.engine.R.string.lan_sonos_group
                        it is ConnectCode.Failure -> app.myspoti.engine.R.string.lan_sonos_refused
                        else -> app.myspoti.engine.R.string.lan_failed
                    }
                    android.widget.Toast.makeText(Graph.app, Graph.app.getString(message, d.info.name.ifBlank { d.service }), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun login(d: LanDevice): SongPlayer.ConnectDevice {
        connectDevice(d)?.let { log("already a Spotify Connect device"); return it }
        val spotify = MyspotiRuntime.spotify
        val user = (spotify.raw.service("UserAPI", "getUser") as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty()
        val me = android.os.Build.MODEL.ifBlank { "Android" }
        if (d.info.wantsCode) return loginWithCode(d, user, me)
        val auth = spotify.raw.request("POST", "https://spclient.wg.spotify.com/device-auth/v1/refresh",
            JSONObject().put("clientId", d.info.clientId).put("deviceId", d.info.deviceId).toString().toByteArray(),
            mapOf("content-type" to "text/plain;charset=UTF-8"))
        // The token is never logged.
        val token = (if (auth.status == 200) runCatching { JSONObject(auth.body).optString("accessToken").ifBlank { null } }.getOrNull() else null)
            ?: throw IOException("device-auth ${auth.status}")
        val request = Zeroconf.addUserRequest(d.host.hostAddress.orEmpty(), d.port, d.cpath, user, token, d.info, me, controllerId(me))
        val answer = withContext(Dispatchers.IO) { LanDiscovery.call(d.host, d.port, request, 10_000) } ?: throw IOException("no answer to addUser")
        log("addUser answered ${answer.first}, ${Zeroconf.answerSummary(answer.second)} (device says ${d.info.tokenType.ifBlank { "?" }})")
        if (!Zeroconf.addUserAccepted(answer.first, answer.second)) throw IOException("addUser refused ${answer.first}")
        return joined(d, 10) ?: throw IOException("the device did not show up")
    }

    /**
     * The login of a device that wants a code (Sonos): once it is ready, a code ([ConnectCode]) in
     * addUser, sent again on 202 (Sonos often says ERROR-LOGIN-FAILED first; a fresh code from the
     * third try on), once more after 203 once it is ready, then with the next way to a code and with
     * the random login id Spotify's desktop app sends. Ends with one line that says which link failed.
     */
    private suspend fun loginWithCode(d: LanDevice, user: String, me: String): SongPlayer.ConnectDevice {
        var info = ready(d, d.info)
        ConnectCode.log("login: audience is ${if (CodeExchange.audience(info) == CodeExchange.SONOS_CLIENT_ID) "the Sonos id" else "the device's own id ${info.clientId.take(8)}"}")
        val session = ConnectCode.Session(info, user, controllerId(me), d.info.name.ifBlank { d.service })
        var minted: ConnectCode.Minted? = null
        var last = "none"
        var listed = false
        try {
            minted = session.next() ?: throw ConnectCode.Failure("no code")
            var loginId = user
            var left = MAX_LOGIN_FAILED
            var keyRetried = false
            var nextTried = false
            var attempt = 0
            while (true) {
                val m = minted!!
                attempt++
                val answer = withContext(Dispatchers.IO) {
                    LanDiscovery.call(d.host, d.port, Zeroconf.addUserCodeRequest(d.host.hostAddress.orEmpty(), d.port, d.cpath, user, loginId, m.code, info, me), 10_000)
                }
                last = answer?.let { "HTTP ${it.first}, ${Zeroconf.answerSummary(it.second)}" } ?: "no answer"
                ConnectCode.log("addUser #$attempt (${m.strategy.label}, loginId=${if (loginId == user) "username" else "random"}): $last")
                if (answer == null) throw IOException("no answer to addUser")
                when (Zeroconf.codeAnswer(answer.first, answer.second)) {
                    Zeroconf.CodeAnswer.ACCEPTED -> {
                        // A Sonos takes longer than a Bose to join: it redeems the code first.
                        val device = joined(d, 20) ?: throw IOException("the device did not show up")
                        listed = true
                        ConnectCode.remember(user, m.strategy)
                        return device
                    }
                    Zeroconf.CodeAnswer.GROUPED -> throw ConnectCode.Failure("grouped speaker?", grouped = true)
                    Zeroconf.CodeAnswer.REFUSED -> throw ConnectCode.Failure("addUser refused ${answer.first}")
                    Zeroconf.CodeAnswer.INVALID_KEY -> {
                        if (keyRetried) throw ConnectCode.Failure("addUser refused: public key")
                        keyRetried = true
                        info = ready(d, info.copy(availability = "NOT-LOADED"))
                        minted = session.again(m) ?: m
                    }
                    Zeroconf.CodeAnswer.LOGIN_FAILED -> {
                        delay(250)
                        if (--left > 0) {
                            // A code may be good for one try only.
                            if (left <= MAX_LOGIN_FAILED - 2) minted = session.again(m) ?: m
                        } else {
                            val other = if (nextTried) null else session.next()
                            nextTried = true
                            when {
                                other != null -> { minted = other; left = MAX_LOGIN_FAILED }
                                loginId == user -> { loginId = CodeExchange.randomLoginId(); left = 1; minted = session.again(m) ?: m }
                                else -> throw ConnectCode.Failure("addUser login failed")
                            }
                        }
                    }
                }
            }
        } finally {
            ConnectCode.log("result code=${minted?.strategy?.label ?: "none"} addUser=$last joined=${if (listed) "yes" else "no"}")
        }
    }

    /** [info] once the device's Spotify part is loaded (it answers getInfo again every second, 5 s at most). */
    private suspend fun ready(d: LanDevice, info: ZeroconfInfo): ZeroconfInfo {
        var now = info
        for (k in 1..5) {
            if (!now.starting) return now.also { if (k > 1) ConnectCode.log("loaded after ${k - 1}s") }
            delay(1000)
            now = withContext(Dispatchers.IO) { LanDiscovery.getInfo(d.host, d.port, d.cpath) } ?: now
        }
        if (now.starting) ConnectCode.log("still not loaded after 5s, trying anyway")
        return now
    }

    /** [d] once it joined the account's devices ([tries] × 1.5 s): it logs in to Spotify first. */
    private suspend fun joined(d: LanDevice, tries: Int): SongPlayer.ConnectDevice? {
        for (k in 1..tries) {
            delay(1500)
            connectDevice(d)?.let { log("joined the Spotify Connect devices after ${k * 3 / 2}s"); return it }
        }
        log("not in the device list after ${tries * 3 / 2}s")
        return null
    }

    /** 202 answers taken before the next way to a code. */
    private const val MAX_LOGIN_FAILED = 5

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
