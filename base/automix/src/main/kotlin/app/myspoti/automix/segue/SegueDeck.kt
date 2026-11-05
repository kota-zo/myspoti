/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Plays one side of a plan on that song's samples, in its source time: what it does to a sample is
 * fixed by where the sample is in the song, however long the player's buffers hold it afterwards.
 * Pure Kotlin, so the phone and the desktop render run the same sound. Decides nothing.
 */
class SegueDeck(
    private val plan: SeguePlan,
    private val side: LaneSide,
    private val sampleRate: Int,
    private val channels: Int,
    private val tuning: SegueTuning = SegueTuning(),
) {
    private val s = plan.side(side)
    private val level = curve(Target.LEVEL)
    private val bottom = curve(Target.SHELF_BOTTOM); private val core = curve(Target.BAND_CORE); private val top = curve(Target.SHELF_TOP)
    private val sweep = curve(Target.SWEEP_HZ); private val peak = curve(Target.SWEEP_PEAK)
    private val room = curve(Target.SEND_ROOM); private val echo = curve(Target.SEND_ECHO)
    private val usesSends = room != null || echo != null

    private fun curve(t: Target) = plan.lanes.firstOrNull { it.side == side && it.target == t }?.let(::LaneCurve)

    private val shelfBass = Array(channels) { Shape() }
    private val bellCore = Array(channels) { Shape() }
    private val shelfTop = Array(channels) { Shape() }
    private val svf = Array(channels) { Svf() }
    private val echoes = if (echo != null) Array(channels) { Delay(frames(tuning.echoDelayUs)) } else null
    private val rooms = if (room != null) Array(channels) { Room(tuning, sampleRate) } else null

    private var lastGain = Double.NaN
    private val coreHz = sqrt(tuning.shelfLowHz * tuning.shelfHighHz)
    private var filterMix = 0.0
    private var holdMix = 0.0
    private val holdStep = 1.0 / max(1.0, tuning.holdRampUs * sampleRate / 1e6)

    /** The plain song, smoothly, whatever the plan says from now on: the other song has stalled. */
    @Volatile var hold: Boolean = false

    /** [hold] has finished its ramp: only the plain song comes out. */
    val holdSettled: Boolean get() = holdMix >= 1.0

    private fun frames(us: Long) = max(1, (us * sampleRate / 1_000_000).toInt())

    /** Position on the lanes' 0..1000000 scale of a source position. */
    private fun u(sourceUs: Double): Double =
        (sourceUs - s.anchorUs) * SegueFormat.RATE_NOMINAL / s.ratePpm / plan.span.lengthUs * SegueFormat.T_MAX

    private fun gain(u: Double): Double {
        val l = level ?: return 1.0
        return if (l.isNull(u)) 0.0 else 10.0.pow(l.value(u) / 20)
    }

    /** [x] holds [frames] interleaved frames, changed in place; [sourceUs] is the first frame's place in the song. */
    fun process(x: FloatArray, frames: Int, sourceUs: Long) {
        val block = max(1, tuning.blockFrames)
        var done = 0
        while (done < frames) {
            val n = min(block, frames - done)
            val atUs = sourceUs + done * 1e6 / sampleRate
            run(x, done, n, u(atUs), u(atUs + n * 1e6 / sampleRate))
            done += n
        }
    }

    private fun run(x: FloatArray, from: Int, n: Int, u0: Double, u1: Double) {
        val g1 = gain(u1)
        val g0 = if (lastGain.isNaN()) gain(u0) else lastGain
        val bassDb = bottom?.value(u1) ?: 0.0; val coreDb = core?.value(u1) ?: 0.0; val topDb = top?.value(u1) ?: 0.0
        val eqNeutral = bassDb == 0.0 && coreDb == 0.0 && topDb == 0.0
        for (c in 0 until channels) {
            shelfBass[c].lowShelf(tuning.shelfLowHz, bassDb, tuning.shelfQ, sampleRate)
            bellCore[c].bell(coreHz, coreDb, tuning.coreQ, sampleRate)
            shelfTop[c].highShelf(tuning.shelfHighHz, topDb, tuning.shelfQ, sampleRate)
        }
        val hz = sweep?.value(u1) ?: SegueFormat.SWEEP_OPEN_HZ
        val wantFilter = if (hz < SegueFormat.SWEEP_OPEN_HZ * 0.999) 1.0 else 0.0
        val q = sqrt(0.5) * (peak?.value(u1) ?: 1.0)
        if (sweep != null) for (f in svf) f.tune(hz, q, sampleRate)
        val roomSend0 = room?.value(u0) ?: 0.0; val roomSend1 = room?.value(u1) ?: 0.0
        val echoSend0 = echo?.value(u0) ?: 0.0; val echoSend1 = echo?.value(u1) ?: 0.0
        for (i in 0 until n) {
            val p = (i + 1.0) / n
            val gain = g0 + (g1 - g0) * p
            val mix = filterMix + (wantFilter - filterMix) * p
            holdMix = if (hold) min(1.0, holdMix + holdStep) else max(0.0, holdMix - holdStep)
            for (c in 0 until channels) {
                val k = (from + i) * channels + c
                val dry = x[k].toDouble()
                // Run even at 0 dB, where they return the input exactly, so a band's cut starts from a settled state.
                var y = shelfTop[c].run(bellCore[c].run(shelfBass[c].run(dry)))
                if (sweep != null) {
                    val filtered = svf[c].run(y)
                    y += (filtered - y) * mix
                }
                var out = y * gain
                if (usesSends) {
                    // Sends tap the signal before the fader: a tail outlives the song's level.
                    echoes?.let { out += it[c].run(y * (echoSend0 + (echoSend1 - echoSend0) * p), tuning.echoFeedback) }
                    rooms?.let { out += it[c].run(y * (roomSend0 + (roomSend1 - roomSend0) * p)) }
                }
                x[k] = (if (holdMix > 0) dry * holdMix + out * (1 - holdMix) else if (gain == 1.0 && eqNeutral && mix == 0.0 && !usesSends) dry else out).toFloat()
            }
        }
        lastGain = g1; filterMix = wantFilter
    }

    /** Shelves and a bell on one state-variable filter: stable while they move, and the identity at 0 dB. */
    private class Shape {
        private var a1 = 1.0; private var a2 = 0.0; private var a3 = 0.0
        private var m0 = 1.0; private var m1 = 0.0; private var m2 = 0.0
        private var ic1 = 0.0; private var ic2 = 0.0
        private var set = Double.NaN
        private fun prepare(g: Double, k: Double) { a1 = 1 / (1 + g * (g + k)); a2 = g * a1; a3 = g * a2 }
        private fun w(hz: Double, rate: Int) = tan(PI * min(hz, rate * 0.45) / rate)
        fun lowShelf(hz: Double, db: Double, q: Double, rate: Int) {
            if (db == set) return; set = db
            val a = 10.0.pow(db / 40); val k = 1 / q
            prepare(w(hz, rate) / sqrt(a), k); m0 = 1.0; m1 = k * (a - 1); m2 = a * a - 1
        }
        fun highShelf(hz: Double, db: Double, q: Double, rate: Int) {
            if (db == set) return; set = db
            val a = 10.0.pow(db / 40); val k = 1 / q
            prepare(w(hz, rate) * sqrt(a), k); m0 = a * a; m1 = k * (1 - a) * a; m2 = 1 - a * a
        }
        fun bell(hz: Double, db: Double, q: Double, rate: Int) {
            if (db == set) return; set = db
            val a = 10.0.pow(db / 40); val k = 1 / (q * a)
            prepare(w(hz, rate), k); m0 = 1.0; m1 = k * (a * a - 1); m2 = 0.0
        }
        fun run(x: Double): Double {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2
            return m0 * x + m1 * v1 + m2 * v2
        }
    }

    /** A low-pass that stays stable while its cutoff moves. */
    private class Svf {
        private var g = 1.0; private var r = 1.414; private var h = 0.0
        private var s1 = 0.0; private var s2 = 0.0
        fun tune(hz: Double, q: Double, rate: Int) {
            g = tan(PI * min(hz, rate * 0.45) / rate); r = 1 / max(0.1, q); h = 1 / (1 + r * g + g * g)
        }
        fun run(x: Double): Double {
            val hp = (x - (r + g) * s1 - s2) * h
            val bp = g * hp + s1; s1 = g * hp + bp
            val lp = g * bp + s2; s2 = g * bp + lp
            return lp
        }
    }

    private class Delay(size: Int) {
        private val line = DoubleArray(size); private var i = 0
        fun run(x: Double, feedback: Double): Double {
            val out = line[i]; line[i] = x + out * feedback; i = (i + 1) % line.size
            return out
        }
    }

    private class Room(t: SegueTuning, rate: Int) {
        private val combs = t.roomCombUs.map { Delay(max(1, (it * rate / 1_000_000).toInt())) }
        private val passes = t.roomAllPassUs.map { DoubleArray(max(1, (it * rate / 1_000_000).toInt())) }
        private val at = IntArray(passes.size)
        private val feedback = t.roomCombFeedback; private val pass = t.roomAllPassGain
        private val scale = 1.0 / max(1, combs.size)
        fun run(x: Double): Double {
            var y = combs.sumOf { it.run(x, feedback) } * scale
            for (j in passes.indices) {
                val line = passes[j]; val d = line[at[j]]
                val out = d - pass * y
                line[at[j]] = y + pass * out
                at[j] = (at[j] + 1) % line.size
                y = out
            }
            return y
        }
    }

    companion object {
        /** What this engine can execute. Anything else is refused and the transition is left to normal playback. */
        fun playable(p: SeguePlan): Boolean = p.span.clock == Clock.ELAPSED && p.gestures.isEmpty() && SegueValidator.structural(p).isEmpty()

        /** How long past the window the outgoing song must keep running for its sends to ring out. */
        fun tailUs(p: SeguePlan, t: SegueTuning = SegueTuning()): Long =
            if (p.lanes.any { it.side == LaneSide.OUTGOING && (it.target == Target.SEND_ROOM || it.target == Target.SEND_ECHO) }) t.sendTailUs else 0
    }
}
