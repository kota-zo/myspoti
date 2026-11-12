package app.myspoti.spotify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Virtual time for the player's tests: the player's [scope], the bridge's clock ([now]) and the
 * test's waits share one scheduler, so a 9 s wait for a stall takes no real time. The player's
 * timers (stall ticks, ad settles, reload graces) were waited for in real time before.
 */
internal class VirtualTime {
    private val dispatcher = StandardTestDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Epoch ms: a plausible date, so the page's timestamps look like real ones. */
    fun now(): Long = START + dispatcher.scheduler.currentTime

    /**
     * Runs [body] on virtual time. The player's scope is cancelled at its end: runTest runs every
     * task left before it returns, and the player's watch never runs out of them.
     */
    fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { body() } finally { scope.cancel() }
    }

    /** Waits (virtual time) until [cond], failing after [ms]. */
    suspend fun waitFor(what: String, ms: Long, cond: () -> Boolean) {
        val end = now() + ms
        while (!cond()) {
            check(now() < end) { "timed out: $what" }
            delay(20)
        }
    }

    private companion object {
        const val START = 1_790_000_000_000L
    }
}
