// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.host.android

import app.myspoti.engine.AccessToken
import app.myspoti.engine.ClientToken
import app.myspoti.engine.EngineConfig
import app.myspoti.engine.TokenSource
import app.myspoti.spotify.gql.SpotifyHttp

/**
 * The native engine's tokens, phase 1: the ones the app's direct HTTP calls already use
 * ([SpotifyHttp.credentials]): the web token the Web Player page holds (bridge `token.get`, asked
 * again when stale or after a 401) and the client token minted and kept by SpotifyHttp for this
 * install's client id. Nothing is computed here: no TOTP, no secret from Spotify's bundles.
 */
class NativeTokens(private val http: SpotifyHttp) : TokenSource {
    override suspend fun accessToken(forceRefresh: Boolean): AccessToken {
        val c = http.credentials(forceRefresh)
        return AccessToken(c.accessToken, c.accessExpiresAtMs, c.clientId)
    }

    override suspend fun clientToken(): ClientToken {
        val c = http.credentials()
        return ClientToken(c.clientToken, c.clientDeviceId)
    }

    companion object {
        /**
         * The engine's browser identity, the one this install already shows Spotify: the WebView's
         * Chrome user agent and client hints ([Fingerprint], as the relay sends them) and the page's
         * `clientVersion`. A Chrome identity also matches what the Chromium HTTP stack looks like on
         * the wire, where a Firefox user agent would not.
         */
        suspend fun config(http: SpotifyHttp): EngineConfig {
            val f = MyspotiRuntime.host.fingerprint
            val language = Fingerprint.acceptLanguage(android.os.LocaleList.getDefault().toLanguageTags().split(','))
            return EngineConfig(
                appVersion = http.credentials().appVersion,
                browserName = "Chrome",
                browserVersion = "${f.major}.0.0.0",
                os = "linux",
                userAgent = f.userAgent,
                acceptLanguage = language,
                clientHints = linkedMapOf(
                    "sec-ch-ua" to f.secChUa,
                    "sec-ch-ua-mobile" to "?0",
                    "sec-ch-ua-platform" to "\"Linux\"",
                ),
            )
        }
    }
}
