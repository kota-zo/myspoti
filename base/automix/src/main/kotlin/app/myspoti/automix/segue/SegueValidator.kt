/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

/** Every way a plan can be unusable; an empty list means it may be executed. */
enum class SegueViolation {
    SCHEMA,
    AUDIO_KEY_MISSING,
    RATE_NOT_POSITIVE,
    LENGTH_NOT_POSITIVE,
    ANCHOR_NEGATIVE,
    LOCKED_NEEDS_GRID_KEYS,
    LOCKED_NEEDS_TEMPO,
    FREE_HAS_TEMPO,
    DUPLICATE_LANE,
    UNIT_MISMATCH,
    KNOTS_NOT_INCREASING,
    KNOTS_OUT_OF_RANGE,
    KNOTS_NOT_SPANNING,
    FORM_NEEDS_FREQUENCY,
    FREQUENCY_NOT_POSITIVE,
    EASE_NOT_POSITIVE,
    GESTURE_INVALID,
    WINDOW_PAST_OUTGOING,
    WINDOW_PAST_INCOMING,
    STALE_OUTGOING_AUDIO,
    STALE_OUTGOING_GRID,
    STALE_INCOMING_AUDIO,
    STALE_INCOMING_GRID,
}

/** What the current analyses say a plan must match. */
class SegueSideFacts(val audioKey: String, val gridKey: String?, val durationUs: Long)

object SegueValidator {
    /** The rules a plan can be judged on alone. */
    fun structural(p: SeguePlan): List<SegueViolation> {
        val out = LinkedHashSet<SegueViolation>()
        if (p.schema != SegueFormat.SCHEMA) out += SegueViolation.SCHEMA
        for (s in listOf(p.outgoing, p.incoming)) {
            if (s.audioKey.isBlank()) out += SegueViolation.AUDIO_KEY_MISSING
            if (s.ratePpm <= 0 || s.listenerRate <= 0) out += SegueViolation.RATE_NOT_POSITIVE
            if (s.anchorUs < 0) out += SegueViolation.ANCHOR_NEGATIVE
        }
        if (p.span.lengthUs <= 0) out += SegueViolation.LENGTH_NOT_POSITIVE
        when (p.pulse.state) {
            PulseState.LOCKED -> {
                if (p.outgoing.gridKey.isNullOrBlank() || p.incoming.gridKey.isNullOrBlank()) out += SegueViolation.LOCKED_NEEDS_GRID_KEYS
                if ((p.pulse.tempoMicroPerMinute ?: 0) <= 0) out += SegueViolation.LOCKED_NEEDS_TEMPO
            }
            PulseState.FREE -> if (p.pulse.tempoMicroPerMinute != null) out += SegueViolation.FREE_HAS_TEMPO
            PulseState.LOOSE -> Unit
        }
        if (p.lanes.map { it.side to it.target }.toSet().size != p.lanes.size) out += SegueViolation.DUPLICATE_LANE
        for (lane in p.lanes) lane(lane, out)
        for (g in p.gestures) if (g.atUs < 0 || g.units <= 0) out += SegueViolation.GESTURE_INVALID
        return out.toList()
    }

    private fun lane(lane: SeguePlan.Lane, out: MutableSet<SegueViolation>) {
        if (lane.unit != lane.target.unit) out += SegueViolation.UNIT_MISMATCH
        val k = lane.knots
        if (k.isEmpty() || k.first().t != 0 || k.last().t != SegueFormat.T_MAX) out += SegueViolation.KNOTS_NOT_SPANNING
        for (i in k.indices) {
            if (k[i].t !in 0..SegueFormat.T_MAX) out += SegueViolation.KNOTS_OUT_OF_RANGE
            if (i > 0 && k[i].t <= k[i - 1].t) out += SegueViolation.KNOTS_NOT_INCREASING
            val form = k[i].form
            if (form == KnotForm.Octave && lane.unit != SegueUnit.HZ) out += SegueViolation.FORM_NEEDS_FREQUENCY
            if (form is KnotForm.Ease && form.k <= 0) out += SegueViolation.EASE_NOT_POSITIVE
            if (lane.unit == SegueUnit.HZ && (k[i].valueMilli ?: 1) <= 0) out += SegueViolation.FREQUENCY_NOT_POSITIVE
        }
    }

    /** Structural rules, then the rules that need the songs: the window fits and the analyses are the plan's. */
    fun against(p: SeguePlan, a: SegueSideFacts, b: SegueSideFacts): List<SegueViolation> {
        val out = LinkedHashSet(structural(p))
        if (out.isEmpty()) {
            if (windowEnd(p, p.outgoing) > a.durationUs) out += SegueViolation.WINDOW_PAST_OUTGOING
            if (windowEnd(p, p.incoming) > b.durationUs) out += SegueViolation.WINDOW_PAST_INCOMING
        }
        out += stale(p, a, b)
        return out.toList()
    }

    /** A changed audio or grid key means the plan is recomputed, never replayed. */
    fun stale(p: SeguePlan, a: SegueSideFacts, b: SegueSideFacts): List<SegueViolation> = buildList {
        if (p.outgoing.audioKey != a.audioKey) add(SegueViolation.STALE_OUTGOING_AUDIO)
        if (p.outgoing.gridKey != null && p.outgoing.gridKey != a.gridKey) add(SegueViolation.STALE_OUTGOING_GRID)
        if (p.incoming.audioKey != b.audioKey) add(SegueViolation.STALE_INCOMING_AUDIO)
        if (p.incoming.gridKey != null && p.incoming.gridKey != b.gridKey) add(SegueViolation.STALE_INCOMING_GRID)
    }

    /** Where the window ends in [s]'s source time, its speed applied. */
    fun windowEnd(p: SeguePlan, s: SeguePlan.Side): Long =
        s.anchorUs + if (p.span.clock == Clock.SOURCE) p.span.lengthUs else p.span.lengthUs * s.ratePpm / SegueFormat.RATE_NOMINAL
}
