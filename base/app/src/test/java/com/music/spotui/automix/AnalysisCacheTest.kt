package com.music.spotui.automix

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisCacheTest {
    @Test fun aCacheWithinItsBoundKeepsEverything() {
        assertEquals(emptyList<String>(), AnalysisCache.toTrim(listOf(AnalysisCache.Entry("a", 10, 1), AnalysisCache.Entry("b", 10, 2)), maxBytes = 20))
    }

    @Test fun theLeastRecentlyUsedGoFirstUntilTheRestFits() {
        val entries = listOf(AnalysisCache.Entry("new", 10, 30), AnalysisCache.Entry("old", 10, 10), AnalysisCache.Entry("mid", 10, 20))
        assertEquals(listOf("old"), AnalysisCache.toTrim(entries, maxBytes = 25))
        assertEquals(listOf("old", "mid"), AnalysisCache.toTrim(entries, maxBytes = 10))
    }

    @Test fun theThirdSongIsAnalysedAheadOnlyWhileCharging() {
        assertEquals(2, AnalysisCache.lookahead(charging = false, frugal = false))
        assertEquals(3, AnalysisCache.lookahead(charging = true, frugal = false))
        assertEquals(2, AnalysisCache.lookahead(charging = true, frugal = true))
    }
}
