/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** The kinds of transition, best first: a lower one is only used when no candidate of a better one survives. */
enum class OrpheonFamily(val wire: String) { LOCKED("locked"), SMOOTH("smooth"), SILENCE("silence"), FALLBACK("fallback") }

/** What a style does to the sound. Each is lanes and gestures in the plan: the deck knows nothing of them. */
enum class StyleSound { GAPLESS, FILTERED, DELAY_OUT, LOOP_OUT, REVERB_OUT, PLAIN_FADE, SILENCE_JOIN }

/** One pass of a loop out: from this share of the window, this many beats go round. */
data class LoopStep(val at: Double, val beats: Double)

/**
 * The point of its window where a style hands the lead to the incoming song. The planner lands that
 * point on the start of a phrase of the incoming song, so the song takes over where its own music turns.
 */
enum class Arrival {
    /** The incoming song leads from the window's first bar. */
    START,
    /** A quarter in: a long blend does its hand-over in the middle half. */
    QUARTER,
    /** One bar before the end: the outgoing song's last bar is only its tail. */
    BAR_BEFORE_END,
    /** The window's last bar line: the incoming song is alone exactly on its phrase. */
    END;

    /** Bars from the window's start to the arrival, for a window of [bars] of the incoming song. */
    fun bars(bars: Int): Int = when (this) {
        START -> 0
        QUARTER -> bars / 4
        BAR_BEFORE_END -> bars - 1
        END -> bars
    }
}

data class OrpheonStyle(
    val id: String,
    val family: OrpheonFamily,
    val sound: StyleSound,
    val genres: Set<GenreFamily>,
    /** Bar counts it plays over; a family without a grid has none. */
    val bars: Set<Int> = emptySet(),
    val minSec: Double = 0.0,
    val maxSec: Double = Double.MAX_VALUE,
    /** Where the speed ramps end, share of the window: a short style may reach the new tempo early and hold it. */
    val rampEnd: Double = 1.0,
    /** A long filtered blend: the tighter speed limit. */
    val longBlend: Boolean = false,
    val weight: Double = 1.0,
    val loops: List<LoopStep> = emptyList(),
    /** The length the style is written for: of its bar counts, the one nearest to it is preferred. */
    val targetSec: Double = 0.0,
    val arrive: Arrival = Arrival.END,
) {
    /**
     * Bars from the window's start to the hand-over: from there the outgoing song is gone, or only a tail,
     * and the incoming one stands alone. Its sound decides it, not where the planner lands its phrase.
     */
    fun handover(bars: Int): Int = when (sound) {
        StyleSound.DELAY_OUT -> (bars - 1).coerceAtLeast(0)
        StyleSound.FILTERED -> if (arrive == Arrival.QUARTER) bars * 3 / 4 else bars
        else -> bars
    }
}

/**
 * Our styles, by family, genre family and bar count. A style written for a family plays between two
 * songs of that family; any other pair gets the general blends. Weights are each style's share of the
 * pairs it can play, set from how often each kind of transition is heard on the reference recordings.
 */
object OrpheonStyles {
    private val ALL = GenreFamily.values().toSet()
    private val HIPHOP = setOf(GenreFamily.HIPHOP)
    private val DANCE = setOf(GenreFamily.DANCE)
    private val POP = setOf(GenreFamily.POP)
    private val OTHER = setOf(GenreFamily.OTHER)

    private const val SHORT_SEC = 7.2
    private const val MID_SEC = 14.5
    private const val LONG_SEC = 29.0

    private fun short(id: String, sound: StyleSound, genres: Set<GenreFamily>, weight: Double, arrive: Arrival, rampEnd: Double = 0.75, loops: List<LoopStep> = emptyList()) =
        OrpheonStyle(id, OrpheonFamily.LOCKED, sound, genres, setOf(2, 4), 5.4, 10.5, rampEnd, weight = weight, loops = loops, targetSec = SHORT_SEC, arrive = arrive)

    private fun mid(id: String, genres: Set<GenreFamily>, weight: Double) =
        OrpheonStyle(id, OrpheonFamily.LOCKED, StyleSound.FILTERED, genres, setOf(4, 8), 10.5, 21.0, longBlend = true, weight = weight, targetSec = MID_SEC, arrive = Arrival.START)

