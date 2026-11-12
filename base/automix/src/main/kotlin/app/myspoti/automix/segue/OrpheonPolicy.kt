/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.DurationBounds
import app.myspoti.automix.MixPlanner
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** A request as the Orpheon planner reads it: nothing here depends on one analyser. */
class OrpheonRequest(
    val earliestExitUs: Long = 0,
    val listenerRate: Long = SegueFormat.RATE_NOMINAL,
    val maxRatePpm: Long? = null,
    val maxBars: Int? = null,
    /**
     * The listener's mix length, in seconds: windows shorter than [fadeSec] are kept only for when no
     * longer one fits (the planner's own taste is short, like the reference it was tuned on), and the
     * plain fade lasts [fadeSec]. [minBars] is kept for the request's shape and is not read any more.
     */
    val minBars: Int? = null,
    val fadeSec: Double? = null,
    val eq: Boolean = true,
    val harmonic: Boolean = true,
    val sameAlbum: Boolean = false,
    val tuning: SegueTuning = SegueTuning(),
    val durationBounds: DurationBounds = DurationBounds(),
)

/** The decision, with what the bench and the tests look at. */
class OrpheonOutcome(val decision: SegueDecision, val family: OrpheonFamily?, val style: OrpheonStyle?, val survivors: Int)

/**
 * The Orpheon planner: pure Kotlin, reading only [SongMap]s. Each family is tried in turn, best
 * first (beat-locked, smooth blend, silence removal, the plain fade); inside the first family that
 * has survivors, one candidate is drawn with a probability proportional to its score by a generator
 * seeded from the pair, so the same pair gets the same transition.
 */
internal object OrpheonPolicy {
    val ORIGIN = SeguePlan.Origin("myspoti-orpheon-1", "uncalibrated")

    private val RATIOS = doubleArrayOf(1.0, 0.5, 2.0)

    /** For the bench: every locked place scored, with what each cost took from it. Null in the app. */
    @Volatile internal var trace: ((String) -> Unit)? = null

    /** How far a family read from a catalogue is trusted (the analyser's own guess is far below). */
    private const val CATALOGUE_GENRE_CONFIDENCE = 0.9

    fun decide(r: SegueRequest): SegueDecision = plan(
        SongMaps.from(r.a, r.tuning, r.genreA, CATALOGUE_GENRE_CONFIDENCE),
        SongMaps.from(r.b, r.tuning, r.genreB, CATALOGUE_GENRE_CONFIDENCE), OrpheonRequest(
        r.earliestExitUs, r.listenerRate, r.maxRatePpm, r.maxBars, r.minBars, r.fadeSec, r.eq, r.harmonic, r.sameAlbum, r.tuning, r.durationBounds)).decision

    /** [only] keeps a single style of the catalogue: for listening to one style, and for the tests. */
    fun plan(a: SongMap, b: SongMap, r: OrpheonRequest, only: String? = null): OrpheonOutcome = Run(a, b, r, only).decide()

    /** FNV-1a, 64 bits: the same pair gives the same seed on every platform. */
    internal fun seed(a: String, b: String): Long {
        var h = -3750763034362895579L
        for (c in "$a|$b") h = (h xor c.code.toLong()) * 1099511628211L
        return h
    }

    /** SplitMix64: one fixed generator, so a draw does not depend on the platform's. */
    private class Draws(private var state: Long) {
        fun next(): Double {
            state += -7046029254386353131L
            var z = state
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            z = z xor (z ushr 31)
            return (z ushr 11).toDouble() / (1L shl 53).toDouble()
        }
    }

    private fun us(sec: Double): Long = (sec * 1e6).roundToLong()

    private class Candidate(
        val style: OrpheonStyle,
        val outSec: Double,
        val inSec: Double,
        /** Seconds of each song the window covers. */
        val spanA: Double,
        val spanB: Double,
        val bars: Int,
        val barsA: Int,
        val score: Double,
        /** Its style's weight in the draw. */
        val share: Double = style.weight,
    )

    private class Run(val a: SongMap, val b: SongMap, val r: OrpheonRequest, val only: String?) {
        val all = r.tuning
        val t = all.orpheon
        val bounds = r.durationBounds.forPlaybackRate(r.listenerRate.toDouble() / SegueFormat.RATE_NOMINAL)
        val ideas = MixIdea.of(all.ideas)
        val lanes = OrpheonLanes(all, ideas)
        val attempts = ArrayList<SegueAttempt>()
        val draws = Draws(seed(a.audioKey, b.audioKey))
        val earliestAsked = r.earliestExitUs / 1e6
        val earliest = earliestAsked + t.safetySec
        val duration = a.durationSec
        val exitFloor = max(earliest, duration - max(0.0, min(t.exitLeadSec, (duration - t.exitShortSongSec) / 2)))
        val musicEndA = a.musicEndSec(t.silenceBelowMainDb)
        val musicStartB = b.musicStartSec(t.silenceBelowMainDb)
        /** Where the outgoing song's body ends: where a window should finish. */
        val loudEndA = a.musicEndSec(t.exitLoudDb)
        /** The family whose styles play: one both songs share, the general blends for any other pair. */
        val genre = pairGenre()
        val facts = SegueSideFacts(a.audioKey, a.gridKey, us(a.durationSec)) to SegueSideFacts(b.audioKey, b.gridKey, us(b.durationSec))

