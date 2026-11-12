/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.TrackAnalysis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The small genre families the styles are chosen by. */
enum class GenreFamily(val wire: String) {
    HIPHOP("hiphop"), DANCE("dance"), POP("pop"), OTHER("other");

    companion object {
        fun parse(text: String?): GenreFamily = values().firstOrNull { it.wire == text } ?: OTHER

        private val HIPHOP_WORDS = listOf("hip hop", "hip-hop", "rap", "drill", "trap", "grime")
        private val DANCE_WORDS = listOf("dance", "edm", "house", "techno", "electro", "trance", "dubstep", "drum and bass", "dnb", "eurodance", "hardstyle")

        /**
         * A family from a catalogue's genre names (an artist's, a song's): hip-hop words first, then
         * dance, then pop, anything else is OTHER; null when there is no name to go by.
         */
        fun fromNames(names: List<String>): GenreFamily? {
            val words = names.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return when {
                words.any { w -> HIPHOP_WORDS.any { it in w } } -> HIPHOP
                words.any { w -> DANCE_WORDS.any { it in w } } -> DANCE
                words.any { "pop" in it } -> POP
                else -> OTHER
            }
        }
    }
}

enum class VocalKind(val wire: String) {
    SUNG("sung"), SPOKEN("spoken"), RAPPED("rapped");

    companion object {
        fun parse(text: String?): VocalKind? = values().firstOrNull { it.wire == text }
    }
}

data class SongRange(val fromSec: Double, val toSec: Double) {
    val lengthSec get() = toSec - fromSec
}

data class SongVocal(val fromSec: Double, val toSec: Double, val strength: Double, val kind: VocalKind? = null)

/** [tonic] is 0..11 from C. */
data class SongKey(val tonic: Int, val minor: Boolean) {
    /** The code [app.myspoti.automix.MixPlanner.camelot] reads: 0..11 major, 12..23 minor. */
    val code get() = tonic + if (minor) 12 else 0
}

/** One value for the start, the main part and the end of a song. */
data class SongThirds<T>(val start: T, val main: T, val end: T)

/** How far each group of fields can be trusted, 0..1. */
data class SongConfidence(
    val grid: Double = 1.0,
    val sections: Double = 1.0,
    val loudness: Double = 1.0,
    val vocals: Double = 1.0,
    val key: Double = 1.0,
    val fades: Double = 1.0,
    val genre: Double = 1.0,
)

/**
 * What the planner knows of a song, whatever analysed it: every analyser fills this, and the planner
 * reads nothing else. Times are seconds of the song's own timeline. An optional descriptor that is
 * null is unknown, never zero.
 */