    private fun long(id: String, genres: Set<GenreFamily>, weight: Double) =
        OrpheonStyle(id, OrpheonFamily.LOCKED, StyleSound.FILTERED, genres, setOf(8, 16), 21.0, 40.0, longBlend = true, weight = weight, targetSec = LONG_SEC, arrive = Arrival.QUARTER)

    val CATALOGUE: List<OrpheonStyle> = listOf(
        short("hiphop-delay", StyleSound.DELAY_OUT, HIPHOP, 0.27, Arrival.BAR_BEFORE_END),
        short("hiphop-loop", StyleSound.LOOP_OUT, HIPHOP, 0.43, Arrival.END, rampEnd = 1.0, loops = listOf(LoopStep(0.5, 1.0))),
        mid("hiphop-filter", HIPHOP, 0.25),
        long("hiphop-long", HIPHOP, 0.05),
        short("dance-rush", StyleSound.LOOP_OUT, DANCE, 0.15, Arrival.END,
            loops = listOf(LoopStep(0.5, 1.0), LoopStep(0.75, 0.5), LoopStep(0.875, 0.25))),
        mid("dance-filter", DANCE, 0.6),
        long("dance-long", DANCE, 0.2),
        OrpheonStyle("dance-join", OrpheonFamily.LOCKED, StyleSound.GAPLESS, DANCE, setOf(2), 2.0, 6.0, rampEnd = 0.5, weight = 0.05, targetSec = 4.0),
        short("pop-delay", StyleSound.DELAY_OUT, POP, 0.3, Arrival.BAR_BEFORE_END),
        short("pop-reverb", StyleSound.REVERB_OUT, POP, 0.3, Arrival.END),
        mid("pop-filter", POP, 0.3),
        long("pop-long", POP, 0.1),
        short("blend-short", StyleSound.FILTERED, OTHER, 0.03, Arrival.END, rampEnd = 0.5),
        mid("blend", OTHER, 0.65),
        long("blend-long", OTHER, 0.32),
        OrpheonStyle("smooth-reverb", OrpheonFamily.SMOOTH, StyleSound.REVERB_OUT, POP + OTHER),
        OrpheonStyle("smooth-fade", OrpheonFamily.SMOOTH, StyleSound.PLAIN_FADE, ALL, weight = 0.8),
        OrpheonStyle("silence-join", OrpheonFamily.SILENCE, StyleSound.SILENCE_JOIN, ALL),
        OrpheonStyle("fade", OrpheonFamily.FALLBACK, StyleSound.PLAIN_FADE, ALL),
    )

    fun byId(id: String): OrpheonStyle? = CATALOGUE.firstOrNull { it.id == id }

    /** Those of [family] a pair of [genre] may use: its own when it has some, the general ones otherwise. */
    fun of(family: OrpheonFamily, genre: GenreFamily): List<OrpheonStyle> {
        val own = CATALOGUE.filter { it.family == family && genre in it.genres }
        return own.ifEmpty { CATALOGUE.filter { it.family == family && GenreFamily.OTHER in it.genres } }
    }
}

/** What the planner knows of the place it chose, for the lanes that depend on it. */
internal class StylePlace(
    /** The window's elapsed length. */
    val lengthSec: Double,
    /** Where it starts in the outgoing song and how much of that song it covers. */
    val startUs: Long,
    val spanUs: Long,
    /** That song's beats there: one beat's length and how many the window holds; no beat without a grid. */
    val beatSec: Double?,
    val beats: Int,
    /** Where in the window an incoming lead vocal starts over an outgoing one, null when none does. */
    val clashAt: Double? = null,
)

/** A style as the deck plays it, on the window's 0..1. */
internal class OrpheonLanes(private val tuning: SegueTuning, private val ideas: Set<MixIdea> = MixIdea.of(tuning.ideas)) {
    private val n = tuning.orpheon
    private val base = CompactLanes(tuning)
    private val out = LaneSide.OUTGOING
    private val inc = LaneSide.INCOMING
    private val open = SegueFormat.SWEEP_OPEN_HZ
    private val risen = SegueFormat.RISE_OPEN_HZ

    class Shape(val lanes: List<SeguePlan.Lane>, val gestures: List<SeguePlan.Gesture> = emptyList(), val echoUs: Long? = null)

    private fun k(x: Double, value: Double?, form: KnotForm = KnotForm.Ramp) = base.knot(x, value, form)

