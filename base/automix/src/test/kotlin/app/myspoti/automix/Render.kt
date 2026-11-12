/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Desktop preview of a plan, the way the phone plays it: A at 1×, B stretched by ffmpeg's atempo
 * (pitch kept, as the phone's AudioTrack time stretch), gains and 3-band levels per [MixPlan.levels].
 * B's speed stays at speedB after the mix here (the phone brings it back to 1 over restoreSec).
 */
object Render {
    const val SR = 44100

    fun stereo(file: File): FloatArray {
        val fb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** B from [from] seconds on, at [speed] (pitch kept), as stereo float. */
    private fun stretched(src: File, from: Double, seconds: Double, speed: Double, tmp: File): FloatArray {
        val cmd = listOf("ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", "$SR", "-ac", "2", "-ss", "%.6f".format(from), "-t", "%.3f".format(seconds * speed + 1),
            "-i", src.path, "-af", "atempo=%.6f".format(speed), "-f", "f32le", "-ar", "$SR", "-ac", "2", tmp.path)
        check(ProcessBuilder(cmd).inheritIO().start().waitFor() == 0) { "ffmpeg failed" }
        return stereo(tmp)
    }

    fun render(a: File, b: File, plan: MixPlan, out: File, pre: Double = 12.0, post: Double = 15.0) {
        val pcmA = stereo(a)
        val total = pre + plan.length + post
        val bPart = stretched(b, plan.entryB, plan.length + post, plan.speedB, File(out.parentFile, "b.tmp.f32"))
        val n = (total * SR).toInt()
        val o = FloatArray(2 * n)
        val eqA = Eq(); val eqB = Eq()
        var lv = plan.levels(-1.0)
        for (i in 0 until n) {
            val u = i.toDouble() / SR - pre
            if (i % 64 == 0) lv = plan.levels(u / plan.length)
            val ia = ((plan.exitA + u) * SR).toInt()
            val ib = (u * SR).toInt()
            val gainB = if (u >= plan.length) plan.gainAfter(plan.resumeB + (u - plan.length) * plan.speedB) else lv.gainB
            for (c in 0..1) {
                val xa = if (u < plan.length + 0.05 && ia >= 0 && 2 * ia + c < pcmA.size) pcmA[2 * ia + c] else 0f
                val xb = if (ib >= 0 && 2 * ib + c < bPart.size) bPart[2 * ib + c] else 0f
                val ya = eqA.process(c, xa.toDouble(), lv.lowA, 0.0, lv.highA) * lv.gainA
                val yb = eqB.process(c, xb.toDouble(), lv.lowB, 0.0, lv.highB) * gainB
                o[2 * i + c] = (ya + yb).toFloat()
            }
        }
        writeWav(out, o)
    }

    /** Three bands: low under 180 Hz, high over 2.5 kHz (4th-order Linkwitz–Riley splits), mid the rest. */
    class Eq {
        private val lp1 = Array(2) { Biquad.lowPass(180.0) }; private val lp1b = Array(2) { Biquad.lowPass(180.0) }
        private val lp2 = Array(2) { Biquad.lowPass(2500.0) }; private val lp2b = Array(2) { Biquad.lowPass(2500.0) }
        fun process(c: Int, x: Double, lowDb: Double, midDb: Double, highDb: Double): Double {
            val low = lp1b[c].run(lp1[c].run(x))
            val lowMid = lp2b[c].run(lp2[c].run(x))
            val high = x - lowMid; val mid = lowMid - low
            return low * db(lowDb) + mid * db(midDb) + high * db(highDb)
        }
        private fun db(v: Double) = 10.0.pow(v / 20)
    }

    class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private var z1 = 0.0; private var z2 = 0.0
        fun run(x: Double): Double { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }
        companion object {
            fun lowPass(f: Double): Biquad {
                val k = tan(PI * f / SR); val q = 1 / sqrt(2.0); val norm = 1 / (1 + k / q + k * k)
                return Biquad(k * k * norm, 2 * k * k * norm, k * k * norm, 2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
            }
        }
    }

    /** Listening preview only: returns the gain used, so it cannot be mistaken for a raw render. */
    fun writeWav(f: File, x: FloatArray): Float {
        var peak = 0f; for (v in x) peak = maxOf(peak, abs(v))
        val g = if (peak > 0.99f) 0.99f / peak else 1f
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            val bb = ByteBuffer.allocate(44 + x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()); bb.putInt(36 + x.size * 2); bb.put("WAVEfmt ".toByteArray())
            bb.putInt(16); bb.putShort(1); bb.putShort(2); bb.putInt(SR); bb.putInt(SR * 4); bb.putShort(4); bb.putShort(16)
            bb.put("data".toByteArray()); bb.putInt(x.size * 2)
            for (v in x) bb.putShort((v * g * 32767).toInt().coerceIn(-32768, 32767).toShort())
            r.write(bb.array())
        }
        return g
    }

    /** IEEE-float WAV: retain decoded overshoots and exact mix levels, with no normalization. */
    fun writeFloatWav(f: File, x: FloatArray) {
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            val bb = ByteBuffer.allocate(56 + x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()); bb.putInt(48 + x.size * 4); bb.put("WAVEfmt ".toByteArray())
            bb.putInt(16); bb.putShort(3); bb.putShort(2); bb.putInt(SR); bb.putInt(SR * 8); bb.putShort(8); bb.putShort(32)
            bb.put("fact".toByteArray()); bb.putInt(4); bb.putInt(x.size / 2)
            bb.put("data".toByteArray()); bb.putInt(x.size * 4)
            for (v in x) bb.putFloat(v)
            r.write(bb.array())
        }
    }
}
