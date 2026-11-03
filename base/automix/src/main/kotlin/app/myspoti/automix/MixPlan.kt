/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A transition from song A (going out) to song B (coming in).
 *
 * At A's time [exitA] B is heard from its time [entryB]; the mix lasts [length] seconds of A's
 * timeline, after which A stops. B plays at [speedB] (its beats then fall on A's) from the start of
 * the mix until A is gone, then goes back to 1 over [restoreSec]. [levels] gives both decks' gains
 * and EQ along the mix.
 */
class MixPlan(
    val style: Style,
    val exitA: Double,
    val entryB: Double,
    val length: Double,
    val speedB: Double,
    /** Beats of A in the mix (0 for an unsynced fade). */
    val beats: Int,
    /** Where the bass moves from A to B, 0..1 along the mix (a downbeat). */
    val swapAt: Double,
    /** One beat of A as a share of the mix (bass swap and cut ramps last a beat). */
    val beatShare: Double,
    /** B's gain during the mix (B louder than A is brought down to A's level, then back to 1 after). */
    val matchB: Double,
    val restoreSec: Double,
    val note: String,
    /** A's gain at the middle of the mix and on: A louder than B comes down towards B's level (1: untouched). */
    val matchA: Double = 1.0,
) {
    enum class Style {
        /** Beat-matched blend over several bars with a bass swap. */
        BLEND,
        /** Beat-aligned swap on a downbeat: B comes in whole, A leaves within a beat or two. */
        CUT,
        /** No common beat: an equal-power fade, A's top end cut on its way out. */
        FADE,
    }

    val synced get() = style != Style.FADE
    val endA get() = exitA + length
    /** B's time when A is gone. */
    val resumeB get() = entryB + length * speedB

    /** Gains (linear) and band levels (dB, 0 = untouched) of both decks. */
    class Levels(val gainA: Double, val gainB: Double, val lowA: Double, val lowB: Double, val highA: Double, val highB: Double)

    /** Levels at [x] (0 = start of the mix, 1 = A gone). Outside the mix: A alone before, B alone after. */
    fun levels(x: Double): Levels {
        if (x <= 0) return Levels(1.0, 0.0, 0.0, KILL, 0.0, 0.0)
        if (x >= 1) return Levels(0.0, matchB, KILL, 0.0, 0.0, 0.0)
        val raw = rawLevels(x)
        if (matchA >= 0.999) return raw
        // A eased down to B's level over the first half, so the loudness holds when A leaves.
        val down = 1 - (1 - matchA) * smooth((x / 0.5).coerceIn(0.0, 1.0))
        return Levels(raw.gainA * down, raw.gainB, raw.lowA, raw.lowB, raw.highA, raw.highB)
    }

    private fun rawLevels(x: Double): Levels {
        return when (style) {
            Style.BLEND -> {
                val s = swapAt
                val ramp = beatShare.coerceAtLeast(0.01)
                // B's fader rises over the first half with its bass cut, sitting under A.
                val gB = if (x < s) 0.8 * sin(PI / 2 * (x / s)).pow(0.8) else 0.8 + 0.2 * ((x - s) / (1 - s)).coerceIn(0.0, 1.0)
                // A stays up until the swap, then fades out (equal power) over the second half.
                val gA = if (x < s) 1.0 else cos(PI / 2 * ((x - s) / (1 - s)).coerceIn(0.0, 1.0))
                // The bass swap: B's low band comes in as A's goes, over one beat from the swap.
                val swap = ((x - s) / ramp).coerceIn(0.0, 1.0)
                val lowA = KILL * smooth(swap); val lowB = KILL * (1 - smooth(swap))
                // A's top end goes down over the last quarter (as a DJ closes A's high EQ).
                val highA = if (x > 0.7) -15.0 * ((x - 0.7) / 0.3) else 0.0
                // B's top end slightly held back at first so A's hi-hats lead until the middle.
                val highB = if (x < s) -6.0 * (1 - x / s) else 0.0
                Levels(gA, gB * matchB, lowA, lowB, highA, highB)
            }
            Style.CUT -> {
                // B whole from the downbeat; A's bass gone at once, A out over the mix.
                val gA = cos(PI / 2 * x)
                // A few tens of ms of ramp on B: no click if the downbeat lands between two steps.
                Levels(gA, matchB * (x / 0.04).coerceAtMost(1.0), KILL, 0.0, -10.0 * x, 0.0)
            }
            Style.FADE -> {
                val gA = cos(PI / 2 * x); val gB = sin(PI / 2 * x)
                Levels(gA, gB * matchB, KILL * smooth(((x - 0.4) / 0.3).coerceIn(0.0, 1.0)), KILL * (1 - smooth((x / 0.6).coerceIn(0.0, 1.0))), -12.0 * x, 0.0)
            }
        }
    }

    /** B's speed at its own time [tB] after the mix: back to 1 over [restoreSec] seconds of B. */
    fun speedAfter(tB: Double): Double {
        val past = tB - resumeB
        if (past <= 0) return speedB
        if (past >= restoreSec || restoreSec <= 0) return 1.0
        return speedB + (1 - speedB) * smooth(past / restoreSec)
    }

    /** B's gain after the mix: [matchB] back to 1 over 8 s of B. */
    fun gainAfter(tB: Double): Double = matchB + (1 - matchB) * smooth(((tB - resumeB) / 8.0).coerceIn(0.0, 1.0))

    override fun toString() = String.format("%s A %.2f→%.2f (%.1f s, %d beats) B from %.2f ×%.4f swap %.2f gainB %.2f | %s",
        style, exitA, endA, length, beats, entryB, speedB, swapAt, matchB, note)

    companion object {
        /** A band "off", in dB. */
        const val KILL = -30.0
        private fun smooth(t: Double) = t * t * (3 - 2 * t)
    }
}