        fun decide(): OrpheonOutcome {
            if (r.sameAlbum && us(duration - musicEndA) <= all.gaplessMaxSilenceUs && us(musicStartB) <= all.gaplessMaxSilenceUs)
                return OrpheonOutcome(SegueDecision(SegueLevel.NORMAL, null, listOf(SegueAttempt(SegueLevel.LOCKED_SHORT, SegueCause.ALBUM_CONTINUITY))), null, null, 0)
            for (family in OrpheonFamily.values()) {
                val survivors = when (family) {
                    OrpheonFamily.LOCKED -> locked()
                    OrpheonFamily.SMOOTH -> smooth()
                    OrpheonFamily.SILENCE -> silence()
                    OrpheonFamily.FALLBACK -> fallback()
                }.filter { only == null || it.style.id == only }
                pick(family, survivors)?.let { return it }
            }
            return OrpheonOutcome(SegueDecision(SegueLevel.NORMAL, null, attempts.toList()), null, null, 0)
        }

        private fun level(family: OrpheonFamily, bars: Int) = when {
            family != OrpheonFamily.LOCKED -> SegueLevel.SIMPLE_FADE
            bars >= 16 -> SegueLevel.LOCKED_LONG
            bars <= 2 -> SegueLevel.LOCKED_BEAT
            else -> SegueLevel.LOCKED_SHORT
        }

        private fun pick(family: OrpheonFamily, survivors: List<Candidate>): OrpheonOutcome? {
            var left = survivors.sortedWith(compareBy<Candidate>({ it.outSec }, { it.inSec }, { it.bars }, { it.style.id }))
            while (left.isNotEmpty()) {
                val chosen = draw(left)
                val plan = build(family, chosen)
                val problems = SegueValidator.against(plan, facts.first, facts.second)
                if (problems.isEmpty()) return OrpheonOutcome(
                    SegueDecision(level(family, chosen.bars), plan, attempts.toList(), chosen.score, style = "${family.wire}/${chosen.style.id}" + ideas.joinToString("") { "+${it.wire}" }),
                    family, chosen.style, survivors.size)
                attempts += SegueAttempt(level(family, chosen.bars), SegueCause.INVALID_PLAN, problems.joinToString(","))
                left = left - chosen
            }
            return null
        }

        /**
         * First a style, each weighing its share of the catalogue times how well its best place scores;
         * then a place for it, the best ones taking nearly all of the draw. A style that finds many places
         * has no more chances than one that finds a single good one.
         */
        private fun draw(list: List<Candidate>): Candidate {
            val styles = list.groupBy { it.style.id }.toSortedMap().values.toList()
            val style = weighted(styles) { places -> places.first().share * places.maxOf { it.score }.pow(t.drawPower) }
            return weighted(style) { it.score.pow(t.placePower) }
        }

        private fun <T> weighted(list: List<T>, weight: (T) -> Double): T {
            val weights = list.map { max(0.0, weight(it)) }
            var at = draws.next() * weights.sum()
            for (i in list.indices) { at -= weights[i]; if (at < 0) return list[i] }
            return list.last()
        }

        /** Elapsed length of a window whose two sides ramp together, as [Candidate.spanA] and [Candidate.spanB] say. */
        private fun lengthOf(spanA: Double, spanB: Double, rampEnd: Double): Double {
            val c0 = 1 / spanA; val c1 = 1 / spanB
            return 1 / ((c0 + c1) / 2 * rampEnd + c1 * (1 - rampEnd))
        }

