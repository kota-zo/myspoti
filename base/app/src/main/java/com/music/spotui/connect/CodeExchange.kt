package com.music.spotui.connect

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The code a device announcing `authorization_code` (Sonos) takes in its addUser blob: Spotify mints
 * it in a token exchange (RFC 8693) at accounts.spotify.com, from an access token of the account
 * (the subject) for the device's client id (the audience). It lives about 600 s and the device
 * redeems it itself. The one setup reported working (SpotifyPlus, Spotify's desktop app traced) uses
 * a token of Spotify's desktop client as the subject, got by PKCE; the other subjects [ConnectCode]
 * tries first cost one request each. Pure functions: the calls are in [ConnectCode].
 */
object CodeExchange {
    const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    /** Spotify's desktop app, the only client the exchange is known to accept. */
    const val DESKTOP_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    /** Sonos's Spotify app id: the audience when a Sonos does not say its own client id. */
    const val SONOS_CLIENT_ID = "9b377073ea334637b1406f329ce005de"
    /** The redirect registered for the desktop client: nothing listens there, the page is stopped on it. */
    const val DESKTOP_REDIRECT = "http://127.0.0.1:4381/login"
    const val DESKTOP_USER_AGENT = "Spotify/124300420 Win32_x86_64/0 (PC desktop)"
    const val CODE_TYPE = "urn:spotify:params:oauth:authorization_code"
    private const val ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"

    /**
     * The client the code is minted for: Sonos's Spotify app for a Sonos whatever id it announces
     * (as SpotifyPlus does, the setup reported working on an Arc and a Symfonisk), else the device's own.
     */
    fun audience(info: ZeroconfInfo): String =
        if (info.brand.equals("Sonos", ignoreCase = true)) SONOS_CLIENT_ID else info.clientId.ifBlank { SONOS_CLIENT_ID }

    /** The exchange of [subjectToken] (a token of [clientId]) for a code [audience] takes. */
    fun exchangeForm(subjectToken: String, audience: String, clientId: String): String = Zeroconf.form(
        "grant_type" to "urn:ietf:params:oauth:grant-type:token-exchange",
        "subject_token" to subjectToken,
        "subject_token_type" to ACCESS_TOKEN_TYPE,
        "requested_token_type" to CODE_TYPE,
        "resource" to "urn:spotify:resources:connect",
        "scope" to "streaming",
        "audience" to audience,
        "client_id" to clientId,
    )

    /** A new desktop token from the refresh token kept since the consent. */
    fun refreshForm(refreshToken: String): String = Zeroconf.form(
        "grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to DESKTOP_CLIENT_ID,
    )

    /** The desktop token for the code the consent page ended on. */
    fun consentForm(code: String, verifier: String): String = Zeroconf.form(
        "grant_type" to "authorization_code", "code" to code, "redirect_uri" to DESKTOP_REDIRECT,
        "client_id" to DESKTOP_CLIENT_ID, "code_verifier" to verifier,
    )

    /** The consent page: Spotify asks the user once to let the desktop client stream (PKCE, S256). */
    fun authorizeUrl(challenge: String, state: String): String = "$AUTHORIZE_URL?" + Zeroconf.form(
        "client_id" to DESKTOP_CLIENT_ID, "response_type" to "code", "redirect_uri" to DESKTOP_REDIRECT,
        "code_challenge_method" to "S256", "code_challenge" to challenge, "scope" to "streaming", "state" to state,
    )

    /** A PKCE verifier (64 URL-safe characters) or a state. */
    fun verifier(random: SecureRandom = SecureRandom()): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also { random.nextBytes(it) })

    fun challenge(verifier: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** The query of [url] when it is the desktop redirect (the consent's end), else null. */
    fun redirected(url: String): Map<String, String>? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (u.scheme != "http" || u.host != "127.0.0.1" || u.port != 4381 || u.path != "/login") return null
        return u.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
    }

    /** 32 hex characters, the login id Spotify's desktop app sends a Sonos (a variant tried last). */
    fun randomLoginId(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** An answer of accounts.spotify.com/api/token. The tokens stay in memory, never in a log line. */
    class Answer(
        val status: Int,
        val accessToken: String?,
        val refreshToken: String?,
        val issuedType: String,
        val expiresIn: Int,
        val error: String,
        val errorDescription: String,
    ) {
        /** A code a device takes: an answer that says it issued anything else is not one. */
        val isCode: Boolean get() = status == 200 && !accessToken.isNullOrBlank() && (issuedType.isBlank() || issuedType == CODE_TYPE)
        val isToken: Boolean get() = status == 200 && !accessToken.isNullOrBlank()

        /** For the log: the status, the error or what was issued, never a token. */
        val summary: String get() = if (isToken)
            "HTTP $status issued=${issuedType.substringAfterLast(':').ifBlank { "?" }} expires=${expiresIn}s len=${accessToken.orEmpty().length}" +
                if (refreshToken.isNullOrBlank()) "" else " +refresh"
        else "HTTP $status ${error.ifBlank { "-" }} ${errorDescription.take(80)}".trimEnd()
    }

    fun parse(status: Int, body: String): Answer {
        val j = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
        return Answer(
            status = status,
            accessToken = j.optString("access_token").ifBlank { null },
            refreshToken = j.optString("refresh_token").ifBlank { null },
            issuedType = j.optString("issued_token_type"),
            expiresIn = j.optInt("expires_in", 0),
            error = j.optString("error"),
            errorDescription = j.optString("error_description"),
        )
    }
}