class SongMap(
    val durationSec: Double,
    val beatsSec: DoubleArray,
    /** Bar starts. */
    val downbeatsSec: DoubleArray,
    val tempoBpm: SongThirds<Double>,
    val beatsPerBar: Int = 4,
    /** Ordered inner boundaries, on downbeats when possible: neither the song's start nor its end. */
    val sections: DoubleArray = DoubleArray(0),
    val loudnessStepSec: Double = 0.5,
    /** dB, one value every [loudnessStepSec] from the song's start. */
    val loudnessCurve: FloatArray = FloatArray(0),
    val loudness: SongThirds<Double> = SongThirds(-14.0, -14.0, -14.0),
    val vocals: List<SongVocal> = emptyList(),
    val key: SongThirds<SongKey?> = SongThirds(null, null, null),
    val fadeIn: SongRange? = null,
    val fadeOut: SongRange? = null,
    val genre: GenreFamily = GenreFamily.OTHER,
    val energy: Double? = null,
    val danceability: Double? = null,
    val acousticness: Double? = null,
    val melodicness: Double? = null,
    val confidence: SongConfidence = SongConfidence(),
    /** What identifies the audio and the grid in a plan (see [SegueValidator.stale]). */
    val audioKey: String = "",
    val gridKey: String? = null,
) {
    /** The level at [sec]; below the curve's floor outside it. */
    fun levelAt(sec: Double): Double {
        if (loudnessCurve.isEmpty() || sec < 0 || sec > durationSec) return FLOOR_DB
        return loudnessCurve[(sec / loudnessStepSec).toInt().coerceIn(0, loudnessCurve.lastIndex)].toDouble()
    }

    /** The average level (dB) of [fromSec]..[toSec]. */
    fun level(fromSec: Double, toSec: Double): Double {
        if (loudnessCurve.isEmpty() || toSec <= fromSec) return FLOOR_DB
        val i0 = (max(0.0, fromSec) / loudnessStepSec).toInt().coerceIn(0, loudnessCurve.lastIndex)
        val i1 = (ceil(min(durationSec, toSec) / loudnessStepSec).toInt()).coerceIn(i0 + 1, loudnessCurve.size)
        var sum = 0.0
        for (i in i0 until i1) sum += loudnessCurve[i]
        return sum / (i1 - i0)
    }

    /** Where the music begins: the first point this far under the main level or louder. */
    fun musicStartSec(belowMainDb: Double): Double {
        val at = loudnessCurve.indexOfFirst { it >= threshold(belowMainDb) }
        return if (at < 0) 0.0 else at * loudnessStepSec
    }

    /** Where the music ends: past the last point this far under the main level or louder. */
    fun musicEndSec(belowMainDb: Double): Double {
        val at = loudnessCurve.indexOfLast { it >= threshold(belowMainDb) }
        return if (at < 0) durationSec else min(durationSec, (at + 1) * loudnessStepSec)
    }

    /** Past the last point at [db] or louder; the song's end when there is none. */
    fun lastAboveSec(db: Double): Double {
        val at = loudnessCurve.indexOfLast { it >= db }
        return if (at < 0) durationSec else min(durationSec, (at + 1) * loudnessStepSec)
    }

    private fun threshold(belowMainDb: Double) = max(SILENCE_FLOOR_DB, loudness.main - belowMainDb)

    /** The index of the downbeat nearest [sec], -1 without downbeats. */
    fun downbeatNear(sec: Double): Int {
        if (downbeatsSec.isEmpty()) return -1
        val at = lowerBound(downbeatsSec, sec)
        if (at <= 0) return 0
        if (at >= downbeatsSec.size) return downbeatsSec.lastIndex
        return if (sec - downbeatsSec[at - 1] <= downbeatsSec[at] - sec) at - 1 else at
    }

    /** The length of the bar around [sec]; the tempo's when there are no downbeats. */
    fun barSecAt(sec: Double): Double {
        val at = downbeatNear(sec)
        if (at < 0 || downbeatsSec.size < 2) return beatsPerBar * 60 / max(1.0, tempoBpm.main)
        return if (at + 1 < downbeatsSec.size) downbeatsSec[at + 1] - downbeatsSec[at] else downbeatsSec[at] - downbeatsSec[at - 1]
    }

    /**
     * Whether the beats stay regular over [fromSec]..[toSec]: each interval close to the median
     * one, none missing at either end, and all of them close to one straight line.
     */
    fun beatStable(fromSec: Double, toSec: Double, maxIntervalDev: Double = 0.12, maxResidualSec: Double = 0.04): Boolean {
        val from = lowerBound(beatsSec, fromSec - 0.05)
        val to = lowerBound(beatsSec, toSec + 0.05)
        val n = to - from
        if (n < 4) return false
        val gaps = DoubleArray(n - 1) { beatsSec[from + it + 1] - beatsSec[from + it] }
        val median = gaps.sorted()[gaps.size / 2]
        if (median <= 0 || gaps.any { abs(it / median - 1) > maxIntervalDev }) return false
        if (beatsSec[from] - fromSec > 1.5 * median || toSec - beatsSec[to - 1] > 1.5 * median) return false
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) { val x = i.toDouble(); val y = beatsSec[from + i]; sx += x; sy += y; sxx += x * x; sxy += x * y }
        val den = n * sxx - sx * sx
        if (den <= 0) return false
        val slope = (n * sxy - sx * sy) / den
        val origin = (sy - slope * sx) / n
        val residuals = DoubleArray(n) { abs(beatsSec[from + it] - (origin + slope * it)) }.also { it.sort() }
        return residuals[((n - 1) * 0.95).toInt()] <= maxResidualSec
    }

    companion object {
        /** The level that stands for digital silence. */
        const val FLOOR_DB = -80.0
        /** Never counted as music, however quiet the song. */
        const val SILENCE_FLOOR_DB = -60.0

        internal fun lowerBound(x: DoubleArray, value: Double): Int {
            var lo = 0; var hi = x.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (x[m] < value) lo = m + 1 else hi = m }
            return lo
        }
    }
}