        private fun build(family: OrpheonFamily, c: Candidate): SeguePlan {
            val locked = family == OrpheonFamily.LOCKED
            val lengthSec = if (locked) lengthOf(c.spanA, c.spanB, c.style.rampEnd) else c.spanA
            val lengthUs = us(lengthSec)
            val nominal = SegueFormat.RATE_NOMINAL
            val ramps = locked && abs(c.spanA / c.spanB - 1) > 1e-4
            val endT = (c.style.rampEnd * SegueFormat.T_MAX).toInt().coerceIn(1, SegueFormat.T_MAX)
            val rampOut = if (ramps) SeguePlan.Ramp(nominal, ((c.spanA / c.spanB) * nominal).roundToLong(), endT) else null
            val rampIn = if (ramps) SeguePlan.Ramp(((c.spanB / c.spanA) * nominal).roundToLong(), nominal, endT) else null
            val rateOut = if (ramps) ((c.spanA / lengthSec) * nominal).roundToLong() else nominal
            val rateIn = if (ramps) ((c.spanB / lengthSec) * nominal).roundToLong() else nominal
            val beats = max(1, c.barsA * a.beatsPerBar)
            val place = StylePlace(lengthSec, us(c.outSec), us(c.spanA), if (locked) c.spanA / beats else beatNear(c.outSec + c.spanA),
                if (locked) beats else 0, clashAt(c))
            val shape = lanes.shape(c.style, place, r.eq)
            return SeguePlan(ORIGIN,
                SeguePlan.Side(a.audioKey, if (locked) a.gridKey else null, us(c.outSec), rateOut, r.listenerRate, rampOut),
                SeguePlan.Side(b.audioKey, if (locked) b.gridKey else null, us(c.inSec), rateIn, r.listenerRate, rampIn),
                SeguePlan.Span(lengthUs, c.bars.takeIf { locked }, UnitKind.BAR, Clock.ELAPSED),
                if (locked) SeguePlan.Pulse(PulseState.LOCKED, (60.0 * beats / lengthSec * 1e6).roundToLong(), null) else SeguePlan.Pulse(PulseState.FREE, null, null),
                shape.lanes, shape.gestures, echoUs = shape.echoUs)
        }

        /** The outgoing song's beat around [sec], when its beats are steady there; null leaves the echo to the engine's own length. */
        private fun beatNear(sec: Double): Double? {
            val to = min(sec, a.beatsSec.lastOrNull() ?: return null)
            val bar = a.barSecAt(to)
            if (a.confidence.grid < t.minGridConfidence || !stable(a, to - t.snapBars * bar, to)) return null
            return bar / a.beatsPerBar
        }

        /** Where in [c]'s window a lead vocal of the incoming song starts while the outgoing one still has its own, null when none does. */
        private fun clashAt(c: Candidate): Double? {
            if (MixIdea.VOCAL_CLEAR !in ideas) return null
            val outEnd = c.outSec + c.spanA; val inEnd = c.inSec + c.spanB
            return b.vocals.filter { it.strength >= VOICE_LEAD && it.fromSec < inEnd && it.toSec > c.inSec }.mapNotNull { v ->
                val share = ((max(v.fromSec, c.inSec) - c.inSec) / c.spanB).coerceIn(0.0, 1.0)
                val atA = c.outSec + share * c.spanA
                share.takeIf { a.vocals.any { o -> o.strength >= VOICE_LEAD && o.fromSec < outEnd && o.toSec > atA } }
            }.minOrNull()
        }

        private fun refuse(level: SegueLevel, cause: SegueCause, detail: String = ""): List<Candidate> {
            attempts += SegueAttempt(level, cause, detail)
            return emptyList()
        }

        // ── Beat-locked ──

        private fun sure(s: SongMap) = s.confidence.genre >= t.minGenreConfidence

        private fun pairGenre(): GenreFamily {
            val ga = if (sure(a)) a.genre else GenreFamily.OTHER
            val gb = if (sure(b)) b.genre else GenreFamily.OTHER
            return if (ga == gb) ga else GenreFamily.OTHER
        }

        /** The bar of each song around the junction: analysers disagree on the tempo more than on the bars. */
        private val barA = medianBar(a, max(0, a.downbeatsSec.size - 16), a.downbeatsSec.size)
        private val barB = medianBar(b, 0, min(b.downbeatsSec.size, 16))

        /** Half or double time of the incoming song against the outgoing one, whichever brings the bars closest. */
        private fun barRatio(): Double {
            if (barA <= 0 || barB <= 0) return RATIOS.minByOrNull { abs(b.tempoBpm.start * it / a.tempoBpm.end - 1) } ?: 1.0
            return RATIOS.minByOrNull { abs(ln(barB / it / barA)) } ?: 1.0
        }

        private fun medianBar(s: SongMap, from: Int, to: Int): Double {
            val gaps = (from until to - 1).map { s.downbeatsSec[it + 1] - s.downbeatsSec[it] }.sorted()
            return if (gaps.isEmpty()) 0.0 else gaps[gaps.size / 2]
        }

        /** Bars to a phrase of the incoming song: four, or eight when its bars are short, whichever lasts nearer [OrpheonTuning.phraseSec]. */
        private fun phraseOf(bar: Double) = if (bar > 0 && abs(ln(8 * bar / t.phraseSec)) < abs(ln(4 * bar / t.phraseSec))) 8 else 4
        private val phraseBars = phraseOf(barB)

        /** The incoming song's first phrase is a quiet opening: well under its main level, the next one clearly louder. */
        private fun quietOpening(first: Int): Boolean {
            val d = b.downbeatsSec
            if (first + 2 * phraseBars >= d.size) return false
            val opening = b.level(d[first], d[first + phraseBars])
            return opening < b.loudness.main - t.openingQuietDb && b.level(d[first + phraseBars], d[first + 2 * phraseBars]) > opening + t.openingRiseDb
        }

