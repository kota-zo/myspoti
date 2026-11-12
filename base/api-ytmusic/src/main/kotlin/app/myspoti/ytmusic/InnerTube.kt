/* SPDX-License-Identifier: GPL-3.0-only
 *
 * The request shape (the WEB_REMIX client, the X-Origin and auth headers) follows InnerTune
 * (https://github.com/z-huang/InnerTune, GPL-3.0, innertube/.../InnerTube.kt). No API key is sent.
 */
package app.myspoti.ytmusic

import app.myspoti.api.account.SignedOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The web app's own API (`music.youtube.com/youtubei/v1/…`) on the user's session: the cookies of
 * their sign-in ([cookies], read from the host's browser when asked) and the auth header made from
 * them. Only reads (browse, search, the watch-next list): nothing that gets a stream, nothing that
 * changes the account. [clientVersion] is the web app's own, read from the page when it is up.
 */
class InnerTube(
    private val http: OkHttpClient,
    private val cookies: suspend () -> String?,
    private val clientVersion: () -> String = { DEFAULT_CLIENT_VERSION },
    private val language: () -> String = { "en" },
    private val country: () -> String = { "US" },
    private val userAgent: String = DESKTOP_USER_AGENT,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private fun context() = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", clientVersion())
                put("hl", language())
                put("gl", country())
            }
        }
    }

    suspend fun browse(browseId: String): JsonObject = post("browse", body { put("browseId", browseId) })

    /** The next page of a list, by the token its previous page gave. */
    suspend fun browseMore(token: String): JsonObject = post("browse", body { put("continuation", token) }, mapOf("ctoken" to token, "continuation" to token, "type" to "next"))

    suspend fun search(query: String): JsonObject = post("search", body { put("query", query) })

    /** The watch-next list of a song: its own row first (title, artists, album, length). */
    suspend fun next(videoId: String): JsonObject = post("next", body { put("videoId", videoId); put("isAudioOnly", true) })

    private inline fun body(fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
        val base = context()
        return buildJsonObject { base.forEach { (k, v) -> put(k, v) }; fill() }
    }

    private suspend fun post(endpoint: String, body: JsonObject, query: Map<String, String> = emptyMap()): JsonObject {
        val cookie = cookies()
        val sapisid = Ytm.sapisid(cookie) ?: throw SignedOut(Ytm.PROVIDER)
        val request = Request.Builder()
            .url("${Ytm.ORIGIN}/youtubei/v1/$endpoint".toHttpUrl().newBuilder().addQueryParameter("prettyPrint", "false")
                .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build())
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", userAgent)
            .header("Origin", Ytm.ORIGIN)
            .header("X-Origin", Ytm.ORIGIN)
            .header("Referer", Ytm.HOME_URL)
            .header("X-Goog-AuthUser", "0")
            .header("X-YouTube-Client-Name", CLIENT_NAME_NUMBER)
            .header("X-YouTube-Client-Version", clientVersion())
            .header("Cookie", cookie!!)
            .header("Authorization", Ytm.authorization(sapisid, nowSeconds()))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw SignedOut(Ytm.PROVIDER)
                if (!r.isSuccessful) throw IOException("${Ytm.PROVIDER}: $endpoint answered ${r.code}")
                val text = r.body.string()
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("${Ytm.PROVIDER}: $endpoint answer is not JSON") }
            }
        }
    }

    companion object {
        /** The web app's version when the page has not said its own yet (it answers older ones too). */
        const val DEFAULT_CLIENT_VERSION = "1.20251103.01.00"
        /** WEB_REMIX's number in the client-name header. */
        private const val CLIENT_NAME_NUMBER = "67"
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
