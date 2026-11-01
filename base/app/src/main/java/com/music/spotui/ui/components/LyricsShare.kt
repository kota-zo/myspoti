package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import app.myspoti.engine.R
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.di.SongPlayer

/**
 * Sends the lines [range] of [lyrics] as text, like the official app's lyrics share: the lines,
 * "— Title · Artist", then the song's link. Breaks (an empty line, a note mark) are left out.
 * Used by the selection mode of both looks' lyrics.
 */
fun shareLyricLines(context: Context, lyrics: Lyrics, range: IntRange, title: String, artist: String) {
    val lines = range.mapNotNull { lyrics.lines.getOrNull(it)?.text?.trim() }.filter { it.isNotEmpty() && it != "♪" }
    if (lines.isEmpty()) return
    val id = SongPlayer.currentTrackId
    val text = buildString {
        append(lines.joinToString("\n"))
        append("\n\n— ").append(title).append(" · ").append(artist)
        if (id.isNotBlank()) append('\n').append("https://open.spotify.com/track/").append(id)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.lyrics_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
