package com.music.spotui.connect

import android.content.Context
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.spotify.bridge.LogMask
import com.music.spotui.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Gets the code a Sonos takes ([CodeExchange]) by trying the subject tokens in turn: the one that
 * worked last time for the account, then the silent ones (one request each), and last Spotify's
 * consent page for its desktop client, shown in the device picker ([DesktopConsent]) and only after a
 * tap on the speaker. The desktop refresh token got there is kept encrypted with the Keystore key of
 * the downloads ([com.music.spotui.data.offline.OfflineCrypto]) and wiped at sign-out ([clear]).
 *
 * Every call is a plain request to accounts.spotify.com: never the raw path, whose web headers
 * (Bearer, client-token) are not the exchange's. Each step is logged as "lan: sonos: …" by name
 * and HTTP status, never a token, a code or the account.
 */
object ConnectCode {
    enum class Strategy(val label: String) {
        DESKTOP_REFRESH("desktop-refresh"),
        DEVICEAUTH_DESKTOP("deviceauth-desktop"),
        WEB_WEB("web-web"),
        WEB_DESKTOP("web-desktop"),
        DEVICEAUTH_DEVICE("deviceauth-device"),
        DESKTOP_CONSENT("desktop-consent"),
    }

    class Minted(val strategy: Strategy, val code: String)

    /** No code, or the device would not take one: [grouped] when it answered as a grouped speaker. */
    class Failure(message: String, val grouped: Boolean = false) : IOException(message)

    /** An access token to exchange, the client it belongs to and how that client calls. */
    private class Subject(val token: String, val clientId: String, val userAgent: String, val web: Boolean = false)

