package com.music.spotui.automix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixDiagnosticsTest {
    @Test fun copiedJournalUsesWhitelistedEventsAndNeverIncludesUserContent() {
        val id = "0123456789abcdefghijkl"
        val safe = AutoMixDiagnostics.safeJournal(listOf(
            "analysed Private Song: 120.0 BPM steady, 64 bars, key 3, decode 400 ms (bytes 300 ms, 2.4 MB, convert 20 ms, 0 restarts), analysis 2450 ms on 4 threads",
            "analysed Chanson privée key 7 id 1234567890123456789012: 120,0 BPM steady, 64 bars, key 7, decode 400 ms (bytes 300 ms, 2,4 MB, convert 20 ms, 0 restarts), analysis 2450 ms on 4 threads",
            "plan Private Song → Other Secret: BLEND (8.0 s)",
            "deck preparing Another Song from 12000 ms",
            "source https://private.example/path/$id",
            "analysis 2450 ms on 4 threads",
            "no transition fits the current constraints",
            "no transition fits the current constraints Private Song",
        )).joinToString("\n")

        listOf("Private Song", "Chanson privée", "Other Secret", "Another Song", "private.example", id, "1234567890123456789012", "key 3", "key 7", "2.4 MB", "2,4 MB").forEach {
            assertFalse("leaked $it", safe.contains(it))
        }
        assertTrue(safe.contains("Track analysis completed"))
        assertTrue(safe.contains("Transition plan updated"))
        assertTrue(safe.contains("Incoming deck preparing"))
        assertTrue(safe.contains("No transition fits the current constraints"))
        assertTrue(safe.contains("120.0 BPM, 64 bars"))
        assertTrue(safe.contains("120,0 BPM, 64 bars"))
        assertTrue(safe.contains("decode 400 ms, analysis 2450 ms on 4 threads"))
        assertFalse(safe.contains("source"))
    }
}