        private fun stable(s: SongMap, from: Double, to: Double) = s.beatStable(from, to, t.beatMaxIntervalDev, t.beatMaxResidualSec)

        /** The incoming song's first bar in its music, -1 without one. */
        private val firstB = b.downbeatsSec.indexOfFirst { it >= musicStartB - 0.1 }

        /** The bar of the incoming song's drop: the first one near its main level, the next one too; -1 when it stays quiet as far as an entry may be. */
        private val dropB: Int = run {
            val d = b.downbeatsSec
            var j = firstB
            if (j < 0) return@run -1
            while (j + 2 < d.size && d[j] <= t.entryLastSec) {
                val loud = b.loudness.main - t.dropLoudDb
                if (b.level(d[j], d[j + 1]) >= loud && b.level(d[j], d[j + 2]) >= loud) return@run j
                j++
            }
            -1
        }

        /** The incoming song has an intro: bars before its drop, clearly quieter than it. */
        private val introB = dropB > firstB && b.level(b.downbeatsSec[firstB], b.downbeatsSec[dropB]) <= b.loudness.main - t.dropLoudDb - t.dropRiseDb

        private fun locked(): List<Candidate> {
            val level = SegueLevel.LOCKED_SHORT
            if (a.confidence.grid < t.minGridConfidence || b.confidence.grid < t.minGridConfidence) return refuse(level, SegueCause.NO_GRID, "low confidence")
            if (a.downbeatsSec.size <= t.matchBars || b.downbeatsSec.size <= t.matchBars) return refuse(level, SegueCause.NO_GRID, "too few bars")
            if (a.beatsPerBar != b.beatsPerBar) return refuse(level, SegueCause.NO_GRID, "bar lengths differ")
            val ratio = barRatio()
            val gap = if (barA > 0 && barB > 0) abs(ln(barB / ratio / barA)) else abs(ln(b.tempoBpm.start * ratio / a.tempoBpm.end))
            val listenerLimit = min((r.maxRatePpm ?: Long.MAX_VALUE).toDouble() / SegueFormat.RATE_NOMINAL,
                if (MixIdea.GENTLE_TEMPO in ideas) t.gentleMaxRate else Double.MAX_VALUE)
            // One style asked for by name is for listening to it: the pair's own gates do not apply.
            if (only == null) {
                if (gap > min(gapLimit(), listenerLimit)) return refuse(level, SegueCause.TEMPO_OUT_OF_REACH, String.format(java.util.Locale.ROOT, "%.1f %%", gap * 100))
                refusal()?.let { return refuse(level, SegueCause.GUARDS, it) }
            }
            val first = firstB
            if (first < 0) return refuse(level, SegueCause.NO_GRID, "no bar in the music")
            val out = ArrayList<Candidate>()
            var noRate = 0; var noLength = 0; var noBeat = 0; var noScore = 0
            val exitsCache = HashMap<Int, List<Int>>()
            val stableA = HashMap<Long, Boolean>()
            val stableB = HashMap<Long, Boolean>()
            for (style in OrpheonStyles.of(OrpheonFamily.LOCKED, genre)) {
                val weight = style.weight * (if (genre in style.genres) 1.0 else t.genericWeight) * energyFavour(style, first)
                for (bars in style.bars) {
                    if (bars > (r.maxBars ?: Int.MAX_VALUE)) continue
                    val barsA = if (ratio == 2.0) bars * 2 else bars
                    val barsB = if (ratio == 0.5) bars * 2 else bars
                    val outs = exitsCache.getOrPut(barsA) { exits(barsA) }
                    for (i in outs) for (j in entries(first, barsB)) {
                        val spanA = a.downbeatsSec[i + barsA] - a.downbeatsSec[i]
                        val spanB = b.downbeatsSec[j + barsB] - b.downbeatsSec[j]
                        if (spanA <= 0 || spanB <= 0) continue
                        val toOut = spanA / spanB - 1
                        val toIn = spanB / spanA - 1
                        val limitOut = min(if (style.longBlend) t.maxLong else t.outMaxShort, listenerLimit)
                        val limitIn = min(if (style.longBlend) t.maxLong else t.inMaxShort, listenerLimit)
                        if (abs(toOut) > limitOut || abs(toIn) > limitIn) { noRate++; continue }
                        val lengthSec = lengthOf(spanA, spanB, style.rampEnd)
                        if (lengthSec !in style.minSec..style.maxSec || !bounds.contains(lengthSec)) { noLength++; continue }
                        // The matching bars reach back before a short window on the way out, forward after it on the way in.
                        val fromA = a.downbeatsSec[max(0, i + barsA - max(barsA, t.matchBars))]
                        val toB = b.downbeatsSec[min(b.downbeatsSec.lastIndex, j + max(barsB, t.matchBars))]
                        if (!stableA.getOrPut(i.toLong() * 1000 + barsA) { stable(a, fromA, a.downbeatsSec[i + barsA]) }) { noBeat++; continue }
                        if (!stableB.getOrPut(j.toLong() * 1000 + barsB) { stable(b, b.downbeatsSec[j], toB) }) { noBeat++; continue }
                        // Shorter than the listener's mix length: still a candidate, but drawn far less often. In seconds,
                        // not bars: four bars are 12 s of a rap song at 80 BPM and 7.5 s of a dance one at 128.
                        val drawWeight = if (lengthSec < (r.fadeSec ?: 0.0)) t.shortWeight else 1.0
                        val score = score(style, i, barsA, j, barsB, first, spanA, spanB, lengthSec, max(abs(toOut) / limitOut, abs(toIn) / limitIn))
                        if (score > t.minScore) out += Candidate(style, a.downbeatsSec[i], b.downbeatsSec[j], spanA, spanB, bars, barsA, score, weight * drawWeight) else noScore++
                    }
                }
            }
            if (out.isEmpty()) attempts += SegueAttempt(level, SegueCause.NO_CANDIDATE, "rate $noRate, length $noLength, beat $noBeat, score $noScore")
            // One style asked for by name is for listening to it: the gate does not apply.
            if (only == null && out.isNotEmpty() && out.maxOf { it.score } < t.lockMinScore)
                return refuse(level, SegueCause.GUARDS, out.maxByOrNull { it.score }!!.let {
                    String.format(java.util.Locale.ROOT, "unsure %.2f (%s, out %.1f in %.1f)", it.score, it.style.id, it.outSec, it.inSec)
                })
            return out
        }

