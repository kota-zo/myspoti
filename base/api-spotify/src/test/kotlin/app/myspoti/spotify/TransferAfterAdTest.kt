package app.myspoti.spotify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransferAfterAdTest {
    /** The local player: its song and position move on while the transfer waits. */
    private var song = "spotify:track:A"
    private var positionMs = 10_000L
    private val sent = ArrayList<String>()
    private fun transfer(device: String) = { sent += "$device $song $positionMs" }

    @Test fun `with no ad the transfer goes at once`() = runTest {
        val t = TransferAfterAd(backgroundScope, MutableStateFlow(false))
        assertFalse(t.run("Speaker", transfer("Speaker")))
        assertEquals(listOf("Speaker spotify:track:A 10000"), sent)
        assertFalse(t.pending)
    }

    @Test fun `during an ad it waits for the end, with the song and position of then`() = runTest {
        val ad = MutableStateFlow(true)
        val t = TransferAfterAd(backgroundScope, ad)
        assertTrue(t.run("Speaker", transfer("Speaker")))
        runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(emptyList<String>(), sent)
        assertTrue(t.pending)
        // The local player went on to its next song meanwhile.
        song = "spotify:track:B"; positionMs = 4_000L
        ad.value = false; runCurrent()
        assertEquals(listOf("Speaker spotify:track:B 4000"), sent)
        assertFalse(t.pending)
    }

    @Test fun `a newer ask replaces the one waiting`() = runTest {
        val ad = MutableStateFlow(true)
        val t = TransferAfterAd(backgroundScope, ad)
        t.run("Speaker", transfer("Speaker"))
        t.run("TV", transfer("TV"))
        runCurrent()
        ad.value = false; runCurrent()
        assertEquals(listOf("TV spotify:track:A 10000"), sent)
    }

    @Test fun `an ad that never ends does not hold the transfer for good`() = runTest {
        val t = TransferAfterAd(backgroundScope, MutableStateFlow(true), maxWaitMs = 90_000)
        t.run("Speaker", transfer("Speaker"))
        advanceTimeBy(89_000); runCurrent()
        assertEquals(emptyList<String>(), sent)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, sent.size)
    }

    @Test fun `a cancelled transfer is never sent`() = runTest {
        val ad = MutableStateFlow(true)
        val t = TransferAfterAd(backgroundScope, ad)
        t.run("Speaker", transfer("Speaker"))
        runCurrent()
        t.cancel()
        ad.value = false; runCurrent()
        assertEquals(emptyList<String>(), sent)
    }
}
