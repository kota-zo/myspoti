// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine.android

import app.myspoti.engine.WebSocketConnection
import app.myspoti.engine.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The dealer WebSocket over OkHttp, because neither Cronet nor HttpEngine has a WebSocket client.
 *
 * Known limitation, to revisit: OkHttp's TLS ClientHello (cipher order, extensions, no GREASE) is
 * not Chrome's, so the dealer connection alone does not carry the browser fingerprint every other
 * call has through [ChromiumHttp]. The upgrade request carries the headers Chrome adds to its own
 * WebSocket handshakes next to the core's (user agent, origin, language, client hints); OkHttp's
 * own `Sec-WebSocket-*`, `Upgrade` and `Connection` are what any client sends.
 *
 * No protocol-level pings: Chrome sends none, and the core sends the dealer's JSON ping itself.
 */
class OkHttpWebSockets(client: OkHttpClient? = null) : WebSockets {
    private val client = (client ?: OkHttpClient()).newBuilder()
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun connect(url: String, headers: Map<String, String>): WebSocketConnection {
        val builder = Request.Builder().url(url)
        // What Chrome itself adds to every WebSocket handshake [I: Chrome's known behaviour, not
        // checked against our dealer captures, which kept frames but not handshake headers].
        builder.header("pragma", "no-cache").header("cache-control", "no-cache")
            .header("accept-encoding", "gzip, deflate, br, zstd")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val opened = CompletableDeferred<Unit>()
        val frames = Channel<String>(Channel.UNLIMITED)
        val ws = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.trySend(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); frames.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { frames.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val e = IOException("dealer: ${response?.code?.let { "HTTP $it " } ?: ""}${t.message ?: t.javaClass.simpleName}", t)
                if (!opened.isCompleted) opened.completeExceptionally(e)
                frames.close(e)
            }
        })
        try {
            opened.await()
        } catch (t: Throwable) {
            ws.cancel()
            throw t
        }
        return object : WebSocketConnection {
            override suspend fun receive(): String? = frames.receiveCatching().getOrNull()
            override suspend fun send(text: String) { if (!ws.send(text)) throw IOException("dealer closed") }
            override fun close() { ws.close(1000, null); frames.close() }
        }
    }
}