        /**
         * Style by energy: a quiet opening is blended into, so the blends are drawn more often and the short
         * arrivals less; an opening as loud as the song after a loud ending is arrived on, the other way round.
         */
        private fun energyFavour(style: OrpheonStyle, first: Int): Double {
            if (MixIdea.ENERGY_STYLE !in ideas) return 1.0
            val blend = style.sound == StyleSound.FILTERED || style.sound == StyleSound.REVERB_OUT
            val d = b.downbeatsSec
            val opening = if (first + phraseBars < d.size) b.level(d[first], d[first + phraseBars]) else b.loudness.start
            val quiet = quietOpening(first) || opening < b.loudness.main - t.openingQuietDb
            val loud = opening >= b.loudness.main - t.energyLoudDb && a.level(loudEndA - 2 * barA, loudEndA) >= a.loudness.main - t.energyLoudDb
            return when {
                quiet -> if (blend) t.energyFavour else 1 / t.energyFavour
                loud -> if (blend) 1 / t.energyFavour else t.energyFavour
                else -> 1.0
            }
        }

        /** A hip-hop pair may lock over a wider tempo gap than any other. */
        private fun gapLimit() = if (bothHiphop() || !genreSure()) t.lockMaxGap else t.lockMaxGapOther

        private fun genreSure() = a.confidence.genre >= t.genreGateConfidence && b.confidence.genre >= t.genreGateConfidence

        private fun bothHiphop() = a.genre == GenreFamily.HIPHOP && b.genre == GenreFamily.HIPHOP

        /** How danceable a song is when no analyser says: what its family usually is. */
        private fun danceable(s: SongMap) = s.danceability ?: when (s.genre) {
            GenreFamily.DANCE -> t.assumeDance
            GenreFamily.HIPHOP -> t.assumeHiphop
            GenreFamily.POP -> t.assumePop
            GenreFamily.OTHER -> t.assumeOther
        }

        /**
         * Why this pair should not lock at all, null when it may: such a pair plays to its end and the
         * next song starts. Two hip-hop songs lock unless one hardly has a beat to dance to or the
         * outgoing one is acoustic; any other pair only when both are plainly danceable. A genre the
         * analyser merely guessed refuses nothing but an acoustic song.
         */
        private fun refusal(): String? {
            if (!genreSure()) return "acoustic".takeIf { (a.acousticness ?: 0.0) > t.lockMaxAcoustic }
            val hiphop = bothHiphop()
            if ((a.acousticness ?: 0.0) > (if (hiphop) t.lockMaxAcoustic else t.lockMaxAcousticOther)) return "acoustic"
            if (!hiphop && (b.acousticness ?: 0.0) > t.lockMaxAcousticOther) return "acoustic"
            val least = min(danceable(a), danceable(b))
            return "not danceable".takeIf { least < if (hiphop) t.lockMinDanceHiphop else t.lockMinDanceability }
        }

        /**
         * The bars of the outgoing song a window may start on: from where it may begin, on the song's
         * half phrases counted from its first bar ([OrpheonTuning.exitOnPhrase]), or on every bar.
         */
        private fun exits(barsA: Int): List<Int> {
            val d = a.downbeatsSec
            val from = d.indices.firstOrNull { d[it] >= exitFloor - 1e-6 } ?: return emptyList()
            val first = d.indexOfFirst { it >= a.musicStartSec(t.silenceBelowMainDb) - 0.1 }.coerceAtLeast(0)
            val hop = if (t.exitOnPhrase) max(1, phraseOf(barA) / 2) else 1
            return (from until d.size - barsA).filter { Math.floorMod(it - first, hop) == 0 }
        }

