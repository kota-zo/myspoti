package com.music.spotui.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOutboxTest {
    @Test fun theLastTapOnAnItemWinsAndKeepsTheOrderOfTaps() {
        val p = LibraryOutbox.Pending(null)
        p.put("spotify:track:a", true)
        p.put("spotify:track:b", true)
        p.put("spotify:track:a", false) // unliked again: one change, now the newest
        assertEquals(listOf("spotify:track:b" to true, "spotify:track:a" to false), p.all.toList())

        // Kept across restarts as text.
        val again = LibraryOutbox.Pending(p.text())
        assertEquals(p.all.toList(), again.all.toList())

        // A sent change goes; one the user changed meanwhile stays to be sent.
        again.sent("spotify:track:b", true)
        again.sent("spotify:track:a", true)
        assertEquals(listOf("spotify:track:a" to false), again.all.toList())
        assertEquals(emptyMap<String, Boolean>(), LibraryOutbox.Pending("not json").all)
    }

    @Test fun eachAccountKeepsItsOwnChangesAcrossRestartsAndSignOuts() {
        val book = LibraryOutbox.Book(null)
        book.put("spotify:track:before", true) // account not known yet: the session's
        book.signedIn("a")
        book.put("spotify:track:x", true)
        assertEquals(listOf("spotify:track:before" to true, "spotify:track:x" to true), book.due(book.key).toList())
        book.signedOut()
        book.put("spotify:track:unknown", true) // signed out before anyone was known again: put aside
        book.signedOut()
        book.signedIn("b")
        assertEquals(emptyMap<String, Boolean>(), book.due(book.key))

        val again = LibraryOutbox.Book(book.text())
        assertEquals("b", again.account)
        again.signedIn("a")
        assertEquals(listOf("spotify:track:before" to true, "spotify:track:x" to true), again.due(again.key).toList())
        assertEquals(1, again.keptForOthers) // the one put aside, never sent
    }

    @Test fun changesOfAnOlderVersionGoToTheSessionSignedInAtTheUpdateOnly() {
        val legacy = LibraryOutbox.Pending(null).apply { put("spotify:track:old", true) }.text()
        val signedIn = LibraryOutbox.Book(null).apply { adopt(legacy, signedIn = true); put("spotify:track:new", false) }
        signedIn.signedIn("a")
        assertEquals(listOf("spotify:track:old" to true, "spotify:track:new" to false), signedIn.due("a").toList())

        val signedOut = LibraryOutbox.Book(null).apply { adopt(legacy, signedIn = false) }
        signedOut.signedIn("b")
        assertEquals(emptyMap<String, Boolean>(), signedOut.due(signedOut.key))
        assertEquals(1, signedOut.keptForOthers)
    }

    @Test fun failuresAreToldApart() {
        assertEquals(LibraryOutbox.Failure.NETWORK, LibraryOutbox.failureOf(java.net.UnknownHostException("x")))
        assertEquals(LibraryOutbox.Failure.NETWORK, LibraryOutbox.failureOf(Exception("timeout after 20000 ms: library.add")))
        assertEquals(LibraryOutbox.Failure.NETWORK, LibraryOutbox.failureOf(Exception("Request failed with status code 429")))
        assertEquals(LibraryOutbox.Failure.SESSION, LibraryOutbox.failureOf(Exception("HTTP 401: token expired")))
        assertEquals(LibraryOutbox.Failure.REFUSED, LibraryOutbox.failureOf(Exception("HTTP 404: no such playlist")))
        assertEquals(LibraryOutbox.Failure.REFUSED, LibraryOutbox.failureOf(IllegalArgumentException("not a Spotify address")))
        assertEquals(LibraryOutbox.Failure.SERVER, LibraryOutbox.failureOf(Exception("HTTP 503: busy")))
        assertEquals(LibraryOutbox.Failure.SERVER, LibraryOutbox.failureOf(Exception("something odd")))
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 600_000L), listOf(0, 1, 2, 30).map(LibraryOutbox::backoff))
    }

    /** A worker on a fake Spotify: [fail] decides each send's error (null: taken). */
    private class Rig(scope: CoroutineScope) {
        var account: String? = "a"
        val sent = mutableListOf<Triple<String?, String, Boolean>>()
        var fail: (String) -> Exception? = { null }
        var hold: CompletableDeferred<Unit>? = null
        val pauses = mutableListOf<Long>()
        val resume = Channel<Unit>(Channel.UNLIMITED)
        val saved = mutableListOf<String>()
        val sync = LibraryOutbox.Sync(
            LibraryOutbox.Book(null),
            save = { saved += it },
            send = { uri, on ->
                val to = account // the account the request goes to, when it is made
                hold?.await()
                fail(uri)?.let { throw it }
                sent += Triple(to, uri, on)
            },
            ready = {},
            account = { account },
            pause = { pauses += it; resume.receive() },
            log = {},
            scope = scope,
        )
    }

    private fun test(block: suspend CoroutineScope.(Rig) -> Unit) = runBlocking {
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        block(Rig(scope))
        job.cancel()
    }

    private suspend fun settle() = repeat(200) { yield() }

    @Test fun anotherAccountNeverReceivesTheChangesOfTheFirst() = test { rig ->
        rig.hold = CompletableDeferred()
        rig.sync.put("spotify:track:x", true)
        settle()
        // A signs out while its like waits; B signs in and likes something.
        rig.sync.signedOut()
        rig.account = "b"
        rig.sync.put("spotify:track:y", true)
        rig.hold!!.complete(Unit)
        settle()
        // The request already made for x when A left was A's; B's like goes to B, and nothing else.
        assertEquals(listOf(Triple("a", "spotify:track:x", true), Triple("b", "spotify:track:y", true)), rig.sent)
        assertEquals(0, rig.sync.waiting)
    }

    @Test fun aChangeWaitingForItsAccountIsSentOnlyWhenItSignsInAgain() = test { rig ->
        rig.sync.put("spotify:track:x", true)
        settle()
        rig.sent.clear()
        // Offline: A likes, nothing goes; A signs out, B signs in.
        rig.fail = { java.io.IOException("offline") }
        rig.sync.put("spotify:track:a-like", true)
        settle()
        rig.sync.signedOut()
        rig.account = "b"
        rig.fail = { null }
        rig.sync.request() // the network is back
        settle()
        assertEquals(emptyList<Any>(), rig.sent)
        rig.sync.put("spotify:track:b-like", true)
        settle()
        assertEquals(listOf(Triple("b", "spotify:track:b-like", true)), rig.sent)
        // A comes back: its like goes to A, the one time.
        rig.sync.signedOut()
        rig.account = "a"
        rig.sync.request()
        settle()
        assertEquals(Triple("a", "spotify:track:a-like", true), rig.sent.last())
        assertEquals(2, rig.sent.size)
    }

    @Test fun aSessionLostWithoutSignOutSendsNothingOfTheAccountBeforeToTheNextOne() = test { rig ->
        // Offline: A likes, nothing goes. A's session is lost (no sign-out): its id is no longer known.
        rig.fail = { java.io.IOException("offline") }
        rig.sync.put("spotify:track:a-like", true)
        settle()
        rig.sync.signedOut()
        rig.account = null
        rig.fail = { null }
        // B's session is ready before its id is read: nothing of A's goes to it.
        rig.sync.request()
        settle()
        assertEquals(emptyList<Any>(), rig.sent)
        // B's id read: still nothing of A's, B's own like goes to B.
        rig.account = "b"
        rig.sync.request()
        settle()
        assertEquals(emptyList<Any>(), rig.sent)
        rig.sync.put("spotify:track:b-like", true)
        settle()
        assertEquals(listOf(Triple("b", "spotify:track:b-like", true)), rig.sent)
        assertEquals(1, LibraryOutbox.Book(rig.saved.last()).keptForOthers)
    }

    @Test fun roundsAskedTogetherSendEachChangeOnce() = test { rig ->
        rig.hold = CompletableDeferred()
        rig.sync.put("spotify:track:a", true)
        rig.sync.put("spotify:track:b", false)
        repeat(5) { rig.sync.request() } // a like, the network back, the session ready...
        settle()
        rig.hold!!.complete(Unit)
        settle()
        assertEquals(listOf("spotify:track:a" to true, "spotify:track:b" to false), rig.sent.map { it.second to it.third })
        assertEquals(0, rig.sync.waiting)
    }

    @Test fun aRefusedChangeIsDroppedAndTheNextOnesAreStillSent() = test { rig ->
        rig.fail = { if (it == "spotify:playlist:gone") Exception("HTTP 404: not found") else null }
        rig.sync.put("spotify:playlist:gone", true)
        rig.sync.put("spotify:track:b", true)
        settle()
        assertEquals(listOf("spotify:track:b"), rig.sent.map { it.second })
        assertEquals(0, rig.sync.waiting)
        assertEquals(emptyList<Long>(), rig.pauses) // nothing left: no retry
    }

    @Test fun tooManyRequestsWaitsLongerEachTimeThenSendsEverything() = test { rig ->
        var refusals = 2
        rig.fail = { if (refusals > 0) { refusals--; Exception("status code 429") } else null }
        rig.sync.put("spotify:track:a", true)
        rig.sync.put("spotify:track:b", true)
        settle()
        assertEquals(emptyList<Any>(), rig.sent) // b is not tried while Spotify says "too many"
        assertEquals(listOf(5_000L), rig.pauses)
        rig.resume.send(Unit)
        settle()
        assertEquals(listOf(5_000L, 10_000L), rig.pauses)
        rig.resume.send(Unit)
        settle()
        assertEquals(listOf("spotify:track:a", "spotify:track:b"), rig.sent.map { it.second })
        assertEquals(0, rig.sync.waiting)
    }

    @Test fun aServerErrorKeepsTheChangeButNotTheOthersWaiting() = test { rig ->
        var down = true
        rig.fail = { if (down && it == "spotify:track:a") Exception("HTTP 502: bad gateway") else null }
        rig.sync.put("spotify:track:a", true)
        rig.sync.put("spotify:track:b", true)
        settle()
        assertEquals(listOf("spotify:track:b"), rig.sent.map { it.second })
        assertEquals(1, rig.sync.waiting)
        down = false
        rig.resume.send(Unit)
        settle()
        assertEquals(listOf("spotify:track:b", "spotify:track:a"), rig.sent.map { it.second })
    }

    @Test fun theNetworkBackSendsAtOnceWithoutWaitingForTheRetry() = test { rig ->
        rig.fail = { java.io.IOException("offline") }
        rig.sync.put("spotify:track:a", true)
        settle()
        assertEquals(listOf(5_000L), rig.pauses)
        rig.fail = { null }
        rig.sync.request() // the network is back, before the retry is due
        settle()
        assertEquals(listOf("spotify:track:a"), rig.sent.map { it.second })
        // The cancelled retry sends nothing more.
        settle()
        assertEquals(1, rig.sent.size)
        assertTrue(rig.saved.isNotEmpty())
    }
}
