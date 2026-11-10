package app.myspoti.host.android

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Fetches Spotify's audio files for the WebView, instead of letting the WebView fetch them.
 *
 * Every request a WebView makes carries `X-Requested-With: <package name>` — measured on the wire,
 * it rode on the music files themselves and on Spotify's telemetry. No browser sends that header,
 * so it is the one thing that names this client wherever it goes; the telemetry and the API calls
 * are relayed (see [ControlRelay]), and the audio is the bulk of what is left: it is fetched from
 * here, so those requests never leave the WebView at all.
 *
 * Range requests are forwarded as they arrive, so seeking, buffering and the CDN's own caching
 * behave exactly as before; the response travels back as a live stream, never buffered.
 */
internal class MediaProxy(private val client: OkHttpClient, private val identity: () -> Pair<String, String>?) {

    /** A Spotify audio file (tracks, podcast episodes). */
    fun handles(url: String) = ENABLED && AUDIO.containsMatchIn(url)

    fun fetch(request: WebResourceRequest): WebResourceResponse? = runCatching {
        val builder = Request.Builder().url(request.url.toString())
        // The method comes from the page (HEAD probes included): asking for something else would
        // hand back a body where Chromium expects none.
        builder.method(request.method?.uppercase() ?: "GET", null)
        request.requestHeaders.forEach { (key, value) ->
            // The transport headers are the client's own, and `accept-encoding` must not be copied:
            // OkHttp asks for what it can undo, and a copy Chromium cannot read would be corruption.
            if (key.lowercase() !in SKIP) builder.header(key, value)
        }
        // The page's identity, so the CDN sees the same client the page claims to be.
        identity()?.let { (userAgent, language) ->
            builder.header("User-Agent", userAgent)
            if (language.isNotEmpty()) builder.header("Accept-Language", language)
        }
        val response = client.newCall(builder.build()).execute()
        val body = response.body
        if (body == null || (response.code != 200 && response.code != 206)) {
            response.close()
            return null
        }
        // Everything the CDN said, minus what the hop replaces. A whitelist looked safer and was
        // wrong: the Web Player's audio element goes through CORS, and dropping
        // `access-control-allow-origin` made the media fail — the player threw and playback died.
        val headers = response.headers.toMultimap()
            .filterKeys { it.lowercase() !in DROP }
            .mapValues { (_, values) -> values.joinToString(",") }
        WebResourceResponse(
            body.contentType()?.toString()?.substringBefore(';') ?: "audio/mpeg",
            null,
            response.code,
            response.message.ifBlank { "OK" },
            headers,
            ClosingStream(body.byteStream(), response),
        )
    }.getOrNull()

    companion object {
        /**
         * Off while the regression it was meant to fix is being worked out: the header it removes is
         * the fingerprint, but a proxy that breaks playback is worse than a fingerprint. Flipped back
         * on once the test battery passes with it.
         */
        const val ENABLED = false

        /** Spotify's audio CDNs. Images and fonts (also on these hosts) stay with the WebView. */
        private val AUDIO = Regex("^https://[a-z0-9-]+\\.(scdn\\.co|spotifycdn\\.com)/audio/")

        /** Never copied from the incoming request; the transport sets them (or must not copy them). */
        private val SKIP = setOf(
            "host", "connection", "accept-encoding", "content-length", "x-requested-with",
            "user-agent", "accept-language", "origin", "referer",
        )

        /**
         * What the hop replaces, and nothing else: every other header the CDN sent travels through
         * untouched (CORS above all — see the comment in [fetch]).
         */
        private val DROP = setOf("content-encoding", "transfer-encoding", "connection", "keep-alive", "set-cookie")
        /** A connection of its own: audio streams are long, and one kept alive per track is enough. */
        fun client(): OkHttpClient = TlsCompat.apply(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Closes the whole HTTP response once Chromium has finished with the body it was handed. */
private class ClosingStream(stream: InputStream, private val response: Response) : FilterInputStream(stream) {
    override fun close() {
        runCatching { super.close() }
        runCatching { response.close() }
    }
}