        /** The bars of the incoming song a window of [barsB] may start on: from its first, as late as an entry may be. */
        private fun entries(first: Int, barsB: Int): IntRange {
            val d = b.downbeatsSec
            var last = first
            while (last + 1 + barsB < d.size && d[last + 1] <= t.entryLastSec) last++
            return if (first + barsB < d.size) first..last else IntRange.EMPTY
        }

        private fun score(style: OrpheonStyle, i: Int, barsA: Int, j: Int, barsB: Int, first: Int, spanA: Double, spanB: Double, lengthSec: Double, tempoShare: Double): Double {
            val outSec = a.downbeatsSec[i]; val outEnd = a.downbeatsSec[i + barsA]
            val inSec = b.downbeatsSec[j]; val inEnd = b.downbeatsSec[j + barsB]
            val tempo = t.costTempo * tempoShare
            val voice = voiceCost(outSec, outEnd, inSec, inEnd, spanA, spanB)
            val key = keyCost()
            // The window ends on the last bar line of the song's body: each bar sooner leaves music unplayed, each bar later mixes its tail.
            val bar = spanA / barsA
            val off = (outEnd - loudEndA) / bar
            var exit = if (off > t.exitSlackBars) t.costExitLate * (off - t.exitSlackBars) else t.costExitEarly * min(-off + t.exitSlackBars, t.exitEarlyBars).coerceAtLeast(0.0) / t.exitEarlyBars
            if (outEnd - musicEndA > bar) exit += t.costPastMusic * ((outEnd - musicEndA) / (4 * bar)).coerceIn(0.0, 1.0)
            if (a.level(outSec, outEnd) < a.loudness.main - t.quietWindowDb) exit += t.costQuietWindow
            // The outgoing song is not cut a few seconds before the end of a line: its lead voice is over, or not begun, where it
            // leaves. A voice that sings on long after is the song itself, left in its middle as any mix leaves it.
            val leaveA = outSec + spanA * style.handover(barsB) / barsB
            val cut = a.vocals.filter { it.strength >= VOICE_LEAD && it.fromSec < leaveA - VOICE_CUT_SEC && it.toSec - leaveA in VOICE_LAST_SEC..VOICE_LINE_SEC }
                .maxOfOrNull { it.strength }?.let { t.costCutVoice * it * ((a.confidence.vocals - VOICE_GUESSED) / (1 - VOICE_GUESSED)).coerceIn(0.0, 1.0) } ?: 0.0
            // Where the outgoing song is gone, the incoming one must carry the music: left alone while still quiet, the energy sags.
            val d = b.downbeatsSec
            val hand = j + style.handover(barsB)
            val alone = b.level(d[min(hand, d.lastIndex - 1)], d[min(hand + 2, d.lastIndex)])
            val weak = t.costWeakHandover * ((b.loudness.main - t.dropLoudDb - alone) / t.weakHandoverDb).coerceIn(0.0, 1.0)
            var place = 0.0
            if (introB) {
                // A song with an intro: the intro plays under the outgoing song's ending and the hand-over falls on the drop,
                // so the drop is heard clean. Its loud part under the outgoing song is two songs at once; past the drop, music is skipped.
                val fromDrop = hand - dropB
                place += when {
                    fromDrop == 0 -> 0.0
                    // One bar past the drop, the drop is heard over the outgoing song's last bar; one bar short, the song is alone before it.
                    fromDrop == 1 -> t.costPastDrop
                    fromDrop == -1 -> t.costNearDrop
                    Math.floorMod(fromDrop, phraseBars) == 0 -> t.costHalfPhrase
                    else -> t.costOffDrop
                }
                val loudUnder = hand - max(j, dropB)
                if (loudUnder > 0) place += t.costLoudUnder * (loudUnder.toDouble() / phraseBars).coerceAtMost(1.0)
                if (j > dropB) place += t.costLaterPhrase * ((j - dropB).toDouble() / phraseBars)
            } else {
                // The incoming song takes the lead on one of its phrases, at worst half-way through one: a style that leads
                // from its first bar plays the song from its start unless that is a quiet opening, any other arrives on the second phrase.
                val arrival = j + style.arrive.bars(barsB) - first
                place += when {
                    arrival % phraseBars == 0 -> 0.0
                    arrival % (phraseBars / 2) == 0 -> t.costHalfPhrase
                    else -> t.costOffPhrase
                }
                val phrase = arrival.toDouble() / phraseBars
                if (style.arrive == Arrival.START) {
                    val quiet = quietOpening(first)
                    place += if (arrival == 0) (if (quiet) t.costQuietOpening else 0.0) else t.costLaterPhrase * (phrase - 1).coerceAtLeast(0.0) + if (quiet) 0.0 else t.costSkipOpening
                } else {
                    if (j == first) place += t.costNoRunUp
                    place += t.costLaterPhrase * (phrase - 1).coerceAtLeast(0.0)
                }
            }
            val late = if (inSec > t.entryUsualSec) t.costLateEntry * ((inSec - t.entryUsualSec) / (t.entryLastSec - t.entryUsualSec)).coerceIn(0.0, 1.0) else 0.0
            val length = if (style.targetSec > 0) t.costLength * abs(ln(lengthSec / style.targetSec)) else 0.0
            val cost = tempo + voice + key + exit + cut + weak + place + late + length
            val laterShare = ((outSec - exitFloor) / max(1.0, duration - exitFloor)).coerceIn(0.0, 1.0)
            val score = (1 - cost).coerceAtLeast(0.0) + t.laterBonus * laterShare + t.earlierBonus * (1 - (inSec / t.entryLastSec).coerceIn(0.0, 1.0))
            trace?.invoke(String.format(java.util.Locale.ROOT, "%s|%s %-14s %d bars out %.1f in %.1f score %.3f = 1 - tempo %.2f voice %.2f key %.2f exit %.2f cut %.2f weak %.2f place %.2f late %.2f length %.2f",
                a.audioKey.take(8), b.audioKey.take(8), style.id, barsB, outSec, inSec, score, tempo, voice, key, exit, cut, weak, place, late, length))
            return score
        }

