/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin

class AnalysisStructureTest {
    private fun analysis(novelty: FloatArray, voice: FloatArray = FloatArray(novelty.size)): TrackAnalysis {
        val n = novelty.size
        val db = FloatArray(n) { -12f }
        return TrackAnalysis(n * 2.0, 0.0, n * 2.0, -12.0, -1, 0.0, 120.0, true,
            null, null, DoubleArray(n * 4) { it * 0.5 }, DoubleArray(n) { it * 2.0 },
            db, db, db, FloatArray(n), novelty, 1, false, Double.NaN, Double.NaN, barVocalRisk = voice)
    }

    @Test fun structuralChangesRestartPhrasesAfterAnIrregularSection() {
        val novelty = FloatArray(64) { 0.2f }.also { it[13] = 5f; it[37] = 6f }
        val a = analysis(novelty)
        assertArrayEquals(intArrayOf(13, 37), a.sectionStarts)
        assertEquals(0, a.phrasePos(13))
        assertEquals(4, a.phrasePos(17))
        assertEquals(0, a.phrasePos(37))
        assertEquals(0, a.phrasePos(1)) // Global phase still applies before the first strong boundary.
        assertTrue(a.sectionStrength(13) > 0.65)
        assertEquals(0.0, a.sectionStrength(14), 0.0)
    }

    @Test fun aSteadyTextureDoesNotInventSections() {
        val novelty = FloatArray(64) { 0.2f + (it % 3) * 0.01f }
        assertTrue(analysis(novelty).sectionStarts.isEmpty())
    }

    @Test fun duplicateAndOverlappingLyricsDoNotInventSungTime() {
        val a = analysis(FloatArray(4))
        // Only 0.4 seconds of this two-second bar are sung, even with duplicate annotations.
        val single = a.withSung(listOf(0.0 to 0.4))
        val duplicated = a.withSung(listOf(0.0 to 0.4, 0.0 to 0.4, 0.1 to 0.3))
        assertArrayEquals(single.barVocalRisk, duplicated.barVocalRisk, 0f)
        assertTrue(duplicated.barVocalRisk[0] < 0.6f)
        assertTrue(duplicated.voiceKnown)
        // Distinct fragments still add up: 0.7 seconds exceeds the 30 percent threshold.
        val fragments = a.withSung(listOf(0.0 to 0.4, 1.0 to 1.3))
        assertTrue(fragments.barVocalRisk[0] >= 0.6f)
    }

    @Test fun lyricCoverageIsOrderIndependentAndClippedToAudibleBounds() {
        val a = analysis(FloatArray(4))
        val canonical = a.withSung(listOf(0.0 to 2.5, 6.0 to 8.0))
        val provider = a.withSung(listOf(6.0 to 50.0, 1.0 to 2.5, -3.0 to 1.5,
            Double.NaN to 3.0, 0.0 to Double.POSITIVE_INFINITY, 4.0 to 3.0))
        assertArrayEquals(canonical.barVocalRisk, provider.barVocalRisk, 0f)
        assertTrue(provider.barVocalRisk[0] >= 0.6f)
        assertTrue(provider.barVocalRisk[1] < 0.6f)
        assertTrue(provider.barVocalRisk[3] >= 0.6f)
    }

    @Test fun aBroadChangeHasOneBoundary() {
        val novelty = FloatArray(48) { 0.2f }.also { it[15] = 3f; it[16] = 5f; it[17] = 5f; it[18] = 3f }
        assertArrayEquals(intArrayOf(16), analysis(novelty).sectionStarts)
    }

    @Test fun aContinuingCentralVoiceLikeBandIsNotHiddenByItsOwnMedian() {
        val risk = VocalRisk.estimate(floatArrayOf(-12f), floatArrayOf(-14f), floatArrayOf(12f), floatArrayOf(0f), true)
        assertTrue("absolute centre should retain the risk", risk[0] > 0.8f)
        val mono = VocalRisk.estimate(floatArrayOf(-12f), floatArrayOf(-14f), floatArrayOf(0f), floatArrayOf(0f), false)
        assertTrue("mono is uncertain, not voice-free", mono[0] in 0.2f..0.56f)
        val silent = VocalRisk.estimate(floatArrayOf(-120f), floatArrayOf(-120f), floatArrayOf(30f), floatArrayOf(10f), true)
        assertEquals(0f, silent[0], 0f)
    }

    @Test fun cachedAnalysisKeepsLocalRiskAndSectionAnchors() {
        val novelty = FloatArray(32) { 0.2f }.also { it[13] = 5f }
        val a = analysis(novelty, FloatArray(32) { if (it in 8..15) 0.9f else 0.1f })
        val bytes = ByteArrayOutputStream().also(a::write).toByteArray()
        val b = TrackAnalysis.read(ByteArrayInputStream(bytes))!!
        assertArrayEquals(a.barVocalRisk, b.barVocalRisk, 0f)
        assertArrayEquals(a.sectionStarts, b.sectionStarts)
        assertEquals(a.phrasePos(17), b.phrasePos(17))
    }

    @Test fun oneWorkerBudgetDoesNotSpawnASecondFeatureWorker() {
        val threads = Collections.synchronizedSet(HashSet<Long>())
        val mid = FloatArray(Features.RATE * 2) { (sin(2 * PI * 440 * it / Features.RATE) * 0.2).toFloat() }
        Features.of(mid, FloatArray(mid.size), cancelled = { threads.add(Thread.currentThread().id); false }, threads = 1)
        assertEquals("the decoder supplies zero side for mono; chroma and onsets must share the worker", 1, threads.size)
    }

    @Test fun featureSlicesPreserveResultsWithinTheirThreadBudget() {
        val mid = FloatArray(Features.RATE * 2) { (sin(2 * PI * (220 + it % 7) * it / Features.RATE) * 0.2).toFloat() }
        val one = Features.of(mid, null, threads = 1)
        val workers = Collections.synchronizedSet(HashSet<Long>())
        val two = Features.of(mid, null, cancelled = { workers.add(Thread.currentThread().id); false }, threads = 2)
        assertTrue(workers.size <= 2)
        assertArrayEquals(one.onset, two.onset, 0f)
        assertArrayEquals(one.rmsDb, two.rmsDb, 0f)
        assertArrayEquals(one.chroma[4], two.chroma[4], 0f)
    }

    @Test(expected = InterruptedException::class)
    fun melPreparationCanBeCancelledBeforeRunningTheModel() {
        MelFrontend.spectrogram(FloatArray(Features.RATE * 3), 0, Features.RATE * 3, cancelled = { true })
    }

    @Test fun melPreparationStopsWhenTheAskIsCancelledDuringItsWork() {
        var checks = 0
        try {
            MelFrontend.spectrogram(FloatArray(Features.RATE * 10), 0, Features.RATE * 10,
                cancelled = { ++checks >= 3 })
            fail("an obsolete ask must not finish its full spectrogram")
        } catch (_: InterruptedException) {
            assertTrue("cancellation happened after work began", checks >= 3)
        }
    }
}
