package app.myspoti.spotify.report

import app.myspoti.spotify.AccountPlan

/**
 * The "402 summary" at the top of a diagnostic report: what one report from an account whose
 * Connect commands Spotify refuses (402 COMMAND_FAILED, some free accounts) must settle, in one
 * screen, so nobody has to dig it out of the log again (402-ANALYSIS, 402-logger-REPORT):
 *
 * 1. per route (okhttp, HTTP_ENGINE, CRONET, the page's own fetch): the answers, their latency,
 *    the headers Spotify adds on a refusal, the body's error_type, and whether a 2xx on another
 *    route really started the audio within 10 s;
 * 2. the account's devices as the first cluster shows them (no ids), and the ads this session met;
 * 3. what the page's own fetch got for the same command, and the page's connect-state PUT;
 * 4. timing: page ready / registration to the first command, whether any command was accepted,
 *    refusals soon after an ad break or a transfer;
 * 5. the audio licence's answers by code, its retry-after, and whether 403/429 met a 402.
 *
 * Events come from the host (relay, WebView) and the player; nothing here holds an id, a token or
 * an address. Thread-safe; [clock] is a monotonic clock in ms (replaced in tests).
 */
object Refusal402 {
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Two events this close count as "after" one another (a refusal after an ad break or a transfer). */
    const val NEAR_MS = 60_000L
    /** A licence 403/429 this close to a 402 counts as coinciding with it. */
    const val COINCIDE_MS = 30_000L

    private class Route(var answers: MutableMap<Int, Int> = sortedMapOf(), val ms: MutableList<Long> = ArrayList())

    private val lock = Any()
    private var pageReadyAt = -1L
    private var registeredAt = -1L
    private var registration = sortedMapOf<Int, Int>()
    private var firstCommand: String? = null
    private val firstPerPage = ArrayDeque<String>()
    private var pageFirstPending = false
    private var commands = 0
    private var accepted = 0
    private var refusedCommands = 0
    private val refusedKinds = sortedMapOf<String, Int>()
    private val routes = LinkedHashMap<String, Route>()
    private val errorTypes = sortedMapOf<String, Int>()
    private val refusalHeaders = sortedMapOf<String, MutableSet<String>>()
    private val wonRetries = sortedMapOf<String, IntArray>() // route -> [2xx after a refusal, audio started]
    private val pageFetch = sortedMapOf<Int, Int>()
    private var pageKept = 0
    private var pageDropped = 0
    private val connectPut = sortedMapOf<Int, Int>()
    private var connectPutHeaders: String? = null
    private var firstCluster: String? = null
    private var lastCluster: String? = null
    private var adBreaks = 0
    private var adStatesLeftOut = 0
    private var lastAdAt = -1L
    private var lastTransferAt = -1L
    private var afterAd = 0
    private var afterTransfer = 0
    private val refusalTimes = ArrayDeque<Long>()
    private val licence = sortedMapOf<Int, Int>()
    private val licenceErrorTimes = ArrayDeque<Long>()
    private var licenceHeld = 0
    private val retryAfter = LinkedHashSet<String>()
    private var songs = 0

    fun reset() = synchronized(lock) {
        pageReadyAt = -1; registeredAt = -1; registration.clear(); firstCommand = null; firstPerPage.clear()
        pageFirstPending = false; commands = 0; accepted = 0; refusedCommands = 0; refusedKinds.clear(); routes.clear()
        errorTypes.clear(); refusalHeaders.clear(); wonRetries.clear(); pageFetch.clear(); pageKept = 0; pageDropped = 0
        connectPut.clear(); connectPutHeaders = null; firstCluster = null; lastCluster = null; adBreaks = 0
        adStatesLeftOut = 0; lastAdAt = -1; lastTransferAt = -1; afterAd = 0; afterTransfer = 0; refusalTimes.clear()
        licence.clear(); licenceErrorTimes.clear(); licenceHeld = 0; retryAfter.clear(); songs = 0
    }

    /** The page's player said "ready" (a new page: its first command is timed again). */
    fun pageReady() = synchronized(lock) { pageReadyAt = clock(); pageFirstPending = true }

    /** The playback device registration was answered. */
    fun registered(code: Int) = synchronized(lock) {
        registration.merge(code, 1, Int::plus)
        if (code in 200..299) registeredAt = clock()
    }

