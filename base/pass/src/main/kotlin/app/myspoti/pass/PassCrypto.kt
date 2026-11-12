package app.myspoti.pass

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The transfer's encryption: ECDH on P-256 between the receiver's key (in its QR code) and a key
 * the sender makes for this one transfer, HKDF-SHA256 over the shared secret with the QR code's
 * one-time token, then AES-256-GCM with the token as associated data. P-256 because every Android
 * version and every JVM has it. Whoever did not see the QR code can neither read nor forge it.
 */
object PassCrypto {
    private val random = SecureRandom()
    private const val INFO = "orpheon-pass-v1"

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun publicKey(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    /** What the sender posts: its public key, the nonce and the ciphertext. */
    class Sealed(val senderKey: ByteArray, val nonce: ByteArray, val box: ByteArray)

    fun seal(receiverKey: ByteArray, token: ByteArray, plain: ByteArray): Sealed {
        val mine = keyPair()
        val key = derive(mine, publicKey(receiverKey), token)
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(token) }
        return Sealed(mine.public.encoded, nonce, c.doFinal(plain))
    }

    /** Throws when the box was not sealed for [mine] and [token] (a forged or altered transfer). */
    fun open(mine: KeyPair, token: ByteArray, sealed: Sealed): ByteArray {
        val key = derive(mine, publicKey(sealed.senderKey), token)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.nonce)); updateAAD(token) }
        return c.doFinal(sealed.box)
    }

    private fun derive(mine: KeyPair, theirs: PublicKey, token: ByteArray): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").apply { init(mine.private); doPhase(theirs, true) }.generateSecret()
        // HKDF (RFC 5869): extract with the token as salt, one block of expand.
        val prk = hmac(token, shared)
        return hmac(prk, INFO.encodeToByteArray() + byteArrayOf(1)).copyOf(32)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
