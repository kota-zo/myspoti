/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

enum class SegueGuard { RATE, PHASE_MEDIAN, PHASE_P95, DRIFT, GRID_RELIABILITY, VOICE, CONTENT_CUT, MIN_PLAYED, LOUDNESS_JUMP, ALBUM }

/** The treatments a plan can have. Scores are comparable only within one of them. */
enum class SegueClass { LOCKED, FILTERED, HARD, FADE }

/** What the guards read of a candidate, measured apart from any score. */
data class SegueMeasures(
    val rateDeviationPpm: Long = 0,
    /** Null: the phase could not be measured, which a locked candidate cannot accept. */
    val phaseMedianUs: Long? = 0,
    val phaseP95Us: Long? = 0,
    val driftBeats: Double = 0.0,
    val gridReliable: Boolean = true,
    val voiceOverlapUs: Long = 0,
    val contentCutUs: Long = 0,
    val contentCutShare: Double = 0.0,
    val playedUs: Long = Long.MAX_VALUE,
    val loudnessJumpDb: Double = 0.0,
    val albumContinuity: Boolean = false,
)

object SegueGuards {
    /** Every guard the candidate crosses for [cls]. Any one rejects it; nothing compensates. */
    fun crossed(cls: SegueClass, m: SegueMeasures, t: SegueTuning): List<SegueGuard> = buildList {
        if (m.albumContinuity) add(SegueGuard.ALBUM)
        if (m.contentCutUs > t.contentCutMaxUs || m.contentCutShare > t.contentCutMaxShare) add(SegueGuard.CONTENT_CUT)
        if (m.playedUs < t.minPlayedUs) add(SegueGuard.MIN_PLAYED)
        if (cls != SegueClass.HARD && m.voiceOverlapUs > t.voiceOverlapMaxUs) add(SegueGuard.VOICE)
        if (cls == SegueClass.LOCKED) {
            if (m.rateDeviationPpm > t.maxRateDeviationPpm) add(SegueGuard.RATE)
            if (m.phaseMedianUs == null || m.phaseMedianUs > t.phaseMedianMaxUs) add(SegueGuard.PHASE_MEDIAN)
            if (m.phaseP95Us == null || m.phaseP95Us > t.phaseP95MaxUs) add(SegueGuard.PHASE_P95)
            if (m.driftBeats > t.driftMaxBeats) add(SegueGuard.DRIFT)
        }
        if ((cls == SegueClass.LOCKED || cls == SegueClass.HARD) && !m.gridReliable) add(SegueGuard.GRID_RELIABILITY)
        if (cls == SegueClass.HARD && m.loudnessJumpDb > t.loudnessJumpMaxDb) add(SegueGuard.LOUDNESS_JUMP)
    }
}
