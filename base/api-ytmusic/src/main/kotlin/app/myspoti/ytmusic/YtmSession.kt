/* SPDX-License-Identifier: GPL-3.0-only
 *
 * The sign-in url, its "reached music.youtube.com" test and the SAPISIDHASH header follow InnerTune
 * (https://github.com/z-huang/InnerTune, GPL-3.0, commit bfba5ec: app/.../ui/screens/LoginScreen.kt and
 * innertube/.../InnerTube.kt), as Metrolist does. See THIRD_PARTY_NOTICES.md.
 */
package app.myspoti.ytmusic

import app.myspoti.web.parseCookieHeader
import java.security.MessageDigest

/** YouTube Music's side of the account provider: its key, its ids, its urls, its session. */
object Ytm {
    /** The provider key, as in the catalog's uris (`ytmusic:track:{videoId}`) and the sync's track ids. */
    const val PROVIDER = "ytmusic"
    const val ORIGIN = "https://music.youtube.com"
    const val HOME_URL = "$ORIGIN/"

    /**
     * The official sign-in, opened in a WebView the user signs in themself: Google's web sign-in
     * for YouTube, back on music.youtube.com once done (the url InnerTune and Metrolist use).
     */
    const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

    /** The sites whose cookies hold the session (all deleted at sign-out). */
    val SESSION_URLS = listOf(ORIGIN, "https://www.youtube.com", "https://youtube.com", "https://accounts.google.com", "https://www.google.com", "https://google.com")

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** A YouTube video id (11 characters): only those are played on the page. */
    fun isVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)

    fun watchUrl(videoId: String) = "$ORIGIN/watch?v=$videoId"

    /** The sign-in WebView reached YouTube Music: the flow is over (signed in, or the user went there signed out). */
    fun signInDone(url: String?): Boolean = url != null && (url == ORIGIN || url.startsWith("$ORIGIN/"))

    /** The cookie the auth header is made from, when the session has one: the account is signed in. */
    fun sapisid(cookieHeader: String?): String? {
        val c = parseCookieHeader(cookieHeader)
        return (c["SAPISID"] ?: c["__Secure-3PAPISID"])?.takeIf { it.isNotBlank() }
    }

    fun signedIn(cookieHeader: String?): Boolean = sapisid(cookieHeader) != null

    /** `SAPISIDHASH <seconds>_<sha1("<seconds> <SAPISID> <origin>")>`, the web app's own auth header. */
    fun authorization(sapisid: String, nowSeconds: Long, origin: String = ORIGIN): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$nowSeconds $sapisid $origin".toByteArray())
        return "SAPISIDHASH ${nowSeconds}_" + digest.joinToString("") { "%02x".format(it) }
    }
}
