package app.myspoti.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the watch keeps between two runs of its app: the session the phone gave (sealed with a key
 * of the watch's own keystore, which never leaves it), how to reach the phone over the Wi-Fi, and
 * the last playlists and songs read (so a list plays from the watch's extensions without the phone).
 */
class WatchStore(context: Context) {
    private val dir = File(context.filesDir, "watch").apply { mkdirs() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    /** Kept sealed: a session or the Wi-Fi link's secret. */
    fun putSecret(name: String, text: String?) = runCatching {
        val f = File(dir, "$name.bin")
        if (text == null) { f.delete(); return@runCatching }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val box = c.doFinal(text.encodeToByteArray())
        f.writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + box)
    }

    fun secret(name: String): String? = runCatching {
        val b = File(dir, "$name.bin").takeIf { it.isFile }?.readBytes() ?: return null
        val n = b[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 1, n)) }
        c.doFinal(b, 1 + n, b.size - 1 - n).decodeToString()
    }.getOrNull()

    /** Kept as it is: lists of playlists and songs (nothing secret). */
    fun put(name: String, text: String) = runCatching { File(dir, safe(name)).writeText(text) }
    fun get(name: String): String? = runCatching { File(dir, safe(name)).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun safe(name: String) = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".json"

    private companion object { const val ALIAS = "orpheon-watch" }
}
