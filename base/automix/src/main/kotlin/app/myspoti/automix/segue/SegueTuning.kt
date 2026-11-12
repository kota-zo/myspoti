/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

/**
 * Every number of the segue policy, in one place. None is a result: each is a starting parameter to
 * confirm by measurement (docs/automix/segue-design.md). Labels: `[U]` usual order of magnitude,
 * `[I]` design proposal, `[R]` reused from a value already in this repository. Where the design
 * gives a range, the cautious end is the start value and the range is noted. A data class, so the
 * measurement harness can vary each value.
 */
data class SegueTuning(
    // ── Guards: one crossing rejects a candidate, whatever its score ──
    /** Expected phase error: median [U], against double attacks. */
    val phaseMedianMaxUs: Long = 20_000,
    /** Expected phase error: 95th percentile [U]. */
    val phaseP95MaxUs: Long = 40_000,
    /** Least grid reliability: beats [R], share of beats on the grid [R], drift over 8 beats in ms [R]. */
    val gridMinBeats: Int = 16,
    val gridMinSteadiness: Double = 0.6,
    val gridMaxDriftMs: Double = 30.0,
    /** A stretch of bars must have this share of its beats from the model [I]. */
    val stretchMinBeatShare: Double = 0.85,
    /** And the model must be this sure of its downbeats, on average [I]. */
    val stretchMinDownConfidence: Double = 0.5,
    /** No more silence than this at both sides of a junction: the album means it to run on [I]. */
    val gaplessMaxSilenceUs: Long = 300_000,

    /** Per-bar voice-like risk from which a bar counts as sung or rapped [R]. */
    val voiceBarRisk: Double = 0.6,

    // ── Reliability of what cannot state its own [I] ──
    val reliabilityForMiddle: Double = 0.6,

    // ── Placement ──
    /** A build-up's beat carried back from this many bars after it [I], landing this near its start [I]. */
    val carryFromBars: Int = 4,
    val carryMaxOffUs: Long = 80_000,

    /** Which planner writes the plan: [PLANNER_COMPACT] (the default) or [PLANNER_ORPHEON], for an A/B. */
    val planner: Int = PLANNER_COMPACT,
    /** The [MixIdea]s the Orpheon planner plays with, as their bits: none by default, the Mix Lab chooses. */
    val ideas: Int = 0,

    /** The planner's values. */
    val compact: CompactTuning = CompactTuning(),
    val orpheon: OrpheonTuning = OrpheonTuning(),

    // ── The engine's sound ──
    /** Headroom while both songs play. */
    val headroom: Headroom = Headroom(),
    /** Automation is read every this many frames, gains interpolated between [R]. */
    val blockFrames: Int = 64,
    /** Band edges of the shelves [R]. */
    val shelfLowHz: Double = 180.0,
    val shelfHighHz: Double = 2_500.0,
    /** The isolator's crossover (fourth-order, its two bands summing flat) [R: the shelf's edge], and how fast it comes in and out of the chain [I]. */
    val isolatorHz: Double = 180.0,
    val isolatorFadeUs: Long = 30_000,
    /** Slope of the shelves [U], width of the core bell between them [I]. */
    val shelfQ: Double = 0.7071,
    val coreQ: Double = 0.5,
    /** Echo send: delay when the plan sets none [I], feedback [I]. */
    val echoDelayUs: Long = 375_000,
    val echoFeedback: Double = 0.5,
    /** Sends leave out what is below this [I]: bass that rings on muddies the song coming in. */
    val sendLowCutHz: Double = 250.0,
    /** Room send: comb delays and all-pass delays of a small Schroeder reverb [U], comb feedback [I], all-pass gain [U]. */
    val roomCombUs: List<Long> = listOf(29_700, 37_100, 41_100, 43_700),
    val roomAllPassUs: List<Long> = listOf(5_000, 1_700),
    val roomCombFeedback: Double = 0.87,
    val roomAllPassGain: Double = 0.7,
    /** How long the outgoing player keeps running for a send's tail [I]. */
    val sendTailUs: Long = 3_000_000,
    /** Back to the plain song when the incoming one stalls [R]. */
    val holdRampUs: Long = 400_000,
) {
    companion object {
        const val PLANNER_COMPACT = 0
        const val PLANNER_ORPHEON = 1

        /** The band where two bass lines mask each other [U]. Fixed at analysis: not varied by the harness. */
        const val BASS_LOW_HZ = 20.0
        const val BASS_HIGH_HZ = 150.0

        /** Measurement: how far each parameter is varied [I], and the corpus split for tuning, validation, final test [I]. */
        const val STABILITY_RANGE = 0.20
        const val SPLIT_TUNING_SHARE = 0.60
        const val SPLIT_VALIDATION_SHARE = 0.20
    }
}

