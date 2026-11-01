package com.music.spotui.extensions

import app.myspoti.extensions.LyricsResult
import com.music.spotui.data.api.LyricsExtras
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The order hooks are asked in, without Android: every extension providing the hook is asked
 * at once (each on its own thread, within its time limit), and the answers are taken in
 * priority order ([Registry.byPriority]: the user's order on the Installed tab, else
 * installation order), so the higher one wins a tie and a slow one never makes a quick one wait
 * longer than its own limit.
 */
object HookDispatch {
    /**
     * The first non-null answer in [candidates]' order. Answers behind it are not waited for
     * (they are cancelled); a failure is no answer.
     */
    suspend fun <C, R : Any> firstInOrder(candidates: List<C>, ask: suspend (C) -> R?): Pair<C, R>? = coroutineScope {
        val asked = candidates.map { c -> c to async { answer { ask(c) } } }
        for ((c, job) in asked) {
            val r = job.await() ?: continue
            asked.forEach { it.second.cancel() }
            return@coroutineScope c to r
        }
        null
    }

    /** The best answer by [rank] (higher is better), ties to the earlier candidate. Waits for all. */
    suspend fun <C, R : Any> bestInOrder(candidates: List<C>, rank: (R) -> Int, ask: suspend (C) -> R?): Pair<C, R>? = coroutineScope {
        val asked = candidates.map { c -> c to async { answer { ask(c) } } }
        var best: Pair<C, R>? = null
        for ((c, job) in asked) {
            val r = job.await() ?: continue
            if (best == null || rank(r) > rank(best.second)) best = c to r
        }
        best
    }

    private suspend fun <R> answer(block: suspend () -> R?): R? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * Lyrics from extensions next to the app's own: the extensions' answer is used when it is at
 * least as good (word by word > line by line > plain text), except against Spotify's own complete
 * lyrics, which stay first at the same level (they are what the official app shows, with colours).
 */
object LyricsMerge {
    fun rank(l: Lyrics): Int = when {
        l.synced && l.wordSynced -> 3
        l.synced -> 2
        else -> 1
    }

    /** Whether [own] is Spotify's own, complete lyrics. */
    fun isSpotifyComplete(own: Lyrics?): Boolean = own != null && own.colors != null && own.uncapped

    /** The better of the two; translations and romanizations the other one carries are kept when the chosen has none. */
    fun choose(own: Lyrics?, fromExtension: Lyrics?): Lyrics? {
        if (fromExtension == null || fromExtension.isEmpty) return own
        if (own == null || own.isEmpty) return fromExtension
        val mine = rank(own)
        val theirs = rank(fromExtension)
        return when {
            theirs > mine -> fromExtension.copy(colors = own.colors).let { LyricsExtras.carry(it, own) }
            theirs < mine -> LyricsExtras.carry(own, fromExtension)
            isSpotifyComplete(own) -> LyricsExtras.carry(own, fromExtension)
            else -> fromExtension.copy(colors = own.colors).let { LyricsExtras.carry(it, own) }
        }
    }

    /** An extension's checked answer as the app's lyrics. */
    fun toLyrics(r: LyricsResult): Lyrics = Lyrics(
        lines = r.lines.map { l ->
            LyricLine(
                timeMs = if (r.synced) l.startMs else 0,
                text = l.text,
                words = if (r.synced) l.words?.map { w -> LyricWord(w.startMs, w.endMs ?: 0, w.text) } else null,
                translation = l.translation, romanization = l.romanization, voice = l.voice, background = l.background,
            )
        },
        synced = r.synced,
        provider = r.provider,
        translationLanguage = r.translationLanguage,
    )
}
