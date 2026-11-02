/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * What AutoMix knows of a song. Times in seconds of the song's own timeline.
 *
 * Bars are 4 beats: bar i starts at [barStarts][i]. Per bar: level (dB), low-band level (dB),
 * high-band level (dB), centre (voice-band mid over side, dB above the song's median) and novelty
 * (how much the 4 bars from here differ from the 4 before). [phraseOffset]: bars i with
 * (i - phraseOffset) % 8 == 0 start 8-bar phrases.
 */
class TrackAnalysis(
    val duration: Double,
    val contentStart: Double,
    val contentEnd: Double,
    val loudnessDb: Double,
    /** 0..11 major (C = 0), 12..23 minor (A minor = 21); -1 unknown. */
    val key: Int,
    val keyStrength: Double,
    val bpm: Double,
    /** The whole song follows one grid (most produced music). */
    val steady: Boolean,
    /** Grids fitted on the song's first and last minute or so (beat-matching uses these). */
    val head: Grid?,
    val tail: Grid?,
    val beats: DoubleArray,
    val barStarts: DoubleArray,
    val barDb: FloatArray,
    val barLowDb: FloatArray,
    val barHighDb: FloatArray,
    val barCentre: FloatArray,
    val barNovelty: FloatArray,
    val phraseOffset: Int,
    val stereo: Boolean,
    /** A downbeat of the [head] and of the [tail] grid (NaN without the grid): bars there start every 4 beats from it. */
    val headDown: Double,
    val tailDown: Double,
    /** Why a start or end got no grid (for the journal), "" otherwise. */
    val note: String = "",
) {
    val bars get() = barStarts.size

    /** The bar containing [t], or -1 before the first. */
    fun barAt(t: Double): Int {
        var lo = 0; var hi = bars - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (barStarts[m] <= t) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }

    fun barEnd(i: Int): Double = if (i + 1 < bars) barStarts[i + 1] else contentEnd

    /** 0 on an 8-bar phrase start, 4 on its middle (a 4-bar start), else other. */
    fun phrasePos(i: Int): Int = Math.floorMod(i - phraseOffset, 8)

    fun write(out: OutputStream) {
        val o = DataOutputStream(out)
        o.writeInt(VERSION)
        o.writeDouble(duration); o.writeDouble(contentStart); o.writeDouble(contentEnd); o.writeDouble(loudnessDb)
        o.writeInt(key); o.writeDouble(keyStrength); o.writeDouble(bpm); o.writeBoolean(steady)
        writeGrid(o, head); writeGrid(o, tail)
        o.writeInt(beats.size); beats.forEach(o::writeDouble)
        o.writeInt(bars); barStarts.forEach(o::writeDouble)
        for (a in listOf(barDb, barLowDb, barHighDb, barCentre, barNovelty)) a.forEach(o::writeFloat)
        o.writeInt(phraseOffset); o.writeBoolean(stereo); o.writeDouble(headDown); o.writeDouble(tailDown); o.writeUTF(note.take(2000))
        o.flush()
    }

    companion object {
        const val VERSION = 4_07

        fun read(input: InputStream): TrackAnalysis? {
            val i = DataInputStream(input)
            if (i.readInt() != VERSION) return null
            val duration = i.readDouble(); val cs = i.readDouble(); val ce = i.readDouble(); val loud = i.readDouble()
            val key = i.readInt(); val ks = i.readDouble(); val bpm = i.readDouble(); val steady = i.readBoolean()
            val head = readGrid(i); val tail = readGrid(i)
            val beats = DoubleArray(i.readInt()) { i.readDouble() }
            val n = i.readInt()
            val starts = DoubleArray(n) { i.readDouble() }
            val arrays = List(5) { FloatArray(n) { i.readFloat() } }
            return TrackAnalysis(duration, cs, ce, loud, key, ks, bpm, steady, head, tail, beats, starts,
                arrays[0], arrays[1], arrays[2], arrays[3], arrays[4], i.readInt(), i.readBoolean(), i.readDouble(), i.readDouble(), i.readUTF())
        }

        private fun writeGrid(o: DataOutputStream, g: Grid?) {
            o.writeBoolean(g != null)
            if (g != null) { o.writeDouble(g.first); o.writeDouble(g.period); o.writeInt(g.count); o.writeDouble(g.steadiness); o.writeDouble(g.driftMs); o.writeDouble(g.contrast) }
        }

        private fun readGrid(i: DataInputStream): Grid? =
            if (!i.readBoolean()) null else Grid(i.readDouble(), i.readDouble(), i.readInt(), i.readDouble(), i.readDouble(), i.readDouble())
    }
}
