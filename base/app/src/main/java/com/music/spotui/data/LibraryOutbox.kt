package com.music.spotui.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Likes, unlikes, saves and follows made without a connection: shown at once, kept here (the last
 * tap on an item wins) and sent to Spotify when it answers again, in the order they were made.
 * Each change is kept under the account that made it: another account signed in on the phone
 * never receives it, and it waits for its own account to come back.
 */
object LibraryOutbox {
    private const val PREFS = "library_outbox"
    /** Before accounts were told apart: uri -> saved, of whoever was signed in. Moved to [BOOK]. */
    private const val LEGACY = "pending"
    private const val BOOK = "book"

    /** What waits, in order: item uri -> saved. Pure, for tests: see [Pending]. */
    class Pending(text: String?) {
        private val items = LinkedHashMap<String, Boolean>().apply {
            runCatching { JSONObject(text ?: "{}") }.getOrNull()?.let { o -> o.keys().forEach { put(it, o.optBoolean(it)) } }
        }
        val all: Map<String, Boolean> get() = items

        /** The newest tap on [uri]: it moves to the end, the earlier one is forgotten. */
        fun put(uri: String, saved: Boolean) { items.remove(uri); items[uri] = saved }
        fun sent(uri: String, saved: Boolean) { if (items[uri] == saved) items.remove(uri) }
        fun text(): String = JSONObject(items as Map<*, *>).toString()
    }

    /**
     * The changes of every account, each list in tap order. [account] is the Spotify user id of
     * the session (kept across restarts, forgotten at sign-out); changes made before it is known
     * go to the session's own list, which becomes the account's as soon as it is known, or is
     * put aside at sign-out (never sent: nobody knows whose they are). Pure, for tests.
     */
    class Book(text: String?) {
        private val lists = LinkedHashMap<String, Pending>()
        var account: String? = null
            private set

        init {
            runCatching { JSONObject(text ?: "{}") }.getOrNull()?.let { o ->
                account = o.optString("account").ifBlank { null }
                o.optJSONObject("lists")?.let { l -> l.keys().forEach { k -> lists[k] = Pending(l.optJSONObject(k)?.toString()) } }
            }
        }

        /** The list sent now: the account's, or the session's while its account is not known. */
        val key: String get() = account ?: SESSION
        fun due(key: String): Map<String, Boolean> = lists[key]?.all.orEmpty()
        /** Changes kept for another account (or put aside), not sent in this session. */
        val keptForOthers: Int get() = lists.filterKeys { it != key }.values.sumOf { it.all.size }

        fun put(uri: String, saved: Boolean) = list(key).put(uri, saved)
        fun sent(key: String, uri: String, saved: Boolean) {
            lists[key]?.let { it.sent(uri, saved); if (it.all.isEmpty()) lists.remove(key) }
        }

        /** The session's account is [id]: what the session did before is that account's (and newer). */
        fun signedIn(id: String) {
            if (id == account || id.isBlank() || id == SESSION || id == ASIDE) return
            account = id
            move(SESSION, id)
        }

        /** Signed out: what the session did without knowing its account is put aside, never sent. */
        fun signedOut() {
            move(SESSION, ASIDE)
            account = null
        }

        /**
         * The changes of an older version, which kept one list for whoever was signed in:
         * the session still signed in at the update made them (older than anything here);
         * signed out at the update, nobody knows whose they are and they are put aside.
         */
        fun adopt(legacy: String?, signedIn: Boolean) {
            val old = Pending(legacy).all
            if (old.isEmpty()) return
            val to = if (signedIn) key else ASIDE
            val merged = Pending(null)
            old.forEach { (u, s) -> merged.put(u, s) }
            lists[to]?.all?.forEach { (u, s) -> merged.put(u, s) }
            lists[to] = merged
        }

        fun text(): String = JSONObject().apply {
            account?.let { put("account", it) }
            put("lists", JSONObject().apply { lists.forEach { (k, p) -> if (p.all.isNotEmpty()) put(k, JSONObject(p.text())) } })
        }.toString()

        private fun list(key: String) = lists.getOrPut(key) { Pending(null) }
        private fun move(from: String, to: String) {
            val moved = lists.remove(from)?.all ?: return
            val into = list(to)
            moved.forEach { (u, s) -> into.put(u, s) }
        }

        companion object {
            const val SESSION = "~session"
            const val ASIDE = "~aside"
        }
    }

    /** Why a change was not taken, and what is done with it. */
    enum class Failure {
        /** No connection, a timeout, the page reloading, too many requests: kept, the rest waits too. */
        NETWORK,
        /** Spotify failed (5xx) or said something unknown: kept, the next ones are still sent. */
        SERVER,
        /** The session was refused (401/403): kept, the rest waits for the session. */
        SESSION,
        /** Spotify refused this change for good (400/404/410): dropped. */
        REFUSED,
    }

    private val statusCode = Regex("""(?i)\b(?:http|status(?:\s*code)?|code)\D{0,3}([1-5]\d\d)\b""")

