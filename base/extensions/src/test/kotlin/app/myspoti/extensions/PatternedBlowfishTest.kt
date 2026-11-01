package app.myspoti.extensions

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

class PatternedBlowfishTest {
    private val key = "0000000000000000"
    private val iv = "0000000000000000"

    @Test fun publishedBlowfishVector() {
        val out = ByteArrayOutputStream()
        PatternedBlowfish.transform(ByteArrayInputStream(hex("4ef997456198dd78")), out,
            FileTransform(key, iv, 8, 1, 0))
        assertArrayEquals(ByteArray(8), out.toByteArray())
    }

    @Test fun partialReadsPatternAndTail() {
        val clear = ByteArray(2048 * 8 + 117) { (it * 31).toByte() }
        val encrypted = clear.copyOf()
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        for (offset in 0 until clear.size step 2048) {
            if (offset / 2048 % 3 == 0 && clear.size - offset >= 2048) {
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex(key), "Blowfish"), IvParameterSpec(hex(iv)))
                cipher.doFinal(clear, offset, 2048).copyInto(encrypted, offset)
            }
        }
        val input = object : ByteArrayInputStream(encrypted) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 37))
        }
        val out = ByteArrayOutputStream()
        PatternedBlowfish.transform(input, out, FileTransform(key, iv, 2048, 3, 0))
        assertArrayEquals(clear, out.toByteArray())
    }

    @Test fun independentPythonEncryptedFlac() {
        val encrypted = javaClass.getResourceAsStream("/blowfish/sine.flac.enc")!!.readBytes()
        val expected = javaClass.getResourceAsStream("/blowfish/sine.sha256")!!.bufferedReader().readText().trim()
        val out = ByteArrayOutputStream()
        PatternedBlowfish.transform(ByteArrayInputStream(encrypted), out,
            FileTransform("000102030405060708090a0b0c0d0e0f", "0001020304050607"))
        val clear = out.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(clear).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, digest)
        assertEquals("fLaC", String(clear.copyOfRange(0, 4), Charsets.US_ASCII))
    }

    @Test fun seekedStreamMatchesWholeFileAtEveryBoundary() {
        val encrypted = javaClass.getResourceAsStream("/blowfish/sine.flac.enc")!!.readBytes()
        val spec = FileTransform("000102030405060708090a0b0c0d0e0f", "0001020304050607")
        val whole = ByteArrayOutputStream().also { PatternedBlowfish.transform(ByteArrayInputStream(encrypted), it, spec) }.toByteArray()
        for (at in listOf(0,1,2047,2048,4096,6143,6144,6145,whole.size-100)) {
            val aligned = at / 2048 * 2048
            val decoded = PatternedBlowfish.open(ByteArrayInputStream(encrypted.copyOfRange(aligned, encrypted.size)), spec, aligned.toLong())
            var skip = at-aligned
            while (skip-- > 0) assertTrue(decoded.read() >= 0)
            assertArrayEquals(whole.copyOfRange(at,whole.size), decoded.readBytes())
        }
    }

    @Test fun rejectsInvalidParameters() {
        for (t in listOf(FileTransform("bad",iv),FileTransform(key,"00"),FileTransform(key,iv,7),FileTransform(key,iv,2048,0))) {
            try { PatternedBlowfish.transform(ByteArrayInputStream(byteArrayOf(1)),ByteArrayOutputStream(),t); fail("invalid transform accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
