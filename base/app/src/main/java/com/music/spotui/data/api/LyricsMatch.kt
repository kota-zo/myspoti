package com.music.spotui.data.api

import java.text.Normalizer

/**
 * How a catalogue's title and artist are compared with ours: case, accents ("Beyoncé" and
 * "Beyonce"), punctuation and the order of an artist's words ("Hi Lee" and "Lee Hi") do not matter.
 */
object LyricsMatch {
    // The combining accents only: kana voicing marks and Hangul jamo are not "accents".
    private val accents = Regex("[̀-ͯ]")
    private val notWord = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /** [s] lowercased, without accents, with the few letters that do not decompose spelled out. */
    fun fold(s: String): String {
        val plain = Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFD).replace(accents, ""), Normalizer.Form.NFC)
        return plain.lowercase().replace("ß", "ss").replace("æ", "ae").replace("œ", "oe").replace("ø", "o").replace("đ", "dj").replace("ł", "l")
    }

    /** Letters and digits of [s] only, folded. */
    fun key(s: String): String = fold(s).filter { it.isLetterOrDigit() }

    private fun words(s: String): Set<String> = fold(s).split(notWord).filter { it.isNotEmpty() }.toSet()

    /** The same artist: the same letters, or the same set of words in any order. */
    fun sameArtist(a: String, b: String): Boolean {
        val ka = key(a)
        return ka.isNotEmpty() && (ka == key(b) || words(a) == words(b))
    }
}