    fun shape(style: OrpheonStyle, place: StylePlace, eq: Boolean): Shape {
        val lengthSec = place.lengthSec
        // Without the filters a hand-over made of them would be two whole songs: the plain fade instead.
        val plain = !eq && style.sound != StyleSound.SILENCE_JOIN && style.sound != StyleSound.GAPLESS
        val shape = if (plain) Shape(base.fade()) else when (style.sound) {
            StyleSound.GAPLESS -> Shape(overlap(share(n.joinCutSec, lengthSec), lengthSec))
            StyleSound.SILENCE_JOIN -> Shape(overlap(share(n.silenceCutSec, lengthSec), lengthSec))
            StyleSound.FILTERED -> Shape(filtered(style.arrive == Arrival.QUARTER, lengthSec))
            StyleSound.DELAY_OUT -> delayOut(place)
            StyleSound.LOOP_OUT -> loopOut(style, place)
            StyleSound.REVERB_OUT -> Shape(reverbOut(style, lengthSec))
            StyleSound.PLAIN_FADE -> Shape(base.fade())
        }
        return if (eq) withIdeas(style, place, shape) else shape
    }

    private fun share(sec: Double, lengthSec: Double) = (sec / lengthSec).coerceIn(1e-4, 0.25)

    /** One beat of the outgoing song as a share of the window; a quarter of a second's worth without a beat. */
    private fun beat(place: StylePlace) = if (place.beats > 0) 1.0 / place.beats else share(0.25, place.lengthSec)

    /** Both songs at full level: the outgoing one stops in the last [cut] of the window, the incoming one is there at once. */
    private fun overlap(cut: Double, lengthSec: Double) = listOf(
        base.lane(out, Target.LEVEL, listOf(k(0.0, 0.0), k(1 - cut, 0.0), k(1.0, null))),
        base.lane(inc, Target.LEVEL, listOf(k(0.0, null), k(min(cut, share(n.edgeCutSec, lengthSec)), 0.0), k(1.0, 0.0))),
    )

    /**
     * A frequency gliding from [f0] to [f1] over [from]..[to] of the window, held outside it. The way is
     * counted in octaves, so the ear hears an even sweep; [bend] above 1 keeps most of it for the end,
     * under 1 spends most of it at once.
     */
    private fun glide(side: LaneSide, target: Target, from: Double, to: Double, f0: Double, f1: Double, bend: Double = 1.0): SeguePlan.Lane {
        val knots = ArrayList<SeguePlan.Knot>()
        if (from > 0) knots += k(0.0, f0)
        for (x in base.grid(from, to, 8)) knots += k(x, f0 * (f1 / f0).pow(((x - from) / (to - from)).pow(bend)), KnotForm.Octave)
        if (to < 1) knots += k(1.0, f1)
        return base.lane(side, target, knots)
    }

    private fun db(gain: Double) = 10.0.pow(gain / 20)

    /**
     * Both songs stay loud and the filters do the hand-over. The outgoing song's low-pass closes, slowly
     * then faster, until little but its low end is left, and it is cut there; the incoming song enters as
     * its top alone, a little under, and opens downwards, its low end coming last. A long blend does all
     * of it in the middle half, the incoming song silent before and the outgoing one gone after.
     */
    private fun filtered(middleHalf: Boolean, lengthSec: Double): List<SeguePlan.Lane> {
        val from = if (middleHalf) 0.25 else 0.0
        val to = if (middleHalf) 0.75 else 1.0
        val cut = share(n.handoverSec, lengthSec)
        val under = db(n.blendInDb)
        val xs = base.grid(from + cut, to, 8)
        return listOf(
            base.lane(out, Target.LEVEL, listOf(k(0.0, 0.0), k(to - cut, 0.0), k(to, null)) + if (to < 1) listOf(k(1.0, null)) else emptyList()),
            base.level(inc, listOf(0.0, from) + xs + 1.0) { x -> if (x <= from) 0.0 else if (x >= to) 1.0 else under + (1 - under) * ((x - from) / (to - from)) },
            glide(out, Target.SWEEP_HZ, from, to, open, n.blendCloseHz, n.blendCloseBend),
            glide(inc, Target.RISE_HZ, from, to, n.blendInHighHz, risen, n.blendInBend),
        )
    }

