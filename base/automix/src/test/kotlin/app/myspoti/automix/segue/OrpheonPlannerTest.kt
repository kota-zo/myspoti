/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.Grid
import app.myspoti.automix.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class OrpheonPlannerTest {
    private val t = SegueTuning(planner = SegueTuning.PLANNER_ORPHEON)

    /** A steady song: bars of four beats, a section every [sectionBars] bars, level -10 dB except where told. */
    private fun song(
        key: String,
        duration: Double = 200.0,
        bpm: Double = 120.0,
        genre: GenreFamily = GenreFamily.POP,
        sectionBars: Int = 16,
        firstSectionBars: Int = sectionBars,
        grid: Boolean = true,
        fade: SongRange? = null,
        silentEnd: Double = 0.0,
        silentStart: Double = 0.0,
        vocals: List<SongVocal> = emptyList(),
    ): SongMap {
        val beat = 60 / bpm
        val count = ((duration - silentEnd - silentStart) / beat).toInt()
        val beats = DoubleArray(count) { silentStart + it * beat }
        val downs = DoubleArray(count / 4) { beats[it * 4] }
        val sections = generateSequence(firstSectionBars) { it + sectionBars }.takeWhile { it < downs.size }.map { downs[it] }.toList().toDoubleArray()
        val curve = FloatArray((duration / 0.5).toInt()) { i ->
            val at = i * 0.5
            when {
                at < silentStart || at >= duration - silentEnd -> SongMap.FLOOR_DB.toFloat()
                fade != null && at >= fade.fromSec -> (-10.0 - 50.0 * ((at - fade.fromSec) / fade.lengthSec).coerceAtMost(1.0)).toFloat()
                else -> -10f
            }
        }
        return SongMap(duration, beats, downs, SongThirds(bpm, bpm, bpm), 4, sections, 0.5, curve, SongThirds(-10.0, -10.0, -10.0), vocals,
            fadeOut = fade, genre = genre, confidence = SongConfidence(grid = if (grid) 0.9 else 0.0, sections = 0.8), audioKey = key, gridKey = key)
    }

    private fun plan(a: SongMap, b: SongMap, only: String? = null, earliestUs: Long = 0, harmonic: Boolean = true, ideas: Set<MixIdea> = emptySet()) =
        OrpheonPolicy.plan(a, b, OrpheonRequest(earliestExitUs = earliestUs, harmonic = harmonic, tuning = t.copy(ideas = MixIdea.mask(ideas))), only)

    private fun SongMap.with(genre: GenreFamily = this.genre, danceability: Double? = this.danceability, loudnessCurve: FloatArray = this.loudnessCurve) =
        SongMap(durationSec, beatsSec, downbeatsSec, tempoBpm, beatsPerBar, sections, loudnessStepSec, loudnessCurve, loudness, vocals, key, fadeIn, fadeOut,
            genre, energy, danceability, acousticness, melodicness, confidence, audioKey, gridKey)

    private fun validated(a: SongMap, b: SongMap, p: SeguePlan) {
        val problems = SegueValidator.against(p, SegueSideFacts(a.audioKey, a.gridKey, (a.durationSec * 1e6).toLong()),
            SegueSideFacts(b.audioKey, b.gridKey, (b.durationSec * 1e6).toLong()))
        assertTrue(problems.toString(), problems.isEmpty())
        assertTrue(SegueDeck.playable(p))
    }

    private fun pairFor(style: OrpheonStyle): Pair<SongMap, SongMap> {
        val genre = style.genres.first()
        val bpm = if (style.id == "blend-long") 140.0 else 120.0
        return when (style.family) {
            OrpheonFamily.LOCKED -> song("a", genre = genre, bpm = bpm) to song("b", genre = genre, bpm = bpm * 1.03)
            OrpheonFamily.SMOOTH -> song("a", genre = genre, grid = false, fade = SongRange(165.0, 198.0), sectionBars = 8) to song("b")
            OrpheonFamily.SILENCE -> song("a", genre = genre, grid = false, silentEnd = 6.0) to song("b", grid = false)
            OrpheonFamily.FALLBACK -> song("a", genre = genre, grid = false) to song("b", grid = false)
        }
    }

    private fun lane(p: SeguePlan, side: LaneSide, target: Target) = p.lanes.firstOrNull { it.side == side && it.target == target }

    @Test fun everyStyleOfTheCatalogueGivesAPlanTheDeckCanPlay() {
        for (style in OrpheonStyles.CATALOGUE) {
            val (a, b) = pairFor(style)
            val o = plan(a, b, style.id)
            assertEquals(style.id, style, o.style)
            assertEquals(style.id, style.family, o.family)
            validated(a, b, o.decision.plan!!)
        }
    }

    @Test fun twoSteadySongsLockAndSitWhereTheyMay() {
        val a = song("a"); val b = song("b")
        val o = plan(a, b)
        assertEquals(OrpheonFamily.LOCKED, o.family)
        val p = o.decision.plan!!
        validated(a, b, p)
        assertEquals(PulseState.LOCKED, p.pulse.state)
        assertTrue(p.outgoing.anchorUs >= 140_000_000)
        assertTrue(p.incoming.anchorUs <= 50_000_000)
        assertTrue(o.survivors > 1)
    }

    @Test fun theOldPlannerStaysTheDefault() {
        fun song(key: String): TrackAnalysis {
            val g = Grid(0.0, 0.5, 400, 1.0, 0.0, 1.0)
            return TrackAnalysis(200.0, 0.0, 200.0, -10.0, 0, 1.0, 120.0, true, g, g, DoubleArray(400) { it * 0.5 }, DoubleArray(100) { it * 2.0 },
                FloatArray(100) { -10f }, FloatArray(100) { -16f }, FloatArray(100) { -20f }, FloatArray(100), FloatArray(100), 0, true, 0.0, 0.0,
                audioKey = key, headMetre = 1f, tailMetre = 1f)
        }
        val a = song("a"); val b = song("b")
        assertEquals("myspoti-adaptive-2", SeguePolicy.decide(SegueRequest(a, b)).plan!!.origin.engine)
        val d = SeguePolicy.decide(SegueRequest(a, b, tuning = t))
        assertEquals(OrpheonPolicy.ORIGIN, d.plan!!.origin)
        assertNotNull(d.style)
        assertNotNull(SeguePolicy.timing(d))
    }

    @Test fun theConverterFillsTheMapFromAnAnalysis() {
        val g = Grid(0.0, 0.5, 400, 1.0, 0.0, 1.0)
        val risk = FloatArray(100) { if (it in 10..19) 0.9f else 0f }
        val s = TrackAnalysis(200.0, 2.0, 198.0, -10.0, 21, 0.9, 120.0, true, g, g, DoubleArray(400) { it * 0.5 }, DoubleArray(100) { it * 2.0 },
            FloatArray(100) { -10f }, FloatArray(100) { -16f }, FloatArray(100) { -20f }, FloatArray(100), FloatArray(100), 0, true, 0.0, 0.0,
            barVocalRisk = risk, audioKey = "x", headMetre = 1f, tailMetre = 1f)
        val m = SongMaps.from(s, t, GenreFamily.DANCE)
        assertEquals(120.0, m.tempoBpm.main, 0.01)
        assertEquals(100, m.downbeatsSec.size)
        assertEquals(SongKey(9, true), m.key.main)
        assertEquals(1, m.vocals.size)
        assertEquals(20.0, m.vocals[0].fromSec, 1e-9)
        assertEquals(40.0, m.vocals[0].toSec, 1e-9)
        assertEquals(GenreFamily.DANCE, m.genre)
        assertNull("absent is unknown", m.energy)
        assertTrue(m.beatStable(100.0, 130.0))
        assertEquals(SongMap.FLOOR_DB, m.levelAt(1.0), 1e-9)
        assertEquals(-10.0, m.levelAt(100.0), 1e-9)
    }

    @Test fun irregularBeatsAreNotStable() {
        val beats = DoubleArray(100) { it * 0.5 + if (it % 2 == 0) 0.08 else -0.08 }
        val m = SongMap(50.0, beats, DoubleArray(0), SongThirds(120.0, 120.0, 120.0))
        assertFalse(m.beatStable(5.0, 30.0))
        val gap = DoubleArray(100) { it * 0.5 }.filterIndexed { i, _ -> i !in 20..23 }.toDoubleArray()
        assertFalse(SongMap(50.0, gap, DoubleArray(0), SongThirds(120.0, 120.0, 120.0)).beatStable(5.0, 30.0))
    }

    @Test fun bothSidesRampAndTheirBeatsCoincideOverTheWholeWindow() {
        val a = song("a", bpm = 120.0); val b = song("b", bpm = 126.0)
        val p = plan(a, b, "pop-delay").decision.plan!!
        val rampOut = p.outgoing.ramp!!; val rampIn = p.incoming.ramp!!
        assertEquals(1_000_000L, rampOut.startPpm)
        assertEquals(1.05, rampOut.endPpm / 1e6, 0.001)
        assertEquals(120.0 / 126.0, rampIn.startPpm / 1e6, 0.001)
        assertEquals(1_000_000L, rampIn.endPpm)
        assertEquals("the delay's ramp ends early", 750_000, rampOut.endT)
        val ma = SegueTimeMap(p.outgoing, p.span.lengthUs); val mb = SegueTimeMap(p.incoming, p.span.lengthUs)
        for (i in 0..50) {
            val e = p.span.lengthUs * i / 50.0
            val beatA = (ma.sourceUs(e) - p.outgoing.anchorUs) / 1e6 / (60.0 / 120.0)
            val beatB = (mb.sourceUs(e) - p.incoming.anchorUs) / 1e6 / (60.0 / 126.0)
            assertEquals("at $i", beatA, beatB, 0.002)
            assertEquals(e, ma.elapsedUs(ma.sourceUs(e)), 0.01)
            assertEquals(e, mb.elapsedUs(mb.sourceUs(e)), 0.01)
        }
        // Each side ends where the window says, in its own time.
        assertEquals(p.outgoing.anchorUs + 8_000_000.0, ma.sourceUs(p.span.lengthUs.toDouble()), 100.0)
        assertEquals(p.incoming.anchorUs + 8_000_000.0 * 120 / 126, mb.sourceUs(p.span.lengthUs.toDouble()), 100.0)
        assertEquals(SegueValidator.windowEnd(p, p.outgoing).toDouble(), ma.sourceUs(p.span.lengthUs.toDouble()), 2_000.0)
    }

    @Test fun aRampHoldsAfterItsEnd() {
        val side = SeguePlan.Side("a", null, 0, 1_037_500, ramp = SeguePlan.Ramp(1_000_000, 1_050_000, 500_000))
        val m = SegueTimeMap(side, 10_000_000)
        assertEquals(1.0, m.rate(0.0), 1e-9)
        assertEquals(1.025, m.rate(2_500_000.0), 1e-9)
        assertEquals(1.05, m.rate(5_000_000.0), 1e-9)
        assertEquals(1.05, m.rate(8_000_000.0), 1e-9)
        assertEquals(1.0375 * 10_000_000, m.sourceUs(10_000_000.0), 0.01)
        assertEquals(-1_000_000.0, m.sourceUs(-1_000_000.0), 0.01)
    }

    @Test fun aRampIsValidatedAndSurvivesTheCodec() {
        val a = song("a"); val b = song("b", bpm = 124.0)
        val p = plan(a, b, "pop-delay").decision.plan!!
        val back = SegueCodec.decode(SegueCodec.encode(p))
        assertEquals(p, back)
        val broken = p.copy(outgoing = p.outgoing.copy(ramp = p.outgoing.ramp!!.copy(endT = 0)))
        assertTrue(SegueViolation.RAMP_INVALID in SegueValidator.structural(broken))
        val drift = p.copy(outgoing = p.outgoing.copy(ratePpm = p.outgoing.ratePpm + 50_000))
        assertTrue(SegueViolation.RAMP_INVALID in SegueValidator.structural(drift))
    }

    @Test fun equalTempiNeedNoRamp() {
        val a = song("a"); val b = song("b")
        val p = plan(a, b, "pop-delay").decision.plan!!
        assertNull(p.outgoing.ramp); assertNull(p.incoming.ramp)
        assertEquals(SegueFormat.RATE_NOMINAL, p.outgoing.ratePpm)
    }

    @Test fun halfAndDoubleTimeLock() {
        val half = plan(song("a", bpm = 160.0, genre = GenreFamily.HIPHOP), song("b", bpm = 80.0, genre = GenreFamily.HIPHOP), "hiphop-delay")
        assertEquals(OrpheonFamily.LOCKED, half.family)
        val p = half.decision.plan!!
        // Four bars of 1.5 s against two of 3 s.
        assertEquals(6_000_000L, p.span.lengthUs)
        val double = plan(song("a", bpm = 80.0, genre = GenreFamily.HIPHOP), song("b", bpm = 160.0, genre = GenreFamily.HIPHOP), "hiphop-delay")
        assertEquals(OrpheonFamily.LOCKED, double.family)
        assertEquals(6_000_000L, double.decision.plan!!.span.lengthUs)
    }

    @Test fun tempiOutOfReachAreNotLocked() {
        val o = plan(song("a", bpm = 120.0), song("b", bpm = 162.0))
        assertTrue(o.family != OrpheonFamily.LOCKED)
        assertTrue(o.decision.why, o.decision.attempts.any { it.cause == SegueCause.TEMPO_OUT_OF_REACH })
    }

    @Test fun aLongBlendReachesAWiderGapThanAShortStyle() {
        // A gap of 29 %: a filtered blend may, a short style may not.
        assertEquals(OrpheonFamily.LOCKED, plan(song("a"), song("b", bpm = 154.8), "pop-filter").family)
        assertNull(plan(song("a"), song("b", bpm = 154.8), "pop-delay").style)
    }

    @Test fun differentBarLengthsAreNotLocked() {
        val a = song("a")
        val waltz = song("w").let { SongMap(it.durationSec, it.beatsSec, it.downbeatsSec, it.tempoBpm, 3, it.sections, it.loudnessStepSec, it.loudnessCurve, it.loudness,
            genre = it.genre, confidence = it.confidence, audioKey = "w", gridKey = "w") }
        assertTrue(plan(a, waltz).family != OrpheonFamily.LOCKED)
    }

    @Test fun anUnstableSpanIsNotLocked() {
        val s = song("a")
        val shaky = SongMap(s.durationSec, DoubleArray(s.beatsSec.size) { s.beatsSec[it] + if (it % 2 == 0) 0.07 else -0.07 }, s.downbeatsSec, s.tempoBpm, 4, s.sections, s.loudnessStepSec,
            s.loudnessCurve, s.loudness, genre = s.genre, confidence = s.confidence, audioKey = "a", gridKey = "a")
        assertTrue(plan(shaky, song("b")).family != OrpheonFamily.LOCKED)
    }

    @Test fun eachStyleSoundsAsItsGesture() {
        fun of(id: String) = OrpheonStyles.byId(id)!!.let { s -> pairFor(s).let { (a, b) -> plan(a, b, id).decision.plan!! } }
        val delay = of("hiphop-delay")
        assertNotNull(lane(delay, LaneSide.OUTGOING, Target.SEND_ECHO))
        assertNotNull(lane(delay, LaneSide.OUTGOING, Target.RISE_HZ))
        // Three quarters of a beat at 120 BPM.
        assertEquals(375_000L, delay.echoUs)
        // The incoming song comes up under the outgoing one, then leads from one bar before the end, where the outgoing one is cut.
        val inLevel = LaneCurve(lane(delay, LaneSide.INCOMING, Target.LEVEL)!!)
        val n = SegueTuning().orpheon
        assertTrue(inLevel.value(0.1 * SegueFormat.T_MAX) < inLevel.value(0.3 * SegueFormat.T_MAX))
        assertTrue(inLevel.value(0.3 * SegueFormat.T_MAX) in (n.arriveUnderDb + n.arriveRiseDb)..n.arriveUnderDb)
        assertEquals(0.0, inLevel.value(0.8 * SegueFormat.T_MAX), 1e-6)
        assertTrue(LaneCurve(lane(delay, LaneSide.OUTGOING, Target.LEVEL)!!).isNull(0.8 * SegueFormat.T_MAX))

        val rush = of("dance-rush")
        assertEquals(3, rush.gestures.size)
        assertTrue(rush.gestures.all { it.kind == GestureKind.LOOP })
        assertTrue(rush.gestures[0].units > rush.gestures[1].units && rush.gestures[1].units > rush.gestures[2].units)
        assertNotNull(lane(rush, LaneSide.OUTGOING, Target.SWEEP_HZ))

        val reverb = of("pop-reverb")
        assertNotNull(lane(reverb, LaneSide.OUTGOING, Target.SEND_ROOM))
        assertTrue(SegueDeck.tailUs(reverb) > 0)

        val long = of("blend-long")
        val sweep = LaneCurve(lane(long, LaneSide.OUTGOING, Target.SWEEP_HZ)!!)
        assertEquals("the long blend waits for the middle half", SegueFormat.SWEEP_OPEN_HZ, sweep.value(0.2 * SegueFormat.T_MAX), 1e-6)
        assertEquals(SegueTuning().orpheon.blendCloseHz, sweep.value(0.75 * SegueFormat.T_MAX), 1.0)
        // The long blend's outgoing song is gone at three quarters.
        assertEquals(SegueFormat.LEVEL_FLOOR_DB, LaneCurve(lane(long, LaneSide.OUTGOING, Target.LEVEL)!!).value(0.8 * SegueFormat.T_MAX), 1e-6)
        val short = LaneCurve(lane(of("blend"), LaneSide.OUTGOING, Target.SWEEP_HZ)!!)
        // Most of the closing is late: half way through, less than a third of the octaves have gone.
        assertTrue(short.value(0.5 * SegueFormat.T_MAX) >= 5_000)

        val join = of("dance-join")
        val joinLevel = LaneCurve(lane(join, LaneSide.OUTGOING, Target.LEVEL)!!)
        assertEquals(0.0, joinLevel.value(0.5 * SegueFormat.T_MAX), 1e-6)
    }

    @Test fun aFadingEndBlendsSmoothlyFromASection() {
        val a = song("a", grid = false, fade = SongRange(165.0, 198.0), sectionBars = 8)
        val o = plan(a, song("b"))
        assertEquals(OrpheonFamily.SMOOTH, o.family)
        val p = o.decision.plan!!
        assertEquals(5_000_000L, p.span.lengthUs)
        assertEquals(SegueFormat.RATE_NOMINAL, p.outgoing.ratePpm)
        assertEquals(PulseState.FREE, p.pulse.state)
        assertTrue("on a section", a.sections.any { abs(it * 1e6 - p.outgoing.anchorUs) < 1.0 })
        assertEquals(0L, p.incoming.anchorUs % 2_000_000)
        validated(a, song("b"), p)
    }

    @Test fun aSilentEndIsButtedAgainstTheNextSong() {
        val a = song("a", grid = false, silentEnd = 6.0)
        val b = song("b", grid = false)
        val o = plan(a, b)
        assertEquals(OrpheonFamily.SILENCE, o.family)
        val p = o.decision.plan!!
        // The six silent seconds and the lead before them.
        assertEquals(6_750_000L, p.span.lengthUs)
        assertEquals(0L, p.incoming.anchorUs)
        assertEquals(SegueFormat.RATE_NOMINAL, p.incoming.ratePpm)
        assertTrue("levels only", p.lanes.all { it.target == Target.LEVEL })
        assertEquals("up to the song's end", a.durationSec - 6.75, p.outgoing.anchorUs / 1e6, 0.01)
    }

    @Test fun aSilentStartOfTheNextSongIsRemovedToo() {
        val o = plan(song("a", grid = false, silentEnd = 2.0), song("b", grid = false, silentStart = 5.0))
        assertEquals(OrpheonFamily.SILENCE, o.family)
        val p = o.decision.plan!!
        assertEquals(0L, p.incoming.anchorUs)
        assertTrue(p.span.lengthUs in 1_000_000L..9_000_000L)
        // After a song loud to its end there is nothing to butt against: the fade, entering past the silence.
        val faded = plan(song("a", grid = false), song("b", grid = false, silentStart = 5.0))
        assertEquals(OrpheonFamily.FALLBACK, faded.family)
        assertEquals(4.8, faded.decision.plan!!.incoming.anchorUs / 1e6, 0.01)
    }

    @Test fun songsLoudToTheJunctionAreFadedNotButted() {
        // Build 3127 by ear: joining two songs with no silence between them was a cut, the plain fade won.
        val loud = plan(song("a", grid = false), song("b", grid = false))
        assertEquals(OrpheonFamily.FALLBACK, loud.family)
        assertTrue(loud.decision.attempts.any { it.cause == SegueCause.GUARDS && it.detail == "no dead air" })
        assertEquals(OrpheonFamily.SILENCE, plan(song("a", grid = false, silentEnd = 2.0), song("b", grid = false)).family)
    }

    @Test fun aSongWithAnIntroIsHandedTheLeadOnItsDrop() {
        // Eight quiet bars, then the song for good: the intro belongs under the outgoing song, the hand-over on the drop.
        val a = song("a", genre = GenreFamily.HIPHOP)
        val plainB = song("b", genre = GenreFamily.HIPHOP)
        val drop = plainB.downbeatsSec[8]
        val b = plainB.with(loudnessCurve = FloatArray(plainB.loudnessCurve.size) { i -> if (i * 0.5 < drop) -26f else -10f })
        for (style in listOf("hiphop-delay", "hiphop-loop", "hiphop-filter")) {
            val o = plan(a, b, style)
            val p = o.decision.plan!!
            val bars = p.span.musicalUnits!!
            val hand = p.incoming.anchorUs / 1e6 + o.style!!.handover(bars) * 2.0
            assertEquals(style, drop, hand, 0.01)
        }
        // Left alone in its intro, the song would sag: such a place scores far lower than the drop.
        val t0 = SegueTuning().orpheon
        assertTrue(t0.costWeakHandover > t0.costNearDrop)
    }

    @Test fun theOutgoingSongIsNotCutInTheMiddleOfALine() {
        val a = song("a", genre = GenreFamily.HIPHOP)
        val free = plan(a, song("b", genre = GenreFamily.HIPHOP), "hiphop-loop").decision.plan!!
        val leave = (free.outgoing.anchorUs + free.span.lengthUs) / 1e6
        val sung = SongMap(a.durationSec, a.beatsSec, a.downbeatsSec, a.tempoBpm, a.beatsPerBar, a.sections, a.loudnessStepSec, a.loudnessCurve, a.loudness,
            listOf(SongVocal(leave - 3.0, leave + 3.0, 1.0)), a.key, a.fadeIn, a.fadeOut, a.genre, a.energy, a.danceability, a.acousticness, a.melodicness,
            a.confidence, a.audioKey, a.gridKey)
        val moved = plan(sung, song("b", genre = GenreFamily.HIPHOP), "hiphop-loop").decision.plan!!
        val left = (moved.outgoing.anchorUs + moved.span.lengthUs) / 1e6
        assertTrue("leaves at $left, the line runs ${leave - 3}..${leave + 3}", left <= leave - 3.0 + 0.5 || left >= leave + 3.0 - 0.5)
    }

    @Test fun withNothingElseThereIsAlwaysAPlainFade() {
        val a = song("a", grid = false); val b = song("b", grid = false)
        // Too late for the silence join: only the fade still has room.
        val o = plan(a, b, earliestUs = 198_000_000)
        assertEquals(OrpheonFamily.FALLBACK, o.family)
        val p = o.decision.plan!!
        validated(a, b, p)
        assertEquals(setOf(Target.LEVEL), p.lanes.map { it.target }.toSet())
        // The middle of an equal-power fade.
        val c = LaneCurve(lane(p, LaneSide.OUTGOING, Target.LEVEL)!!)
        assertEquals(-3.0, c.value(SegueFormat.T_MAX / 2.0), 0.3)
    }

    @Test fun theSafetyMarginIsKeptAndGivesWayOnlyToTheFallback() {
        val a = song("a"); val b = song("b")
        val at = 170_000_000L
        val p = plan(a, b, earliestUs = at).decision.plan!!
        assertTrue(p.outgoing.anchorUs >= at + (SegueTuning().orpheon.safetySec * 1e6).toLong())
        val late = 198_000_000L
        val o = plan(a, b, earliestUs = late)
        assertEquals(OrpheonFamily.FALLBACK, o.family)
        assertTrue(o.decision.plan!!.outgoing.anchorUs >= late)
    }

    @Test fun aMissedStartIsCancelled() {
        val p = plan(song("a"), song("b")).decision.plan!!
        assertFalse(SeguePolicy.cueMissed(p, p.outgoing.anchorUs - 1))
        assertTrue(SeguePolicy.cueMissed(p, p.outgoing.anchorUs + 1))
    }

    @Test fun theDrawIsDecidedByThePairAlone() {
        val a = song("a"); val b = song("b")
        val one = plan(a, b).decision
        val two = plan(song("a"), song("b")).decision
        assertEquals(one.fingerprint, two.fingerprint)
        assertEquals(one.style, two.style)
        val drawn = (0 until 24).map { plan(song("a$it"), song("b$it")).decision.fingerprint }.toSet()
        assertTrue("different pairs draw differently", drawn.size > 1)
    }

    @Test fun aLeadVocalAgainstAnotherIsACostNotAVeto() {
        val sung = listOf(SongVocal(0.0, 200.0, 0.9, VocalKind.SUNG))
        val clean = plan(song("a"), song("b")).decision.score!!
        val clash = plan(song("a", vocals = sung), song("b", vocals = sung))
        assertEquals(OrpheonFamily.LOCKED, clash.family)
        assertTrue(clash.decision.score!! < clean)
    }

    @Test fun anEntryKeepsClearOfTheFirstBars() {
        // The first section ends at bar 12: four bars counted back from it would start at bar 8.
        for (seed in 0 until 12) {
            val o = plan(song("a$seed"), song("b$seed", firstSectionBars = 12), "pop-delay")
            assertTrue(o.decision.plan!!.incoming.anchorUs != 16_000_000L)
        }
    }

    @Test fun aLateEntryNeedsAClearBoundary() {
        val b = song("b", firstSectionBars = 16)
        val vague = SongMap(b.durationSec, b.beatsSec, b.downbeatsSec, b.tempoBpm, 4, b.sections, b.loudnessStepSec, b.loudnessCurve, b.loudness,
            genre = b.genre, confidence = SongConfidence(grid = 0.9, sections = 0.2), audioKey = "b", gridKey = "b")
        for (seed in 0 until 12) {
            val p = plan(song("a$seed"), vague).decision.plan!!
            assertTrue(p.incoming.anchorUs <= 20_000_000)
        }
    }

    @Test fun nothingClipsOnARenderedPair() {
        for (ideas in listOf(emptySet(), MixIdea.values().toSet())) for (style in OrpheonStyles.CATALOGUE) {
            val (a, b) = pairFor(style)
            val p = plan(a, b, style.id, ideas = ideas).decision.plan!!
            validated(a, b, p)
            val peak = renderPeak(p)
            assertTrue("${style.id} with ${ideas.size} ideas: $peak", peak < 1.0f)
        }
    }

    // ── Where a locked window sits ──

    @Test fun aWindowEndsOnTheLastBarLineOfTheBody() {
        // The level falls from 180 s on: 15 dB under the body is reached six seconds later.
        val a = song("a", genre = GenreFamily.HIPHOP, fade = SongRange(180.0, 200.0))
        for (seed in 0 until 8) {
            val p = plan(a, song("b$seed", genre = GenreFamily.HIPHOP)).decision.plan!!
            val end = (p.outgoing.anchorUs + SegueTimeMap(p.outgoing, p.span.lengthUs).let { it.sourceUs(p.span.lengthUs.toDouble()) - p.outgoing.anchorUs }) / 1e6
            assertTrue("$end", end <= 186.6 && end > 178.0)
        }
    }

    @Test fun theIncomingSongTakesTheLeadOnAPhrase() {
        // 120 BPM: bars of 2 s, phrases of eight bars.
        fun entry(id: String, seed: Int) = plan(song("a$seed", genre = OrpheonStyles.byId(id)!!.genres.first()), song("b$seed", genre = OrpheonStyles.byId(id)!!.genres.first()), id)
            .let { o -> o.decision.plan!!.let { Triple(it.incoming.anchorUs / 1e6, it.span.musicalUnits!!, o.style!!) } }
        for (seed in 0 until 6) for (id in listOf("hiphop-loop", "hiphop-delay", "hiphop-filter", "hiphop-long", "pop-reverb")) {
            val (at, bars, style) = entry(id, seed)
            val arrival = at + style.arrive.bars(bars) * 2.0
            assertEquals("$id enters at $at over $bars bars", 0.0, arrival % 16.0, 1e-6)
            if (style.arrive != Arrival.START) assertTrue("$id has no run-up", at > 0.0)
        }
    }

    @Test fun aFamilysStylesPlayBetweenTwoSongsOfIt() {
        for (seed in 0 until 8) {
            val same = plan(song("a$seed", genre = GenreFamily.HIPHOP), song("b$seed", genre = GenreFamily.HIPHOP))
            assertTrue(same.style!!.id, GenreFamily.HIPHOP in same.style!!.genres)
            val mixed = plan(song("a$seed", genre = GenreFamily.HIPHOP).with(danceability = 0.9), song("b$seed", genre = GenreFamily.DANCE))
            assertEquals(OrpheonFamily.LOCKED, mixed.family)
            assertTrue(mixed.style!!.id, GenreFamily.OTHER in mixed.style!!.genres)
        }
    }

    @Test fun songsOneCannotDanceToPlayToTheirEnd() {
        val a = song("a", genre = GenreFamily.HIPHOP); val b = song("b", genre = GenreFamily.HIPHOP)
        assertEquals(OrpheonFamily.LOCKED, plan(a.with(danceability = 0.5), b.with(danceability = 0.5)).family)
        // Loud to their last second, these songs have no silence to remove: the plain fade.
        assertEquals(OrpheonFamily.FALLBACK, plan(a.with(danceability = 0.5), b.with(danceability = 0.2)).family)
        // Any other pair must be plainly danceable; unknown, a song is what its family usually is.
        assertEquals(OrpheonFamily.FALLBACK, plan(a.with(GenreFamily.POP, 0.5), b.with(GenreFamily.DANCE, 0.9)).family)
        assertEquals(OrpheonFamily.LOCKED, plan(a.with(GenreFamily.POP, 0.8), b.with(GenreFamily.DANCE, 0.9)).family)
        assertEquals(OrpheonFamily.FALLBACK, plan(a.with(GenreFamily.OTHER), b.with(GenreFamily.DANCE)).family)
        assertEquals(OrpheonFamily.LOCKED, plan(a.with(GenreFamily.DANCE), b.with(GenreFamily.DANCE)).family)
    }

    // ── Ideas ──

    @Test fun withoutIdeasAStyleKeepsToItsOwnLanes() {
        val (a, b) = pairFor(OrpheonStyles.byId("blend")!!)
        val p = plan(a, b, "blend").decision.plan!!
        assertNull(lane(p, LaneSide.INCOMING, Target.BASS_BAND))
        assertNull(lane(p, LaneSide.OUTGOING, Target.SEND_ECHO))
        assertNull(lane(p, LaneSide.OUTGOING, Target.BAND_CORE))
        assertEquals(0L, SegueDeck.tailUs(p))
    }

    @Test fun theBassWaitsUntilTheOutgoingSongIsGone() {
        val (a, b) = pairFor(OrpheonStyles.byId("blend")!!)
        val bass = LaneCurve(lane(plan(a, b, "blend", ideas = setOf(MixIdea.BASS_HANDOVER)).decision.plan!!, LaneSide.INCOMING, Target.BASS_BAND)!!)
        assertEquals(SegueTuning().compact.bassKillDb, bass.value(0.9 * SegueFormat.T_MAX), 1e-6)
        assertEquals(0.0, bass.value(1.0 * SegueFormat.T_MAX), 1e-6)
        // A long blend's outgoing song is cut at three quarters: the bass changes hands there.
        val (c, d) = pairFor(OrpheonStyles.byId("blend-long")!!)
        val long = LaneCurve(lane(plan(c, d, "blend-long", ideas = setOf(MixIdea.BASS_HANDOVER)).decision.plan!!, LaneSide.INCOMING, Target.BASS_BAND)!!)
        assertEquals(0.0, long.value(0.76 * SegueFormat.T_MAX), 1e-6)
        // A plain fade swaps the two low ends in its middle.
        val (e, f) = pairFor(OrpheonStyles.byId("fade")!!)
        val fade = plan(e, f, "fade", ideas = setOf(MixIdea.BASS_HANDOVER)).decision.plan!!
        assertEquals(SegueTuning().compact.bassKillDb, LaneCurve(lane(fade, LaneSide.OUTGOING, Target.BASS_BAND)!!).value(0.6 * SegueFormat.T_MAX), 1e-6)
    }

    @Test fun theOutgoingSongLeavesOnAnEcho() {
        val (a, b) = pairFor(OrpheonStyles.byId("blend")!!)
        val p = plan(a, b, "blend", ideas = setOf(MixIdea.ECHO_TAIL)).decision.plan!!
        val send = LaneCurve(lane(p, LaneSide.OUTGOING, Target.SEND_ECHO)!!)
        assertEquals(0.0, send.value(0.5 * SegueFormat.T_MAX), 1e-6)
        assertTrue(send.value(0.99 * SegueFormat.T_MAX) > 0.3)
        assertEquals(375_000L, p.echoUs)
        assertTrue(SegueDeck.tailUs(p) > 0)
        // A style with a tail of its own keeps it.
        val (c, d) = pairFor(OrpheonStyles.byId("pop-reverb")!!)
        assertNull(lane(plan(c, d, "pop-reverb", ideas = setOf(MixIdea.ECHO_TAIL)).decision.plan!!, LaneSide.OUTGOING, Target.SEND_ECHO))
    }

    @Test fun twoVoicesAreKeptApartAndRoomIsMadeWhereTheyMeet() {
        // Leads, but not so strong that the pair is too unsure to lock at all (it would then be faded).
        val sung = listOf(SongVocal(0.0, 200.0, 0.6, VocalKind.SUNG))
        val a = song("a", vocals = sung); val b = song("b", vocals = sung)
        val plain = plan(a, b)
        val clear = plan(a, b, ideas = setOf(MixIdea.VOCAL_CLEAR))
        assertTrue(clear.decision.score!! < plain.decision.score!!)
        assertNull(lane(plain.decision.plan!!, LaneSide.OUTGOING, Target.BAND_CORE))
        val carve = LaneCurve(lane(clear.decision.plan!!, LaneSide.OUTGOING, Target.BAND_CORE)!!)
        assertEquals(SegueTuning().orpheon.vocalCarveDb, carve.value(0.9 * SegueFormat.T_MAX), 1e-6)
        // Nothing to carve when only one of them sings.
        assertNull(lane(plan(song("a"), b, ideas = setOf(MixIdea.VOCAL_CLEAR)).decision.plan!!, LaneSide.OUTGOING, Target.BAND_CORE))
    }

    @Test fun aJoinLandsTheFirstDownbeatOnTheOutgoingBeat() {
        // The outgoing song ends 6.3 s before its file does; the incoming one's music starts 0.4 s in.
        val a = song("a", silentEnd = 6.3); val b = song("b", silentStart = 0.4)
        fun lands(ideas: Set<MixIdea>): Double {
            val p = plan(a, b, "silence-join", ideas = ideas).decision.plan!!
            return ((p.outgoing.anchorUs / 1e6 + b.downbeatsSec.first()) - a.beatsSec.last()) / 0.5
        }
        val snapped = lands(setOf(MixIdea.PHRASED_FADE))
        assertEquals(0.0, snapped - Math.rint(snapped), 1e-3)
        val free = lands(emptySet())
        assertTrue(abs(free - Math.rint(free)) > 0.01)
    }

    @Test fun gentleTempoLeavesDistantSongsUnlocked() {
        val a = song("a", genre = GenreFamily.HIPHOP, bpm = 120.0); val b = song("b", genre = GenreFamily.HIPHOP, bpm = 138.0)
        assertEquals(OrpheonFamily.LOCKED, plan(a, b).family)
        assertEquals(OrpheonFamily.FALLBACK, plan(a, b, ideas = setOf(MixIdea.GENTLE_TEMPO)).family)
        assertEquals(OrpheonFamily.LOCKED, plan(a, song("c", genre = GenreFamily.HIPHOP, bpm = 126.0), ideas = setOf(MixIdea.GENTLE_TEMPO)).family)
    }

    @Test fun aQuietOpeningIsBlendedIntoMoreOften() {
        fun blends(ideas: Set<MixIdea>) = (0 until 40).count { seed ->
            val b = song("b$seed", genre = GenreFamily.HIPHOP)
            // Its first phrase 12 dB under the rest.
            val quiet = b.with(loudnessCurve = FloatArray(b.loudnessCurve.size) { if (it * 0.5 < 16.0) -22f else -10f })
            plan(song("a$seed", genre = GenreFamily.HIPHOP), quiet, ideas = ideas).style!!.sound == StyleSound.FILTERED
        }
        assertTrue(blends(setOf(MixIdea.ENERGY_STYLE)) > blends(emptySet()))
    }

    @Test fun ideasAreBitsOfTheTuning() {
        assertEquals(MixIdea.values().toSet(), MixIdea.of(MixIdea.mask(MixIdea.values().toSet())))
        assertEquals(setOf(MixIdea.ECHO_TAIL), MixIdea.of(SegueCorpus.withValue(SegueTuning(), "ideas", MixIdea.ECHO_TAIL.bit.toDouble()).ideas))
        assertEquals(MixIdea.VOCAL_CLEAR, MixIdea.parse("vocal-clear"))
        assertEquals(0, SegueTuning().ideas)
    }

    /** Two loud tones through both decks, each in its own time, summed in the time the listener hears. */
    private fun renderPeak(p: SeguePlan): Float {
        val rate = 22_050
        val length = p.span.lengthUs / 1e6
        val pre = 1.0; val post = 4.0
        fun side(which: LaneSide, hz: Double): Pair<FloatArray, SegueTimeMap> {
            val s = p.side(which)
            val map = SegueTimeMap(s, p.span.lengthUs)
            val fromSec = max(0.0, s.anchorUs / 1e6 - pre)
            val frames = ((pre + length * 1.2 + post) * rate).toInt()
            val x = FloatArray(frames * 2) { i -> (0.97 * sin(2 * PI * hz * (i / 2) / rate)).toFloat() }
            SegueDeck(p, which, rate, 2).process(x, frames, (fromSec * 1e6).toLong())
            return x to map
        }
        val (oa, ma) = side(LaneSide.OUTGOING, 220.0)
        val (ob, mb) = side(LaneSide.INCOMING, 277.0)
        val fromA = max(0.0, p.outgoing.anchorUs / 1e6 - pre)
        val fromB = max(0.0, p.incoming.anchorUs / 1e6 - pre)
        var peak = 0f
        for (i in 0 until ((length + post) * rate).toInt()) {
            val e = i * 1e6 / rate
            val ia = ((ma.sourceUs(e) / 1e6 - fromA) * rate).toInt()
            val ib = ((mb.sourceUs(e) / 1e6 - fromB) * rate).toInt()
            for (c in 0..1) {
                val va = oa.getOrElse(ia * 2 + c) { 0f }
                val vb = ob.getOrElse(ib * 2 + c) { 0f }
                peak = maxOf(peak, abs(va + vb))
            }
        }
        return peak
    }

    @Test fun theDeckGoesRoundASliceAndNeverLeavesItsPlace() {
        val rate = 44_100
        val p = SeguePlan(SeguePlan.Origin("t", "t"), SeguePlan.Side("a", null, 500_000, SegueFormat.RATE_NOMINAL), SeguePlan.Side("b", null, 0, SegueFormat.RATE_NOMINAL),
            SeguePlan.Span(2_000_000, null, UnitKind.BAR, Clock.ELAPSED), SeguePlan.Pulse(PulseState.FREE, null, null), emptyList(),
            listOf(SeguePlan.Gesture(GestureKind.LOOP, 1_000_000, 100_000), SeguePlan.Gesture(GestureKind.LOOP, 1_500_000, 50_000)))
        assertTrue(SegueDeck.playable(p))
        val x = FloatArray(2 * rate) { it / (2f * rate) }
        val out = x.copyOf()
        SegueDeck(p, LaneSide.OUTGOING, rate, 1).process(out, out.size, 0)
        // Before and during the first pass the song is as it was.
        assertEquals(x[rate - 100], out[rate - 100], 0f)
        assertEquals(x[rate + 2_000], out[rate + 2_000], 0f)
        // Second pass (0.1 s further): the slice again, as it was at the same place.
        assertEquals(x[rate + 2_000], out[rate + 2_000 + 4_410], 0f)
        // The shorter loop goes round the start of the same slice.
        val second = rate * 3 / 2
        assertEquals(x[rate + 500], out[second + 2_205 + 500], 0f)
        assertFalse(SegueDeck.playable(p.copy(gestures = listOf(SeguePlan.Gesture(GestureKind.REPEAT, 1_000_000, 4)))))
    }

    @Test fun theTailOfASendStaysUnderTheCeiling() {
        val style = OrpheonStyles.byId("pop-reverb")!!
        val (a, b) = pairFor(style)
        val p = plan(a, b, style.id).decision.plan!!
        val headroom = SegueTuning().headroom
        val rate = 22_050
        val lengthUs = p.span.lengthUs
        val start = p.outgoing.anchorUs + lengthUs + 200_000
        val tail = FloatArray(rate) { 0.9f }
        SegueDeck(p, LaneSide.OUTGOING, rate, 1).process(tail, rate, start)
        assertTrue(tail.maxOf { abs(it) } <= headroom.tailPeak + 1e-3f)
    }

    @Test fun theBenchComparesPlansWithReferences() {
        val dir = Files.createTempDirectory("orpheon-bench").toFile()
        try {
            val maps = java.io.File(dir, "maps").also { it.mkdirs() }
            fun write(id: String, s: SongMap) {
                val text = buildString {
                    append("{\"durationSec\":${s.durationSec},\"beatsSec\":${s.beatsSec.toList()},\"downbeatsSec\":${s.downbeatsSec.toList()},")
                    append("\"tempoBpm\":{\"start\":120,\"main\":120,\"end\":120},\"beatsPerBar\":4,\"sections\":${s.sections.toList()},\"loudnessStepSec\":0.5,")
                    append("\"loudnessCurve\":${s.loudnessCurve.toList()},\"loudness\":{\"start\":-10,\"main\":-10,\"end\":-1e1},\"genre\":\"pop\",")
                    append("\"confidence\":{\"grid\":0.9,\"sections\":0.8}}")
                }
                java.io.File(maps, "$id.json").writeText(text)
            }
            val a = song("a"); val b = song("b")
            write("a", a); write("b", b)
            val p = plan(a, b).decision.plan!!
            val out = p.outgoing.anchorUs / 1e6; val inn = p.incoming.anchorUs / 1e6; val len = p.span.lengthUs / 1e6
            java.io.File(dir, "plans.jsonl").writeText(
                "{\"a\":\"a\",\"b\":\"b\",\"family\":\"locked\",\"style\":\"x\",\"duration\":$len,\"out_start\":$out,\"out_end\":${out + len},\"in_start\":$inn,\"in_end\":${inn + len},\"rate_out\":1,\"rate_in\":1}\n" +
                    "{\"a\":\"a\",\"b\":\"nope\",\"family\":\"silence\",\"style\":\"x\",\"duration\":3,\"out_start\":0,\"out_end\":3,\"in_start\":0,\"in_end\":3,\"rate_out\":1,\"rate_in\":1}\n")
            val report = OrpheonBench.measure(dir, t)
            assertEquals(1, report.rows.size)
            assertEquals(1, report.missing)
            assertEquals(1.0, report.familyAgreement, 1e-9)
            assertEquals(1.0, report.withinBarShare, 1e-9)
            assertEquals(0.0, report.rows[0].outSec!!, 1e-3)
            assertTrue(report.summary(), report.summary().contains("within_1_bar=1.000"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theBenchSearchesOverTheNewParameters() {
        val changed = SegueCorpus.withValue(SegueTuning(), "orpheon.safetySec", 3.0)
        assertEquals(3.0, changed.orpheon.safetySec, 0.0)
        assertEquals(1, SegueCorpus.withValue(SegueTuning(), "planner", 1.0).planner)
    }

    @Test fun aCatalogueFamilyComesFromTheGenreNames() {
        assertEquals(GenreFamily.HIPHOP, GenreFamily.fromNames(listOf("pop urbaine", "french hip hop")))
        assertEquals(GenreFamily.HIPHOP, GenreFamily.fromNames(listOf("Rap français")))
        assertEquals(GenreFamily.DANCE, GenreFamily.fromNames(listOf("tech house")))
        assertEquals(GenreFamily.POP, GenreFamily.fromNames(listOf("dance pop".replace("dance ", ""), "chanson")))
        assertEquals(GenreFamily.OTHER, GenreFamily.fromNames(listOf("variete francaise", "r&b francais")))
        assertEquals(null, GenreFamily.fromNames(listOf(" ")))
    }
}