    /**
     * One route's answer to a relayed command ([code] null: the route failed to answer). [kind] is
     * the command's endpoint ("play", "seek_to"...) or "transfer". [headers] are the answer's own;
     * only the names a refusal can explain itself with are kept ([explaining]).
     */
    fun attempt(kind: String, route: String, code: Int?, ms: Long, headers: List<Pair<String, String>>, body: String) = synchronized(lock) {
        val r = routes.getOrPut(route) { Route() }
        r.answers.merge(code ?: 0, 1, Int::plus)
        if (code != null) r.ms += ms
        if (code != null && code >= 400) {
            errorType(body)?.let { errorTypes.merge(it, 1, Int::plus) }
            headers.filter { explaining(it.first) }.forEach { (n, v) ->
                val values = refusalHeaders.getOrPut(n.lowercase()) { LinkedHashSet() }
                if (values.size < 4) values += v.take(40)
            }
        }
    }

    /** A relayed command's final answer (after every retry route); [retried] when a route was refused before. */
    fun command(kind: String, code: Int, retried: Boolean) = synchronized(lock) {
        val now = clock()
        commands++
        val ok = code in 200..299
        if (ok) accepted++
        val timing = buildString {
            append("$code")
            if (retried) append(" after a refusal")
            if (pageReadyAt >= 0) append(", ${secs(now - pageReadyAt)} after page ready")
            if (registeredAt >= 0) append(", ${secs(now - registeredAt)} after registration")
        }
        if (firstCommand == null) firstCommand = "$kind -> $timing"
        if (pageFirstPending) {
            pageFirstPending = false
            firstPerPage.addLast("$kind ${if (ok && !retried) "ok" else if (ok) "ok after refusal" else "$code"}")
            while (firstPerPage.size > 4) firstPerPage.removeFirst()
        }
        if (retried || code == 402 || code == 403) {
            refusedCommands++
            refusedKinds.merge(kind, 1, Int::plus)
            if (lastAdAt >= 0 && now - lastAdAt <= NEAR_MS) afterAd++
            if (lastTransferAt >= 0 && now - lastTransferAt <= NEAR_MS && kind != "transfer") afterTransfer++
            refusalTimes.addLast(now)
            while (refusalTimes.size > 200) refusalTimes.removeFirst()
        }
        if (kind == "transfer") lastTransferAt = now
    }

    /** A refused command that another route answered 2xx, 10 s later: did the audio start? */
    fun audioAfterRetry(route: String, started: Boolean) = synchronized(lock) {
        val c = wonRetries.getOrPut(route) { IntArray(2) }
        c[0]++
        if (started) c[1]++
    }

    /** The page's own fetch of a command the relay saw refused. */
    fun pageFetch(code: Int) = synchronized(lock) { pageFetch.merge(code, 1, Int::plus) }

    /** 10 s after a page fetch answered 2xx: something played (kept) or nothing did. */
    fun pageKept(kept: Boolean) = synchronized(lock) { if (kept) pageKept++ else pageDropped++ }

    /** The page's connect-state device PUT (it joins the cluster): status and header names. */
    fun connectStatePut(code: Int, headerNames: String) = synchronized(lock) {
        connectPut.merge(code, 1, Int::plus)
        connectPutHeaders = headerNames.take(300)
    }

    /** A cluster, already without ids (devices, their types, player_state). */
    fun cluster(brief: String) = synchronized(lock) {
        if (firstCluster == null) firstCluster = brief
        lastCluster = brief
    }

    fun adBreak() = synchronized(lock) { adBreaks++; lastAdAt = clock() }
    fun adStateLeftOut() = synchronized(lock) { adStatesLeftOut++; lastAdAt = clock() }

    /** A song the page started (its track changed). */
    fun song() = synchronized(lock) { songs++ }

    /** An audio licence answer the page saw (0: the status was not readable). */
    fun licence(code: Int) = synchronized(lock) {
        licence.merge(code, 1, Int::plus)
        if (code == 403 || code == 429) {
            licenceErrorTimes.addLast(clock())
            while (licenceErrorTimes.size > 200) licenceErrorTimes.removeFirst()
        }
    }

    /** An audio licence request answered 429 here, never sent (the app's hold). */
    fun licenceHeld() = synchronized(lock) { licenceHeld++ }

    fun licenceRetryAfter(value: String) = synchronized(lock) { if (retryAfter.size < 4) retryAfter += value.take(20) }

