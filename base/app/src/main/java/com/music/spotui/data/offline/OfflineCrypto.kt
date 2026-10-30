package com.music.spotui.data.offline

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloaded songs are kept encrypted (AES-CTR), so they only play in Myspoti on this
 * phone: copied out of the app, a file is noise.
 *
 * The file key is random, and stored wrapped by a key of the Android Keystore that
 * never leaves the phone (nor its secure hardware). File layout: "MSE1", a 16-byte IV,
 * then the encrypted audio. CTR mode lets the player seek anywhere.
 */
object OfflineCrypto {
    private val MAGIC = "MSE1".toByteArray()
    private const val IV_BYTES = 16
    const val HEADER = 4 + IV_BYTES
    private const val KEYSTORE_ALIAS = "myspoti_offline"
    private const val PREFS = "offline_crypto"
    private const val LOG = app.myspoti.host.android.WebPlayerHost.LOG

    @Volatile private var key: SecretKey? = null

    /** The file key; made on first use. Null only when the Keystore lost its key. */
    fun key(context: Context): SecretKey = key ?: synchronized(this) {
        key ?: loadOrCreate(context).also { key = it }
    }

    private fun loadOrCreate(context: Context): SecretKey {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString("key", null)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val master = (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)
        if (wrapped != null && master != null) {
            runCatching {
                val raw = android.util.Base64.decode(wrapped, android.util.Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                return SecretKeySpec(c.doFinal(raw, 12, raw.size - 12), "AES")
            }
        }
        // No key yet (or the Keystore's is gone: older files can no longer be read).
        val newMaster = master ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, newMaster) }
        val sealed = c.iv + c.doFinal(fileKey)
        prefs.edit().putString("key", android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP)).commit()
        return SecretKeySpec(fileKey, "AES")
    }

    fun isEncrypted(file: File): Boolean = runCatching {
        file.length() >= HEADER && file.inputStream().use { s -> ByteArray(4).also { s.read(it) }.contentEquals(MAGIC) }
    }.getOrDefault(false)

    /** Writes [plain] encrypted to [out]. */
    fun encrypt(context: Context, plain: File, out: File) {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val c = cipher(key(context), iv, 0)
        out.outputStream().buffered(256 * 1024).use { o ->
            o.write(MAGIC); o.write(iv)
            plain.inputStream().use { i ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(c.update(buf, 0, n))
                }
            }
            c.doFinal().takeIf { it.isNotEmpty() }?.let(o::write)
        }
    }

    /** Encrypts [file] in place (through a temporary file next to it). */
    fun encryptInPlace(context: Context, file: File) {
        if (isEncrypted(file)) return
        val tmp = File(file.parentFile, file.name + ".enc")
        try {
            encrypt(context, file, tmp)
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } finally {
            tmp.delete()
        }
    }

    /** AES-CTR positioned at byte [offset] of the stream (the caller skips offset % 16). */
    internal fun cipher(key: SecretKey, iv: ByteArray, offset: Long): Cipher {
        val counter = BigInteger(1, iv).add(BigInteger.valueOf(offset / 16))
        val bytes = counter.toByteArray().let { b ->
            when {
                b.size > 16 -> b.copyOfRange(b.size - 16, b.size) // wrapped around
                b.size < 16 -> ByteArray(16 - b.size) + b
                else -> b
            }
        }
        return Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(bytes)) }
            .also { c -> val skip = (offset % 16).toInt(); if (skip > 0) c.update(ByteArray(skip)) }
    }

    /** The player's data source: decrypts downloaded files, reads anything else as usual. */
    @OptIn(UnstableApi::class)
    class Factory(private val context: Context) : DataSource.Factory {
        private val fallback = DefaultDataSource.Factory(context)
        override fun createDataSource(): DataSource = Switching(context, fallback.createDataSource())
    }

    @OptIn(UnstableApi::class)
    private class Switching(private val context: Context, private val other: DataSource) : BaseDataSource(false) {
        private var raf: RandomAccessFile? = null
        private var cipher: Cipher? = null
        private var uri: Uri? = null
        private var left = 0L
        private var usingOther = false
        /** The address an extension gave is read through the safe client, never through [other] (see [MediaDataSource]). */
        private val net = com.music.spotui.extensions.MediaDataSource()
        /** Which of [other] and [net] is open when [usingOther]. */
        private var active: DataSource = other
        /** An extension's DASH answer, read as one file (see [DashLive]). */
        private var dash: DashLive? = null
        private var dashAt = 0L
        /** The song's first bytes read ahead ([StreamHead]), served before [other] goes on from their end. */
        private var head: ByteArray? = null
        private var headAt = 0

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            // A song an extension gives the audio of (audio-source hook): asked here, on the
            // player's loading thread, then read from where it says with its headers. An address
            // the server refuses (expired or gone: 401, 403, 404, 410) is asked for once more.
            if (dataSpec.uri.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME) {
                val status = { t: Throwable -> (t as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode }
                // How its address opened, for the song's play attempt ("Why this source?").
                val trackId = dataSpec.uri.lastPathSegment
                val t0 = android.os.SystemClock.elapsedRealtime()
                var tried: app.myspoti.extensions.AudioStream? = null
                val length = try {
                    com.music.spotui.extensions.AudioRoute.openStream(
                        ask = { again -> com.music.spotui.extensions.ExtensionAudio.resolve(dataSpec.uri, again) },
                        open = { stream ->
                            tried = stream
                            try {
                                if (stream.manifest != null) openDash(dataSpec, stream) else openUrl(dataSpec, stream)
                            } catch (e: IOException) {
                                // Closed before it is opened again (it keeps the source that failed).
                                runCatching { net.close() }
                                throw e
                            }
                        },
                        status = status,
                    )
                } catch (e: IOException) {
                    val code = generateSequence<Throwable>(e) { it.cause }.take(8).mapNotNull(status).firstOrNull()
                    com.music.spotui.extensions.Extensions.noteAttempt(trackId) {
                        it.copy(open = app.myspoti.extensions.PlayAttempt.Open(false, android.os.SystemClock.elapsedRealtime() - t0, code, e.message ?: e.javaClass.simpleName, tried?.url?.ifBlank { null }))
                    }
                    throw e
                }
                usingOther = dash == null
                val code = if (dash == null && dataSpec.position == 0L) net.responseCode.takeIf { it > 0 } else null
                com.music.spotui.extensions.Extensions.noteAttempt(trackId) {
                    if (it.open?.ok == true && dataSpec.position != 0L) it
                    else it.copy(open = app.myspoti.extensions.PlayAttempt.Open(true, android.os.SystemClock.elapsedRealtime() - t0, code, null, tried?.url?.ifBlank { null } ?: "dash manifest"))
                }
                return length
            }
            val file = dataSpec.uri.takeIf { it.scheme == "file" }?.path?.let(::File)
            if (file == null || !isEncrypted(file)) {
                usingOther = true
                active = other
                return other.open(dataSpec)
            }
            transferInitializing(dataSpec)
            val r = RandomAccessFile(file, "r")
            val iv = ByteArray(IV_BYTES)
            r.seek(4); r.readFully(iv)
            val total = file.length() - HEADER
            if (dataSpec.position > total) { r.close(); throw IOException("position past the end") }
            r.seek(HEADER + dataSpec.position)
            cipher = cipher(key(context), iv, dataSpec.position)
            raf = r
            left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else total - dataSpec.position
            transferStarted(dataSpec)
            return left
        }

        /** The address an extension gave; from its start read ahead when there is one (else as usual). */
        private fun openUrl(dataSpec: DataSpec, stream: app.myspoti.extensions.AudioStream): Long {
            active = net
            val spec = dataSpec.buildUpon().setUri(Uri.parse(stream.url))
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + stream.headers).build()
            val ahead = if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong())
                StreamHead.take(dataSpec.uri.lastPathSegment.orEmpty(), stream.url) else null
            if (ahead != null) {
                try {
                    val rest = net.open(spec.buildUpon().setPosition(ahead.bytes.size.toLong()).build())
                    head = ahead.bytes
                    headAt = 0
                    android.util.Log.d(LOG, "stream: opened from its read-ahead start (${ahead.bytes.size} bytes), ${if (rest == C.LENGTH_UNSET.toLong()) "length unknown" else "${ahead.bytes.size + rest} bytes"}")
                    return if (rest == C.LENGTH_UNSET.toLong()) rest else ahead.bytes.size + rest
                } catch (e: IOException) {
                    // The rest could not be had from there: the whole file, as without a head.
                    runCatching { net.close() }
                }
            }
            return net.open(spec).also { n ->
                android.util.Log.d(LOG, "stream: opened at ${dataSpec.position}, ${if (n == C.LENGTH_UNSET.toLong()) "length unknown" else "$n bytes"}")
            }
        }

        private fun openDash(dataSpec: DataSpec, stream: app.myspoti.extensions.AudioStream): Long {
            val live = DashLive.open(stream.manifest!!, stream.headers, OfflineDownloader.DownloadUa)
            if (dataSpec.position > live.length) throw IOException("position past the end")
            transferInitializing(dataSpec)
            dash = live
            dashAt = dataSpec.position
            left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else live.length - dataSpec.position
            transferStarted(dataSpec)
            android.util.Log.d(LOG, "dash: read from $dashAt of ${live.length} bytes")
            return left
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            head?.let { h ->
                if (headAt < h.size) {
                    if (length == 0) return 0
                    val n = minOf(length, h.size - headAt)
                    System.arraycopy(h, headAt, buffer, offset, n)
                    headAt += n
                    return n
                }
                head = null
            }
            if (usingOther) return active.read(buffer, offset, length)
            dash?.let { live ->
                if (length == 0) return 0
                if (left == 0L) return C.RESULT_END_OF_INPUT
                val n = live.read(dashAt, buffer, offset, minOf(length.toLong(), left).toInt())
                if (n < 0) return C.RESULT_END_OF_INPUT
                dashAt += n; left -= n
                bytesTransferred(n)
                return n
            }
            if (length == 0) return 0
            if (left == 0L) return C.RESULT_END_OF_INPUT
            val r = raf ?: return C.RESULT_END_OF_INPUT
            val n = r.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n < 0) return C.RESULT_END_OF_INPUT
            cipher!!.update(buffer, offset, n, buffer, offset)
            left -= n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = if (usingOther) active.uri else uri

        // Without this, a station's "icy-metaint" response header never reaches the extractor
        // (BaseDataSource's default is an empty map): it can't tell the audio apart from the
        // station-title metadata the server interleaves into it every few kilobytes, so that
        // metadata gets decoded as if it were audio — a little glitch, right on the interval,
        // on every station that sends it (one that doesn't is unaffected either way).
        override fun getResponseHeaders(): Map<String, List<String>> = if (usingOther) other.responseHeaders else super.getResponseHeaders()

        override fun close() {
            head = null
            if (usingOther) { usingOther = false; active.close(); return }
            if (dash != null) { dash = null; if (uri != null) { uri = null; transferEnded() }; return }
            raf?.close(); raf = null; cipher = null
            if (uri != null) { uri = null; transferEnded() }
        }
    }
}
