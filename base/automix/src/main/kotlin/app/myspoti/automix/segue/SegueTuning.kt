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
    /** Largest speed change in the locked class, ppm. [U] range 4–8 %, the top of it: the stretch keeps the pitch, and the bench lost most pairs to this guard. */
    val maxRateDeviationPpm: Long = 80_000,
    /** Expected phase error: median [U], against double attacks. */
    val phaseMedianMaxUs: Long = 20_000,
    /** Expected phase error: 95th percentile [U]. */
    val phaseP95MaxUs: Long = 40_000,
    /** Drift over the window, in beats [I]: an unstable grid is rejected. */
    val driftMaxBeats: Double = 0.1,
    /** Least grid reliability: beats [R], share of beats on the grid [R], drift over 8 beats in ms [R]. */
    val gridMinBeats: Int = 16,
    val gridMinSteadiness: Double = 0.6,
    val gridMaxDriftMs: Double = 30.0,
    /** A stretch of bars must have this share of its beats from the model [I]. */
    val stretchMinBeatShare: Double = 0.85,
    /** And the model must be this sure of its downbeats, on average [I]. */
    val stretchMinDownConfidence: Double = 0.5,
    /** Two voices at comparable levels, tolerated total [I] range 1–2 s. */
    val voiceOverlapMaxUs: Long = 1_000_000,
    /** Content removed at the end of a song: share of it [I] range 20–30 %, and a cap [I]. */
    val contentCutMaxShare: Double = 0.20,
    val contentCutMaxUs: Long = 60_000_000,
    /** Least playing time before a song may leave [I] range 30–60 s. */
    val minPlayedUs: Long = 60_000_000,
    /** Loudness jump at a cut [U]. */
    val loudnessJumpMaxDb: Double = 3.0,
    /** No more silence than this at both sides of a junction: the album means it to run on [I]. */
    val gaplessMaxSilenceUs: Long = 300_000,

    // ── Score: weights and penalties. Strong 1.0, medium 0.5, weak 0.2, weak-to-medium 0.35: all [I] ──
    val weightRhythm: Double = 1.0,
    val weightPhrase: Double = 1.0,
    val penaltySpeed: Double = 0.5,
    val weightEnergy: Double = 0.5,
    val penaltyVoice: Double = 1.0,
    /** Voice left unheard: after the window on the way out, before the entry on the way in [I]. */
    val penaltyVoiceCut: Double = 0.5,
    val weightExit: Double = 0.5,
    val weightEntry: Double = 0.5,
    val penaltyBass: Double = 0.5,
    val weightKey: Double = 0.2,
    val penaltyLost: Double = 0.35,
    /** Choosing within a level: this much music skipped, both songs together [I], costs this much [I], locked or faded. */
    val lostFullUs: Long = 20_000_000,
    val lostPickLocked: Double = 0.4,
    val lostPickFade: Double = 1.0,
    /** A cut skipping more than this, both songs together, is left to the short fade [I]. */
    val hardLostMaxUs: Long = 12_000_000,
    /** The last resort over a voice: a fade this short at the very end [I]. */
    val simpleVoiceFadeUs: Long = 2_500_000,
    val penaltySobriety: Double = 0.2,

    // ── Shapes of the score's terms ──
    /** Phase error under which the rhythm term is full [U] range 10–20 ms; it reaches 0 at [phaseMedianMaxUs]. */
    val rhythmFullUs: Long = 10_000,
    /** Speed change under which it costs nothing, ppm [U] range 2–3 %. */
    val rateCostFreePpm: Long = 20_000,
    /** Width of the energy continuity Gaussian, dB [U] range 3–6. */
    val energySigmaDb: Double = 3.0,
    /** Entry note on a later section, and anywhere else [I]; the first attack scores 1. */
    val entryNoteSection: Double = 0.6,
    val entryNoteOther: Double = 0.3,
    /** Phrase note per side for a boundary at the middle or end of a stretch, against 0.5 at its start [I]. */
    val phraseNoteInside: Double = 0.25,
    /** Per-bar voice-like risk from which a bar counts as sung or rapped [R]. */
    val voiceBarRisk: Double = 0.6,
    /** Effects at which the sobriety defect is full [I]. */
    val sobrietyFullEffects: Int = 3,
    /** Points sampled along the window for the common bass energy [I]. */
    val bassSampleSteps: Int = 32,

    // ── Reliability of what cannot state its own [I] ──
    /** Structure where only the fallback phrase grid speaks, no local boundary. */
    val reliabilityPhraseFallback: Double = 0.3,
    /** Voice heuristic: stereo uses the centre of the image, mono has much less to go on. */
    val reliabilityVocalStereo: Double = 0.6,
    val reliabilityVocalMono: Double = 0.2,
    /** Reliability from which the longest, then the middle length is allowed. */
    val reliabilityForLong: Double = 0.8,
    val reliabilityForMiddle: Double = 0.6,

    // ── Class thresholds: the score a class's best candidate must reach ──
    /** Same value as the earlier blend floor [R]; its scale differs, so this is a proposal to measure. */
    val thresholdLocked: Double = 0.5,
    /** [I] */
    val thresholdFiltered: Double = 0.25,
    /** [I] */
    val thresholdHard: Double = 0.25,

    // ── Placement ──
    /** Locked lengths in bars [U]. */
    val lockedLongBars: Int = 16,
    val lockedMiddleBars: Int = 8,
    val lockedShortBars: Int = 4,
    val lockedTinyBars: Int = 2,
    /** Half or double time: two grooves meeting every other beat, kept short [R]. */
    val halfDoubleMaxBars: Int = 8,
    /** Filtered fade lengths [U] range 4–12 s; the middle one is [I]. */
    val filteredLongUs: Long = 8_000_000,
    val filteredMiddleUs: Long = 6_000_000,
    val filteredShortUs: Long = 4_000_000,
    /** Exits lie in this last share of the song. */
    val exitZoneShare: Double = 1.0 / 3.0,
    /** Entries lie within this much of the first attack [R]. */
    val entryZoneUs: Long = 40_000_000,
    /** Best candidates kept per side [I] range 3–8. */
    val candidatesPerSide: Int = 8,
    /** The latest exits always tried besides the best-ranked [I]. */
    val lateCandidates: Int = 4,
    /** Anti-click fade of a cut [U] range 5–30 ms. */
    val microFadeUs: Long = 30_000,
    /** Plain short fade [R]. */
    val simpleFadeUs: Long = 6_000_000,

    // ── Lane shapes ──
    /** Band "off", dB [R]. */
    val bassKillDb: Double = -30.0,
    /** The bass changes hands over this many beats [R], within this share of the window [R]. */
    val bassSwapBeats: Int = 1,
    /** Outgoing top end: from this share of the window, down to this level [R]. */
    val outgoingTopFrom: Double = 0.7,
    val outgoingTopDb: Double = -15.0,
    /** Incoming top end held back until the swap [R]. */
    val incomingTopDb: Double = -6.0,
    /** Incoming fader at the swap [R]. */
    val incomingBedGain: Double = 0.8,
    /** Filtered: bass hand-over [R], incoming top end [R], incoming fader at the hand-over [R]. */
    val filteredSwapShare: Double = 0.6,
    val filteredIncomingTopDb: Double = -9.0,
    val filteredBedGain: Double = 0.6,
    /** Filtered: the outgoing song's filter closes to this cutoff [I] with this accent [I]. */
    val sweepClosedHz: Double = 400.0,
    val sweepPeakRatio: Double = 1.2,
    /** The outgoing song's high-pass rises to this cutoff [I]. */
    val riseClosedHz: Double = 1_200.0,
    /** Send levels at their height [I]: echo, room. */
    val echoSend: Double = 0.5,
    val roomSend: Double = 1.5,
    /** How much of a room send comes back out at its loudest, against the fader's bound [M]. */
    val roomWetShare: Double = 0.25,
    /** Where in a fade the outgoing song is thrown into the echo [I]: late, so the repeats have the end of the window to themselves. */
    val echoThrowShare: Double = 0.72,
    /** An echo's delay stays between these [I]: shorter is a flam, longer loses the pulse. */
    val echoMinUs: Long = 240_000,
    val echoMaxUs: Long = 620_000,
    /** Middle band handed over in a three-band shape: outgoing down to, incoming up from [I]. */
    val coreOutDb: Double = -24.0,
    val coreInDb: Double = -12.0,
    /** Top band of the incoming song held back in a three-band shape [I]. */
    val topInDb: Double = -12.0,
    /** A cut on the beat lasts this share of a beat [I]: quick, without a click. */
    val cutBeatShare: Double = 0.25,
    /** The two faders' largest sum [I]: 1 can never clip two peaks that meet, and dips by 3 dB at equal gains. */
    val overlapMaxSum: Double = 1.1,
    /** Knots per equal-power curve [I]: enough that the steps between them are not heard. */
    val curveKnots: Int = 16,
    /** Loudness gap left alone, and the largest one corrected, dB [R]. */
    val loudnessMatchFromDb: Double = 1.5,
    val loudnessMatchToDb: Double = 6.0,
    /** The louder outgoing song is brought down over this share of the window [R]. */
    val matchEaseShare: Double = 0.5,
    /** Tempo return after the window: share of the speed per beat [R], least beats [R]. */
    val restoreRatePerBeat: Double = 0.001,
    val restoreMinBeats: Double = 16.0,

    // ── The engine's sound ──
    /** Automation is read every this many frames, gains interpolated between [R]. */
    val blockFrames: Int = 64,
    /** Band edges of the shelves [R]. */
    val shelfLowHz: Double = 180.0,
    val shelfHighHz: Double = 2_500.0,
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
        /** The band where two bass lines mask each other [U]. Fixed at analysis: not varied by the harness. */
        const val BASS_LOW_HZ = 20.0
        const val BASS_HIGH_HZ = 150.0

        /** Measurement: how far each parameter is varied [I], and the corpus split for tuning, validation, final test [I]. */
        const val STABILITY_RANGE = 0.20
        const val SPLIT_TUNING_SHARE = 0.60
        const val SPLIT_VALIDATION_SHARE = 0.20
    }
}