    private const val PREFS = "lan_connect_code"
    private val http by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build() }
    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    /**
     * One login of [info]: the subject tokens got so far stay here (in memory) so a fresh code costs
     * one request. [controllerId] is this phone's id for device-auth, [deviceName] the speaker's name
     * for the consent page.
     */
    class Session(private val info: ZeroconfInfo, private val account: String, private val controllerId: String, private val deviceName: String) {
        private val audience = CodeExchange.audience(info)
        private val order: List<Strategy> = run {
            // The consent's token is kept: the next time it is the refresh that is tried first.
            val cached = cachedStrategy(account)?.let { if (it == Strategy.DESKTOP_CONSENT) Strategy.DESKTOP_REFRESH else it }
            listOfNotNull(cached) + Strategy.entries.filter { it != cached }
        }
        private var at = 0
        private val subjects = HashMap<Strategy, Subject>()

        /** A code from the next way that gives one, or null when none is left. */
        suspend fun next(): Minted? {
            while (at < order.size) {
                val s = order[at++]
                val subject = runCatching { subjects[s] ?: subject(s)?.also { subjects[s] = it } }
                    .onFailure { log("code ${s.label}: no subject token (${it.javaClass.simpleName})") }.getOrNull() ?: continue
                exchange(s, subject)?.let { return Minted(s, it) }
            }
            return null
        }

        /** A fresh code the way [m] got its own (a code may be good for one addUser only); null when it fails now. */
        suspend fun again(m: Minted): Minted? = subjects[m.strategy]?.let { s -> exchange(m.strategy, s)?.let { Minted(m.strategy, it) } }

        private suspend fun exchange(s: Strategy, subject: Subject): String? {
            val answer = post(CodeExchange.exchangeForm(subject.token, audience, subject.clientId), subject.userAgent, subject.web)
            log("code ${s.label}: ${LogMask.redact(answer.summary)}")
            return answer.accessToken.takeIf { answer.isCode }
        }

        private suspend fun subject(s: Strategy): Subject? = when (s) {
            Strategy.DESKTOP_REFRESH -> refreshed(account)
            Strategy.DEVICEAUTH_DESKTOP -> deviceAuth("desktop", CodeExchange.DESKTOP_CLIENT_ID, controllerId)?.let { Subject(it, CodeExchange.DESKTOP_CLIENT_ID, CodeExchange.DESKTOP_USER_AGENT) }
            Strategy.WEB_WEB -> webToken()?.let { (token, client) -> Subject(token, client, MyspotiRuntime.host.userAgent, web = true) }
            Strategy.WEB_DESKTOP -> webToken()?.let { (token, _) -> Subject(token, CodeExchange.DESKTOP_CLIENT_ID, CodeExchange.DESKTOP_USER_AGENT) }
            Strategy.DEVICEAUTH_DEVICE -> deviceAuth("device", info.clientId, info.deviceId)?.let { Subject(it, CodeExchange.DESKTOP_CLIENT_ID, CodeExchange.DESKTOP_USER_AGENT) }
            Strategy.DESKTOP_CONSENT -> consented(account, deviceName)
        }
    }

    /** The way that logged a device in for [account]: tried first next time. */
    fun remember(account: String, strategy: Strategy) {
        prefs().edit().putString("strategy:${key(account)}", strategy.name).apply()
    }

    /** Sign-out: the desktop refresh token and the ways remembered go. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun cachedStrategy(account: String): Strategy? =
        prefs().getString("strategy:${key(account)}", null)?.let { n -> Strategy.entries.firstOrNull { it.name == n } }

    /** The desktop token from the refresh token kept for [account]; a refused one is dropped. */
    private suspend fun refreshed(account: String): Subject? {
        val refresh = storedRefresh(account) ?: return null
        val answer = post(CodeExchange.refreshForm(refresh), CodeExchange.DESKTOP_USER_AGENT)
        log("desktop refresh: ${LogMask.redact(answer.summary)}")
        if (!answer.isToken) {
            if (answer.status in 400..499) storeRefresh(account, null)
            return null
        }
        answer.refreshToken?.let { storeRefresh(account, it) }
        return Subject(answer.accessToken!!, CodeExchange.DESKTOP_CLIENT_ID, CodeExchange.DESKTOP_USER_AGENT)
    }

    /** Spotify's consent page for its desktop client, then its token; its refresh token is kept. */
    private suspend fun consented(account: String, deviceName: String): Subject? {
        val verifier = CodeExchange.verifier()
        val state = CodeExchange.verifier()
        val url = DesktopConsent.ask(CodeExchange.authorizeUrl(CodeExchange.challenge(verifier), state), deviceName) ?: return null
        val query = CodeExchange.redirected(url) ?: return null
        val code = query["code"]
        if (query["state"] != state || code.isNullOrBlank()) {
            log("consent: ended without a code (${query["error"]?.take(40) ?: if (query["state"] != state) "state differs" else "no code"})")
            return null
        }
        log("consent: code received")
        val answer = post(CodeExchange.consentForm(code, verifier), CodeExchange.DESKTOP_USER_AGENT)
        log("consent token: ${LogMask.redact(answer.summary)}")
        if (!answer.isToken) return null
        answer.refreshToken?.let { storeRefresh(account, it) }
        return Subject(answer.accessToken!!, CodeExchange.DESKTOP_CLIENT_ID, CodeExchange.DESKTOP_USER_AGENT)
    }

    /** A token Spotify's device-auth issues for [clientId], as the accesstoken login gets one (through the account's own path). */
    private suspend fun deviceAuth(which: String, clientId: String, deviceId: String): String? {
        if (clientId.isBlank()) return null
        val auth = MyspotiRuntime.spotify.raw.request("POST", "https://spclient.wg.spotify.com/device-auth/v1/refresh",
            JSONObject().put("clientId", clientId).put("deviceId", deviceId).toString().toByteArray(),
            mapOf("content-type" to "text/plain;charset=UTF-8"))
        log("device-auth $which: HTTP ${auth.status}")
        return if (auth.status == 200) runCatching { JSONObject(auth.body).optString("accessToken").ifBlank { null } }.getOrNull() else null
    }

    /** The Web Player's token and its client id, from the page; null for an anonymous one. */
    private suspend fun webToken(): Pair<String, String>? {
        val t = MyspotiRuntime.spotify.bridge.call("token.get").jsonObject
        if (t["isAnonymous"]?.jsonPrimitive?.contentOrNull == "true") return null
        val token = t["accessToken"]?.jsonPrimitive?.contentOrNull ?: return null
        val client = t["clientId"]?.jsonPrimitive?.contentOrNull ?: return null
        return token to client
    }

    /** A form POST to accounts.spotify.com/api/token with no cookie and no credential header. */
    private suspend fun post(form: String, userAgent: String, web: Boolean = false): CodeExchange.Answer = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(CodeExchange.TOKEN_URL).post(form.toRequestBody(FORM))
            .header("user-agent", userAgent).header("accept", "application/json")
            .apply { if (web) header("origin", "https://open.spotify.com").header("referer", "https://open.spotify.com/") }
            .build()
        runCatching { http.newCall(request).execute().use { r -> CodeExchange.parse(r.code, r.body.string()) } }
            .getOrElse { e -> log("token call failed: ${e.javaClass.simpleName}"); CodeExchange.parse(-1, "") }
    }

    // The refresh token, sealed with the downloads' Keystore key (AES-GCM) and kept for one account only.

    private fun storedRefresh(account: String): String? {
        val p = prefs()
        if (p.getString("refresh_for", null) != key(account)) return null
        val sealed = p.getString("refresh", null) ?: return null
        return runCatching {
            val raw = android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, com.music.spotui.data.offline.OfflineCrypto.key(Graph.app), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun storeRefresh(account: String, token: String?) {
        if (token == null) { prefs().edit().remove("refresh").remove("refresh_for").apply(); return }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, com.music.spotui.data.offline.OfflineCrypto.key(Graph.app)) }
            val sealed = c.iv + c.doFinal(token.toByteArray(Charsets.UTF_8))
            prefs().edit().putString("refresh", android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP)).putString("refresh_for", key(account)).apply()
        }.onFailure { log("desktop token not kept: ${it.javaClass.simpleName}") }
    }

    /** The account as stored: a hash, never the name. */
    private fun key(account: String) = Zeroconf.sha1Hex("lan-code:$account").take(16)

    private fun prefs() = Graph.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun log(line: String) = Logbook.log("lan: sonos: $line")
}