    /**
     * The outgoing song plays whole until one bar before the end, the incoming one coming up under it,
     * thin and low. On that bar line the incoming song opens and takes the lead, and the outgoing one is
     * gone but for an echo of its last beat, which rings through the window's last bar.
     */
    private fun delayOut(place: StylePlace): Shape {
        val beat = beat(place)
        val arrive = (1 - 4 * beat).coerceIn(0.25, 0.9)
        val jump = share(n.edgeCutSec, place.lengthSec)
        val under = db(n.arriveUnderDb)
        val far = db(n.arriveRiseDb)
        val lanes = listOf(
            base.lane(out, Target.LEVEL, listOf(k(0.0, 0.0), k(arrive - jump, 0.0), k(arrive, null), k(1.0, null))),
            glide(out, Target.RISE_HZ, max(0.0, arrive - 4 * beat), arrive, risen, n.delayLowCutHz),
            base.lane(out, Target.SEND_ECHO, listOf(k(0.0, 0.0), k(arrive - beat, 0.0), k(arrive - beat / 2, n.delaySend), k(arrive, n.delaySend), k(min(arrive + jump, 1.0), 0.0), k(1.0, 0.0))),
            // It comes up under the outgoing song rather than appearing there: heard by ear on build 3127 as a sound that "enters oddly".
            base.level(inc, listOf(0.0) + base.grid(jump, arrive - beat, 4) + listOf(arrive, 1.0)) { x ->
                if (x <= 0.0) 0.0 else if (x >= arrive) 1.0 else under * (far + (1 - far) * ((x - jump) / (arrive - beat - jump)).coerceIn(0.0, 1.0))
            },
            glide(inc, Target.RISE_HZ, arrive - beat, arrive, n.arriveHighHz, risen, 0.7),
        )
        val echo = place.beatSec?.let { (it * n.delayBeats * 1e6).roundToLong().coerceIn(1, SegueFormat.ECHO_MAX_US) }
        return Shape(lanes, echoUs = echo)
    }

    /**
     * The incoming song is there from the start, low and thin, rising and opening; the outgoing one goes
     * round its last beat from the middle on while its low-pass closes. On the window's last beat the
     * incoming song opens fully, so that it stands alone on its phrase.
     */
    private fun loopOut(style: OrpheonStyle, place: StylePlace): Shape {
        val beat = beat(place)
        val first = style.loops.first().at
        val near = (1 - beat).coerceIn(0.6, 0.98)
        val low = db(n.loopInDb); val nearly = db(n.loopNearDb)
        val lanes = listOf(
            base.lane(out, Target.LEVEL, listOf(k(0.0, 0.0), k(1 - share(n.edgeCutSec, place.lengthSec), 0.0), k(1.0, null))),
            glide(out, Target.SWEEP_HZ, min(first + beat, 0.9), 1.0, open, n.loopCloseHz, 1.2),
            base.level(inc, base.grid(0.0, near, 8) + 1.0) { x -> if (x >= near) nearly + (1 - nearly) * (x - near) / (1 - near) else low + (nearly - low) * (x / near) },
            base.lane(inc, Target.RISE_HZ, listOf(k(0.0, n.loopInHighHz), k(near, n.loopNearHighHz, KnotForm.Octave), k(1.0, risen, KnotForm.Octave))),
        )
        val gestures = if (place.beatSec == null) emptyList() else style.loops.map { step ->
            // On a beat of the song: the window's shares are of its span, beat for beat.
            val at = (step.at * place.beats).roundToInt().toDouble() / place.beats
            SeguePlan.Gesture(GestureKind.LOOP, place.startUs + (place.spanUs * at).roundToLong(), (place.beatSec * step.beats * 1e6).roundToInt())
        }
        return Shape(lanes, gestures)
    }