/** Fills a [SongMap] from what the app's analyser measured. */
object SongMaps {
    private const val STEP_SEC = 0.5
    private const val LOUD_SHARE = 0.7
    private const val THIRD_SEC = 20.0
    private const val FADE_NEAR_DB = 3.0
    private const val FADE_IN_DB = 6.0
    private const val FADE_OUT_DB = 12.0
    private const val FADE_OUT_MIN_SEC = 4.0
    private const val FADE_OUT_MAX_SEC = 45.0
    /** The estimate below is a guess from tempo, voice and bass: its confidence says so. */
    private const val GENRE_GUESS_CONFIDENCE = 0.35

    fun from(a: TrackAnalysis, t: SegueTuning = SegueTuning(), genre: GenreFamily? = null, genreConfidence: Double = 1.0): SongMap {
        val beats = beatsOf(a)
        val curve = FloatArray(max(1, ceil(a.duration / STEP_SEC).toInt())) { levelAt(a, (it + 0.5) * STEP_SEC).toFloat() }
        val inside = curve.filterIndexed { i, _ -> (i + 0.5) * STEP_SEC in a.contentStart..a.contentEnd }.sorted()
        val main = if (inside.isEmpty()) SongMap.FLOOR_DB else inside[((inside.size - 1) * LOUD_SHARE).toInt()].toDouble()
        fun mean(from: Double, to: Double): Double {
            val i0 = (max(0.0, from) / STEP_SEC).toInt().coerceIn(0, curve.lastIndex)
            val i1 = (min(a.duration, to) / STEP_SEC).toInt().coerceIn(i0 + 1, curve.size)
            var sum = 0.0
            for (i in i0 until i1) sum += curve[i]
            return sum / (i1 - i0)
        }
        val vocals = vocalsOf(a, t)
        val key = a.key.takeIf { it in 0..23 }?.let { SongKey(it % 12, it >= 12) }
        val guess = genre ?: guessGenre(a, vocals, main)
        return SongMap(
            durationSec = a.duration,
            beatsSec = beats,
            downbeatsSec = a.barStarts,
            tempoBpm = SongThirds(tempo(beats, a.contentStart, a.contentStart + 30, a.bpm), tempo(beats, 0.0, a.duration, a.bpm), tempo(beats, a.contentEnd - 30, a.contentEnd, a.bpm)),
            beatsPerBar = 4,
            sections = a.sectionStarts.filter { it in 1 until a.bars }.map { a.barStarts[it] }.toDoubleArray(),
            loudnessStepSec = STEP_SEC,
            loudnessCurve = curve,
            loudness = SongThirds(mean(a.contentStart, a.contentStart + THIRD_SEC), main, mean(a.contentEnd - THIRD_SEC, a.contentEnd)),
            vocals = vocals,
            key = SongThirds(key, key, key),
            fadeIn = fadeIn(a, main),
            fadeOut = fadeOut(a, main),
            genre = guess,
            confidence = SongConfidence(
                grid = gridConfidence(a),
                sections = sectionConfidence(a),
                loudness = 1.0,
                vocals = if (a.voiceKnown) 0.9 else 0.5,
                key = if (key == null) 0.0 else a.keyStrength.coerceIn(0.0, 1.0),
                fades = 0.7,
                genre = if (genre != null) genreConfidence else GENRE_GUESS_CONFIDENCE,
            ),
            audioKey = a.audioKey,
            gridKey = a.beatKey,
        )
    }

    /**
     * The analyser's own grid for a song it found steady (one tempo fitted on the whole song: no beat
     * missing in a break, none doubled), else the model's beats when they are at the bars' level, else
     * the tracker's, else four to a bar.
     */
    private fun beatsOf(a: TrackAnalysis): DoubleArray {
        fun fits(x: DoubleArray) = a.bars > 0 && x.size.toDouble() in 3.4 * a.bars..4.6 * a.bars
        if (a.steady && fits(a.beats)) return a.beats
        if (fits(a.modelBeats)) return a.modelBeats
        if (fits(a.beats)) return a.beats
        return DoubleArray(a.bars * 4) { i ->
            val bar = i / 4
            val from = a.barStarts[bar]
            from + (a.barEnd(bar) - from) * (i % 4) / 4
        }
    }