/**
 * The planner's values. Labels as in [SegueTuning], and `[M]`: read from
 * measurements of how short mixes are placed and shaped. A class of its own, the JVM bounding how
 * many values one constructor takes.
 */
data class CompactTuning(
    /** Largest tempo gap that may lock, B's beat against A's with no half or double time, and the gap that costs nothing [M]. */
    val lockMaxTempo: Double = 0.10,
    val lockFreeTempo: Double = 0.045,
    /** What a tempo gap at [lockMaxTempo] costs [I]. */
    val tempoCost: Double = 0.1,
    /** Shortest and longest locked windows, bars; each length doubles the one before [M]. */
    val shortestBars: Int = 2,
    val longestBars: Int = 16,
    /** No locked window starts before this share of the outgoing song [M]. */
    val exitMinShare: Double = 0.67,
    /** Bars between the window's end and the end of the music: best inside, a little off outside, far allowed at a cost, never beyond [M]. */
    val exitBestFrom: Double = 0.5,
    val exitBestTo: Double = 3.0,
    val exitNearBars: Double = 8.0,
    val exitFarBars: Double = 16.0,
    val exitPastBars: Double = 1.0,
    /** Bars of the incoming song left out at the entry: best inside, allowed up to the last, and never past this share of the song [M]. */
    val entryBestFrom: Int = 2,
    val entryBestTo: Int = 4,
    val entryLastBar: Int = 12,
    val entryMaxShare: Double = 0.29,
    /** What a window's length costs: nothing up to this, then this much a second and this much a second squared past it [M]. */
    val lengthFreeSec: Double = 5.0,
    val lengthSlope: Double = 0.0,
    val lengthCurve: Double = 0.0002,
    /** Score steps [M]: candidates closer than one step are equal, then the longest wins, then the earliest exit. */
    val step: Double = 0.025,
    /** Key clash (Camelot numbers two or more apart) [M]; two voices at once [I]. */
    val keyCost: Double = 0.1,
    val clashCost: Double = 0.1,
    /** The plain fade: its length, its shortest when clipped by the file's end, and how far before the incoming song's first sound it starts [M]. */
    val fadeUs: Long = 5_000_000,
    val fadeMinUs: Long = 2_510_000,
    val fadeLeadUs: Long = 2_500_000,
    /**
     * Metrical level: a song's tempo folded into [this, 2 × this) BPM before its bars are counted
     * [I]; 0 leaves every song at the level its beat was tracked at (the default: see the bench).
     */
    val foldFromBpm: Double = 0.0,
    /** Where an exit's distance is counted from: 0 the end of the content, 1 the end of the file, 2 the end of the music [M]. */
    val exitLandmark: Int = 0,
    /** The rest of the outgoing song still counts as in full flight this close to its loud level, dB [I]. */
    val restLoudDb: Double = 3.0,
    /** An opening left out is quiet this far under the window that follows it, dB [I], and earns this many steps [I]. */
    val quietIntroDb: Double = 3.0,
    val quietSteps: Int = 1,
    /**
     * Weak bars (this far under the song's loud level, dB, or short of the model's beats) may borrow
     * the grid of the reliable bars next to them, at most this many bars away (-1: never), for
     * windows of at most this many bars, at this many steps of cost [I].
     */
    val extendQuietDb: Double = 6.0,
    val extendGapBars: Int = 2,
    val extendMaxBars: Int = 4,
    val extendSteps: Int = 1,
    /** Bars holding half as many of the model's beats as they should are measured on those beats with the missing ones halfway [I]. */
    val halfModelBeats: Boolean = true,
    /** Two beats a metrical level apart may lock when the faster is in this range, BPM; 0 never [M]: most music there is heard at the faster tempo. */
    val pairFoldMinBpm: Double = 110.0,
    val pairFoldMaxBpm: Double = 150.0,
    /** The plain fade's end of the music: the end of the last bar this loud, dB, moved by this [M]. */
    val fadeEndDb: Double = -20.0,
    val fadeEndOffsetUs: Long = 750_000,
    /**
     * A long quiet opening of B, left out [M: long intros are skipped]: at least this many bars,
     * this far under the song's loud level, dB, until the bar it comes within this of it, dB; an
     * entry inside such an opening costs this many steps [I]. Off by default (an unreachable bar
     * count) until the arrival detection is tuned: on, it entered 13 s late on the one long intro
     * of the bench and lost 4 locks; 8 is the value it was tried with.
     */
    val introMinBars: Int = 1_000,
    val introQuietDb: Double = 6.0,
    val introLoudDb: Double = 3.0,
    val introSteps: Int = 2,
    /** The isolator's bass kill in the compact shapes, dB [I]: the band gone, not a shelf turned down. */
    val bassKillDb: Double = -80.0,
    /** B's return to its own tempo after the window: at least this many bars, at most this much speed a bar at the steepest, nothing under this gap [I]. */
    val restoreMinBars: Double = 4.0,
    val restoreMaxPerBar: Double = 0.005,
    val restoreFromGap: Double = 0.005,
    /** Filter ends of the compact shapes [M]: low-pass closed, high-pass closed. */
    val lowPassHz: Double = 150.0,
    val highPassHz: Double = 6_000.0,
    /** A jump in a compact shape takes this long, so it does not click [I]. */
    val jumpUs: Long = 20_000,
)

