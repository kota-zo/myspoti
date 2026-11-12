/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.DurationBounds
import app.myspoti.automix.MixPlan
import app.myspoti.automix.TrackAnalysis
import kotlin.math.roundToLong

/** The levels of a transition, the compact planner's: locked mixes by length, then the plain fade. Normal playback is not a failure. */
enum class SegueLevel(val cls: SegueClass?) {
    LOCKED_LONG(SegueClass.LOCKED),
    LOCKED_SHORT(SegueClass.LOCKED),
    LOCKED_BEAT(SegueClass.LOCKED),
    SIMPLE_FADE(SegueClass.FADE),
    NORMAL(null),
}

/** The treatments a plan can have: a locked mix (the bass and the beat handed over) or a fade. */
enum class SegueClass { LOCKED, FADE }

enum class SegueCause { NO_GRID, TEMPO_OUT_OF_REACH, LENGTH_NOT_ALLOWED, NO_CANDIDATE, GUARDS, INVALID_PLAN, ALBUM_CONTINUITY }

/** Why a level was not used: every level above the chosen one has one. */
data class SegueAttempt(val level: SegueLevel, val cause: SegueCause, val detail: String = "") {
    override fun toString() = if (detail.isEmpty()) "$level: $cause" else "$level: $cause ($detail)"
}

/** The policy's answer: the level, the plan (none for normal playback), and why the levels above failed. */
class SegueDecision(
    val level: SegueLevel,
    val plan: SeguePlan?,
    val attempts: List<SegueAttempt>,
    val score: Double? = null,
    /** Where the bass changes hands, 0..1 of the window. */
    val swapShare: Double = 0.5,
    /** Seconds the incoming song takes to return to its own tempo. */
    val restoreSec: Double = 0.0,
    /** How the transition sounds, for the journal: the plan's lanes are what plays. */
    val shape: SegueShape? = null,
    /** The Orpheon planner's family and style, for the journal. */
    val style: String? = null,
) {
    val why: String get() = attempts.joinToString("; ")
    val fingerprint: String? get() = plan?.let(SegueCodec::fingerprint)
}

class SegueRequest(
    val a: TrackAnalysis,
    val b: TrackAnalysis,
    /** Nothing may begin before this point of A: its preparation lead is already counted. */
    val earliestExitUs: Long = 0,
    val listenerRate: Long = SegueFormat.RATE_NOMINAL,
    /** The listener's settings may only tighten the guards. */
    val maxRatePpm: Long? = null,
    val maxBars: Int? = null,
    val eq: Boolean = true,
    val harmonic: Boolean = true,
    /** Both songs are on one album: a junction without silence is left to play as it is. */
    val sameAlbum: Boolean = false,
    val tuning: SegueTuning = SegueTuning(),
    /** Limits on listener elapsed seconds, before applying listenerRate. */
    val durationBounds: DurationBounds = DurationBounds(),
    /**
     * Each song's family from its catalogue (the artist's genres), when known: trusted over the
     * analyser's guess, it is what lets the planner refuse a lock to a song with no steady beat.
     */
    val genreA: GenreFamily? = null,
    val genreB: GenreFamily? = null,
    /** The listener's mix length for the Orpheon planner (see [OrpheonRequest.minBars]). */
    val minBars: Int? = null,
    val fadeSec: Double? = null,
)


/**
 * The planner's entry points: [decide] writes the transition as a plan ([CompactPolicy]); [timing]
 * and [stale] serve the players and the cache. Deterministic: the same request gives the same plan.
 */
object SeguePolicy {
    fun decide(r: SegueRequest): SegueDecision =
        if (r.tuning.planner == SegueTuning.PLANNER_ORPHEON) OrpheonPolicy.decide(r) else CompactPolicy.decide(r)

    /** A plan whose start has already gone by when it is ready is cancelled, never played late. */
    fun cueMissed(p: SeguePlan, positionUs: Long): Boolean = positionUs > p.outgoing.anchorUs

    /** The plan's timing as the players need it. */
    fun timing(d: SegueDecision): MixPlan? {
        val p = d.plan ?: return null
        val style = if (d.level.cls == SegueClass.LOCKED) MixPlan.Style.BLEND else MixPlan.Style.FADE
        val rate = p.incoming.ratePpm.toDouble() / SegueFormat.RATE_NOMINAL
        val beats = if (p.span.unitKind == UnitKind.BAR) (p.span.musicalUnits ?: 0) * 4 else p.span.musicalUnits ?: 0
        return MixPlan(style, p.outgoing.anchorUs / 1e6, p.incoming.anchorUs / 1e6, p.span.lengthUs / 1e6, rate, beats,
            d.swapShare, if (beats > 0) 1.0 / beats else 0.1, 1.0, d.restoreSec,
            "segue ${d.level}" + (d.shape?.let { " $it" } ?: "") + (d.style?.let { " $it" } ?: "") + (d.score?.let { String.format(java.util.Locale.ROOT, " score %.2f", it) } ?: "") +
                (if (d.attempts.isEmpty()) "" else " | ${d.why}"),
            segue = p)
    }

    /** Checks a plan against the analyses it must match; a non-empty answer means it is recomputed. */
    fun stale(p: SeguePlan, a: TrackAnalysis, b: TrackAnalysis): List<SegueViolation> {
        val (fa, fb) = facts(a, b)
        return SegueValidator.stale(p, fa, fb)
    }

    internal fun facts(a: TrackAnalysis, b: TrackAnalysis) =
        SegueSideFacts(a.audioKey, a.beatKey, us(a.duration)) to SegueSideFacts(b.audioKey, b.beatKey, us(b.duration))

    private fun us(seconds: Double): Long = (seconds * 1e6).roundToLong()
}
