/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SegueFormatTest {
    private val ramp = KnotForm.Ramp
    private fun plan(
        state: PulseState = PulseState.FREE,
        tempo: Long? = null,
        gridA: String? = null,
        rateA: Long = SegueFormat.RATE_NOMINAL,
        listener: Long = SegueFormat.RATE_NOMINAL,
        lengthUs: Long = 8_000_000,
        anchorA: Long = 100_000_000,
        audioA: String = "a",
        lanes: List<SeguePlan.Lane> = listOf(level()),
        gestures: List<SeguePlan.Gesture> = emptyList(),
        schema: String = SegueFormat.SCHEMA,
    ) = SeguePlan(SeguePlan.Origin("test", "none"),
        SeguePlan.Side(audioA, gridA, anchorA, rateA, listener),
        SeguePlan.Side("b", "gb", 0, 1_020_000, listener),
        SeguePlan.Span(lengthUs, 4, UnitKind.BAR, Clock.ELAPSED),
        SeguePlan.Pulse(state, tempo, if (state == PulseState.FREE) null else 1234), lanes, gestures, schema)

    private fun level(vararg knots: SeguePlan.Knot = arrayOf(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, null, ramp))) =
        SeguePlan.Lane(LaneSide.OUTGOING, Target.LEVEL, SegueUnit.DB, knots.toList())

    private fun violations(p: SeguePlan) = SegueValidator.structural(p)
    private fun assertViolates(v: SegueViolation, p: SeguePlan) = assertTrue("$v in ${violations(p)}", v in violations(p))

    // ── Validation: one test per rule ──

    @Test fun aWellFormedPlanHasNoViolation() = assertEquals(emptyList<SegueViolation>(), violations(plan()))

    @Test fun positionsMustStrictlyIncrease() {
        assertViolates(SegueViolation.KNOTS_NOT_INCREASING, plan(lanes = listOf(level(SeguePlan.Knot(0, 0, ramp),
            SeguePlan.Knot(5, 0, ramp), SeguePlan.Knot(5, 0, ramp), SeguePlan.Knot(SegueFormat.T_MAX, 0, ramp)))))
    }

    @Test fun positionsMustStayInTheNormalisedRange() {
        assertViolates(SegueViolation.KNOTS_OUT_OF_RANGE, plan(lanes = listOf(level(SeguePlan.Knot(0, 0, ramp), SeguePlan.Knot(SegueFormat.T_MAX + 1, 0, ramp)))))
        assertViolates(SegueViolation.KNOTS_OUT_OF_RANGE, plan(lanes = listOf(level(SeguePlan.Knot(-1, 0, ramp), SeguePlan.Knot(SegueFormat.T_MAX, 0, ramp)))))
    }

    @Test fun aLaneMustCoverTheWholeWindow() {
        assertViolates(SegueViolation.KNOTS_NOT_SPANNING, plan(lanes = listOf(level(SeguePlan.Knot(10, 0, ramp), SeguePlan.Knot(SegueFormat.T_MAX, 0, ramp)))))
        assertViolates(SegueViolation.KNOTS_NOT_SPANNING, plan(lanes = listOf(SeguePlan.Lane(LaneSide.OUTGOING, Target.LEVEL, SegueUnit.DB, emptyList()))))
    }

    @Test fun theUnitMustMatchTheTarget() {
        val k = listOf(SeguePlan.Knot(0, 1000, ramp), SeguePlan.Knot(SegueFormat.T_MAX, 1000, ramp))
        for (t in Target.values()) {
            for (u in SegueUnit.values()) {
                val v = violations(plan(lanes = listOf(SeguePlan.Lane(LaneSide.OUTGOING, t, u, k))))
                assertEquals("$t in $u", u != t.unit, SegueViolation.UNIT_MISMATCH in v)
            }
        }
    }

    @Test fun lockedNeedsBothGridKeysAndATempo() {
        assertViolates(SegueViolation.LOCKED_NEEDS_GRID_KEYS, plan(PulseState.LOCKED, 120_000_000, gridA = null))
        assertViolates(SegueViolation.LOCKED_NEEDS_TEMPO, plan(PulseState.LOCKED, null, gridA = "ga"))
        assertEquals(emptyList<SegueViolation>(), violations(plan(PulseState.LOCKED, 120_000_000, gridA = "ga")))
    }

    @Test fun freeHasNoTempo() = assertViolates(SegueViolation.FREE_HAS_TEMPO, plan(PulseState.FREE, 120_000_000))

    @Test fun ratesMustBePositive() {
        assertViolates(SegueViolation.RATE_NOT_POSITIVE, plan(rateA = 0))
        assertViolates(SegueViolation.RATE_NOT_POSITIVE, plan(listener = -1))
    }

    @Test fun theWindowMustHaveALength() = assertViolates(SegueViolation.LENGTH_NOT_POSITIVE, plan(lengthUs = 0))

    @Test fun anchorsAreSourcePositions() = assertViolates(SegueViolation.ANCHOR_NEGATIVE, plan(anchorA = -1))

    @Test fun audioKeysAreRequired() = assertViolates(SegueViolation.AUDIO_KEY_MISSING, plan(audioA = ""))

    @Test fun aLaneIsOnePerSideAndTarget() = assertViolates(SegueViolation.DUPLICATE_LANE, plan(lanes = listOf(level(), level())))

    @Test fun octaveIsForFrequencies() {
        val k = listOf(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, 0, KnotForm.Octave))
        assertViolates(SegueViolation.FORM_NEEDS_FREQUENCY, plan(lanes = listOf(SeguePlan.Lane(LaneSide.OUTGOING, Target.LEVEL, SegueUnit.DB, k))))
    }

    @Test fun frequenciesArePositiveButNullMeansOpen() {
        fun sweep(v: Long?) = plan(lanes = listOf(SeguePlan.Lane(LaneSide.OUTGOING, Target.SWEEP_HZ, SegueUnit.HZ,
            listOf(SeguePlan.Knot(0, v, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, 400_000, KnotForm.Octave)))))
        assertViolates(SegueViolation.FREQUENCY_NOT_POSITIVE, sweep(0))
        assertEquals(emptyList<SegueViolation>(), violations(sweep(null)))
    }

    @Test fun nullHasAMeaningForEveryTarget() {
        for (t in Target.values()) {
            assertTrue(t.nullMeaning.isNotEmpty())
            val k = listOf(SeguePlan.Knot(0, null, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, null, KnotForm.Step))
            assertEquals("$t", emptyList<SegueViolation>(), violations(plan(lanes = listOf(SeguePlan.Lane(LaneSide.OUTGOING, t, t.unit, k)))))
        }
    }

    @Test fun easeNeedsAPositiveCurvature() {
        assertEquals(null, KnotForm.parse("ease:0"))
        assertEquals(null, KnotForm.parse("ease:-3"))
        assertEquals(KnotForm.Ease(1500), KnotForm.parse("ease:1500"))
        assertViolates(SegueViolation.EASE_NOT_POSITIVE, plan(lanes = listOf(level(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, 0, KnotForm.Ease(0))))))
    }

    @Test fun gesturesNeedAPlaceAndALength() {
        assertViolates(SegueViolation.GESTURE_INVALID, plan(gestures = listOf(SeguePlan.Gesture(GestureKind.REPEAT, -1, 2))))
        assertViolates(SegueViolation.GESTURE_INVALID, plan(gestures = listOf(SeguePlan.Gesture(GestureKind.REPEAT, 5, 0))))
    }

    @Test fun theWindowWithItsSpeedMustFitBothSongs() {
        val p = plan() // A: 100 s + 8 s; B: 0 + 8 s × 1.02 = 8.16 s
        assertEquals(emptyList<SegueViolation>(), SegueValidator.against(p, facts("a", null, 108_000_000), facts("b", "gb", 8_160_000)))
        assertTrue(SegueViolation.WINDOW_PAST_OUTGOING in SegueValidator.against(p, facts("a", null, 107_999_999), facts("b", "gb", 9_000_000)))
        assertTrue(SegueViolation.WINDOW_PAST_INCOMING in SegueValidator.against(p, facts("a", null, 108_000_000), facts("b", "gb", 8_159_999)))
    }

    private fun facts(audio: String, grid: String?, duration: Long) = SegueSideFacts(audio, grid, duration)

    // ── Invalidation ──

    @Test fun aChangedKeyInvalidatesThePlan() {
        val p = plan(PulseState.LOCKED, 120_000_000, gridA = "ga")
        val a = facts("a", "ga", 200_000_000); val b = facts("b", "gb", 200_000_000)
        assertEquals(emptyList<SegueViolation>(), SegueValidator.stale(p, a, b))
        assertEquals(listOf(SegueViolation.STALE_OUTGOING_AUDIO), SegueValidator.stale(p, facts("x", "ga", 1), b))
        assertEquals(listOf(SegueViolation.STALE_OUTGOING_GRID), SegueValidator.stale(p, facts("a", "x", 1), b))
        assertEquals(listOf(SegueViolation.STALE_INCOMING_AUDIO), SegueValidator.stale(p, a, facts("x", "gb", 1)))
        assertEquals(listOf(SegueViolation.STALE_INCOMING_GRID), SegueValidator.stale(p, a, facts("b", "x", 1)))
    }

    // ── Serialization ──

    private fun full() = plan(PulseState.LOCKED, 123_456_789, gridA = "ga", lanes = listOf(
        level(SeguePlan.Knot(0, -1500, KnotForm.Step), SeguePlan.Knot(250_000, 0, KnotForm.Ease(1800)), SeguePlan.Knot(SegueFormat.T_MAX, null, ramp)),
        SeguePlan.Lane(LaneSide.INCOMING, Target.SWEEP_HZ, SegueUnit.HZ, listOf(SeguePlan.Knot(0, null, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, 400_000, KnotForm.Octave))),
        SeguePlan.Lane(LaneSide.INCOMING, Target.SEND_ECHO, SegueUnit.RATIO, listOf(SeguePlan.Knot(0, 125, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, 1, ramp))),
    ), gestures = listOf(SeguePlan.Gesture(GestureKind.REPEAT, 90_000_000, 4)))

    @Test fun aPlanSurvivesARoundTrip() {
        val p = full()
        val text = SegueCodec.encode(p)
        assertEquals(p, SegueCodec.decode(text))
        assertEquals(text, SegueCodec.encode(SegueCodec.decode(text)))
    }

    @Test fun theCanonicalFormSortsKeysAndHasOneSpellingPerNumber() {
        val text = SegueCodec.encode(full())
        assertTrue(text, text.startsWith("{\"gestures\":"))
        assertFalse(text, text.contains(' '))
        assertTrue(text, text.contains("[0,-1.5,\"step\"]"))
        assertTrue(text, text.contains("[250000,0,\"ease:1800\"]"))
        assertTrue(text, text.contains("[1000000,0.001,\"ramp\"]"))
        assertTrue(text, text.contains("[1000000,null,\"ramp\"]"))
    }

    @Test fun aFingerprintIsStableAndIgnoresOnlyTheOrigin() {
        val p = full()
        assertEquals(64, SegueCodec.fingerprint(p).length)
        assertEquals(SegueCodec.fingerprint(p), SegueCodec.fingerprint(SegueCodec.decode(SegueCodec.encode(p))))
        assertEquals(SegueCodec.fingerprint(p), SegueCodec.fingerprint(p.copy(origin = SeguePlan.Origin("other", "other"))))
        assertNotEquals(SegueCodec.fingerprint(p), SegueCodec.fingerprint(p.copy(span = p.span.copy(lengthUs = 1))))
        assertNotEquals(SegueCodec.fingerprint(p), SegueCodec.fingerprint(p.copy(outgoing = p.outgoing.copy(listenerRate = 1_250_000))))
    }

    @Test fun keyOrderInTheInputDoesNotChangeTheFingerprint() {
        val text = SegueCodec.encode(plan())
        val reordered = "{\"origin\":{\"corpus\":\"none\",\"engine\":\"test\"}," + text.removePrefix("{").replace("\"origin\":{\"corpus\":\"none\",\"engine\":\"test\"},", "")
        assertEquals(SegueCodec.fingerprint(plan()), SegueCodec.fingerprint(SegueCodec.decode(reordered)))
    }

    @Test fun anotherMajorIsRefusedAndAnUnknownFieldIsIgnoredWithinThisOne() {
        val text = SegueCodec.encode(plan())
        assertThrows(SegueFormatException::class.java) { SegueCodec.decode(text.replace("\"segue/1\"", "\"segue/2\"")) }
        assertThrows(SegueFormatException::class.java) { SegueCodec.decode(text.replace("\"segue/1\"", "\"other/1\"")) }
        val extra = text.replace("\"gestures\":[]", "\"gestures\":[],\"later\":1")
        assertEquals(plan(), SegueCodec.decode(extra))
        assertEquals(SegueCodec.fingerprint(plan()), SegueCodec.fingerprint(SegueCodec.decode(extra)))
    }

    @Test fun malformedPlansAreRefused() {
        val text = SegueCodec.encode(plan())
        for (bad in listOf("", "[]", "{", text + "x", text.replace("\"lengthUs\":8000000", "\"lengthUs\":8.5"),
            text.replace("\"lengthUs\":8000000", "\"lengthUs\":8e6"), text.replace("\"lengthUs\":8000000", "\"lengthUs\":\"8\""),
            text.replace("\"state\":\"free\"", "\"state\":\"maybe\""), text.replace("\"step\"", "\"wobble\""),
            text.replace("[0,0,\"step\"]", "[0,0.0001,\"step\"]"), text.replace("[0,0,\"step\"]", "[0,0]"),
            text.replace("\"span\":", "\"span\":{},\"span\":"))) {
            assertThrows(bad, SegueFormatException::class.java) { SegueCodec.decode(bad) }
        }
    }

    // ── Reading a lane ──

    private fun curve(vararg k: SeguePlan.Knot, target: Target = Target.LEVEL) =
        LaneCurve(SeguePlan.Lane(LaneSide.OUTGOING, target, target.unit, k.toList()))

    @Test fun formsDescribeTheArrivalOnTheirKnot() {
        val m = SegueFormat.T_MAX
        assertEquals(-5.0, curve(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(m, -10_000, ramp)).value(m / 2.0), 1e-9)
        assertEquals(0.0, curve(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(m, -10_000, KnotForm.Step)).value(m - 1.0), 1e-9)
        assertEquals(-10.0, curve(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(m, -10_000, KnotForm.Step)).value(m.toDouble()), 1e-9)
        assertEquals(-2.5, curve(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(m, -10_000, KnotForm.Ease(2000))).value(m / 2.0), 1e-9)
        val octave = curve(SeguePlan.Knot(0, 1_000_000, KnotForm.Step), SeguePlan.Knot(m, 250_000, KnotForm.Octave), target = Target.SWEEP_HZ)
        assertEquals(500.0, octave.value(m / 2.0), 1e-9)
    }

    @Test fun outsideTheWindowALaneHoldsItsEnds() {
        val m = SegueFormat.T_MAX
        val c = curve(SeguePlan.Knot(0, -3000, KnotForm.Step), SeguePlan.Knot(m, -9000, ramp))
        assertEquals(-3.0, c.value(-5.0), 1e-9)
        assertEquals(-9.0, c.value(m + 5.0), 1e-9)
    }

    @Test fun nullIsSilenceOnLevelNotAVeryLowValue() {
        val m = SegueFormat.T_MAX
        val c = curve(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(m, null, ramp))
        assertEquals(SegueFormat.LEVEL_FLOOR_DB / 2, c.value(m / 2.0), 1e-9)
        assertFalse(c.isNull(m - 1.0))
        assertTrue(c.isNull(m.toDouble()))
        assertEquals(1.0, curve(SeguePlan.Knot(0, null, KnotForm.Step), SeguePlan.Knot(m, null, KnotForm.Step), target = Target.SWEEP_PEAK).value(10.0), 1e-9)
        assertEquals(SegueFormat.SWEEP_OPEN_HZ, curve(SeguePlan.Knot(0, null, KnotForm.Step), SeguePlan.Knot(m, null, KnotForm.Step), target = Target.SWEEP_HZ).value(10.0), 1e-9)
    }

    @Test fun elapsedTimeFollowsEachSidesRate() {
        val p = plan()
        assertEquals(1_000_000, p.elapsedUs(LaneSide.OUTGOING, 101_000_000))
        assertEquals(1_000_000, p.elapsedUs(LaneSide.INCOMING, 1_020_000))
    }
}
