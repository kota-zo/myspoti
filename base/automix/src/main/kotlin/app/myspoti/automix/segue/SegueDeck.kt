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
    private val rise = curve(Target.RISE_HZ)
    private val bassBand = curve(Target.BASS_BAND)
    private val room = curve(Target.SEND_ROOM); private val echo = curve(Target.SEND_ECHO)
    private val usesSends = room != null || echo != null
    private val otherSends = plan.lanes.any { it.side != side && (it.target == Target.SEND_ROOM || it.target == Target.SEND_ECHO) }

    private fun curve(t: Target) = plan.lanes.firstOrNull { it.side == side && it.target == t }?.let(::LaneCurve)

    /** The other song's fader: this side's share of the headroom depends on it. */
    private val otherLevel = plan.lanes.firstOrNull { it.side != side && it.target == Target.LEVEL }?.let(::LaneCurve)

    private val shelfBass = Array(channels) { Shape() }
    private val bellCore = Array(channels) { Shape() }
    private val shelfTop = Array(channels) { Shape() }
    private val svf = Array(channels) { Svf() }
    private val riseSvf = Array(channels) { Svf() }
    /** The isolator: a split at the crossover, then each half once more, for fourth-order bands that sum flat. */
    private val isoSplit = Array(channels) { Svf().also { it.tune(tuning.isolatorHz, sqrt(0.5), sampleRate) } }
    private val isoLow = Array(channels) { Svf().also { it.tune(tuning.isolatorHz, sqrt(0.5), sampleRate) } }
    private val isoHigh = Array(channels) { Svf().also { it.tune(tuning.isolatorHz, sqrt(0.5), sampleRate) } }
    private var isoMix = 0.0
    private var lastBassGain = Double.NaN
    private val isoStep = 1.0 / max(1.0, tuning.isolatorFadeUs * sampleRate / 1e6)
    private val sendCut = Array(channels) { LowCut(tuning.sendLowCutHz, sampleRate) }
    private val echoes = if (echo != null) Array(channels) { Delay(frames(plan.echoUs ?: tuning.echoDelayUs)) } else null
    private val rooms = if (room != null) Array(channels) { Room(tuning, sampleRate) } else null

    private var lastGain = Double.NaN
    private val coreHz = sqrt(tuning.shelfLowHz * tuning.shelfHighHz)
    private var filterMix = 0.0
    private var riseMix = 0.0
    private var holdMix = 0.0
    private val holdStep = 1.0 / max(1.0, tuning.holdRampUs * sampleRate / 1e6)

    /** The plain song, smoothly, whatever the plan says from now on: the other song has stalled. */
    @Volatile var hold: Boolean = false

    /** [hold] has finished its ramp: only the plain song comes out. */
    val holdSettled: Boolean get() = holdMix >= 1.0

    private var limitGain = 1.0
    private var need = DoubleArray(0)
    private val attackStep = 1.0 / max(1.0, tuning.headroom.attackUs * sampleRate / 1e6)
    private val releaseStep = 1.0 / max(1.0, tuning.headroom.releaseUs * sampleRate / 1e6)

    private fun frames(us: Long) = max(1, (us * sampleRate / 1_000_000).toInt())

    private val timeMap = s.ramp?.let { SegueTimeMap(s, plan.span.lengthUs) }

    /** The outgoing song's loops, in order of place, and the first slice's audio. */
    private val loops = if (side == LaneSide.OUTGOING) plan.gestures.filter { it.kind == GestureKind.LOOP }.sortedBy { it.atUs } else emptyList()
    private val loopSlice = FloatArray(if (loops.isEmpty()) 0 else frames(loops.maxOf { it.units.toLong() }) * channels)
    private val loopEdge = max(1, frames(LOOP_EDGE_US))

    /** A send's tail outlives the window: both songs are kept under the ceiling until it has rung out. */
    private val tailEnd = tailUs(plan, tuning).let { if (it > 0) SegueFormat.T_MAX * (1.0 + it.toDouble() / plan.span.lengthUs) else 0.0 }

    /** Position on the lanes' 0..1000000 scale of a source position. */
    private fun u(sourceUs: Double): Double =
        if (timeMap != null) timeMap.elapsedUs(sourceUs) / plan.span.lengthUs * SegueFormat.T_MAX
        else (sourceUs - s.anchorUs) * SegueFormat.RATE_NOMINAL / s.ratePpm / plan.span.lengthUs * SegueFormat.T_MAX

    /** The source frames of [from]..[from]+[n], from [atUs] on, replaced by the loop in force: the first pass of a slice is recorded and heard as it is. */
    private fun loop(x: FloatArray, from: Int, n: Int, atUs: Double) {
        val first = loops.first()
        for (i in 0 until n) {
            val posUs = atUs + i * 1e6 / sampleRate
            if (posUs < first.atUs) continue
            val active = loops.last { it.atUs <= posUs }
            val off = Math.round((posUs - active.atUs) * sampleRate / 1e6)
            val length = frames(active.units.toLong())
            val k = (from + i) * channels
            if (active === first && off < length) {
                for (c in 0 until channels) loopSlice[off.toInt() * channels + c] = x[k + c]
                continue
            }
            val phase = (off % length).toInt()
            // The edges of each pass closed for an instant: the slice's two ends do not meet.
            val edge = min(1f, min((phase + 1f) / loopEdge, (length - phase).toFloat() / loopEdge))
            for (c in 0 until channels) x[k + c] = loopSlice[phase * channels + c] * edge
        }
    }

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
            if (loops.isNotEmpty() && !hold) loop(x, done, n, atUs)
            run(x, done, n, u(atUs), u(atUs + n * 1e6 / sampleRate))
            done += n
        }
        if (tuning.headroom.ceiling > 0 && !hold) limit(x, frames, sourceUs)
    }

    /**
     * The peak this side may reach at [u]: none outside the window, without both level lanes, or while the two faders add up to
     * 1 or less, otherwise its fader's share of the ceiling. The two shares add up to the ceiling, so
     * the two players' sum stays under it although neither hears the other.
     */
    private fun ceiling(u: Double): Double {
        // A plan that does not say how both faders move leaves the levels to the players.
        val other = otherLevel ?: return Double.POSITIVE_INFINITY
        if (level != null && u > SegueFormat.T_MAX && u <= tailEnd)
            return if (side == LaneSide.OUTGOING) tuning.headroom.tailPeak else tuning.headroom.ceiling - tuning.headroom.tailPeak
        if (level == null || u < 0 || u > SegueFormat.T_MAX) return Double.POSITIVE_INFINITY
        // A side with a send still sounds when its fader is down: its tail keeps a share of the peak.
        val mine = max(gain(u), if (usesSends) tuning.headroom.sendPeak else 0.0)
        val theirs = max(if (other.isNull(u)) 0.0 else 10.0.pow(other.value(u) / 20), if (otherSends) tuning.headroom.sendPeak else 0.0)
        val sum = mine + theirs
        return if (sum <= 1.0) Double.POSITIVE_INFINITY else tuning.headroom.ceiling * mine / sum
    }

    /**
     * A limiter that looks ahead inside the buffer it is given: its gain is already down when a peak
     * over the ceiling arrives, and comes back up slowly after. A peak right at the start of a buffer
     * is held down at once: the ceiling is never crossed.
     */
    private fun limit(x: FloatArray, frames: Int, sourceUs: Long) {
        if (frames <= 0) return
        if (need.size < frames) need = DoubleArray(frames)
        val block = max(1, tuning.blockFrames)
        var any = false
        var done = 0
        while (done < frames) {
            val n = min(block, frames - done)
            val atUs = sourceUs + done * 1e6 / sampleRate
            val c = min(ceiling(u(atUs)), ceiling(u(atUs + n * 1e6 / sampleRate)))
            for (i in done until done + n) {
                if (c == Double.POSITIVE_INFINITY) { need[i] = 1.0; continue }
                var p = 0.0
                for (ch in 0 until channels) p = max(p, kotlin.math.abs(x[i * channels + ch].toDouble()))
                need[i] = if (p > c) c / p else 1.0
                if (need[i] < 1.0) any = true
            }
            done += n
        }
        if (!any && limitGain >= 1.0) return
        for (i in frames - 2 downTo 0) need[i] = min(need[i], need[i + 1] + attackStep)
        var g = limitGain
        for (i in 0 until frames) {
            g = min(need[i], g + releaseStep)
            if (g < 1.0) for (ch in 0 until channels) { val k = i * channels + ch; x[k] = (x[k] * g).toFloat() }
        }
        limitGain = min(1.0, g)
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
        val riseHz = rise?.value(u1) ?: SegueFormat.RISE_OPEN_HZ
        val wantRise = if (riseHz > SegueFormat.RISE_OPEN_HZ * 1.001) 1.0 else 0.0
        if (rise != null) for (f in riseSvf) f.tune(riseHz, sqrt(0.5), sampleRate)
        // The isolator is in the chain while its band is anything but whole, faded in and out so the split's phase is never a jump.
        val bassGain1 = bassBand?.let { if (it.isNull(u1)) 1.0 else 10.0.pow(it.value(u1) / 20) } ?: 1.0
        val bassGain0 = if (lastBassGain.isNaN()) bassGain1 else lastBassGain
        val wantIso = if (bassBand != null && (bassGain1 < 0.999 || bassGain0 < 0.999)) 1.0 else 0.0
        val roomSend0 = room?.value(u0) ?: 0.0; val roomSend1 = room?.value(u1) ?: 0.0
        val echoSend0 = echo?.value(u0) ?: 0.0; val echoSend1 = echo?.value(u1) ?: 0.0
        for (i in 0 until n) {
            val p = (i + 1.0) / n
            val gain = g0 + (g1 - g0) * p
            val mix = filterMix + (wantFilter - filterMix) * p
            val riseNow = riseMix + (wantRise - riseMix) * p
            holdMix = if (hold) min(1.0, holdMix + holdStep) else max(0.0, holdMix - holdStep)
            isoMix = if (wantIso > isoMix) min(wantIso, isoMix + isoStep) else max(wantIso, isoMix - isoStep)
            val isoNow = isoMix
            for (c in 0 until channels) {
                val k = (from + i) * channels + c
                val dry = x[k].toDouble()
                var src = dry
                if (bassBand != null) {
                    // Runs all along the lane, so its state is settled when it comes in.
                    val low = isoLow[c].run(isoSplit[c].run(dry))
                    isoHigh[c].run(isoSplit[c].high)
                    val split = (bassGain0 + (bassGain1 - bassGain0) * p) * low + isoHigh[c].high
                    if (isoNow > 0) src = dry + (split - dry) * isoNow
                }
                // Run even at 0 dB, where they return the input exactly, so a band's cut starts from a settled state.
                var y = shelfTop[c].run(bellCore[c].run(shelfBass[c].run(src)))
                if (sweep != null) {
                    val filtered = svf[c].run(y)
                    y += (filtered - y) * mix
                }
                if (rise != null) {
                    riseSvf[c].run(y)
                    y += (riseSvf[c].high - y) * riseNow
                }
                var out = y * gain
                if (usesSends) {
                    // Sends tap the signal before the fader: a tail outlives the song's level.
                    val send = sendCut[c].run(y)
                    echoes?.let { out += it[c].run(send * (echoSend0 + (echoSend1 - echoSend0) * p), tuning.echoFeedback) }
                    rooms?.let { out += it[c].run(send * (roomSend0 + (roomSend1 - roomSend0) * p)) }
                }
                x[k] = (if (holdMix > 0) dry * holdMix + out * (1 - holdMix) else if (gain == 1.0 && eqNeutral && mix == 0.0 && riseNow == 0.0 && isoNow == 0.0 && !usesSends) dry else out).toFloat()
            }
        }
        lastGain = g1; filterMix = wantFilter; riseMix = wantRise; lastBassGain = bassGain1
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

    /** A low-pass that stays stable while its cutoff moves; the same pass's high-pass is in [high]. */
    private class Svf {
        var high = 0.0; private set
        private var g = 1.0; private var r = 1.414; private var h = 0.0
        private var s1 = 0.0; private var s2 = 0.0
        fun tune(hz: Double, q: Double, rate: Int) {
            g = tan(PI * min(hz, rate * 0.45) / rate); r = 1 / max(0.1, q); h = 1 / (1 + r * g + g * g)
        }
        fun run(x: Double): Double {
            val hp = (x - (r + g) * s1 - s2) * h
            high = hp
            val bp = g * hp + s1; s1 = g * hp + bp
            val lp = g * bp + s2; s2 = g * bp + lp
            return lp
        }
    }

    /** One pole: enough to keep a send's bass from building up. */
    private class LowCut(hz: Double, rate: Int) {
        private val k = kotlin.math.exp(-2 * PI * hz / rate)
        private var low = 0.0
        fun run(x: Double): Double { low = x + (low - x) * k; return x - low }
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
        /** How long a loop's seam is closed, so its two ends do not click. */
        private const val LOOP_EDGE_US = 3_000L

        /** What this engine can execute. Anything else is refused and the transition is left to normal playback. */
        fun playable(p: SeguePlan): Boolean = p.span.clock == Clock.ELAPSED && p.gestures.all { it.kind == GestureKind.LOOP } && SegueValidator.structural(p).isEmpty()

        /** How long past the window the outgoing song must keep running for its sends to ring out. */
        fun tailUs(p: SeguePlan, t: SegueTuning = SegueTuning()): Long =
            if (p.lanes.any { it.side == LaneSide.OUTGOING && (it.target == Target.SEND_ROOM || it.target == Target.SEND_ECHO) }) t.sendTailUs else 0
    }
}
