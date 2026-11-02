/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Desktop bench on real songs (not part of the app). `<dir>` holds `sNN.f32` (stereo float 44.1 kHz,
 * from ffmpeg) and optionally `sNN.beats` (Beat This output: time and beat number per line) as the
 * reference. Commands: `analyze <dir>` prints tempo, beat and downbeat agreement with the reference.
 */
fun main(args: Array<String>) {
    val cmd = args.getOrNull(0) ?: return println("usage: analyze <dir> | plan <dir> <a> <b> [out.f32]")
    val dir = File(args[1])
    when (cmd) {
        "analyze" -> {
            val names = dir.listFiles { f -> f.name.endsWith(".f32") }!!.map { it.nameWithoutExtension }.sorted()
            for (n in names) analyzeOne(dir, n)
        }
        "plan" -> {
            // plan <dir> a,b a,b ... [render]: plans (and renders) each pair; checks beat alignment on the reference beats.
            val pairs = args.drop(2).filter { it.contains(',') }.map { it.split(',').let { p -> p[0] to p[1] } }
            val render = args.contains("render")
            val cache = HashMap<String, TrackAnalysis>()
            for ((x, y) in pairs) {
                val a = cache.getOrPut(x) { analysisOf(dir, x) }; val b = cache.getOrPut(y) { analysisOf(dir, y) }
                val plan = MixPlanner.plan(a, b)
                println("$x→$y " + plan)
                println("      " + alignment(dir, x, y, plan))
                if (render) Render.render(File(dir, "$x.f32"), File(dir, "$y.f32"), plan, File(dir, "mix-$x-$y.wav"))
            }
        }
        "model" -> dir.listFiles { f -> f.name.endsWith(".f32") }!!.map { it.nameWithoutExtension }.sorted().forEach { modelDebug(dir, it) }
        "profile" -> args.drop(2).forEach { profile(dir, it) }
        "grids" -> args.drop(2).forEach { gridDebug(dir, it) }
        "tempo" -> dir.listFiles { f -> f.name.endsWith(".f32") }!!.map { it.nameWithoutExtension }.sorted().forEach { tempoDebug(dir, it) }
        else -> println("unknown $cmd")
    }
}

/** Stereo float 44.1 kHz → mid and side at 22.05 kHz (pairs averaged). */
fun loadPcm(file: File): Pair<FloatArray, FloatArray> {
    val bytes = file.readBytes()
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val frames = fb.remaining() / 2
    val n = frames / 2
    val mid = FloatArray(n); val side = FloatArray(n)
    for (i in 0 until n) {
        val l0 = fb.get(4 * i); val r0 = fb.get(4 * i + 1); val l1 = fb.get(4 * i + 2); val r1 = fb.get(4 * i + 3)
        mid[i] = (l0 + r0 + l1 + r1) / 4; side[i] = (l0 - r0 + l1 - r1) / 4
    }
    return mid to side
}

/** Beat This, from AUTOMIX_MODEL (the app's asset), or none. */
val model: BeatThis? by lazy { System.getenv("AUTOMIX_MODEL")?.let { BeatThis(File(it).readBytes(), defaultThreads = System.getenv("AUTOMIX_THREADS")?.toInt() ?: 2) } }

fun analysisOf(dir: File, name: String): TrackAnalysis {
    val (mid, side) = loadPcm(File(dir, "$name.f32"))
    return Analyzer.analyze(mid, side, model, threads = System.getenv("AUTOMIX_THREADS")?.toInt() ?: 2)
}

