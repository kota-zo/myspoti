package com.music.spotui.extensions

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import app.myspoti.extensions.Attestation
import app.myspoti.extensions.Enrollment
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * `myspoti.attest` ([app.myspoti.extensions.Action.ATTEST]): one EC P-256 signing key per
 * extension in the AndroidKeyStore, under the alias [Attestation.alias] of its id, so an
 * extension can only ever reach its own. The key is made with the challenge the extension's server
 * gave, StrongBox first and the TEE when the phone has none, and the keystore's attestation chain
 * (the app's package name and signing certificate digest inside) goes back to the extension, which
 * sends it to its server. Nothing leaves the phone from here: the app makes no request of its own.
 */
object ExtensionAttestation : Attestation {
    private const val PROVIDER = "AndroidKeyStore"

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun enroll(extensionId: String, challenge: ByteArray): Enrollment {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw UnsupportedOperationException("hardware key attestation requires Android 7.0 or newer")
        }
        return enrollApi24(extensionId, challenge)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun enrollApi24(extensionId: String, challenge: ByteArray): Enrollment {
        val alias = Attestation.alias(extensionId)
        val store = keyStore()
        store.deleteEntry(alias)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            generateWithStrongBoxFallbackApi28(alias, challenge)
        } else {
            generateTeeApi24(alias, challenge)
        }
        val chain = store.getCertificateChain(alias)?.takeIf { it.isNotEmpty() } ?: run {
            store.deleteEntry(alias)
            throw IllegalStateException("this phone gave no attestation chain")
        }
        val b64 = Base64.getEncoder()
        return Enrollment(b64.encodeToString(chain[0].publicKey.encoded), chain.map { b64.encodeToString(it.encoded) })
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun generateTeeApi24(alias: String, challenge: ByteArray) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateWithStrongBoxFallbackApi28(alias: String, challenge: ByteArray) {
        fun generate(strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(strongBox)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
        }
        try { generate(strongBox = true) } catch (_: StrongBoxUnavailableException) { generate(strongBox = false) }
    }

    override fun publicKey(extensionId: String): String? =
        keyStore().getCertificate(Attestation.alias(extensionId))?.publicKey?.let { Base64.getEncoder().encodeToString(it.encoded) }

    override fun sign(extensionId: String, data: ByteArray): String {
        val key = keyStore().getKey(Attestation.alias(extensionId), null) as? PrivateKey
            ?: throw IllegalStateException("not enrolled: call myspoti.attest.enroll(challenge) first")
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()
        return Base64.getEncoder().encodeToString(signature)
    }

    override fun reset(extensionId: String) {
        runCatching { keyStore().deleteEntry(Attestation.alias(extensionId)) }
    }
}
