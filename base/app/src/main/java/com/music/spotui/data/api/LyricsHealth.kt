package com.music.spotui.data.api

import app.myspoti.extensions.Outcome
import app.myspoti.extensions.SourceCooldown
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which lyrics providers answer. One that fails [FAILURES_IN_A_ROW] times in a row (network, a
 * timeout, a 5xx) is set aside for [SET_ASIDE_MS] ([SourceCooldown], as for the extensions): it is
 * not asked, so a provider that is down costs no waiting. And "no lyrics for this song" from a
 * provider is kept for [MISSING_MS] per song and provider, so reopening a song does not ask again.
 * A failure is never kept as "no lyrics". [clock]: ms, for tests.
 */
class LyricsHealth(private val clock: () -> Long = System::currentTimeMillis) {
    private val cooldown = SourceCooldown(clock)
    private val streak = ConcurrentHashMap<String, Int>()
    private val missing = ConcurrentHashMap<String, Long>()
    private val lost = AtomicLong()

    /** Why [provider] is set aside ("server-down, 540 s left"), or null (it may be asked). */
    fun reason(provider: String): String? = cooldown.reason(provider)

    /** [provider] answered (found or not found): its failures in a row end. */
    fun answered(provider: String) {
        streak.remove(provider)
        cooldown.note(provider, Outcome.Found)
    }

    /** A request to [provider] failed. */
    fun failed(provider: String) {
        lost.incrementAndGet()
        val n = streak.merge(provider, 1, Int::plus) ?: 1
        if (n >= FAILURES_IN_A_ROW) {
            streak.remove(provider)
            cooldown.note(provider, Outcome.ServerDown(untilMs = clock() + SET_ASIDE_MS))
        }
    }

    /** A request was not made (set aside, data saver): not an answer either. */
    fun skipped() { lost.incrementAndGet() }

    /** How many requests were lost so far: unchanged around a call that found nothing, it was a real "no lyrics". */
    fun lostCount(): Long = lost.get()

    private fun key(track: String, provider: String) = "$provider|$track"

    fun isMissing(track: String, provider: String): Boolean {
        val k = key(track, provider)
        val at = missing[k] ?: return false
        if (clock() - at < MISSING_MS) return true
        missing.remove(k, at)
        return false
    }

    fun noteMissing(track: String, provider: String) {
        if (missing.size > MAX_MISSING) { val now = clock(); missing.entries.removeIf { now - it.value >= MISSING_MS } }
        missing[key(track, provider)] = clock()
    }

    /** The song is looked up again (the user asked): its "no lyrics" answers are dropped. */
    fun forgetTrack(track: String) { missing.keys.removeIf { it.endsWith("|$track") } }

    fun forgetAll() { missing.clear() }

    companion object {
        const val FAILURES_IN_A_ROW = 3
        const val SET_ASIDE_MS = 10 * 60_000L
        const val MISSING_MS = 5 * 60_000L
        private const val MAX_MISSING = 500
    }
}
