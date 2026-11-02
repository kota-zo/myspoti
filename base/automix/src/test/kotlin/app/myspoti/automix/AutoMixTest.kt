/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class AutoMixTest {
    /** A drum loop at [bpm]: kick on 1 and 3 (low), snare on 2 and 4 (noise), hat on eighths, a bass note per bar. */
    private fun loop(bpm: Double, seconds: Double, offset: Double = 0.5, seed: Long = 1): FloatArray {
        val sr = Features.RATE
        val x = FloatArray((seconds * sr).toInt())
        val rnd = java.util.Random(seed)
        val beat = 60 / bpm
        var k = 0
        while (offset + k * beat / 2 < seconds) {
            val t0 = offset + k * beat / 2
            val i0 = (t0 * sr).toInt()
            val inBar = k % 8
            for (i in 0 until (0.25 * sr).toInt()) {
                if (i0 + i >= x.size) break
                val t = i.toDouble() / sr
                var v = 0.0
                if (inBar == 0 || inBar == 4) v += 0.8 * sin(2 * PI * (50 + 80 * exp(-t * 30)) * t) * exp(-t * 12)
                if (inBar == 2 || inBar == 6) v += 0.4 * (rnd.nextDouble() * 2 - 1) * exp(-t * 25)
                v += 0.08 * (rnd.nextDouble() * 2 - 1) * exp(-t * 80)
                x[i0 + i] += v.toFloat()
            }
            k++
        }
        // A bass note changing every bar (pitch classes C, F, G, C).
        val notes = doubleArrayOf(65.4, 87.3, 98.0, 65.4)
        for (i in x.indices) {
            val t = i.toDouble() / sr - offset
            if (t < 0) continue
            val bar = (t / (4 * beat)).toInt()
            x[i] += (0.15 * sin(2 * PI * notes[bar % 4] * t)).toFloat()
        }
        return x
    }

    @Test fun gridFindsTempoAndPhase() {
        val a = Analyzer.analyze(loop(124.0, 60.0), null)
        assertEquals(124.0, a.bpm, 0.3)
        assertTrue("steady", a.steady)
        val g = a.tail!!
        // A beat every 60/124 s from 0.5 s (onset lead aside, within 25 ms).
        val k = Math.round(g.index(0.5 + 40 * 60 / 124.0)).toInt()
        assertEquals(0.5 + 40 * 60 / 124.0, g.beat(k), 0.035)
    }

    @Test fun blendBetweenNearTempos() {
        val a = Analyzer.analyze(loop(124.0, 90.0, seed = 1), null)
        val b = Analyzer.analyze(loop(122.0, 90.0, offset = 0.3, seed = 2), null)
        val p = MixPlanner.plan(a, b)
        assertEquals(MixPlan.Style.BLEND, p.style)
        assertEquals(a.tail!!.bpm / b.head!!.bpm, p.speedB, 0.01)
        assertTrue("A goes out late", p.exitA > 45)
        assertTrue("mix inside A", p.endA <= a.contentEnd + 4 * 60 / 124.0)
        // B comes in on one of its beats.
        val gb = b.head!!
        assertEquals(0.0, abs(gb.index(p.entryB) - Math.round(gb.index(p.entryB))), 0.02)
    }

    @Test fun farTemposAreNotStretched() {
        val a = Analyzer.analyze(loop(124.0, 90.0), null)
        val b = Analyzer.analyze(loop(100.0, 90.0, seed = 3), null)
        val p = MixPlanner.plan(a, b)
        assertTrue(p.style != MixPlan.Style.BLEND)
        assertEquals(1.0, p.speedB, 1e-9)
    }

    @Test fun halfTimeCountsAsAMatch() {
        val m = MixPlanner.tempoMatch(140.0, 71.0)
        assertEquals(140.0 / 142.0, m.speed, 1e-9)
        assertEquals(2.0, m.beatsPerB, 0.0)
    }

    @Test fun levelsAreContinuous() {
        for (style in MixPlan.Style.values()) {
            val p = MixPlan(style, 100.0, 1.0, 16.0, 1.02, 32, 0.5, 1.0 / 32, 0.8, 4.0, "")
            var prev = p.levels(0.0)
            var x = 0.0
            while (x <= 1.0) {
                val l = p.levels(x)
                assertTrue("$style gainA step at $x", abs(l.gainA - prev.gainA) < 0.06)
                assertTrue("$style gainB step at $x", abs(l.gainB - prev.gainB) < if (style == MixPlan.Style.CUT) 0.045 else 0.06)
                assertTrue("$style power at $x", l.gainA * l.gainA + l.gainB * l.gainB < 1.7)
                prev = l
                x += 0.002
            }
            assertEquals(0.0, p.levels(1.0).gainA, 1e-9)
            assertEquals(1.02, p.speedAfter(p.resumeB), 1e-9)
            assertEquals(1.0, p.speedAfter(p.resumeB + 5), 1e-9)
        }
    }

    @Test fun camelot() {
        assertEquals(8 to true, MixPlanner.camelot(0))      // C major 8B
        assertEquals(8 to false, MixPlanner.camelot(21))    // A minor 8A
        assertEquals(9 to true, MixPlanner.camelot(7))      // G major 9B
    }

    @Test fun analysisRoundTrip() {
        val a = Analyzer.analyze(loop(124.0, 40.0), null)
        val out = java.io.ByteArrayOutputStream(); a.write(out)
        val b = TrackAnalysis.read(java.io.ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(a.bpm, b.bpm, 0.0); assertEquals(a.bars, b.bars); assertEquals(a.tailDown, b.tailDown, 0.0)
    }

    @Test fun skipMixStartsOnTheNextDownbeat() {
        val a = Analyzer.analyze(loop(124.0, 90.0, seed = 1), null)
        val b = Analyzer.analyze(loop(122.0, 90.0, offset = 0.3, seed = 2), null)
        val p = MixPlanner.planSkip(a, b, now = 40.0, lead = 0.5)
        assertEquals(MixPlan.Style.BLEND, p.style)
        assertTrue("after the lead", p.exitA >= 40.5)
        assertTrue("within a bar", p.exitA < 40.5 + 4 * 60 / 124.0 + 0.01)
        val g = a.tail!!
        assertEquals(0.0, Math.floorMod(Math.round(g.index(p.exitA) - g.index(a.tailDown)).toInt(), 4).toDouble(), 0.0)
        // Nothing known: a short fade.
        assertEquals(MixPlan.Style.FADE, MixPlanner.planSkip(null, null, 40.0, 0.5).style)
    }
}
