// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine.android

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The engine's [Http] on Chromium's network stack, so spclient, the licence server and the CDN see
 * Chrome's TLS and HTTP/2 fingerprint, not OkHttp's: the platform's `android.net.http.HttpEngine`
 * on Android 14+ (Cronet in the system), Cronet from Google Play services before, as the relay's
 * PlatformStack and CronetStack do (host-android). No fallback to another stack: a different
 * fingerprint under a Chrome user agent is worse than a clear failure.
 *
 * Chromium writes its own Accept-Encoding and decodes what it asked for (gzip, br, and zstd where
 * the stack supports it), so the core's headers go out as given and bodies come back decoded. It
 * adds no X-Requested-With (that one is the WebView's) and no cookie.
 */
class ChromiumHttp(context: Context, private val timeoutMs: Long = 30_000) : Http {
    private val app = context.applicationContext
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "engine-http").apply { isDaemon = true } }
    private val backend = CompletableDeferred<Backend>()

    /** "HttpEngine", "Cronet <provider> <version>", "starting", or "unavailable: <why>". */
    @Volatile var status: String = "starting"
        private set

    init {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { PlatformBackend(app) }
                .onSuccess { status = "HttpEngine"; backend.complete(it) }
                .onFailure { fail(it) }
        } else {
            runCatching {
                com.google.android.gms.net.CronetProviderInstaller.installProvider(app).addOnCompleteListener { task ->
                    if (!task.isSuccessful) return@addOnCompleteListener fail(task.exception ?: IOException("no Google Play services"))
                    runCatching {
                        val provider = org.chromium.net.CronetProvider.getAllProviders(app)
                            .firstOrNull { it.isEnabled && it.name != org.chromium.net.CronetProvider.PROVIDER_NAME_FALLBACK }
                            ?: error("no native Cronet provider")
                        val engine = provider.createBuilder().enableHttp2(true).enableQuic(true).enableBrotli(true).build()
                        status = "Cronet ${provider.name} ${provider.version}"
                        backend.complete(CronetBackend(engine))
                    }.onFailure { fail(it) }
                }
            }.onFailure { fail(it) }
        }
    }

    private fun fail(t: Throwable) {
        status = "unavailable: ${t.message ?: t.javaClass.simpleName}"
        backend.completeExceptionally(IOException("Chromium HTTP stack $status", t))
    }

    override suspend fun request(request: HttpRequest): HttpResponse {
        val b = backend.await()
        return withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val collector = Collector(
                    onDone = { r -> if (cont.isActive) cont.resume(r) },
                    onError = { e -> if (cont.isActive) cont.resumeWithException(e) },
                )
                val cancel = runCatching { b.start(request, uploadHeaders(request), collector, executor) }
                    .getOrElse { cont.resumeWithException(IOException("not sent: ${it.message}", it)); return@suspendCancellableCoroutine }
                cont.invokeOnCancellation { cancel() }
            }
        }
    }

    /**
     * Chromium refuses an upload without a content type. The browser sent none on the licence POST
     * (a fetch of raw bytes, NATIVE-PLAYBACK.md section 2); octet-stream is the neutral one. Known
     * deviation, the only header this class adds.
     */
    private fun uploadHeaders(r: HttpRequest): Map<String, String> {
        val h = r.headers.filterKeys { !it.equals("accept-encoding", true) }
        return if (r.body != null && h.keys.none { it.equals("content-type", true) }) h + ("content-type" to "application/octet-stream") else h
    }

    /** One response read to the end, whichever stack produced it. */
    private class Collector(val onDone: (HttpResponse) -> Unit, val onError: (IOException) -> Unit) {
        private val out = ByteArrayOutputStream()
        fun chunk(buffer: ByteBuffer) {
            buffer.flip()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            out.write(bytes)
            buffer.clear()
        }
        fun done(status: Int, headers: List<Map.Entry<String, String>>) {
            val h = LinkedHashMap<String, String>()
            headers.forEach { (k, v) -> val key = k.lowercase(); h[key] = h[key]?.let { "$it, $v" } ?: v }
            onDone(HttpResponse(status, h, out.toByteArray()))
        }
    }

    private interface Backend {
        /** Starts [r]; returns how to cancel it. */
        fun start(r: HttpRequest, headers: Map<String, String>, c: Collector, executor: java.util.concurrent.Executor): () -> Unit
    }

    @RequiresApi(34)
    private class PlatformBackend(context: Context) : Backend {
        private val engine = android.net.http.HttpEngine.Builder(context)
            .setEnableBrotli(true).setEnableHttp2(true).setEnableQuic(true).build()

        override fun start(r: HttpRequest, headers: Map<String, String>, c: Collector, executor: java.util.concurrent.Executor): () -> Unit {
            val callback = object : android.net.http.UrlRequest.Callback {
                override fun onRedirectReceived(q: android.net.http.UrlRequest, i: android.net.http.UrlResponseInfo, url: String) = q.followRedirect()
                override fun onResponseStarted(q: android.net.http.UrlRequest, i: android.net.http.UrlResponseInfo) = q.read(ByteBuffer.allocateDirect(64 * 1024))
                override fun onReadCompleted(q: android.net.http.UrlRequest, i: android.net.http.UrlResponseInfo, b: ByteBuffer) { c.chunk(b); q.read(b) }
                override fun onSucceeded(q: android.net.http.UrlRequest, i: android.net.http.UrlResponseInfo) = c.done(i.httpStatusCode, i.headers.asList)
                override fun onFailed(q: android.net.http.UrlRequest, i: android.net.http.UrlResponseInfo?, e: android.net.http.HttpException) =
                    c.onError(IOException(e.message ?: "failed", e))
                override fun onCanceled(q: android.net.http.UrlRequest, i: android.net.http.UrlResponseInfo?) = c.onError(IOException("canceled"))
            }
            val builder = engine.newUrlRequestBuilder(r.url, executor, callback).setHttpMethod(r.method)
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            r.body?.let { builder.setUploadDataProvider(BytesUpload(it), executor) }
            val request = builder.build()
            request.start()
            return { request.cancel() }
        }
    }

    @RequiresApi(34)
    private class BytesUpload(private val bytes: ByteArray) : android.net.http.UploadDataProvider() {
        private var at = 0
        override fun getLength(): Long = bytes.size.toLong()
        override fun read(sink: android.net.http.UploadDataSink, buffer: ByteBuffer) {
            val n = minOf(buffer.remaining(), bytes.size - at)
            buffer.put(bytes, at, n)
            at += n
            sink.onReadSucceeded(false)
        }
        override fun rewind(sink: android.net.http.UploadDataSink) { at = 0; sink.onRewindSucceeded() }
    }

    private class CronetBackend(private val engine: org.chromium.net.CronetEngine) : Backend {
        override fun start(r: HttpRequest, headers: Map<String, String>, c: Collector, executor: java.util.concurrent.Executor): () -> Unit {
            val callback = object : org.chromium.net.UrlRequest.Callback() {
                override fun onRedirectReceived(q: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo, url: String) = q.followRedirect()
                override fun onResponseStarted(q: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo) = q.read(ByteBuffer.allocateDirect(64 * 1024))
                override fun onReadCompleted(q: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo, b: ByteBuffer) { c.chunk(b); q.read(b) }
                override fun onSucceeded(q: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo) = c.done(i.httpStatusCode, i.allHeadersAsList)
                override fun onFailed(q: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo?, e: org.chromium.net.CronetException) =
                    c.onError(IOException(e.message ?: "failed", e))
                override fun onCanceled(q: org.chromium.net.UrlRequest, i: org.chromium.net.UrlResponseInfo?) = c.onError(IOException("canceled"))
            }
            val builder = engine.newUrlRequestBuilder(r.url, callback, executor).setHttpMethod(r.method)
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            r.body?.let { builder.setUploadDataProvider(org.chromium.net.UploadDataProviders.create(it), executor) }
            val request = builder.build()
            request.start()
            return { request.cancel() }
        }
    }
}
