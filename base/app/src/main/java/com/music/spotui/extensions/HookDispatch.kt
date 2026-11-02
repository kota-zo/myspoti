package com.music.spotui.extensions

import app.myspoti.extensions.LyricsResult
import com.music.spotui.data.api.LyricsExtras
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Every candidate asked at once, the answers handed to [use] in rounds until it takes one
     * (non-null). A round starts once an answer is in, then waits [graceMs] at most for better
     * ones: it starts sooner when the best answer so far fits [bestFit] and every candidate before
     * it has answered. A round holds the answers not tried yet, best [fit] first, ties in
     * [candidates]' order; the answers that come in during [use] make the next round. The
     * candidates still asking are cancelled once [use] takes one or none is left to ask.
     */
    suspend fun <C, R : Any, T : Any> inRounds(
        candidates: List<C>,
        ask: suspend (C) -> R?,
        fit: (R) -> Int,
        bestFit: Int,
        graceMs: (bestSoFar: Int) -> Long,
        use: suspend (List<Pair<C, R>>) -> T?,
    ): T? = coroutineScope {
        val asked = candidates.map { c -> async { answer { ask(c) } } }
        val done = BooleanArray(asked.size)
        val results = arrayOfNulls<Any>(asked.size)
        val tried = BooleanArray(asked.size)
        suspend fun awaitOne() = select<Unit> {
            asked.forEachIndexed { i, d -> if (!done[i]) d.onAwait { done[i] = true; results[i] = it } }
        }
        @Suppress("UNCHECKED_CAST")
        fun fresh() = results.indices.filter { !tried[it] && results[it] != null }
            .sortedWith(compareByDescending<Int> { fit(results[it] as R) }.thenBy { it })
        @Suppress("UNCHECKED_CAST")
        fun ready(): Boolean {
            val best = fresh().firstOrNull() ?: return false
            return fit(results[best] as R) >= bestFit && (0 until best).all { done[it] }
        }
        try {
            var taken: T? = null
            while (taken == null) {
                while (fresh().isEmpty() && done.any { !it }) awaitOne()
                val first = fresh().firstOrNull() ?: break
                @Suppress("UNCHECKED_CAST")
                withTimeoutOrNull(graceMs(fit(results[first] as R))) {
                    while (done.any { !it } && !ready()) awaitOne()
                }
                val round = fresh().onEach { tried[it] = true }
                @Suppress("UNCHECKED_CAST")
                taken = use(round.map { candidates[it] to results[it] as R })
            }
            taken
        } finally {
            asked.forEach { it.cancel() }
        }
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
 * least as good (word by word > line by line > plain text; at the same level, with backing vocals
 * or a duet the other lacks), except against Spotify's own complete lyrics, which stay first at
 * the same level otherwise (they are what the official app shows, with colours).
 */
object LyricsMerge {
    /** Word by word 3, line by line 2, plain text 1. */
    fun level(l: Lyrics): Int = when {
        l.synced && l.wordSynced -> 3
        l.synced -> 2
        else -> 1
    }

    /** [level] first; at the same level, backing vocals of their own, then two singers, win the tie. */
    fun rank(l: Lyrics): Int = level(l) * 4 + (if (LyricsExtras.hasBacks(l)) 2 else 0) + (if (l.hasDuet) 1 else 0)

    /** Whether [own] is Spotify's own, complete lyrics. */
    fun isSpotifyComplete(own: Lyrics?): Boolean = own != null && own.colors != null && own.uncapped

    /** The better of the two; the translations, backing vocals and singers the other one carries are kept when the chosen has none. */
    fun choose(own: Lyrics?, fromExtension: Lyrics?): Lyrics? {
        if (fromExtension == null || fromExtension.isEmpty) return own
        if (own == null || own.isEmpty) return fromExtension
        val keptOwn = LyricsExtras.carry(own, fromExtension)
        val tookTheirs = fromExtension.copy(colors = own.colors).let { LyricsExtras.carry(it, own) }
        val mine = level(own)
        val theirs = level(fromExtension)
        return when {
            theirs > mine -> tookTheirs
            theirs < mine -> keptOwn
            // The same level: the one with backing vocals or a duet, once each has what it could take from the other.
            rank(tookTheirs) != rank(keptOwn) -> if (rank(tookTheirs) > rank(keptOwn)) tookTheirs else keptOwn
            isSpotifyComplete(own) -> keptOwn
            else -> tookTheirs
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