    /** The outgoing song dissolves into a reverb tail: the send rises while the dry level falls, the incoming song rising against it. */
    private fun reverbOut(style: OrpheonStyle, lengthSec: Double): List<SeguePlan.Lane> {
        val from = if (style.family == OrpheonFamily.SMOOTH) 0.0 else n.roomStart
        val rise = from + min(n.roomRiseSec / lengthSec, (1 - from) * 0.6)
        val end = 1 - share(n.edgeCutSec, lengthSec)
        val xs = base.grid(from, 1.0)
        return listOf(
            base.level(out, listOf(0.0) + xs) { x -> if (x <= from) 1.0 else cos(PI / 2 * (x - from) / (1 - from)) },
            base.level(inc, listOf(0.0) + xs) { x -> if (x <= from) 0.0 else sin(PI / 2 * (x - from) / (1 - from)) },
            // Back to no send at the end: the song runs on muted while the tail rings, and must not feed it.
            base.lane(out, Target.SEND_ROOM, listOf(k(0.0, 0.0), k(from, 0.0), k(rise, n.roomSend), k(end, n.roomSend), k(1.0, 0.0))),
        )
    }

    // ── Ideas: lanes added to a style's own, never replacing one it wrote ──

    private fun withIdeas(style: OrpheonStyle, place: StylePlace, shape: Shape): Shape {
        if (ideas.isEmpty()) return shape
        val lanes = shape.lanes.toMutableList()
        var echoUs = shape.echoUs
        fun has(side: LaneSide, target: Target) = lanes.any { it.side == side && it.target == target }
        val jump = share(n.edgeCutSec, place.lengthSec)
        if (MixIdea.BASS_HANDOVER in ideas) handover(style)?.let { at ->
            val kill = tuning.compact.bassKillDb
            if (!has(inc, Target.BASS_BAND)) lanes += base.lane(inc, Target.BASS_BAND, listOf(k(0.0, kill), k(max(0.0, at - jump), kill), k(at, 0.0), k(1.0, 0.0)))
            if (at < 1.0 && !has(out, Target.BASS_BAND)) lanes += base.lane(out, Target.BASS_BAND, listOf(k(0.0, 0.0), k(max(0.0, at - jump), 0.0), k(at, kill), k(1.0, kill)))
        }
        if (MixIdea.VOCAL_CLEAR in ideas && place.clashAt != null && !has(out, Target.BAND_CORE)) {
            val at = place.clashAt.coerceIn(0.0, 0.95)
            val from = max(0.0, at - share(n.vocalCarveSec, place.lengthSec))
            lanes += base.lane(out, Target.BAND_CORE, listOf(k(0.0, 0.0)) + (if (from > 0) listOf(k(from, 0.0)) else emptyList()) + listOf(k(max(at, from + 1e-3), n.vocalCarveDb), k(1.0, n.vocalCarveDb)))
        }
        if (MixIdea.ECHO_TAIL in ideas && !has(out, Target.SEND_ECHO) && !has(out, Target.SEND_ROOM)) leaving(style)?.let { (at, fade) ->
            val beat = beat(place)
            val from = if (fade) at - n.tailFadeShare else at - beat
            if (from > 0.0) {
                lanes += base.lane(out, Target.SEND_ECHO, listOf(k(0.0, 0.0), k(from, 0.0), k(if (fade) at - n.tailFadeShare / 2 else at - beat / 2, n.tailSend), k(at, n.tailSend), k(min(at + jump, 1.0), 0.0), k(1.0, 0.0)))
                echoUs = place.beatSec?.let { (it * n.tailBeats * 1e6).roundToLong().coerceIn(1, SegueFormat.ECHO_MAX_US) }
            }
        }
        return Shape(lanes, shape.gestures, echoUs)
    }

    /** Where the low end changes hands in a style, null when its own filters already keep the two apart. */
    private fun handover(style: OrpheonStyle): Double? = when (style.sound) {
        StyleSound.FILTERED -> if (style.arrive == Arrival.QUARTER) 0.75 else 1.0
        StyleSound.REVERB_OUT -> if (style.family == OrpheonFamily.SMOOTH) 0.5 else n.roomStart + (1 - n.roomStart) / 2
        StyleSound.PLAIN_FADE -> 0.5
        else -> null
    }

    /** Where the outgoing song leaves a style and whether it fades there (else it is cut), null when it has its own tail or ends by itself. */
    private fun leaving(style: OrpheonStyle): Pair<Double, Boolean>? = when (style.sound) {
        StyleSound.FILTERED -> (if (style.arrive == Arrival.QUARTER) 0.75 else 1.0) - 1e-3 to false
        StyleSound.LOOP_OUT, StyleSound.GAPLESS -> 1.0 - 1e-3 to false
        StyleSound.PLAIN_FADE -> 0.85 to true
        else -> null
    }
}
