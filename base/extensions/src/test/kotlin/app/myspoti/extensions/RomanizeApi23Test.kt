package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class RomanizeApi23Test {
    @Test
    fun matchesJvmUnicodeScriptAcrossAllCodePoints() {
        val neutral = setOf(
            Character.UnicodeScript.LATIN,
            Character.UnicodeScript.COMMON,
            Character.UnicodeScript.INHERITED,
        )
        for (cp in 0..0x10FFFF) {
            if (Character.isSurrogate(cp.toChar()) && cp <= 0xFFFF) continue
            // U+16FE3 (Old Chinese Iteration Mark) was reclassified from COMMON to HAN in Unicode 14.
            if (cp == 0x16FE3) continue
            val s = String(Character.toChars(cp))
            val expected = Character.isLetter(cp) && Character.UnicodeScript.of(cp) !in neutral
            assertEquals("mismatch at U+%04X".format(cp), expected, Romanize.needs(s))
        }
    }
}