    /**
     * The summary block, lines of "label : value". [account] and [installAge] come from the host
     * (the account fact, the app's install age bucket).
     */
    fun summary(account: String?, installAge: String?): List<Pair<String, String>> = synchronized(lock) {
        val out = ArrayList<Pair<String, String>>()
        val verdict = when {
            commands == 0 -> "no relayed command this session"
            refusedCommands == 0 -> "no command refused ($accepted of $commands accepted)"
            accepted == 0 && pageKept == 0 -> "every command refused ($refusedCommands of $commands), no route played"
            else -> "$refusedCommands of $commands commands refused first; $accepted accepted in the end; page fetch played $pageKept×"
        }
        out += "verdict" to verdict
        out += "account" to listOfNotNull(account ?: "unknown", installAge?.let { "app installed $it" }).joinToString(", ")
        out += "ads" to if (AccountPlan.isPremium) "none (Premium)"
            else "blocked by the app; ad breaks this session=$adBreaks, ad states left out=$adStatesLeftOut"
        out += "devices" to (firstCluster?.let { first -> if (first == lastCluster) first else "$first | last: $lastCluster" } ?: "no cluster seen")
        out += "timing" to listOfNotNull(
            "first command ${firstCommand ?: "none"}",
            firstPerPage.takeIf { it.isNotEmpty() }?.joinToString(", ", "first per page: "),
            "registration ${counts(registration)}",
        ).joinToString("; ")
        if (refusedCommands > 0) {
            out += "refused" to "${counts(refusedKinds)}; within ${NEAR_MS / 1000} s after an ad: $afterAd, after a transfer: $afterTransfer"
            out += "body" to (counts(errorTypes).ifEmpty { "no error_type" })
            out += "refusal hdr" to (refusalHeaders.entries.joinToString(", ") { (n, v) -> "$n=${v.joinToString("|")}" }
                .ifEmpty { "no x-*, retry-after or www-authenticate" })
        }
        out += "routes" to (routes.entries.joinToString(" · ") { (name, r) ->
            "$name ${counts(r.answers)}" + (r.ms.takeIf { it.isNotEmpty() }?.let { " (median ${it.sorted()[it.size / 2]} ms)" } ?: "")
        }.ifEmpty { "none" })
        if (refusedCommands > 0) {
            out += "retry 2xx" to (wonRetries.entries.joinToString(", ") { (r, c) -> "$r 2xx×${c[0]}, audio in 10 s ${c[1]}/${c[0]}" }
                .ifEmpty { "no other route answered 2xx" })
            out += "page fetch" to (if (pageFetch.isEmpty()) "not tried" else "${counts(pageFetch)}; audio in 10 s $pageKept/${pageKept + pageDropped}")
        }
        out += "connect PUT" to (if (connectPut.isEmpty()) "none" else "${counts(connectPut)}; headers: ${connectPutHeaders ?: "-"}")
        val coincide = refusalTimes.count { t -> licenceErrorTimes.any { kotlin.math.abs(it - t) <= COINCIDE_MS } }
        out += "licence" to listOfNotNull(
            counts(licence).ifEmpty { "no answer seen" },
            "over $songs songs",
            licenceHeld.takeIf { it > 0 }?.let { "answered here (hold) $it" },
            retryAfter.takeIf { it.isNotEmpty() }?.joinToString("|", "retry-after=") ,
            if (refusedCommands > 0) "402 within ${COINCIDE_MS / 1000} s of a licence 403/429: $coincide of ${refusalTimes.size}" else null,
        ).joinToString("; ")
        out
    }

    /** Header names a refusal may explain itself with; never authorization or cookies. */
    internal fun explaining(name: String): Boolean {
        val n = name.lowercase()
        if ("auth" in n && n != "www-authenticate" || "cookie" in n || "token" in n) return false
        return n.startsWith("x-") || n == "retry-after" || n == "www-authenticate"
    }

    private val ERROR_TYPE = Regex(""""error_type"\s*:\s*"([A-Za-z_]{1,40})"""")
    internal fun errorType(body: String): String? = ERROR_TYPE.find(body)?.groupValues?.get(1)

    private fun counts(m: Map<*, Int>) = m.entries.joinToString(" ") { (k, v) -> "$k×$v" }
    private fun secs(ms: Long) = "%.1f s".format(java.util.Locale.US, ms / 1000.0)
}