private fun analyzeOne(dir: File, name: String) {
    val (mid, side) = loadPcm(File(dir, "$name.f32"))
    val t0 = System.nanoTime()
    val a = Analyzer.analyze(mid, side, model, threads = System.getenv("AUTOMIX_THREADS")?.toInt() ?: 2)
    val ms = (System.nanoTime() - t0) / 1e6
    val ref = File(dir, "$name.beats").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.map { l -> l.trim().split(Regex("\\s+")).let { it[0].toDouble() to it[1].toInt() } }
    val sb = StringBuilder()
    sb.append(String.format("%s %5.0fms bpm %6.2f steady %-5s head %s tail %s key %2d(%.2f) loud %5.1f content %.2f-%.2f phrase %d",
        name, ms, a.bpm, a.steady, g(a.head), g(a.tail), a.key, a.keyStrength, a.loudnessDb, a.contentStart, a.contentEnd, a.phraseOffset))
    if (ref != null) {
        val rb = ref.map { it.first }.toDoubleArray()
        val rd = ref.filter { it.second == 1 }.map { it.first }.toDoubleArray()
        val rbpm = 60.0 / median(DoubleArray(rb.size - 1) { rb[it + 1] - rb[it] })
        val f = fMeasure(a.beats, rb); val off = meanOffset(a.beats, rb)
        val fHalf = fMeasure(a.beats.filterIndexed { i, _ -> i % 2 == 0 }.toDoubleArray(), rb)
        val fDouble = fMeasure(a.beats, rb.flatMapIndexed { i, t -> if (i + 1 < rb.size) listOf(t, (t + rb[i + 1]) / 2) else listOf(t) }.toDoubleArray())
        val fd = fMeasure(a.barStarts, rd)
        val gl = Analyzer.lastGlobal
        if (gl != null) {
            val gb = DoubleArray(gl.count) { gl.beat(it) }
            fun region(x: DoubleArray, a: Double, b: Double) = x.filter { it in a..b }.toDoubleArray()
            val h = a.contentStart to a.contentStart + 75; val t = a.contentEnd - 90 to a.contentEnd
            val mb = Analyzer.lastModelBeats
            sb.append(String.format("\n      grid %s q %s gridF head %.2f tail %.2f | modelF head %.2f tail %.2f | grid-vs-model head %.2f tail %.2f",
                g(gl), Analyzer.lastWhole, fMeasure(region(gb, h.first, h.second), region(rb, h.first, h.second)), fMeasure(region(gb, t.first, t.second), region(rb, t.first, t.second)),
                fMeasure(region(mb, h.first, h.second), region(rb, h.first, h.second)), fMeasure(region(mb, t.first, t.second), region(rb, t.first, t.second)),
                fMeasure(region(gb, h.first, h.second), region(mb, h.first, h.second)), fMeasure(region(gb, t.first, t.second), region(mb, t.first, t.second))))
        }
        // Downbeat check on the head and tail grids: the grid's downbeats there against the reference ones.
        fun downF(g: Grid?, d: Double, a0: Double, b0: Double): String {
            if (g == null || d.isNaN()) return "-"
            val k0 = Math.ceil(g.index(a0)).toInt(); val k1 = Math.floor(g.index(b0)).toInt()
            val db = (k0..k1).filter { Math.floorMod(it - Math.round(g.index(d)).toInt(), 4) == 0 }.map { g.beat(it) }.toDoubleArray()
            return String.format("%.2f", fMeasure(db, rd.filter { it in a0..b0 }.toDoubleArray()))
        }
        sb.append("\n      head/tail grid downF " + downF(a.head, a.headDown, a.contentStart, a.contentStart + 75) + " / " + downF(a.tail, a.tailDown, a.contentEnd - 90, a.contentEnd))
        sb.append(String.format("\n      ref bpm %6.2f | beatF %.2f (half %.2f, double %.2f) offset %+5.1fms | downF %.2f", rbpm, f, fHalf, fDouble, off * 1000, fd))
    }
    println(sb)
}

private fun g(g: Grid?) = if (g == null) "-" else String.format("%.2f/%.2f/%.0fms/%.1f%s", g.bpm, g.steadiness, g.driftMs, g.contrast, if (g.reliable) "✓" else "✗")

fun median(x: DoubleArray): Double { val s = x.sorted(); return s[s.size / 2] }

fun fMeasure(est: DoubleArray, ref: DoubleArray, tol: Double = 0.07): Double {
    if (est.isEmpty() || ref.isEmpty()) return 0.0
    var hit = 0; var j = 0
    val used = BooleanArray(ref.size)
    for (t in est) {
        while (j < ref.size && ref[j] < t - tol) j++
        var k = j
        while (k < ref.size && ref[k] <= t + tol) { if (!used[k]) { used[k] = true; hit++; break }; k++ }
    }
    val p = hit.toDouble() / est.size; val r = hit.toDouble() / ref.size
    return if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
}

fun meanOffset(est: DoubleArray, ref: DoubleArray, tol: Double = 0.07): Double {
    var s = 0.0; var c = 0
    for (t in est) {
        val r = ref.minByOrNull { abs(it - t) } ?: continue
        if (abs(r - t) <= tol) { s += t - r; c++ }
    }
    return if (c == 0) 0.0 else s / c
}

/** Tempo debugging: autocorrelation peaks of a song's onset envelope. */
fun tempoDebug(dir: File, name: String) {
    val (mid, side) = loadPcm(File(dir, "$name.f32"))
    val f = Features.of(mid, side)
    for ((label, env) in listOf("env" to BeatTracker.envelope(f))) {
        val n = env.size
        val m = env.average()
        val acf = DoubleArray(400) { lag -> var s = 0.0; for (i in 0 until n - lag) s += (env[i] - m) * (env[i + lag] - m); s / (n - lag) }
        val peaks = (20 until 399).filter { acf[it] >= acf[it - 1] && acf[it] >= acf[it + 1] && Features.FPS * 60 / it in 50.0..220.0 }
            .sortedByDescending { acf[it] }.take(8)
        println("$name $label " + peaks.take(5).joinToString(" ") { lag ->
            val g = BeatTracker.fit(env, 0, n, lag.toDouble())
            String.format("%.1f:%.2f[st%.2f c%.2f d%.0f]", Features.FPS * 60 / lag, acf[lag], g?.steadiness ?: 0.0, g?.contrast ?: 0.0, g?.driftMs ?: 0.0)
        })
    }
}