/** Headroom: what the two players together may reach while both play. */
data class Headroom(
    /**
     * While the two faders add up past 1, each side is held under its share of this peak
     * [I], so the two players' sum cannot clip although neither hears the other. 0 turns it off.
     */
    val ceiling: Double = 0.97,
    /** The ceiling's gain falls over this ahead of a peak, and recovers over this after it [U]. */
    val attackUs: Long = 2_000,
    val releaseUs: Long = 80_000,
    /** What a send's tail may reach once its window is over; the incoming song keeps under the ceiling less this until it has rung out [I]. */
    val tailPeak: Double = 0.12,
    /** Inside the window, a side with a send counts for at least this much of the peak, so its echo is neither crushed nor added on top [I]. */
    val sendPeak: Double = 0.25,
)

/**
 * The Orpheon planner's values, fitted on a tuning split of the reference plans with the bench
 * (`with=orpheon.field:value`); `sections`-dependent entries stay coarse. To be confirmed by ear.
 * Scores start at 1 and lose these costs.
 */
data class OrpheonTuning(
    // ── Timing ──
    /**
     * No transition starts sooner than this after the point the player asked for. The player's own
     * lead (run-up, arming the chain) is already in that point, so this is only a small cushion: a
     * larger one throws away the window planned for the song's end after a seek near it.
     */
    val safetySec: Double = 1.0,
    /** The outgoing window starts no earlier than min([exitLeadSec], (duration - [exitShortSongSec]) / 2) before the song's end. */
    val exitLeadSec: Double = 60.0,
    val exitShortSongSec: Double = 30.0,
    /** A window leaves the outgoing song on one of its half phrases, counted from its first bar; else on any bar. */
    val exitOnPhrase: Boolean = true,
    /** Bars that must match around a locked window. */
    val matchBars: Int = 8,
    /** An entry is usually within this, and never past the last. */
    val entryUsualSec: Double = 20.0,
    val entryLastSec: Double = 50.0,
    /** A phrase of the incoming song lasts about this: four of its bars, or eight short ones. */
    val phraseSec: Double = 14.5,
    /** dB under the main level that still counts as the outgoing song's body: a window ends on its last bar line. */
    val exitLoudDb: Double = 15.0,
    /** A window may end this many bars past the body at no cost, and an early one costs the most from this many bars. */
    val exitSlackBars: Double = 0.25,
    val exitEarlyBars: Double = 16.0,

    // ── Reading the songs ──
    val minGridConfidence: Double = 0.4,
    val minGenreConfidence: Double = 0.3,
    val beatMaxIntervalDev: Double = 0.5,
    val beatMaxResidualSec: Double = 0.2,
    /** Under the song's main level by this much is no music. */
    val silenceBelowMainDb: Double = 35.0,
    /** A genre this sure may refuse a lock; a guessed one (the analyser's) only chooses styles. */
    val genreGateConfidence: Double = 0.5,

    // ── Speed ──
    /** The largest tempo gap considered at all; each side's limits on short styles; both sides' on long blends. */
    val lockMaxGap: Double = 0.3,
    val outMaxShort: Double = 0.28,
    val inMaxShort: Double = 0.28,
    val maxLong: Double = 0.30,
    /** Other than hip-hop against hip-hop: a tighter tempo gap, and both songs danceable. */
    val lockMaxGapOther: Double = 0.3,
    val lockMinDanceability: Double = 0.7,
    /** Two hip-hop songs lock unless one is less danceable than this. */
    val lockMinDanceHiphop: Double = 0.3,
    /** What a song's danceability is taken to be when no analyser gives it, by family. */
    val assumeDance: Double = 0.9,
    val assumeHiphop: Double = 0.6,
    val assumePop: Double = 0.7,
    val assumeOther: Double = 0.4,
    /** An outgoing song more acoustic than this (hip-hop, other pairs) never locks. */
    val lockMaxAcoustic: Double = 0.55,
    val lockMaxAcousticOther: Double = 0.7,

    // ── Score ──
    val minScore: Double = 0.025,
    val costTempo: Double = 0.15,
    val costVoice: Double = 0.225,
    val costKey: Double = 0.3,
    /** The outgoing song leaving in the middle of a line of its lead voice. */
    val costCutVoice: Double = 0.3,
    val costPastMusic: Double = 1.0,
    val costQuietWindow: Double = 0.5,
    val costLateEntry: Double = 0.4,
    /** A window ending past the body, a bar; one ending [exitEarlyBars] before it. */
    val costExitLate: Double = 0.5,
    val costExitEarly: Double = 0.6,
    /** An arrival off the incoming song's phrases, one half-way through a phrase, and each phrase after its second. */
    val costOffPhrase: Double = 0.5,
    val costHalfPhrase: Double = 0.12,
    val costLaterPhrase: Double = 0.25,
    /** A blend that leads from its first bar: over a quiet opening it should have skipped, or skipping an opening that was not quiet. */
    val costQuietOpening: Double = 0.08,
    val costSkipOpening: Double = 0.04,
    /** An opening is quiet this far under the song's main level, dB, when the next phrase is this much louder. */
    val openingQuietDb: Double = 9.0,
    val openingRiseDb: Double = 4.0,
    /** Any other style starting on the song's very first bar: nothing of the song is heard before it takes over. */
    val costNoRunUp: Double = 0.3,
    /**
     * The incoming song's drop: its first bar within [dropLoudDb] of its main level, two bars long, when
     * what comes before is [dropRiseDb] quieter still. Such a song has an intro, and its intro belongs
     * under the outgoing song's ending: the hand-over is wanted on the drop.
     */
    val dropLoudDb: Double = 6.0,
    val dropRiseDb: Double = 3.0,
    /**
     * A hand-over that leaves the incoming song alone while it is still quiet, the energy sagging: nothing
     * at [dropLoudDb] under its main level, all of it [weakHandoverDb] lower.
     */
    val costWeakHandover: Double = 0.45,
    val weakHandoverDb: Double = 9.0,
    /**
     * A song with an intro: each phrase of its loud part played under the outgoing song, and a hand-over off its
     * drop: one bar past it, one bar short of it, anywhere else.
     */
    val costLoudUnder: Double = 0.25,
    val costPastDrop: Double = 0.05,
    val costNearDrop: Double = 0.15,
    val costOffDrop: Double = 0.4,
    /** The best locked place scoring under this is not worth the risk: the pair is faded. */
    val lockMinScore: Double = 0.3,
    /** A window e times longer or shorter than its style is written for. */
    val costLength: Double = 0.3,
    /** A smooth blend that starts off a section. */
    val costOffSection: Double = 0.1,
    val quietWindowDb: Double = 12.0,
    /** A style not written for the pair's family still plays, at this weight. */
    val genericWeight: Double = 0.7,
    /** Each style's best score is raised to this before the styles are drawn, and each place's to [placePower]: the best places take nearly all of it. */
    val drawPower: Double = 12.0,
    val placePower: Double = 60.0,
    /** The tie-breaks: a very small bonus for a later exit and for an earlier entry. */
    val laterBonus: Double = 0.002,
    val earlierBonus: Double = 0.002,

    // ── Other families ──
    /** Only a fade this long earns a smooth blend; a short one is just the song's end, for the silence join. */
    val smoothMinFadeSec: Double = 30.0,
    val smoothSec: Double = 5.0,
    /** A smooth blend may start this long before the fade. */
    val smoothLeadSec: Double = 8.0,
    /** Silence removal, the default when a pair does not lock: its window's bounds. */
    val silenceWindowMinSec: Double = 1.2,
    val silenceWindowMaxSec: Double = 10.0,
    /** The window starts this far before the outgoing song goes under [silenceTailDb]. */
    val silenceTailDb: Double = -30.0,
    val silenceLeadSec: Double = 0.75,
    /** Under [silenceTailDb] for at least this long at its end, or a song has no silence to remove. */
    val silenceMinDeadSec: Double = 0.5,
    /** A locked window shorter than the listener's mix length keeps this share of its weight. */
    val shortWeight: Double = 0.1,
    val fallbackSec: Double = 6.0,
    val fallbackMinSec: Double = 1.0,
    /** The fallback's entry starts this long before the incoming song's first sound. */
    val fallbackLeadSec: Double = 0.2,

    // ── Sound ──
    // Ours, set on renders of the bench corpus: frequencies glide in octaves, so a sweep is heard at an even pace.
    /** The short gain changes at the far ends of a span, and the last tenths of a gapless join. */
    val edgeCutSec: Double = 0.05,
    val joinCutSec: Double = 0.25,
    val silenceCutSec: Double = 0.1,
    /** How long a song's level takes at the edge of a filtered blend: the outgoing one's cut, the incoming one's arrival. */
    val handoverSec: Double = 0.25,
    /** The filtered blend's outgoing low-pass closes to here, Hz, slowly at first ([blendCloseBend] above 1). */
    val blendCloseHz: Double = 320.0,
    val blendCloseBend: Double = 1.6,
    /** The incoming song in a filtered blend: its high-pass as it enters, Hz, the bend of its opening, and its level then, dB. */
    val blendInHighHz: Double = 5_000.0,
    val blendInBend: Double = 1.3,
    val blendInDb: Double = -5.0,
    /** Delay out: the incoming song waits under the outgoing one this thin, Hz, and this low, dB, until it arrives. */
    val arriveHighHz: Double = 900.0,
    val arriveUnderDb: Double = -8.0,
    /** Delay out: the incoming song starts this far under that level, dB, and rises to it. */
    val arriveRiseDb: Double = -12.0,
    /** Delay out: the echo's length in beats, the send's level, and where the low-cut on the dry signal stops, Hz. */
    val delayBeats: Double = 0.75,
    val delaySend: Double = 0.6,
    val delayLowCutHz: Double = 180.0,
    /** Loop out: where the outgoing low-pass ends, Hz; the incoming song's level and high-pass as the window starts, and just before it arrives. */
    val loopCloseHz: Double = 180.0,
    val loopInDb: Double = -9.0,
    val loopInHighHz: Double = 1_400.0,
    val loopNearDb: Double = -2.0,
    val loopNearHighHz: Double = 200.0,
    /** Reverb out: the send's level and how long it takes to rise. */
    val roomSend: Double = 0.6,
    val roomRiseSec: Double = 1.5,
    /** Where a locked reverb out starts, share of the span. */
    val roomStart: Double = 0.35,

    // ── Ideas (see [MixIdea]) ──
    /** Clear vocals: a clash costs this many times more, and the outgoing song's core band is turned down by this, dB, under the incoming voice. */
    val vocalClearFactor: Double = 3.0,
    val vocalCarveDb: Double = -7.0,
    val vocalCarveSec: Double = 0.4,
    /** Echo tail: the send's level on the outgoing song's last beat, and the echo's length in beats (seconds without a beat). */
    val tailSend: Double = 0.5,
    val tailBeats: Double = 0.75,
    val tailFadeShare: Double = 0.35,
    /** Fades on the beat: the join may move by this share of a beat at most, on a beat held steady over this many bars. */
    val snapMaxBeat: Double = 0.5,
    val snapBars: Int = 8,
    /** Style by energy: how much more often the fitting kind of style is drawn, and the level under the main one that counts as loud, dB. */
    val energyFavour: Double = 1.8,
    val energyLoudDb: Double = 3.0,
    /** Gentle tempo: the most either song may change speed. */
    val gentleMaxRate: Double = 0.08,
)
