package com.music.spotui.extensions

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import app.myspoti.extensions.ExtensionMediaHttp
import java.io.IOException
import java.io.InputStream

/**
 * The player's HTTP data source for an address an extension gave: [MediaHttp] (never a local
 * address, redirects followed by hand and re-checked, the extension's headers dropped on another
 * host) with the same Range / seek behaviour as ExoPlayer's own: a Range header from the
 * position, the bytes skipped when a server answers 200 to a Range, a 416 at the end of the file
 * read as an empty rest. The headers are the data spec's (the extension's included).
 */
@UnstableApi
internal class MediaDataSource : BaseDataSource(true), HttpDataSource {
    private val props = HttpDataSource.RequestProperties()
    private var opened: ExtensionMediaHttp.Opened? = null
    private var input: InputStream? = null
    private var spec: DataSpec? = null
    private var started = false
    private var toRead = C.LENGTH_UNSET.toLong()
    private var skip = 0L
    private var code = -1

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        toRead = 0; skip = 0
        transferInitializing(dataSpec)
        val fixed = LinkedHashMap<String, String>()
        if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            fixed["Range"] = "bytes=${dataSpec.position}-" + if (dataSpec.length != C.LENGTH_UNSET.toLong()) (dataSpec.position + dataSpec.length - 1).toString() else ""
        }
        System.getProperty("http.agent")?.let { fixed["User-Agent"] = it }
        val o = try {
            MediaHttp.open(dataSpec.uri.toString(), headers = props.snapshot + dataSpec.httpRequestHeaders, fixed = fixed, timeoutMs = TIMEOUT_MS)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        opened = o
        val r = o.response
        code = r.code
        if (code !in 200..299) {
            val message = r.message
            val headers = r.headers.toMultimap()
            // At the very end of the file: nothing left to read, not an error.
            if (code == 416 && dataSpec.position == HttpUtil.getDocumentSize(r.header("Content-Range"))) {
                started = true
                transferStarted(dataSpec)
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
            }
            val body = runCatching { r.peekBody(4096).bytes() }.getOrDefault(ByteArray(0))
            closeResponse()
            throw HttpDataSource.InvalidResponseCodeException(code, message, null, headers, dataSpec, body)
        }
        skip = if (code == 200 && dataSpec.position != 0L) dataSpec.position else 0L
        toRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else {
            val length = HttpUtil.getContentLength(r.header("Content-Length"), r.header("Content-Range"))
            if (length != C.LENGTH_UNSET.toLong()) length - skip else C.LENGTH_UNSET.toLong()
        }
        input = r.body.byteStream()
        started = true
        transferStarted(dataSpec)
        try { skipBytes() } catch (e: IOException) {
            closeResponse()
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        return toRead
    }

    private fun skipBytes() {
        val s = input ?: return
        val junk = ByteArray(4096)
        while (skip > 0) {
            val n = s.read(junk, 0, minOf(skip, junk.size.toLong()).toInt())
            if (n < 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            skip -= n
            bytesTransferred(n)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (toRead == 0L) return C.RESULT_END_OF_INPUT
        val s = input ?: return C.RESULT_END_OF_INPUT
        val want = if (toRead == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), toRead).toInt()
        val n = try {
            s.read(buffer, offset, want)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, spec!!, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (toRead != C.LENGTH_UNSET.toLong()) toRead -= n
        bytesTransferred(n)
        return n
    }

    override fun close() {
        closeResponse()
        if (started) { started = false; transferEnded() }
    }

    private fun closeResponse() {
        runCatching { input?.close() }
        runCatching { opened?.close() }
        input = null; opened = null
    }

    override fun getUri(): Uri? = opened?.url?.toString()?.let(Uri::parse) ?: spec?.uri

    override fun getResponseCode(): Int = code
    override fun getResponseHeaders(): Map<String, List<String>> = opened?.response?.headers?.toMultimap().orEmpty()
    override fun setRequestProperty(name: String, value: String) = props.set(name, value)
    override fun clearRequestProperty(name: String) = props.remove(name)
    override fun clearAllRequestProperties() = props.clear()

    /** What opens the data source above. */
    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = MediaDataSource()
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