/** Beat This small alone on the whole song against the reference file. */
fun modelDebug(dir: File, name: String) {
    val (mid, _) = loadPcm(File(dir, "$name.f32"))
    val t0 = System.nanoTime()
    val act = model!!.run(mid, 0, mid.size)
    val ms = (System.nanoTime() - t0) / 1e6
    val beats = BeatThis.peaks(act, act.beat).map { it.first }.toDoubleArray()
    val downs = BeatThis.peaks(act, act.downbeat).map { it.first }.toDoubleArray()
    val ref = File(dir, "$name.beats").readLines().filter { it.isNotBlank() }.map { l -> l.trim().split(Regex("\\s+")).let { it[0].toDouble() to it[1].toInt() } }
    val rb = ref.map { it.first }.toDoubleArray(); val rd = ref.filter { it.second == 1 }.map { it.first }.toDoubleArray()
    val gaps = beats.toList().zipWithNext { a, b -> b - a }.sorted()
    println(String.format("%s small0 %5.0fms bpm %.2f beatF %.2f downF %.2f (%d beats, %d downs)", name, ms, 60 / gaps[gaps.size / 2], fMeasure(beats, rb), fMeasure(downs, rd), beats.size, downs.size))
}

/** Beat alignment in the mix, on the reference (Beat This final0) beats of both songs mapped through the plan. */
fun alignment(dir: File, x: String, y: String, plan: MixPlan): String {
    fun ref(n: String) = File(dir, "$n.beats").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }
        ?.map { l -> l.trim().split(Regex("\\s+")).let { it[0].toDouble() to it[1].toInt() } }
    val ra = ref(x) ?: return "no reference"; val rb = ref(y) ?: return "no reference"
    if (!plan.synced) return "unsynced"
    val ua = ra.map { (it.first - plan.exitA) to it.second }.filter { it.first in -1.0..plan.length + 1 }
    val ub = rb.map { ((it.first - plan.entryB) / plan.speedB) to it.second }.filter { it.first in 0.0..plan.length }
    if (ua.size < 2 || ub.isEmpty()) return "too few reference beats"
    val aBeats = ua.map { it.first }.let { l -> l + l.zipWithNext { p, q -> (p + q) / 2 } }
    val d = ub.map { b -> aBeats.minOf { abs(it - b.first) } * 1000 }.sorted()
    val aDown = ua.filter { it.second == 1 }.map { it.first }
    val bDown = ub.filter { it.second == 1 }.map { it.first }
    val dd = bDown.mapNotNull { b -> aDown.minOfOrNull { abs(it - b) } }.map { it * 1000 }.sorted()
    return String.format("ref beats: median %.0f ms, 90%% %.0f ms over %d B beats | downbeats on A downbeats: median %s ms", d[d.size / 2], d[(d.size * 0.9).toInt().coerceAtMost(d.size - 1)], d.size,
        if (dd.isEmpty()) "-" else String.format("%.0f", dd[dd.size / 2]))
}

/** Time per analysis stage on one song (desktop). */
fun profile(dir: File, name: String) {
    val (mid, side) = loadPcm(File(dir, "$name.f32"))
    fun <T> t(label: String, f: () -> T): T { val t0 = System.nanoTime(); val r = f(); print(String.format(" %s %.0fms", label, (System.nanoTime() - t0) / 1e6)); return r }
    print(name)
    repeat(2) {
        val f = t("features") { Features.of(mid, side) }
        val env = t("env") { BeatTracker.envelope(f) }
        t("model") { model!!.run(mid, 0, mid.size) }
        t("mel") { MelFrontend.spectrogram(mid, 0, mid.size) }
        t("fit") { BeatTracker.fit(env, 0, env.size, Features.FPS * 60 / 120) }
        t("track") { BeatTracker.track(env, Features.FPS * 60 / 120) }
        t("total") { Analyzer.analyze(mid, side, model, threads = System.getenv("AUTOMIX_THREADS")?.toInt() ?: 2) }
        println()
    }
}

/** One song's grids against the reference beats, by region. */
fun gridDebug(dir: File, name: String) {
    val a = analysisOf(dir, name)
    val rb = File(dir, "$name.beats").readLines().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+"))[0].toDouble() }.toDoubleArray()
    for ((label, g) in listOf("head" to a.head, "tail" to a.tail)) {
        if (g == null) { println("$name $label none"); continue }
        val gb = DoubleArray(g.count) { g.beat(it) }
        println(String.format("%s %s %.3f..%.1f s %.2f BPM: F vs ref %.2f, offset %+.0f ms | note: %s", name, label, g.first, g.last, g.bpm,
            fMeasure(gb, rb.filter { it in g.first..g.last }.toDoubleArray()), meanOffset(gb, rb) * 1000, a.note))
    }
}
