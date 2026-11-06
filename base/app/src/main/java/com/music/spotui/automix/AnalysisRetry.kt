/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

/** Worker-owned retry deadlines. A cancelled ask is never a failure of the audio. */
internal class AnalysisRetry {
    private data class Failure(val attempts: Int, val retryAt: Long)
    private val failures = HashMap<String, Failure>()

    fun allowed(key: String, nowMs: Long): Boolean = failures[key]?.let { nowMs >= it.retryAt } ?: true

    fun failed(key: String, nowMs: Long, cancelled: Boolean = false) {
        if (cancelled) return
        val attempts = ((failures[key]?.attempts ?: 0) + 1).coerceAtMost(5)
        failures[key] = Failure(attempts, nowMs + minOf(120_000L, 5_000L shl (attempts - 1)))
    }

    fun succeeded(key: String) { failures.remove(key) }
    fun clear() { failures.clear() }
}