        /** A lead vocal of the incoming song beginning in the window while the outgoing one still has its own going: the window's places line up by share. */
        private fun voiceCost(outSec: Double, outEnd: Double, inSec: Double, inEnd: Double, spanA: Double, spanB: Double): Double {
            if (a.vocals.isEmpty() || b.vocals.isEmpty()) return 0.0
            val trust = min(a.confidence.vocals, b.confidence.vocals)
            var worst = 0.0
            for (v in b.vocals) {
                if (v.strength < VOICE_LEAD || v.toSec <= inSec || v.fromSec >= inEnd) continue
                val already = v.fromSec < inSec
                val atA = outSec + (max(v.fromSec, inSec) - inSec) / spanB * spanA
                val other = a.vocals.filter { it.strength >= VOICE_LEAD && it.fromSec < outEnd && it.toSec > atA }.maxOfOrNull { it.strength } ?: continue
                worst = max(worst, min(v.strength, other) * if (already) VOICE_ALREADY else 1.0)
            }
            return t.costVoice * worst * trust * if (MixIdea.VOCAL_CLEAR in ideas) t.vocalClearFactor else 1.0
        }

        /** Keys two or more Camelot numbers apart, in proportion to how melodic the songs are; unknown counts as half. */
        private fun keyCost(): Double {
            if (!r.harmonic) return 0.0
            val ka = a.key.end ?: return 0.0
            val kb = b.key.start ?: return 0.0
            val na = MixPlanner.camelot(ka.code).first; val nb = MixPlanner.camelot(kb.code).first
            if (min(Math.floorMod(na - nb, 12), Math.floorMod(nb - na, 12)) < 2) return 0.0
            val melodic = ((a.melodicness ?: UNKNOWN_DESCRIPTOR) + (b.melodicness ?: UNKNOWN_DESCRIPTOR)) / 2
            return t.costKey * melodic * min(a.confidence.key, b.confidence.key)
        }

        // ── The other families ──

        /** A song whose end section really fades: about five seconds from a section, the incoming song from a section's start. */
        private fun smooth(): List<Candidate> {
            val fade = a.fadeOut?.takeIf { it.lengthSec >= t.smoothMinFadeSec } ?: return refuse(SegueLevel.SIMPLE_FADE, SegueCause.NO_CANDIDATE, "no long fade")
            val length = bounded(t.smoothSec) ?: return refuse(SegueLevel.SIMPLE_FADE, SegueCause.LENGTH_NOT_ALLOWED, "smooth")
            val latest = min(fade.toSec - length / 2, duration - length)
            val onSection = a.sections.filter { it >= fade.fromSec - t.smoothLeadSec && it >= exitFloor && it <= latest }
            val places = onSection.ifEmpty { listOf(fade.fromSec).filter { it >= exitFloor && it <= latest } }
            if (places.isEmpty()) return refuse(SegueLevel.SIMPLE_FADE, SegueCause.GUARDS, "smooth place")
            val entries = listOf(b.downbeatsSec.firstOrNull { it >= musicStartB - 0.1 } ?: musicStartB) +
                b.sections.filter { it > musicStartB && it <= t.entryUsualSec }
            val out = ArrayList<Candidate>()
            for (style in OrpheonStyles.of(OrpheonFamily.SMOOTH, genre)) for (place in places) for (entry in entries) {
                if (entry + length > b.durationSec) continue
                val weight = style.weight * if (genre in style.genres) 1.0 else t.genericWeight
                val score = (1 - (if (onSection.isEmpty()) t.costOffSection else 0.0) - t.costLateEntry * entry / t.entryLastSec) * weight
                if (score > t.minScore) out += Candidate(style, place, entry, length, length, 0, 0, score + t.laterBonus * place / duration)
            }
            return out
        }