    private fun tempo(beats: DoubleArray, from: Double, to: Double, fallback: Double): Double {
        val i0 = SongMap.lowerBound(beats, from); val i1 = SongMap.lowerBound(beats, to)
        if (i1 - i0 < 3) return fallback
        val gaps = DoubleArray(i1 - i0 - 1) { beats[i0 + it + 1] - beats[i0 + it] }.also { it.sort() }
        val median = gaps[gaps.size / 2]
        return if (median > 0) 60 / median else fallback
    }

    private fun levelAt(a: TrackAnalysis, sec: Double): Double {
        if (sec < a.contentStart || sec > a.contentEnd || a.bars == 0) return SongMap.FLOOR_DB
        val db = a.barDb[max(0, a.barAt(sec))].toDouble()
        return if (db.isFinite()) max(SongMap.FLOOR_DB, db) else SongMap.FLOOR_DB
    }

    private fun vocalsOf(a: TrackAnalysis, t: SegueTuning): List<SongVocal> {
        val out = ArrayList<SongVocal>()
        var from = -1
        var sum = 0.0
        for (i in 0..a.bars) {
            val risk = if (i < a.bars) a.barVocalRisk.getOrElse(i) { 0f }.toDouble() else 0.0
            if (i < a.bars && risk >= t.voiceBarRisk) {
                if (from < 0) { from = i; sum = 0.0 }
                sum += risk
            } else if (from >= 0) {
                out += SongVocal(a.barStarts[from], a.barEnd(i - 1), (sum / (i - from)).coerceIn(0.0, 1.0))
                from = -1
            }
        }
        return out
    }

    private fun fadeIn(a: TrackAnalysis, main: Double): SongRange? {
        val first = (0 until a.bars).firstOrNull { a.barEnd(it) > a.contentStart } ?: return null
        if (a.barDb[first] > main - FADE_IN_DB) return null
        val full = (first until a.bars).firstOrNull { a.barDb[it] >= main - FADE_NEAR_DB } ?: return null
        return if (full > first) SongRange(a.contentStart, a.barStarts[full]) else null
    }

    private fun fadeOut(a: TrackAnalysis, main: Double): SongRange? {
        if (a.bars < 2) return null
        val full = (a.bars - 1 downTo 0).firstOrNull { a.barDb[it] >= main - FADE_NEAR_DB } ?: return null
        if (full >= a.bars - 1 || a.barDb[a.bars - 1] > main - FADE_OUT_DB) return null
        val from = a.barEnd(full)
        return if (a.contentEnd - from in FADE_OUT_MIN_SEC..FADE_OUT_MAX_SEC) SongRange(from, a.contentEnd) else null
    }

    private const val DOWN_FLOOR = 0.35

    private fun gridConfidence(a: TrackAnalysis): Double {
        if (a.bars < 8 || (a.head == null && a.tail == null && a.modelBeats.isEmpty())) return 0.0
        val down = if (a.barDownConfidence.isNotEmpty()) a.barDownConfidence.average() else (a.headMetre + a.tailMetre) / 2.0
        val grids = listOfNotNull(a.head, a.tail)
        val steady = if (grids.isEmpty()) 1.0 else grids.minOf { it.steadiness }
        // The model is often unsure where a rap song's bar starts while its beat is plainly steady: the beat's
        // steadiness decides, the bar's first beat only lowers the score (each candidate's beat is still checked).
        return min(steady, DOWN_FLOOR + (1 - DOWN_FLOOR) * down).coerceIn(0.0, 1.0)
    }

    private fun sectionConfidence(a: TrackAnalysis): Double {
        val starts = a.sectionStarts
        if (starts.isEmpty()) return 0.0
        return starts.map { a.sectionStrength(it) }.average().coerceIn(0.0, 1.0)
    }

    /** A rough family from the tempo, how much is voiced, and how much the low end stands out. */
    private fun guessGenre(a: TrackAnalysis, vocals: List<SongVocal>, main: Double): GenreFamily {
        val voiced = vocals.sumOf { it.toSec - it.fromSec } / max(1.0, a.contentEnd - a.contentStart)
        val bpm = a.bpm.roundToInt()
        val bass = a.barBassDb.filter { it.isFinite() }.let { if (it.isEmpty()) -60.0 else it.average() }
        return when {
            a.steady && bpm in 118..140 && voiced < 0.5 && bass >= main - 9 -> GenreFamily.DANCE
            (bpm in 70..105 || bpm in 140..175) && voiced >= 0.5 -> GenreFamily.HIPHOP
            voiced >= 0.3 -> GenreFamily.POP
            else -> GenreFamily.OTHER
        }
    }
}
