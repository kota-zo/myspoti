/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.Grid
import app.myspoti.automix.MixPlan
import app.myspoti.automix.PhaseStats
import app.myspoti.automix.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SeguePolicyTest {
    /** A controlled song: 240 s, one bar every 2 s at 120 BPM unless told otherwise. */
    private fun song(
        key: String = "audio",
        duration: Double = 240.0,
        bpm: Double = 120.0,
        grid: Boolean = true,
        tail: Grid? = null,
        steadiness: Double = 1.0,
        phase: PhaseStats? = PhaseStats(3_000, 6_000),
        metre: Float = 1f,
        bars: Boolean = true,
        loudness: Double = -10.0,
        stereo: Boolean = true,
        musicKey: Int = 0,
        contentStart: Double = 0.0,
        contentEnd: Double = duration,
        barDb: Float = -10f,
        barLevel: ((Int) -> Float)? = null,
        down: Double = 0.0,
        risk: (Int) -> Float = { 0f },
        modelBeats: DoubleArray? = null,
    ): TrackAnalysis {
        val beat = 60 / bpm
        val n = if (bars) (duration / (4 * beat)).toInt() else 0
        val g = Grid(0.0, beat, (duration / beat).toInt(), steadiness, 0.0, 1.0)
        return TrackAnalysis(duration, contentStart, contentEnd, loudness, musicKey, 1.0, bpm, grid,
            if (grid) g else null, if (grid) tail ?: g else null, if (grid && bars) DoubleArray(g.count) { g.beat(it) } else DoubleArray(0), DoubleArray(n) { it * 4 * beat },
            FloatArray(n) { barLevel?.invoke(it) ?: barDb }, FloatArray(n) { -16f }, FloatArray(n) { -20f }, FloatArray(n), FloatArray(n), 0, stereo,
            if (grid) down else Double.NaN, if (grid) tail?.first ?: 0.0 else Double.NaN, barVocalRisk = FloatArray(n, risk),
            audioKey = key, barBassDb = FloatArray(n) { -18f }, headPhase = phase, tailPhase = phase, headMetre = metre, tailMetre = metre,
            modelBeats = modelBeats ?: DoubleArray(0))
    }

    private fun decide(a: TrackAnalysis, b: TrackAnalysis, earliest: Long = 0, sameAlbum: Boolean = false, eq: Boolean = true, tuning: SegueTuning = SegueTuning()) =
        SeguePolicy.decide(SegueRequest(a, b, earliest, eq = eq, sameAlbum = sameAlbum, tuning = tuning))

    private val t = SegueTuning()

    // ── Placement ──

    @Test fun theWindowStaysWithinTheLastThirdAndBothSongs() {
        val d = decide(song("a"), song("b", bpm = 118.0))
        val p = d.plan!!
        assertTrue(p.outgoing.anchorUs >= 160_000_000)
        assertTrue(SegueValidator.windowEnd(p, p.outgoing) <= 240_000_000)
        assertEquals(emptyList<SegueViolation>(), SegueValidator.structural(p))
    }

    @Test fun anInstrumentalEndingIsPreferred() {
        // A's voice stops at bar 100 (200 s): the window leaves after it rather than over it.
        val d = decide(song("a", risk = { if (it < 100) 1f else 0f }), song("b"))
        assertEquals(SegueClass.LOCKED, d.level.cls)
        assertTrue(d.plan!!.toString(), d.plan!!.outgoing.anchorUs >= 192_000_000)
    }

    @Test fun anEntryIsNeverBeforeTheSongsStart() {
        // The bars start at 0 s, 2 s...: the first one once the song has begun is the one to use.
        val d = decide(song("a"), song("b", contentStart = 1.95))
        assertEquals(d.why, SegueClass.LOCKED, d.level.cls)
        assertTrue(d.why, d.plan!!.incoming.anchorUs >= 2_000_000L)
    }

    @Test fun nothingStartsBeforeTheEarliestExit() {
        val d = decide(song("a"), song("b"), earliest = 200_000_000)
        assertTrue(d.plan!!.outgoing.anchorUs >= 200_000_000)
    }

    @Test fun theRateIsAnExactInteger() {
        val p = decide(song("a"), song("b", bpm = 118.0)).plan!!
        assertEquals(Math.round(120.0 / 118.0 * 1e6), p.incoming.ratePpm)
        assertEquals(SegueFormat.RATE_NOMINAL, p.outgoing.ratePpm)
    }

    @Test fun listenerSettingsOnlyTighten() {
        val a = song("a"); val b = song("b", bpm = 118.0)
        // A speed change beyond the listener's limit is not locked: the plain fade.
        assertEquals(SegueLevel.SIMPLE_FADE, SeguePolicy.decide(SegueRequest(a, b, maxRatePpm = 1_000)).level)
        assertEquals(2, SeguePolicy.decide(SegueRequest(a, b, maxBars = 2)).plan!!.span.musicalUnits)
    }

    @Test fun theListenerRateIsCarriedWithoutChangingTheAlignment() {
        val p = SeguePolicy.decide(SegueRequest(song("a"), song("b", bpm = 118.0), listenerRate = 1_250_000)).plan!!
        assertEquals(1_250_000L, p.outgoing.listenerRate)
        assertEquals(1_250_000L, p.incoming.listenerRate)
        assertEquals(Math.round(120.0 / 118.0 * 1e6), p.incoming.ratePpm)
    }

    @Test fun withoutTheEqOnlyLevelsAreWritten() {
        val p = decide(song("a"), song("b"), eq = false).plan!!
        assertTrue(p.lanes.all { it.target == Target.LEVEL })
    }

    @Test fun theFadeHasNoGridAndItsFixedLength() {
        val d = decide(song("a", grid = false, bars = false), song("b", grid = false, bars = false))
        assertEquals(d.why, SegueLevel.SIMPLE_FADE, d.level)
        val p = d.plan!!
        assertEquals(PulseState.FREE, p.pulse.state)
        assertNull(p.outgoing.gridKey)
        // Near the end of the file the fade is cut to its floor.
        assertEquals(t.compact.fadeMinUs, p.span.lengthUs)
    }

    @Test fun albumContinuityIsLeftToPlayNatively() {
        val joined = decide(song("a"), song("b"), sameAlbum = true)
        assertEquals(SegueLevel.NORMAL, joined.level)
        assertTrue(joined.attempts.all { it.cause == SegueCause.ALBUM_CONTINUITY })
        // The same album with real silence at the junction is mixed.
        val gap = decide(song("a", contentEnd = 235.0), song("b"), sameAlbum = true)
        assertNotEquals(SegueLevel.NORMAL, gap.level)
    }

    // ── Lanes ──

    @Test fun fadeLanesBoundThePowerAndLockedMixesHandTheBassOver() {
        val fade = decide(song("a", grid = false, bars = false), song("b", grid = false, bars = false)).plan!!
        val la = LaneCurve(fade.lanes.first { it.side == LaneSide.OUTGOING && it.target == Target.LEVEL })
        val lb = LaneCurve(fade.lanes.first { it.side == LaneSide.INCOMING && it.target == Target.LEVEL })
        assertEquals(0.0, la.value(0.0), 1e-3); assertTrue(lb.isNull(0.0))
        assertTrue(la.isNull(SegueFormat.T_MAX.toDouble())); assertEquals(0.0, lb.value(SegueFormat.T_MAX.toDouble()), 1e-3)
        for (i in 0..16) {
            val u = i * SegueFormat.T_MAX / 16.0
            val power = Math.pow(10.0, la.value(u) / 10) + Math.pow(10.0, lb.value(u) / 10)
            assertTrue("at $i: $power", power <= 1.0 + 1e-3)
        }
        val p = decide(song("a"), song("b")).plan!!
        val ba = LaneCurve(p.lanes.first { it.side == LaneSide.OUTGOING && it.target == Target.BASS_BAND })
        val bb = LaneCurve(p.lanes.first { it.side == LaneSide.INCOMING && it.target == Target.BASS_BAND })
        assertEquals(0.0, ba.value(0.0), 1e-9); assertEquals(t.compact.bassKillDb, bb.value(0.0), 1e-9)
        assertEquals(t.compact.bassKillDb, ba.value(SegueFormat.T_MAX.toDouble()), 1e-9); assertEquals(0.0, bb.value(SegueFormat.T_MAX.toDouble()), 1e-9)
    }

    @Test fun aLouderIncomingSongComesBackToItsOwnLevelInsideTheWindow() {
        val d = decide(song("a", loudness = -14.0), song("b", loudness = -9.0))
        val lb = LaneCurve(d.plan!!.lanes.first { it.side == LaneSide.INCOMING && it.target == Target.LEVEL })
        assertEquals(0.0, lb.value(SegueFormat.T_MAX.toDouble()), 1e-3)
    }

    @Test fun timingReadsThePlanForThePlayers() {
        val d = decide(song("a"), song("b", bpm = 118.0))
        val m = SeguePolicy.timing(d)!!
        val p = d.plan!!
        assertEquals(MixPlan.Style.BLEND, m.style)
        assertEquals(p.outgoing.anchorUs / 1e6, m.exitA, 1e-9)
        assertEquals(p.incoming.anchorUs / 1e6, m.entryB, 1e-9)
        assertEquals(p.span.lengthUs / 1e6, m.length, 1e-9)
        assertEquals(p, m.segue)
        val fade = SeguePolicy.timing(decide(song("a", grid = false, bars = false), song("b", grid = false, bars = false)))!!
        assertEquals(MixPlan.Style.FADE, fade.style)
    }

    // ── Determinism and invalidation ──

    @Test fun identicalRunsGiveTheSameFingerprint() {
        val cases = listOf(song("a") to song("b"), song("a") to song("b", bpm = 118.0), song("a") to song("b", bpm = 100.0),
            song("a", risk = { 1f }) to song("b", risk = { 1f }), song("a", grid = false, bars = false) to song("b", grid = false, bars = false))
        for ((x, y) in cases) {
            val one = decide(x, y); val two = decide(x, y)
            assertNotNull(one.fingerprint)
            assertEquals(one.fingerprint, two.fingerprint)
            assertEquals(one.why, two.why)
            assertEquals(SegueCodec.encode(one.plan!!), SegueCodec.encode(two.plan!!))
        }
    }

    @Test fun aChangedAudioOrGridForcesARecalculation() {
        val a = song("a"); val b = song("b")
        val p = decide(a, b).plan!!
        assertEquals(emptyList<SegueViolation>(), SeguePolicy.stale(p, a, b))
        assertEquals(listOf(SegueViolation.STALE_OUTGOING_AUDIO), SeguePolicy.stale(p, song("other"), b))
        assertEquals(listOf(SegueViolation.STALE_INCOMING_AUDIO), SeguePolicy.stale(p, a, song("other")))
        val moved = song("a", modelBeats = DoubleArray(480) { it * 0.5 + 0.01 })
        assertEquals(listOf(SegueViolation.STALE_OUTGOING_GRID), SeguePolicy.stale(p, moved, b))
        assertNotEquals(p.outgoing.gridKey, decide(moved, b).plan!!.outgoing.gridKey)
    }

    @Test fun theNewAnalysisFieldsSurviveTheCache() {
        val s = song("audio-key", phase = PhaseStats(1234, 5678), metre = 0.42f)
        val out = ByteArrayOutputStream(); s.write(out)
        val back = TrackAnalysis.read(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals("audio-key", back.audioKey)
        assertEquals(PhaseStats(1234, 5678), back.headPhase); assertEquals(PhaseStats(1234, 5678), back.tailPhase)
        assertEquals(0.42f, back.headMetre, 1e-6f); assertEquals(0.42f, back.tailMetre, 1e-6f)
        assertEquals(-18f, back.barBassDb[3], 1e-6f)
        assertEquals(s.tailGridKey, back.tailGridKey)
        val none = song("x", phase = null)
        val o2 = ByteArrayOutputStream(); none.write(o2)
        assertNull(TrackAnalysis.read(ByteArrayInputStream(o2.toByteArray()))!!.headPhase)
    }
}