        /** The end of the outgoing song, as the music fades into nothing, butted against the next one's start. */
        private fun silence(): List<Candidate> {
            // A song loud to its last second has no silence to remove: butting the next one against it is a cut, the fade is kinder
            // (and enters the next song past its own silent start).
            val dead = duration - a.lastAboveSec(t.silenceTailDb)
            if (dead < t.silenceMinDeadSec) return refuse(SegueLevel.SIMPLE_FADE, SegueCause.GUARDS, "no dead air")
            val wanted = (dead + t.silenceLeadSec).coerceIn(t.silenceWindowMinSec, t.silenceWindowMaxSec)
            val length = bounded(wanted)?.takeIf { it in t.silenceWindowMinSec..t.silenceWindowMaxSec } ?: return refuse(SegueLevel.SIMPLE_FADE, SegueCause.LENGTH_NOT_ALLOWED, "silence")
            val place = onBeat(duration - length, 0.0)
            if (place < earliest || place < 0 || duration - place > b.durationSec) return refuse(SegueLevel.SIMPLE_FADE, SegueCause.GUARDS, "silence place")
            val style = OrpheonStyles.of(OrpheonFamily.SILENCE, genre).first()
            return listOf(Candidate(style, place, 0.0, duration - place, duration - place, 0, 0, 1.0))
        }

        /**
         * Fades on the beat: a window starting at [at] in the outgoing song, the incoming one entering at
         * [entry], moved by half a beat at most so that the incoming song's first downbeat falls on a beat of
         * the outgoing song, counted on past its last one. Unchanged without the idea or without a steady beat.
         */
        private fun onBeat(at: Double, entry: Double): Double {
            if (MixIdea.PHRASED_FADE !in ideas || a.beatsSec.size < 8 || b.downbeatsSec.isEmpty()) return at
            val beat = beatNear(a.beatsSec.last()) ?: return at
            val down = b.downbeatsSec.firstOrNull { it >= musicStartB - 0.1 && it >= entry } ?: return at
            // The incoming song's first downbeat in the outgoing song's time, against that song's beats.
            val lands = at + (down - entry)
            val phase = (lands - a.beatsSec.last()) / beat
            val off = (phase - Math.rint(phase)) * beat
            return if (abs(off) <= t.snapMaxBeat * beat && at - off + 1e-6 < duration) at - off else at
        }

        /**
         * The plain equal-power fade, centred on the end of the outgoing song's body: a quiet outro then plays
         * under the incoming song's start instead of before it. The safety margin gives way before the transition does.
         */
        private fun fallback(): List<Candidate> {
            val style = OrpheonStyles.of(OrpheonFamily.FALLBACK, genre).first()
            val entry = max(0.0, musicStartB - t.fallbackLeadSec)
            fun place(from: Double): Candidate? {
                val wanted = bounded(r.fadeSec ?: t.fallbackSec) ?: return null
                val at = max(from, min(onBeat(min(loudEndA - wanted / 2, duration - wanted), entry), duration - wanted))
                val length = minOf(wanted, duration - at, b.durationSec - entry)
                if (length < t.fallbackMinSec || !bounds.contains(length)) return null
                return Candidate(style, at, entry, length, length, 0, 0, 1.0)
            }
            val c = place(earliest) ?: place(earliestAsked) ?: return refuse(SegueLevel.SIMPLE_FADE, SegueCause.NO_CANDIDATE, "no room")
            return listOf(c)
        }

        private fun bounded(wanted: Double): Double? {
            if (bounds.isUnbounded) return wanted
            return wanted.coerceIn(bounds.minSeconds, bounds.maxSeconds).takeIf { bounds.contains(it) }
        }
    }

    /** From this strength a voice counts as a lead. */
    private const val VOICE_LEAD = 0.5
    /**
     * A line counts as cut when the song leaves this far after its start with [VOICE_LAST_SEC] to [VOICE_LINE_SEC] of it
     * left: less is its last word, which the tail carries; more is the song itself.
     */
    private const val VOICE_CUT_SEC = 0.5
    /** At this confidence the voice is a guess from the spectrum, bars on end: its lines are not known, and none is protected. */
    private const val VOICE_GUESSED = 0.5
    private const val VOICE_LAST_SEC = 2.0
    private const val VOICE_LINE_SEC = 12.0
    /** A voice already singing when the window starts is less of a surprise than one starting inside it. */
    private const val VOICE_ALREADY = 0.6
    /** An optional descriptor that is absent is unknown, not zero. */
    private const val UNKNOWN_DESCRIPTOR = 0.5
}
