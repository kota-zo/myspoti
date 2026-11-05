package com.music.spotui.data.api

import app.myspoti.extensions.Romanize
import com.music.spotui.data.entity.Lyrics

/**
 * Romanizations from the extensions ([com.music.spotui.extensions.Extensions.romanize]) for the
 * lines of lyrics their source left in another script than Latin without one. What the source
 * gave stays: an extension only fills the lines left empty.
 */
object LyricsRomanize {
    /** The lines of [lyrics] a romanization is asked for: index → text. */
    fun wanted(lyrics: Lyrics): Map<Int, String> =
        Romanize.wanted(lyrics.lines, { it.text }, { !it.romanization.isNullOrBlank() })

    /**
     * [lyrics] with [found] (index → romanization) on its lines that have none and need one; a
     * romanization that only repeats its line is left out, as a source's is. The same instance when
     * nothing is added.
     */
    fun apply(lyrics: Lyrics, found: Map<Int, String>): Lyrics {
        if (found.isEmpty()) return lyrics
        var added = false
        val lines = lyrics.lines.mapIndexed { i, line ->
            val ro = found[i]?.takeIf {
                line.romanization.isNullOrBlank() && Romanize.needs(line.text) && LyricsMatch.key(it) != LyricsMatch.key(line.text)
            }
            if (ro == null) line else line.copy(romanization = ro).also { added = true }
        }
        return if (added) lyrics.copy(lines = lines) else lyrics
    }
}
