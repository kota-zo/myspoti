package app.myspoti.spotify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Spotify Connect transfer asked from the app while an ad plays ([adPlaying]: the page's ad flag,
 * which is also another device's ad while that device plays): the page refuses its commands during
 * the break, so the transfer is sent once the ad is over, or after [maxWaitMs] at most. The transfer
 * reads what to send when it runs (the local player's song and its position then, the account's own
 * playback as Spotify has it then), never what played when it was asked. A newer ask replaces one
 * still waiting. Runs on [scope] (the main thread in the app).
 */
class TransferAfterAd(
    private val scope: CoroutineScope,
    private val adPlaying: StateFlow<Boolean>,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val log: (String) -> Unit = {},
) {
    private var waiting: Job? = null

    /** A transfer waits for the end of an ad. */
    val pending: Boolean get() = waiting?.isActive == true

    /**
     * Runs [transfer] (to [target], for the log) now when no ad plays and returns false; else returns
     * true and runs it once the ad is over.
     */
    fun run(target: String, transfer: () -> Unit): Boolean {
        cancel()
        if (!adPlaying.value) { transfer(); return false }
        log("transfer to $target asked during an ad: sent at its end")
        waiting = scope.launch {
            val over = withTimeoutOrNull(maxWaitMs) { adPlaying.first { !it } } != null
            log(if (over) "the ad is over: transfer to $target sent" else "the ad still on after ${maxWaitMs / 1000} s: transfer to $target sent anyway")
            waiting = null
            transfer()
        }
        return true
    }

    /** Forgets a transfer still waiting (the user picked something else). */
    fun cancel() { waiting?.cancel(); waiting = null }

    companion object {
        /** Longer than any break seen (two 30 s ads). */
        const val MAX_WAIT_MS = 90_000L
    }
}
