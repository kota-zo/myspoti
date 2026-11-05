/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SegueBenchTest {
    private val rate = 44_100

    private fun stereo(seconds: Double, f: (Int) -> Float) = FloatArray((seconds * rate).toInt() * 2) { f(it / 2) }

    private fun tone(i: Int, gain: Float = 0.5f) = gain * sin(2 * PI * 440 * i / rate).toFloat()

    @Test fun aSteadySignalMeasuresClean() {
        val r = SegueMetrics.of(stereo(4.0) { tone(it) }, rate, rate, rate, 5.0, 2.0)
        assertEquals(0.5, r.peak, 0.01)
        assertEquals(0, r.clipped)
        assertEquals(0.0, r.jumpDb, 0.1)
        assertEquals(1.0, r.click, 0.1)
        assertEquals(0.0, r.gapMs, 1.0)
        assertEquals(5.0, r.residualMs!!, 0.0); assertEquals(2.0, r.measuredMs!!, 0.0)
    }

    @Test fun aLoudnessJumpIsMeasured() {
        val jump = SegueMetrics.of(stereo(4.0) { tone(it, if (it < 2 * rate) 0.1f else 0.5f) }, rate, rate, rate, null, null)
        assertEquals(14.0, jump.jumpDb, 0.2)
    }

    @Test fun clippingIsCounted() {
        val r = SegueMetrics.of(stereo(1.0) { 1.5f }, rate / 2, rate / 4, rate, null, null)
        assertEquals(rate * 2, r.clipped)
    }

    @Test fun aClickAtTheEdgeIsMeasured() {
        val x = stereo(4.0) { tone(it, 0.05f) }
        x[2 * rate] = 0.9f
        assertTrue(SegueMetrics.click(x, rate, rate, rate) > 5)
    }

    @Test fun aSilentHoleIsMeasured() {
        val x = stereo(4.0) { if (it in 2 * rate until 2 * rate + rate / 10) 0f else tone(it) }
        assertEquals(100.0, SegueMetrics.longestGapMs(x, rate, 3 * rate, rate), 2.0)
    }

    @Test fun theLagOfOnsetsIsFoundWithSubFrameAccuracy() {
        val fps = 86.0
        fun pulses(shift: Double) = FloatArray(860) { i ->
            var v = 0.0
            for (k in 0 until 20) { val d = i - (k * 43 + shift); v += Math.exp(-d * d / 4) }
            v.toFloat() - 0.2f
        }
        val lag = SegueMetrics.lagMs(pulses(10.0), pulses(12.0), fps)!!
        assertEquals(2 * 1000 / fps, lag, 2.0)
        assertEquals(-2 * 1000 / fps, SegueMetrics.lagMs(pulses(12.0), pulses(10.0), fps)!!, 2.0)
        assertEquals(0.0, SegueMetrics.lagMs(pulses(10.0), pulses(10.0), fps)!!, 0.5)
        assertNull(SegueMetrics.lagMs(FloatArray(4), FloatArray(4), fps))
        assertNull(SegueMetrics.lagMs(FloatArray(860), FloatArray(860), fps))
    }

    @Test fun anArtistAndAnAlbumStayInOneFold() {
        val rows = List(200) { SegueCorpus.Row("s$it", "t", "artist${it / 4}", "") }
        val split = SegueCorpus.split(rows)
        for ((_, group) in rows.groupBy { it.artist }) assertEquals(1, group.map { split[it.id] }.toSet().size)
        val folds = split.values.groupingBy { it }.eachCount()
        assertEquals(setOf("tuning", "validation", "test"), folds.keys)
        assertTrue(folds.getValue("tuning") > folds.getValue("validation"))
        val shared = listOf(SegueCorpus.Row("a", "t", "one", "album"), SegueCorpus.Row("b", "t", "two", "album"))
        val s = SegueCorpus.split(shared)
        assertEquals(s["a"], s["b"])
    }

    @Test fun theSplitIsStable() {
        val rows = List(40) { SegueCorpus.Row("s$it", "t", "artist$it", "") }
        assertEquals(SegueCorpus.split(rows), SegueCorpus.split(rows.reversed()))
    }

    @Test fun corpusNamesAreRead() {
        val r = SegueCorpus.parse("n03|A Title - An Artist.flac")
        assertEquals("n03", r.id); assertEquals("A Title", r.title); assertEquals("An Artist", r.artist)
        assertEquals("unknown:n09", SegueCorpus.parse("n09|Untitled.flac").artist)
    }

    @Test fun aWhatIfChangesOnlyItsParameter() {
        val base = SegueTuning()
        assertEquals(base.copy(maxRateDeviationPpm = 80_000), SegueCorpus.withValue(base, "maxRateDeviationPpm", 80_000.0))
        assertEquals(base.copy(driftMaxBeats = 0.2), SegueCorpus.withValue(base, "driftMaxBeats", 0.2))
    }

    @Test fun variationsChangeOneParameterAtATime() {
        val base = SegueTuning()
        val all = SegueCorpus.variations(base)
        assertTrue(all.size > 80)
        val v = all.first { it.first == "maxRateDeviationPpm\t1.2" }.second
        assertEquals(48_000L, v.maxRateDeviationPpm)
        assertEquals(base.copy(maxRateDeviationPpm = 48_000), v)
        assertNotNull(all.first { it.first == "energySigmaDb\t0.8" })
        for ((_, t) in all) assertNotEquals(base, t)
    }
}
