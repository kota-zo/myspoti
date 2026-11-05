/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.Grid
import app.myspoti.automix.MixPlan
import app.myspoti.automix.PhaseStats
import app.myspoti.automix.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        risk: (Int) -> Float = { 0f },
    ): TrackAnalysis {
        val beat = 60 / bpm
        val n = if (bars) (duration / 2).toInt() else 0
        val g = Grid(0.0, beat, (duration / beat).toInt(), steadiness, 0.0, 1.0)
        return TrackAnalysis(duration, contentStart, contentEnd, loudness, musicKey, 1.0, bpm, grid,
            if (grid) g else null, if (grid) tail ?: g else null, DoubleArray(0), DoubleArray(n) { it * 2.0 },
            FloatArray(n) { barDb }, FloatArray(n) { -16f }, FloatArray(n) { -20f }, FloatArray(n), FloatArray(n), 0, stereo,
            if (grid) 0.0 else Double.NaN, if (grid) tail?.first ?: 0.0 else Double.NaN, barVocalRisk = FloatArray(n, risk),
            audioKey = key, barBassDb = FloatArray(n) { -18f }, headPhase = phase, tailPhase = phase, headMetre = metre, tailMetre = metre)
    }

    private fun decide(a: TrackAnalysis, b: TrackAnalysis, earliest: Long = 0, sameAlbum: Boolean = false, eq: Boolean = true, tuning: SegueTuning = SegueTuning()) =
        SeguePolicy.decide(SegueRequest(a, b, earliest, eq = eq, sameAlbum = sameAlbum, tuning = tuning))

    private fun assertCause(d: SegueDecision, level: SegueLevel, cause: SegueCause) =
        assertTrue(d.why, d.attempts.any { it.level == level && it.cause == cause })

    // ── Guards: each defect injected alone rejects the candidate, whatever the score ──

    private val t = SegueTuning()

    private fun assertRejectsAlone(guard: SegueGuard, m: SegueMeasures, cls: SegueClass = SegueClass.LOCKED) =
        assertEquals(listOf(guard), SegueGuards.crossed(cls, m, t))

    @Test fun aCleanCandidateCrossesNothing() {
        for (c in SegueClass.values()) assertEquals(emptyList<SegueGuard>(), SegueGuards.crossed(c, SegueMeasures(), t))
    }
    @Test fun rateGuard() = assertRejectsAlone(SegueGuard.RATE, SegueMeasures(rateDeviationPpm = t.maxRateDeviationPpm + 1))
    @Test fun phaseMedianGuard() = assertRejectsAlone(SegueGuard.PHASE_MEDIAN, SegueMeasures(phaseMedianUs = t.phaseMedianMaxUs + 1))
    @Test fun phaseP95Guard() = assertRejectsAlone(SegueGuard.PHASE_P95, SegueMeasures(phaseP95Us = t.phaseP95MaxUs + 1))
    @Test fun unmeasuredPhaseIsNotAcceptedAsAverage() {
        assertTrue(SegueGuard.PHASE_MEDIAN in SegueGuards.crossed(SegueClass.LOCKED, SegueMeasures(phaseMedianUs = null), t))
        assertTrue(SegueGuard.PHASE_P95 in SegueGuards.crossed(SegueClass.LOCKED, SegueMeasures(phaseP95Us = null), t))
    }
    @Test fun driftGuard() = assertRejectsAlone(SegueGuard.DRIFT, SegueMeasures(driftBeats = t.driftMaxBeats + 0.01))
    @Test fun gridGuard() = assertRejectsAlone(SegueGuard.GRID_RELIABILITY, SegueMeasures(gridReliable = false))
    @Test fun voiceGuard() = assertRejectsAlone(SegueGuard.VOICE, SegueMeasures(voiceOverlapUs = t.voiceOverlapMaxUs + 1))
    @Test fun contentGuardShare() = assertRejectsAlone(SegueGuard.CONTENT_CUT, SegueMeasures(contentCutShare = t.contentCutMaxShare + 0.01))
    @Test fun contentGuardSeconds() = assertRejectsAlone(SegueGuard.CONTENT_CUT, SegueMeasures(contentCutUs = t.contentCutMaxUs + 1))
    @Test fun playedGuard() = assertRejectsAlone(SegueGuard.MIN_PLAYED, SegueMeasures(playedUs = t.minPlayedUs - 1))
    @Test fun loudnessGuard() = assertRejectsAlone(SegueGuard.LOUDNESS_JUMP, SegueMeasures(loudnessJumpDb = t.loudnessJumpMaxDb + 0.1), SegueClass.HARD)
    @Test fun albumGuard() = assertRejectsAlone(SegueGuard.ALBUM, SegueMeasures(albumContinuity = true))

    @Test fun aHighScoreCannotBuyAGuardBack() {
        // The best-scoring pair in every other respect, with two voices over the whole window.
        val perfect = decide(song(), song())
        assertEquals(SegueLevel.LOCKED_LONG, perfect.level)
        val voices = decide(song(risk = { 1f }), song(key = "b", risk = { 1f }))
        assertCause(voices, SegueLevel.LOCKED_LONG, SegueCause.GUARDS)
        assertTrue(voices.why, voices.why.contains("VOICE"))
        assertFalse(voices.level.cls == SegueClass.LOCKED)
    }

    @Test fun guardsAppliedOnlyToTheirClass() {
        assertEquals(emptyList<SegueGuard>(), SegueGuards.crossed(SegueClass.FILTERED, SegueMeasures(rateDeviationPpm = t.maxRateDeviationPpm + 1, gridReliable = false, phaseMedianUs = null), t))
        assertEquals(emptyList<SegueGuard>(), SegueGuards.crossed(SegueClass.HARD, SegueMeasures(voiceOverlapUs = t.voiceOverlapMaxUs + 1), t))
    }

    // ── Score ──

    @Test fun theScoreFollowsItsFormula() {
        val s = SegueScore(listOf(SegueNote("x", 2.0, 0.5, 0.5), SegueNote("y", 1.0, 1.0, 1.0)), listOf(SegueDefect("z", 0.5, 0.5)))
        assertEquals(2.0 * 0.5 * 0.5 + 1.0 - 0.25, s.total, 1e-12)
    }

    @Test fun anUncertainMeasureWeighsLessInsteadOfCountingAsAverage() {
        val trusted = SegueScore(listOf(SegueNote("x", 1.0, 1.0, 0.9)), emptyList()).total
        val unknown = SegueScore(listOf(SegueNote("x", 1.0, 0.0, 0.9)), emptyList()).total
        assertEquals(0.0, unknown, 1e-12)
        assertTrue(trusted > unknown)
    }

    @Test fun shapesOfTheTerms() {
        assertEquals(1.0, SegueScore.fallingFrom(5.0, 10.0, 20.0), 1e-12)
        assertEquals(0.5, SegueScore.fallingFrom(15.0, 10.0, 20.0), 1e-12)
        assertEquals(0.0, SegueScore.fallingFrom(25.0, 10.0, 20.0), 1e-12)
        assertEquals(0.0, SegueScore.risingFrom(5.0, 10.0, 20.0), 1e-12)
        assertEquals(1.0, SegueScore.gaussian(0.0, 3.0), 1e-12)
        assertTrue(SegueScore.gaussian(3.0, 3.0) < 0.7)
    }

    // ── Fallback: each level is reached by a synthetic situation, its cause recorded ──

    @Test fun level1LockedLong() {
        val d = decide(song("a"), song("b"))
        assertEquals(SegueLevel.LOCKED_LONG, d.level)
        val p = d.plan!!
        assertEquals(t.lockedLongBars, p.span.musicalUnits)
        assertEquals(32_000_000L, p.span.lengthUs)
        assertEquals(PulseState.LOCKED, p.pulse.state)
        assertTrue(d.attempts.isEmpty())
        assertEquals(120_000_000L, p.pulse.tempoMicroPerMinute)
    }

    @Test fun level2LockedShortWhenReliabilityShortensTheWindow() {
        val d = decide(song("a", metre = 0.7f), song("b", metre = 0.7f))
        assertEquals(SegueLevel.LOCKED_SHORT, d.level)
        assertEquals(t.lockedMiddleBars, d.plan!!.span.musicalUnits)
        assertCause(d, SegueLevel.LOCKED_LONG, SegueCause.LENGTH_NOT_ALLOWED)
    }

    @Test fun level3LockedOnADownbeatWhenNoPhraseBoundaryFits() {
        // Phrase boundaries fall at 176 s and 192 s; this checked grid lies strictly between them.
        val tail = Grid(178.0, 0.5, 26, 1.0, 0.0, 1.0)
        val d = decide(song("a", duration = 200.0, tail = tail), song("b"))
        assertEquals(d.why, SegueLevel.LOCKED_BEAT, d.level)
        assertCause(d, SegueLevel.LOCKED_LONG, SegueCause.NO_CANDIDATE)
        assertCause(d, SegueLevel.LOCKED_SHORT, SegueCause.NO_CANDIDATE)
        assertEquals(t.lockedShortBars, d.plan!!.span.musicalUnits)
    }

    @Test fun level3NeedsTheBassManaged() {
        val tail = Grid(178.0, 0.5, 26, 1.0, 0.0, 1.0)
        val d = decide(song("a", duration = 200.0, tail = tail), song("b"), eq = false)
        assertCause(d, SegueLevel.LOCKED_BEAT, SegueCause.BASS_NOT_MANAGED)
    }

    @Test fun level4FilteredWhenTemposAreOutOfReach() {
        val d = decide(song("a"), song("b", bpm = 100.0))
        assertEquals(d.why, SegueLevel.FILTERED, d.level)
        assertCause(d, SegueLevel.LOCKED_LONG, SegueCause.TEMPO_OUT_OF_REACH)
        val p = d.plan!!
        assertEquals(PulseState.FREE, p.pulse.state)
        assertEquals(SegueFormat.RATE_NOMINAL, p.incoming.ratePpm)
        assertTrue(p.span.lengthUs in t.filteredShortUs..t.filteredLongUs)
        assertTrue(p.lanes.any { it.target == Target.SWEEP_HZ && it.side == LaneSide.OUTGOING })
    }

    @Test fun level5HardWhenAnyOverlapWouldHaveTwoVoices() {
        val d = decide(song("a", risk = { 1f }), song("b", risk = { 1f }))
        assertEquals(d.why, SegueLevel.HARD, d.level)
        assertCause(d, SegueLevel.FILTERED, SegueCause.GUARDS)
        val p = d.plan!!
        assertEquals(t.microFadeUs, p.span.lengthUs)
        assertEquals(PulseState.LOOSE, p.pulse.state)
        assertEquals(2, p.lanes.size)
    }

    @Test fun hardNeedsCloseLoudness() {
        val d = decide(song("a", risk = { 1f }, barDb = -10f), song("b", risk = { 1f }, barDb = -20f))
        assertCause(d, SegueLevel.HARD, SegueCause.GUARDS)
        assertTrue(d.why, d.why.contains("LOUDNESS_JUMP"))
    }

    @Test fun level6SimpleFadeWithoutAnyGridOrBar() {
        val d = decide(song("a", grid = false, bars = false), song("b", grid = false, bars = false))
        assertEquals(d.why, SegueLevel.SIMPLE_FADE, d.level)
        assertCause(d, SegueLevel.LOCKED_LONG, SegueCause.NO_GRID)
        assertCause(d, SegueLevel.FILTERED, SegueCause.NO_CANDIDATE)
        assertCause(d, SegueLevel.HARD, SegueCause.NO_GRID)
        val p = d.plan!!
        assertEquals(PulseState.FREE, p.pulse.state)
        assertNull(p.outgoing.gridKey)
        assertEquals(t.simpleFadeUs, p.span.lengthUs)
    }

    @Test fun level7NormalPlaybackWithEveryCauseRecorded() {
        val d = decide(song("a", duration = 45.0), song("b"))
        assertEquals(SegueLevel.NORMAL, d.level)
        assertNull(d.plan)
        assertNull(SeguePolicy.timing(d))
        assertEquals(SegueLevel.values().toList().dropLast(1), d.attempts.map { it.level })
        assertTrue(d.why, d.why.contains("MIN_PLAYED"))
    }

    @Test fun albumContinuityIsLeftToPlayNatively() {
        val joined = decide(song("a"), song("b"), sameAlbum = true)
        assertEquals(SegueLevel.NORMAL, joined.level)
        assertTrue(joined.attempts.all { it.cause == SegueCause.ALBUM_CONTINUITY })
        // The same album with real silence at the junction is mixed.
        val gap = decide(song("a", contentEnd = 235.0), song("b"), sameAlbum = true)
        assertNotEquals(SegueLevel.NORMAL, gap.level)
    }

    // ── Placement ──

    @Test fun theWindowStaysWithinTheLastThirdAndBothSongs() {
        val d = decide(song("a"), song("b", bpm = 118.0))
        val p = d.plan!!
        assertTrue(p.outgoing.anchorUs >= 160_000_000)
        assertTrue(SegueValidator.windowEnd(p, p.outgoing) <= 240_000_000)
        assertEquals(emptyList<SegueViolation>(), SegueValidator.structural(p))
    }

    @Test fun anInstrumentalEndingIsPreferred() {
        // A's voice stops at bar 100 (200 s): leaving there beats leaving earlier over the voice.
        val d = decide(song("a", risk = { if (it < 100) 1f else 0f }), song("b"))
        assertEquals(SegueLevel.LOCKED_LONG, d.level)
        assertTrue(d.plan!!.toString(), d.plan!!.outgoing.anchorUs >= 192_000_000)
    }

    @Test fun nothingStartsBeforeTheEarliestExit() {
        val d = decide(song("a"), song("b"), earliest = 200_000_000)
        assertTrue(d.plan!!.outgoing.anchorUs >= 200_000_000)
    }

    @Test fun theRateIsAnExactIntegerAndLocksHalfTimeToo() {
        val p = decide(song("a"), song("b", bpm = 118.0)).plan!!
        assertEquals(Math.round(120.0 / 118.0 * 1e6), p.incoming.ratePpm)
        assertEquals(SegueFormat.RATE_NOMINAL, p.outgoing.ratePpm)
        assertEquals(SegueLevel.LOCKED_SHORT, decide(song("a"), song("b", bpm = 60.0)).level)
    }

    @Test fun listenerSettingsOnlyTighten() {
        val a = song("a"); val b = song("b", bpm = 118.0)
        assertEquals(SegueLevel.FILTERED, SeguePolicy.decide(SegueRequest(a, b, maxRatePpm = 1_000)).level)
        assertEquals(SegueLevel.LOCKED_LONG, SeguePolicy.decide(SegueRequest(a, b, maxRatePpm = 200_000)).level)
        assertEquals(SegueLevel.LOCKED_SHORT, SeguePolicy.decide(SegueRequest(a, b, maxBars = 8)).level)
    }

    @Test fun theListenerRateIsCarriedWithoutChangingTheAlignment() {
        val p = SeguePolicy.decide(SegueRequest(song("a"), song("b", bpm = 118.0), listenerRate = 1_250_000)).plan!!
        assertEquals(1_250_000L, p.outgoing.listenerRate)
        assertEquals(1_250_000L, p.incoming.listenerRate)
        assertEquals(32_000_000L, p.span.lengthUs)
    }

    @Test fun withoutTheEqOnlyLevelsAreWritten() {
        val p = decide(song("a"), song("b"), eq = false).plan!!
        assertTrue(p.lanes.all { it.target == Target.LEVEL })
    }

    // ── Lanes ──

    @Test fun lanesBoundPowerEndCleanAndHandTheBassOver() {
        for (d in listOf(decide(song("a"), song("b")), decide(song("a"), song("b", bpm = 100.0)), decide(song("a", grid = false, bars = false), song("b", grid = false, bars = false)))) {
            val p = d.plan!!
            val la = LaneCurve(p.lanes.first { it.side == LaneSide.OUTGOING && it.target == Target.LEVEL })
            val lb = LaneCurve(p.lanes.first { it.side == LaneSide.INCOMING && it.target == Target.LEVEL })
            assertEquals(0.0, la.value(0.0), 1e-3); assertTrue(lb.isNull(0.0))
            assertTrue(la.isNull(SegueFormat.T_MAX.toDouble())); assertEquals(0.0, lb.value(SegueFormat.T_MAX.toDouble()), 1e-3)
            for (i in 0..16) {
                val u = i * SegueFormat.T_MAX / 16.0
                val power = Math.pow(10.0, la.value(u) / 10) + Math.pow(10.0, lb.value(u) / 10)
                assertTrue("${d.level} at $i: $power", power <= 1.0 + 1e-3)
            }
        }
        val p = decide(song("a"), song("b")).plan!!
        val ba = LaneCurve(p.lanes.first { it.side == LaneSide.OUTGOING && it.target == Target.SHELF_BOTTOM })
        val bb = LaneCurve(p.lanes.first { it.side == LaneSide.INCOMING && it.target == Target.SHELF_BOTTOM })
        assertEquals(0.0, ba.value(0.0), 1e-9); assertEquals(t.bassKillDb, bb.value(0.0), 1e-9)
        assertEquals(t.bassKillDb, ba.value(SegueFormat.T_MAX.toDouble()), 1e-9); assertEquals(0.0, bb.value(SegueFormat.T_MAX.toDouble()), 1e-9)
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
        assertEquals(32.0, m.length, 1e-9)
        assertEquals(120.0 / 118.0, m.speedB, 1e-6)
        assertTrue(m.restoreSec > 0)
        assertEquals(p, m.segue)
        assertEquals(MixPlan.Style.CUT, SeguePolicy.timing(decide(song("a", risk = { 1f }), song("b", risk = { 1f })))!!.style)
        val filtered = SeguePolicy.timing(decide(song("a"), song("b", bpm = 100.0)))!!
        assertEquals(MixPlan.Style.FADE, filtered.style); assertTrue(filtered.filtered)
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
        val moved = song("a", tail = Grid(0.01, 0.5, 480, 1.0, 0.0, 1.0))
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
