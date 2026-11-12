package com.music.spotui.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test

class CanvasRequestsTest {
    private fun songId(track: String) = ("track:$track").hashCode() and 0x7fffffff

    @Test fun optimisticChangeHidesAAndConfirmedUriStartsBWithoutAnotherSongIdChange() {
        val requests = CanvasRequests()
        val a = requests.select(confirmedTrackIdForDisplay("spotify:track:A", songId("A")))!!
        requests.complete(a, "canvas-a")
        assertEquals("A", requests.state.trackId)
        val optimistic = confirmedTrackIdForDisplay("spotify:track:A", songId("B"))
        assertEquals("", optimistic)
        requests.select(optimistic)
        assertNull(requests.state.url)
        val confirmed = confirmedTrackIdForDisplay("spotify:track:B", songId("B"))
        assertEquals("B", confirmed)
        val b = requests.select(confirmed)!!
        assertTrue(b.fetch)
        assertTrue(requests.complete(b, "canvas-b"))
        assertEquals(CanvasTrackState("B", "canvas-b"), requests.state)
    }

    @Test fun lastTrackWinsWhenAnEarlierResponseCompletesLast() {
        val requests = CanvasRequests()
        val a = requests.select("A")!!
        val b = requests.select("B")!!
        assertTrue(requests.complete(b, "canvas-b"))
        assertFalse(requests.complete(a, "canvas-a"))
        assertEquals(CanvasTrackState("B", "canvas-b"), requests.state)
    }

    @Test fun replayARejectsTheFirstAGenerationAfterAToBToA() {
        val requests = CanvasRequests()
        val firstA = requests.select("A")!!
        val b = requests.select("B")!!
        val replayA = requests.select("A")!!
        assertTrue(requests.complete(replayA, "fresh-a"))
        assertFalse(requests.complete(firstA, "stale-a"))
        assertFalse(requests.complete(b, "canvas-b"))
        assertEquals(CanvasTrackState("A", "fresh-a"), requests.state)
    }

    @Test fun nullCanvasIsResolvedAndDoesNotStartRepeatedLoadsForTheSameTrack() {
        val requests = CanvasRequests()
        val a = requests.select("A")!!
        assertTrue(requests.complete(a, null))
        repeat(3) { assertNull(requests.select("A")) }
        assertEquals(CanvasTrackState("A"), requests.state)
    }

    @Test fun sameTrackAndUrlPreserveTheResolvedSnapshotWithoutNewFetch() {
        val requests = CanvasRequests()
        requests.complete(requests.select("A")!!, "same-url")
        val snapshot = requests.state
        assertNull(requests.select("A"))
        assertSame(snapshot, requests.state)
    }

    @Test fun identicalUrlForANewTrackStillHasTheNewOwner() {
        val requests = CanvasRequests()
        requests.complete(requests.select("A")!!, "same-url")
        val b = requests.select("B")!!
        assertEquals(CanvasTrackState("B"), requests.state)
        requests.complete(b, "same-url")
        assertEquals(CanvasTrackState("B", "same-url"), requests.state)
    }

    @Test fun failureFallsBackToArtworkAndCanBeRetried() {
        val requests = CanvasRequests()
        val a = requests.select("A")!!
        assertTrue(requests.fail(a))
        assertNull(requests.state.url)
        val retry = requests.select("A")!!
        assertTrue(retry.generation > a.generation)
        assertTrue(requests.complete(retry, "retry-a"))
        assertEquals(CanvasTrackState("A", "retry-a"), requests.state)
    }

    @Test fun oldFailureCannotClearTheNewTrackCanvas() {
        val requests = CanvasRequests()
        val a = requests.select("A")!!
        val b = requests.select("B")!!
        requests.complete(b, "canvas-b")
        assertFalse(requests.fail(a))
        assertEquals(CanvasTrackState("B", "canvas-b"), requests.state)
    }

    @Test fun saverDisableInvalidatesPendingAndReenableFetchesTheSameTrackSafely() {
        val requests = CanvasRequests()
        val pending = requests.select("A")!!
        val disabled = requests.select("")!!
        assertFalse(disabled.fetch)
        assertNull(requests.state.url)
        assertFalse(requests.complete(pending, "old-a"))
        val resumed = requests.select("A")!!
        assertTrue(requests.complete(resumed, "new-a"))
        assertFalse(requests.complete(pending, "old-a"))
        assertEquals(CanvasTrackState("A", "new-a"), requests.state)
    }

    @Test fun audioBufferingDefersANewLookupUntilTheSameTrackIsReady() {
        val requests = CanvasRequests()
        val deferred = requests.select("A", allowFetch = false)!!
        assertFalse(deferred.fetch)
        assertFalse(requests.complete(deferred, "unexpected"))
        assertNull(requests.select("A", allowFetch = false))
        val started = requests.select("A", allowFetch = true)!!
        assertTrue(started.fetch)
        requests.complete(started, "canvas-a")
        val snapshot = requests.state
        assertNull(requests.select("A", allowFetch = false))
        assertSame(snapshot, requests.state)
    }

    @Test fun bufferingCancelsAnInFlightGenerationBeforeItCanPublish() {
        val requests = CanvasRequests()
        val pending = requests.select("A")!!
        val blocked = requests.select("A", allowFetch = false)!!
        assertFalse(blocked.fetch)
        assertFalse(requests.complete(pending, "old-a"))
        val resumed = requests.select("A")!!
        requests.complete(resumed, "new-a")
        assertEquals(CanvasTrackState("A", "new-a"), requests.state)
    }

    @Test fun unknownAndEpisodeIdentityNeverSelectATrackCanvas() {
        assertEquals("", confirmedTrackIdForDisplay("", songId("A")))
        assertEquals("", confirmedTrackIdForDisplay("spotify:episode:A", songId("A")))
        assertEquals("", confirmedTrackIdForDisplay("spotify:track:", songId("A")))
        assertEquals("", confirmedTrackIdForDisplay("spotify:track:A", songId("B")))
    }

    @Test fun aDownloadPlayedLocallyIsShownUnderItsSpotifyId() {
        val uri = "local:-42"
        val shownId = uri.hashCode() and 0x7fffffff
        val known = { id: Long -> if (id == -42L) "T" else null }
        org.junit.Assert.assertEquals("T", confirmedTrackIdForDisplay(uri, shownId, known))
        // Another song shown already (an optimistic one): nothing yet.
        org.junit.Assert.assertEquals("", confirmedTrackIdForDisplay(uri, shownId + 1, known))
        // A file of the device: no Spotify id.
        org.junit.Assert.assertEquals("", confirmedTrackIdForDisplay("local:7", "local:7".hashCode() and 0x7fffffff, known))
    }
}
