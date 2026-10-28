package app.myspoti.extensions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * What an extension's `status` hook says: whether its source answers now, and why not ([detail],
 * shown as it is). An inactive extension is skipped by the source hooks until it is live again;
 * it stays on, and this is not a failure. Only an explicit `live: false` makes it inactive: a
 * probe without an answer ([answered] false: late, threw, not one) leaves it in use.
 */
data class ExtensionStatus(val live: Boolean, val detail: String? = null, val answered: Boolean = true) {
    /** "live", "inactive" or "unknown", for logs and the adb CLI. */
    val state: String get() = if (!answered) "unknown" else if (live) "live" else "inactive"

    companion object {
        const val MAX_DETAIL = 120
        val LIVE = ExtensionStatus(true)

        /** No answer from the probe ([detail]: why): not proof the source is down, it stays in use. */
        fun unknown(detail: String?) = ExtensionStatus(true, detail?.take(MAX_DETAIL), answered = false)

        /** `{ live, detail? }`; no answer (the extension has no `status`) is live. */
        fun parse(json: String?): ExtensionStatus {
            if (json == null) return LIVE
            val o = runCatching { ExtJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: throw ExtensionException("status: not an object { live, detail }")
            val live = (o["live"] as? JsonPrimitive)?.booleanOrNull ?: throw ExtensionException("status: \"live\" (true or false) is missing")
            val detail = (o["detail"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(MAX_DETAIL)?.ifBlank { null }
            return ExtensionStatus(live, detail)
        }

        /**
         * Asks [rt]'s `status` within [timeoutMs]: an answer that fails, is late or is not one is
         * [unknown] (a busy phone or a slow start says nothing about the source).
         */
        fun probe(rt: ExtensionRuntime, timeoutMs: Long): ExtensionStatus = try {
            parse(rt.call(Hook.STATUS, "{}", timeoutMs))
        } catch (e: ExtensionException) {
            unknown(e.message)
        }
    }
}

/** The last [ExtensionStatus] of each extension and when it was asked ([clock], ms). */
class StatusBook(private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(val status: ExtensionStatus, val at: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    /** Keeps [status] for [id]; true when its [ExtensionStatus.state] changes (the first one too). */
    fun record(id: String, status: ExtensionStatus): Boolean {
        val before = entries[id]?.status?.state
        entries[id] = Entry(status, clock())
        return before != status.state
    }

    /** Its last status, or null (never probed). */
    fun last(id: String): ExtensionStatus? = entries[id]?.status

    /** Its last probe said it is not live (never probed, or no answer: in use). */
    fun inactive(id: String): Boolean = entries[id]?.status?.live == false

    /** Never probed, or last probed [maxAgeMs] ago or more. */
    fun stale(id: String, maxAgeMs: Long): Boolean = entries[id]?.let { clock() - it.at >= maxAgeMs } ?: true

    fun forget(id: String) { entries.remove(id) }

    /** The inactive ones, with what they said. */
    fun inactiveNow(): Map<String, ExtensionStatus> = entries.filterValues { !it.status.live }.mapValues { it.value.status }
}

/**
 * The sources set aside for a while after their server said to wait ([Outcome.RateLimited]), said
 * it is down ([Outcome.ServerDown]) or failed on the network [NETWORK_IN_A_ROW] times in a row: they
 * are skipped, not asked, until then. What an extension asks is bounded by the app ([MIN_MS] to
 * [MAX_MS]), so it can neither hammer a server nor set itself aside for good.
 */
class SourceCooldown(private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(val until: Long, val reason: String)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val networkInARow = ConcurrentHashMap<String, Int>()

    /**
     * Notes [outcome] of [id]'s call; the delay it is now set aside for (ms), or null. An answer
     * (found, not found, sign-in, quality) ends a cooldown and the network streak.
     */
    fun note(id: String, outcome: Outcome): Long? {
        val now = clock()
        val delay = when (outcome) {
            is Outcome.RateLimited -> bounded(outcome.retryAfterMs ?: RATE_LIMITED_MS)
            is Outcome.ServerDown -> bounded(outcome.untilMs?.let { it - now } ?: SERVER_DOWN_MS)
            is Outcome.Network -> {
                val n = networkInARow.merge(id, 1, Int::plus) ?: 1
                if (n < NETWORK_IN_A_ROW) return null
                bounded(MIN_MS shl (n - NETWORK_IN_A_ROW).coerceAtMost(5))
            }
            is Outcome.Skipped, is Outcome.Bug -> return null
            else -> { networkInARow.remove(id); entries.remove(id); return null }
        }
        if (outcome !is Outcome.Network) networkInARow.remove(id)
        entries[id] = Entry(now + delay, outcome.key)
        return delay
    }

    /** Why [id] is set aside now ("rate-limited, 42 s left"), or null (it may be asked). */
    fun reason(id: String): String? {
        val e = entries[id] ?: return null
        val left = e.until - clock()
        if (left <= 0) { entries.remove(id, e); return null }
        return "${e.reason}, ${(left + 999) / 1000} s left"
    }

    /** When [id] may be asked again (wall-clock ms), or null. */
    fun until(id: String): Long? = entries[id]?.until?.takeIf { it > clock() }

    fun forget(id: String) { entries.remove(id); networkInARow.remove(id) }

    companion object {
        const val MIN_MS = 30_000L
        const val MAX_MS = 15 * 60_000L
        /** A rate limit without a delay. */
        const val RATE_LIMITED_MS = 60_000L
        /** A server down without an end. */
        const val SERVER_DOWN_MS = 5 * 60_000L
        /** Network failures in a row before a source is set aside (then 30 s, doubling up to [MAX_MS]). */
        const val NETWORK_IN_A_ROW = 3

        fun bounded(ms: Long): Long = ms.coerceIn(MIN_MS, MAX_MS)
    }
}