    fun failureOf(e: Throwable): Failure {
        if (e is java.io.IOException) return Failure.NETWORK
        if (e is IllegalArgumentException) return Failure.REFUSED
        val message = e.message.orEmpty()
        val code = statusCode.find(message)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            code == 408 || code == 429 -> Failure.NETWORK
            code == 401 || code == 403 -> Failure.SESSION
            code == 400 || code == 404 || code == 410 -> Failure.REFUSED
            code != null -> Failure.SERVER
            message.startsWith("timeout") || message == "not ready" || message == "page reloaded" -> Failure.NETWORK
            else -> Failure.SERVER
        }
    }

    /** The wait before the next try after [failures] failed rounds: 5 s, doubled, at most 10 min. */
    fun backoff(failures: Int): Long = (5_000L shl failures.coerceIn(0, 7)).coerceAtMost(600_000L)

    /**
     * One worker sends what waits: rounds never overlap, and a round asked while one runs is
     * run once after it (a change is never sent twice by rounds started together). A round
     * that leaves changes it could not send asks for another after [backoff]; any other
     * request (a new change, the network back, the session ready) starts one at once.
     */
    class Sync(
        private val book: Book,
        private val save: (String) -> Unit,
        private val send: suspend (uri: String, saved: Boolean) -> Unit,
        /** Waits for Spotify to answer (the engine started, the Web Player ready). */
        private val ready: suspend () -> Unit,
        /** The signed-in account's Spotify user id, when known. */
        private val account: () -> String?,
        private val pause: suspend (Long) -> Unit,
        private val log: (String) -> Unit,
        private val scope: CoroutineScope,
    ) {
        private val requests = Channel<Unit>(Channel.CONFLATED)
        private var failures = 0
        private var retry: Job? = null

        init {
            scope.launch { for (r in requests) { retry?.cancel(); round() } }
        }

        fun request() { requests.trySend(Unit) }

        fun put(uri: String, saved: Boolean) {
            synchronized(book) { claim(); book.put(uri, saved); save(book.text()) }
            request()
        }

        fun signedOut() = synchronized(book) { book.signedOut(); save(book.text()) }

        val waiting: Int get() = synchronized(book) { claim(); book.due(book.key).size }

        private fun claim() {
            val id = account() ?: return
            if (id == book.account) return
            book.signedIn(id)
            save(book.text())
            book.keptForOthers.takeIf { it > 0 }?.let { log("library: $it change(s) of another account kept for it, not sent to this one") }
        }

        private suspend fun round() {
            if (waiting == 0) { failures = 0; return }
            ready()
            when (pass()) {
                Outcome.DONE -> failures = 0
                Outcome.AGAIN -> request()
                Outcome.LATER -> {
                    val wait = backoff(failures++)
                    retry = scope.launch { pause(wait); request() }
                }
            }
        }

        private enum class Outcome { DONE, AGAIN, LATER }

        /** Sends the session account's changes, oldest first. */
        private suspend fun pass(): Outcome {
            val (key, due) = synchronized(book) { claim(); book.key to book.due(book.key).toList() }
            var later = false
            for ((uri, saved) in due) {
                // Another account signed in meanwhile: its own list, in a new round.
                if (synchronized(book) { claim(); book.key } != key) return Outcome.AGAIN
                val error = try { send(uri, saved); null } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
                if (error == null) {
                    synchronized(book) { book.sent(key, uri, saved); save(book.text()) }
                    log("library: $uri ${if (saved) "saved" else "removed"} on Spotify (kept from earlier)")
                    continue
                }
                val why = "${error.javaClass.simpleName} ${error.message}"
                when (failureOf(error)) {
                    Failure.REFUSED -> {
                        synchronized(book) { book.sent(key, uri, saved); save(book.text()) }
                        log("library: $uri dropped, Spotify refused it ($why)")
                    }
                    Failure.SERVER -> { later = true; log("library: $uri still kept for later ($why)") }
                    Failure.NETWORK -> { log("library: $uri still kept for later ($why)"); return Outcome.LATER }
                    Failure.SESSION -> { log("library: $uri still kept for later, waiting for the session ($why)"); return Outcome.LATER }
                }
            }
            return if (later) Outcome.LATER else Outcome.DONE
        }
    }

    @Volatile private var sync: Sync? = null

    fun put(uri: String, saved: Boolean) { sync?.put(uri, saved) }
    /** Sends what waits (one worker: see [Sync]). */
    fun request() { sync?.request() }
    /** At sign-out: the account's changes stay its own, the next account starts with none. */
    fun signedOut() { sync?.signedOut() }

    /** [sender] writes one change to Spotify (throws when it cannot); called when the network is back. */
    @Synchronized fun init(
        c: Context,
        scope: CoroutineScope,
        sender: suspend (uri: String, saved: Boolean) -> Unit,
        ready: suspend () -> Unit,
        account: () -> String?,
        onAvailable: () -> Unit,
    ) {
        if (sync != null) return
        val prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val book = Book(prefs.getString(BOOK, null))
        prefs.getString(LEGACY, null)?.let { legacy ->
            val signedIn = c.applicationContext.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).getBoolean("LoggedIn", false)
            book.adopt(legacy, signedIn)
            prefs.edit().putString(BOOK, book.text()).remove(LEGACY).apply()
        }
        sync = Sync(
            book,
            save = { prefs.edit().putString(BOOK, it).apply() },
            send = sender,
            ready = ready,
            account = account,
            pause = { kotlinx.coroutines.delay(it) },
            log = { android.util.Log.i("LibraryOutbox", it); app.myspoti.host.android.Logbook.log(it) },
            scope = scope,
        ).also { it.request() }
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable()
            })
        }
    }
}
