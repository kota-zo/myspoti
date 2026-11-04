/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

/**
 * A transition as data: the policy writes it, the audio engine executes it and decides nothing.
 * Times are integer microseconds; modulations are physical quantities, never lookup indexes.
 * See docs/automix/segue-design.md for why each field exists.
 */
data class SeguePlan(
    val origin: Origin,
    val outgoing: Side,
    val incoming: Side,
    val span: Span,
    val pulse: Pulse,
    val lanes: List<Lane>,
    val gestures: List<Gesture> = emptyList(),
    val schema: String = SegueFormat.SCHEMA,
) {
    data class Origin(val engine: String, val corpus: String)

    data class Side(
        val audioKey: String,
        val gridKey: String?,
        val anchorUs: Long,
        val ratePpm: Long,
        val listenerRate: Long = SegueFormat.RATE_NOMINAL,
    )

    data class Span(val lengthUs: Long, val musicalUnits: Int?, val unitKind: UnitKind, val clock: Clock)

    data class Pulse(val state: PulseState, val tempoMicroPerMinute: Long?, val residualUs: Long?)

    data class Lane(val side: LaneSide, val target: Target, val unit: SegueUnit, val knots: List<Knot>)

    /** [valueMilli] is thousandths of the lane's unit; null is silence or neutrality (see [Target]). */
    data class Knot(val t: Int, val valueMilli: Long?, val form: KnotForm)

    data class Gesture(val kind: GestureKind, val atUs: Long, val units: Int)

    fun side(which: LaneSide): Side = if (which == LaneSide.OUTGOING) outgoing else incoming
    fun lanesOf(which: LaneSide): List<Lane> = lanes.filter { it.side == which }
    /** A position in [which]'s source time, in elapsed microseconds from the start of the span. */
    fun elapsedUs(which: LaneSide, sourceUs: Long): Long {
        val s = side(which)
        return Math.floorDiv((sourceUs - s.anchorUs) * SegueFormat.RATE_NOMINAL, s.ratePpm)
    }
}

enum class UnitKind(val wire: String) { BAR("bar"), BEAT("beat") }
enum class Clock(val wire: String) { ELAPSED("elapsed"), SOURCE("source") }
enum class PulseState(val wire: String) { LOCKED("locked"), LOOSE("loose"), FREE("free") }
enum class LaneSide(val wire: String) { OUTGOING("outgoing"), INCOMING("incoming") }
enum class SegueUnit(val wire: String) { DB("db"), HZ("hz"), RATIO("ratio") }
enum class GestureKind(val wire: String) { REPEAT("repeat") }

/**
 * What a lane modulates, its only permitted unit, what a null knot means, and the number a null
 * stands for when a ramp reaches or leaves it.
 */
enum class Target(val wire: String, val unit: SegueUnit, val nullMeaning: String, val nullAs: Double) {
    LEVEL("level", SegueUnit.DB, "silence", SegueFormat.LEVEL_FLOOR_DB),
    SHELF_BOTTOM("shelfBottom", SegueUnit.DB, "neutral", 0.0),
    BAND_CORE("bandCore", SegueUnit.DB, "neutral", 0.0),
    SHELF_TOP("shelfTop", SegueUnit.DB, "neutral", 0.0),
    SWEEP_HZ("sweepHz", SegueUnit.HZ, "filter open", SegueFormat.SWEEP_OPEN_HZ),
    SWEEP_PEAK("sweepPeak", SegueUnit.RATIO, "neutral", 1.0),
    SEND_ROOM("sendRoom", SegueUnit.RATIO, "no send", 0.0),
    SEND_ECHO("sendEcho", SegueUnit.RATIO, "no send", 0.0),
}

/** How the lane arrives on a knot from the previous one. */
sealed class KnotForm(val wire: String) {
    object Step : KnotForm("step")
    object Ramp : KnotForm("ramp")
    object Octave : KnotForm("octave")
    /** Curvature: position^(k / [SegueFormat.EASE_UNIT]), so k = EASE_UNIT is a plain ramp. */
    data class Ease(val k: Int) : KnotForm("ease:$k")

    override fun toString() = wire

    companion object {
        fun parse(text: String): KnotForm? = when {
            text == "step" -> Step
            text == "ramp" -> Ramp
            text == "octave" -> Octave
            text.startsWith("ease:") -> text.drop(5).takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toIntOrNull()?.let(::Ease)
            else -> null
        }
    }
}

/** A lane read at any point of the window. */
class LaneCurve(private val lane: SeguePlan.Lane) {
    private val ts = IntArray(lane.knots.size) { lane.knots[it].t }
    private val nullAs = lane.target.nullAs

    /** The numeric value at [t] (normalised), a null knot standing for its [Target.nullAs]. */
    fun value(t: Double): Double {
        val k = lane.knots
        if (t <= ts.first()) return number(k.first())
        if (t >= ts.last()) return number(k.last())
        var lo = 0; var hi = ts.size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (ts[m] <= t) lo = m else hi = m }
        val a = number(k[lo]); val b = number(k[hi])
        val u = (t - ts[lo]) / (ts[hi] - ts[lo])
        return when (val f = k[hi].form) {
            KnotForm.Step -> a
            KnotForm.Ramp -> a + (b - a) * u
            KnotForm.Octave -> a * Math.pow(b / a, u)
            is KnotForm.Ease -> a + (b - a) * Math.pow(u, f.k.toDouble() / SegueFormat.EASE_UNIT)
        }
    }

    /** True at [t] exactly when the lane sits on a null knot: silence is not merely a very low level. */
    fun isNull(t: Double): Boolean {
        val k = lane.knots
        if (t <= ts.first()) return k.first().valueMilli == null
        if (t >= ts.last()) return k.last().valueMilli == null
        var lo = 0; var hi = ts.size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (ts[m] <= t) lo = m else hi = m }
        return k[lo].valueMilli == null && (k[hi].valueMilli == null || k[hi].form == KnotForm.Step)
    }

    private fun number(k: SeguePlan.Knot) = k.valueMilli?.let { it / SegueFormat.VALUE_SCALE.toDouble() } ?: nullAs
}
