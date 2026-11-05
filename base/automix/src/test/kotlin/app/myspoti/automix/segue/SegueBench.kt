/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.BenchCache
import app.myspoti.automix.BeatTracker
import app.myspoti.automix.Features
import app.myspoti.automix.Render
import app.myspoti.automix.TrackAnalysis
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measures segue plans on a corpus that stays out of the repository. `<dir>` holds `sNN.f32` (stereo
 * float 44.1 kHz) and optionally `names.txt` (`id|title - artist.ext`, for the split). Every command
 * prints tab-separated rows and a summary, and takes `fold=tuning|validation|test`.
 */
object SegueBench {
    private val L = Locale.ROOT
    private const val RATE = Render.SR

    fun run(dir: File, args: List<String>) {
        val fold = args.firstOrNull { it.startsWith("fold=") }?.removePrefix("fold=")
        val limit = args.firstOrNull { it.startsWith("limit=") }?.removePrefix("limit=")?.toInt()
        val rest = args.filter { !it.contains('=') }
        tuning = args.firstOrNull { it.startsWith("with=") }?.removePrefix("with=")?.split(',')?.fold(SegueTuning()) { t, kv ->
            val (k, v) = kv.split(':'); SegueCorpus.withValue(t, k, v.toDouble())
        } ?: SegueTuning()
        when (rest.firstOrNull()) {
            "split" -> printSplit(dir)
            "tempos" -> tempos(dir, fold)
            "tails" -> tails(dir, fold, limit ?: 12)
            "matrix" -> matrix(dir, fold, rest.contains("fresh"))
            "measure" -> measure(dir, fold, limit ?: 100, args.firstOrNull { it.startsWith("only=") }?.removePrefix("only="))
            "stability" -> stability(dir, fold, limit ?: 60)
            "render" -> render(dir, rest.drop(1).filter { it.contains(',') })
            else -> println("segue <dir> split | matrix [fresh] | measure [limit=N] | stability [limit=N] | render a,b ... ; all take fold=")
        }
    }

    // ── The corpus and its split ──

    private fun names(dir: File, fold: String?): List<String> {
        val all = dir.listFiles { f -> f.name.endsWith(".f32") && !f.name.contains(".tmp.") && !f.name.startsWith("mix-") }.orEmpty().map { it.nameWithoutExtension }.sorted()
        if (fold == null) return all
        val split = SegueCorpus.split(SegueCorpus.read(File(dir, "names.txt")))
        return all.filter { split[it] == fold }
    }

    private fun analyses(dir: File, names: List<String>, fresh: Boolean = false) = names.associateWith { BenchCache.analysis(dir, it, fresh).analysis }

    private fun printSplit(dir: File) {
        val rows = SegueCorpus.read(File(dir, "names.txt"))
        val split = SegueCorpus.split(rows)
        for (r in rows) println("${r.id}\t${split[r.id]}")
        println("summary\t" + split.values.groupingBy { it }.eachCount().toSortedMap().entries.joinToString("\t") { "${it.key}=${it.value}" } +
            "\tartists=${rows.map { it.artist }.toSet().size}")
    }

    /** Ordered pairs, spread evenly when there is a limit: the same pairs on every run. */
    private fun pairs(names: List<String>, limit: Int?): List<Pair<String, String>> {
        val all = names.flatMap { a -> names.filter { it != a }.map { a to it } }
        if (limit == null || limit >= all.size) return all
        return all.sortedBy { (a, b) -> SegueCodec.hex("$a|$b".toByteArray()) }.take(limit)
    }

    /** `with=field:value,field:value`: a what-if on the bench only; the plans' parameters stay in [SegueTuning]. */
    private var tuning = SegueTuning()

    private fun request(a: TrackAnalysis, b: TrackAnalysis, t: SegueTuning = tuning) = SegueRequest(a, b, tuning = t)

    // ── Every pair: which level, why ──

    /** For each shape that rings: how loud the outgoing song's tail is against the incoming one, and whether the mix clips. */
    private fun tails(dir: File, fold: String?, limit: Int) {
        val names = names(dir, fold)
        val songs = analyses(dir, names)
        val tmp = File(dir, ".segue-tmp").apply { mkdirs() }
        val cache = HashMap<String, FloatArray>()
        val shapes = setOf(SegueShape.ECHO_OUT, SegueShape.WASH_OUT, SegueShape.ECHO_TAIL, SegueShape.RISE_OUT)
        val seen = HashMap<SegueShape, Int>()
        println("pair\tshape\tseconds\ttail_db\tb_db\ttail_minus_b\tpeak\tclipped_ms")
        for ((x, y) in pairs(names, null).shuffled(java.util.Random(7))) {
            val d = SeguePolicy.decide(request(songs.getValue(x), songs.getValue(y)))
            val shape = d.shape ?: continue
            val p = d.plan ?: continue
            if (shape !in shapes || seen.getOrDefault(shape, 0) >= limit) continue
            seen.merge(shape, 1, Int::plus)
            cache.clear()
            val st = stems(dir, x, y, p, 2.0, 4.0, cache, tmp)
            val end = st.preFrames + (p.span.lengthUs / 1e6 * RATE).toInt()
            fun rms(v: FloatArray, from: Int, to: Int): Double {
                var e = 0.0; var n = 0
                for (i in 2 * from until min(2 * to, v.size)) { e += v[i] * v[i]; n++ }
                return 10 * log10(max(1e-12, e / max(1, n)))
            }
            // From where the send is at its height, or the end of the window when there is none.
            val height = p.lanes.firstOrNull { it.side == LaneSide.OUTGOING && (it.target == Target.SEND_ECHO || it.target == Target.SEND_ROOM) }?.knots?.maxByOrNull { it.valueMilli ?: 0 }?.t
            val from = if (height == null) end else st.preFrames + (height.toDouble() / SegueFormat.T_MAX * p.span.lengthUs / 1e6 * RATE).toInt() + RATE / 3
            val to = from + RATE
            val ma = mix(st)
            val peak = ma.maxOf { abs(it) }
            val clipped = ma.count { abs(it) > 1f } / 2 * 1000 / RATE
            println(String.format(L, "%s,%s\t%s\t%.1f\t%.1f\t%.1f\t%.1f\t%.2f\t%d", x, y, shape, p.span.lengthUs / 1e6, rms(st.a, from, to), rms(st.b, from, to), rms(st.a, from, to) - rms(st.b, from, to), peak, clipped))
        }
        tmp.deleteRecursively()
    }

    /** Each song's tempo as the grid has it, at both ends. */
    private fun tempos(dir: File, fold: String?) {
        val names = names(dir, fold)
        for ((name, s) in analyses(dir, names, false)) println(String.format(L, "%s\t%.2f\t%.2f\t%.2f\t%.2f\t%d", name,
            s.tail?.bpm ?: 0.0, s.tail?.steadiness ?: 0.0, s.head?.bpm ?: 0.0, s.head?.steadiness ?: 0.0, s.modelBeats.size) +
            String.format(L, "\t%.1f\t%.1f\t%.1f\t%.1f", s.duration, s.contentStart, s.contentEnd, s.barStarts.lastOrNull() ?: 0.0))
    }

    private fun matrix(dir: File, fold: String?, fresh: Boolean) {
        val names = names(dir, fold)
        val songs = analyses(dir, names, fresh)
        println(String.format(L, "songs\t%d\twith phase on tail %d\ton head %d\treliable tail %d", names.size,
            songs.values.count { it.tailPhase != null }, songs.values.count { it.headPhase != null }, songs.values.count { it.tail?.let { g -> g.steadiness >= 0.6 && g.driftMs <= 30 } == true }))
        val levels = java.util.EnumMap<SegueLevel, Int>(SegueLevel::class.java)
        val causes = HashMap<String, Int>(); val refused = HashMap<String, Int>()
        val ms = ArrayList<Double>(); var unstable = 0; var invalid = 0
        val lengths = ArrayList<Double>(); val cuts = ArrayList<Double>()
        println("pair\tlevel\tseconds\texit\tentry\trate\tscore\tms\tshape\twhy")
        for ((x, y) in pairs(names, null)) {
            val a = songs.getValue(x); val b = songs.getValue(y)
            val t0 = System.nanoTime()
            val d = SeguePolicy.decide(request(a, b))
            ms += (System.nanoTime() - t0) / 1e6
            levels[d.level] = (levels[d.level] ?: 0) + 1
            for (at in d.attempts) {
                causes.merge("${at.level}:${at.cause}", 1, Int::plus)
                if (at.cause == SegueCause.GUARDS) Regex("([A-Z_0-9]+)×").findAll(at.detail).forEach { refused.merge("${at.level.cls}:${it.groupValues[1]}", 1, Int::plus) }
            }
            if (SeguePolicy.decide(request(a, b)).fingerprint != d.fingerprint) unstable++
            d.plan?.let { p ->
                if (SegueValidator.against(p, SeguePolicy.facts(a, b).first, SeguePolicy.facts(a, b).second).isNotEmpty()) invalid++
                lengths += p.span.lengthUs / 1e6
                cuts += max(0.0, app.myspoti.automix.MixPlanner.musicEnd(a) - (p.outgoing.anchorUs + p.span.lengthUs) / 1e6)
            }
            val p = d.plan
            println(String.format(L, "%s,%s\t%s\t%.3f\t%.3f\t%.3f\t%d\t%s\t%.2f\t%s\t%s", x, y, d.level, (p?.span?.lengthUs ?: 0) / 1e6, (p?.outgoing?.anchorUs ?: 0) / 1e6,
                (p?.incoming?.anchorUs ?: 0) / 1e6, p?.incoming?.ratePpm ?: 0, d.score?.let { String.format(L, "%.3f", it) } ?: "-", ms.last(), d.shape ?: "-", d.why))
        }
        ms.sort(); lengths.sort(); cuts.sort()
        println("summary\tpairs=${ms.size}\t" + levels.entries.joinToString("\t") { "${it.key}=${it.value}" } + "\tnot_reproducible=$unstable\tinvalid=$invalid")
        println(String.format(L, "summary\tplan_median_ms=%.2f\tplan_p95_ms=%.2f\tmedian_seconds=%.1f\tmedian_cut_seconds=%.1f", at(ms, 0.5), at(ms, 0.95), at(lengths, 0.5), at(cuts, 0.5)))
        for ((k, v) in causes.toSortedMap()) println("cause\t$k\t$v")
        for ((k, v) in refused.toSortedMap()) println("refused\t$k\t$v")
    }

    private fun at(x: List<Double>, q: Double) = if (x.isEmpty()) Double.NaN else x[((x.size - 1) * q).toInt()]

    // ── Rendering: both stems through the same sound as the phone ──

    private class Stems(val a: FloatArray, val b: FloatArray, val dryA: FloatArray, val dryB: FloatArray, val preFrames: Int)

    private fun stems(dir: File, x: String, y: String, p: SeguePlan, pre: Double, post: Double, cache: MutableMap<String, FloatArray>, tmp: File): Stems {
        val length = p.span.lengthUs / 1e6
        val frames = ((pre + length + post) * RATE).toInt()
        val pcmA = cache.getOrPut(x) { Render.stereo(File(dir, "$x.f32")) }
        val pcmB = cache.getOrPut(y) { Render.stereo(File(dir, "$y.f32")) }
        val dryA = slice(pcmA, ((p.outgoing.anchorUs / 1e6 - pre) * RATE).toInt(), frames)
        val a = dryA.copyOf()
        SegueDeck(p, LaneSide.OUTGOING, RATE, 2).process(a, frames, p.outgoing.anchorUs - (pre * 1e6).toLong())
        val rate = p.incoming.ratePpm.toDouble() / SegueFormat.RATE_NOMINAL
        val startUs = p.incoming.anchorUs - (pre * rate * 1e6).toLong()
        val sourceFrames = (frames * rate).toInt() + RATE
        val dryB = slice(pcmB, Math.floorDiv(startUs * RATE, 1_000_000L).toInt(), sourceFrames)
        val b = dryB.copyOf()
        SegueDeck(p, LaneSide.INCOMING, RATE, 2).process(b, sourceFrames, startUs)
        return Stems(a, fit(stretch(b, rate, tmp), frames), dryA, fit(stretch(dryB, rate, tmp), frames), (pre * RATE).toInt())
    }

    private fun slice(pcm: FloatArray, firstFrame: Int, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val f = firstFrame + i
            if (f < 0 || 2 * f + 1 >= pcm.size) continue
            out[2 * i] = pcm[2 * f]; out[2 * i + 1] = pcm[2 * f + 1]
        }
        return out
    }

    private fun fit(x: FloatArray, frames: Int) = if (x.size == frames * 2) x else x.copyOf(frames * 2)

    /** The time stretch the phone's AudioTrack does, pitch kept: atempo's the nearest on a desktop. */
    private fun stretch(x: FloatArray, rate: Double, tmp: File): FloatArray {
        if (abs(rate - 1) < 1e-9) return x
        val input = File(tmp, "segue.in.f32"); val output = File(tmp, "segue.out.f32")
        val bytes = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(x)
        RandomAccessFile(input, "rw").use { it.setLength(0); it.write(bytes.array()) }
        val cmd = listOf("ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", "$RATE", "-ac", "2", "-i", input.path,
            "-af", String.format(L, "atempo=%.6f", rate), "-f", "f32le", "-ar", "$RATE", "-ac", "2", output.path)
        check(ProcessBuilder(cmd).inheritIO().start().waitFor() == 0) { "ffmpeg failed" }
        return Render.stereo(output)
    }

    private fun mix(s: Stems) = FloatArray(s.a.size) { s.a[it] + s.b[it] }

    private fun envelope(stereo: FloatArray, fromFrame: Int, toFrame: Int): FloatArray {
        val n = (toFrame - fromFrame) / 2
        val mid = FloatArray(n) { i -> val f = 2 * (fromFrame + 2 * i); (stereo[f] + stereo[f + 1] + stereo[f + 2] + stereo[f + 3]) / 4 }
        return BeatTracker.envelope(Features.of(mid, null))
    }

    // ── Measuring ──

    /** [limit] pairs that got a plan, from those whose level starts with [only]. */
    private fun measure(dir: File, fold: String?, limit: Int, only: String?) {
        val names = names(dir, fold)
        val songs = analyses(dir, names)
        val tmp = File(dir, ".segue-tmp").apply { mkdirs() }
        val cache = object : LinkedHashMap<String, FloatArray>(16, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, FloatArray>?) = size > 16 }
        val results = ArrayList<Pair<SegueLevel, SegueMetrics.Result>>()
        println("pair\tlevel\tseconds\tpeak\tclipped\tjump_db\tclick\tgap_ms\tresidual_ms\tmeasured_ms")
        for ((x, y) in pairs(names, Int.MAX_VALUE).sortedBy { (a, b) -> SegueCodec.hex("$a|$b".toByteArray()) }) {
            if (results.size >= limit) break
            val d = SeguePolicy.decide(request(songs.getValue(x), songs.getValue(y)))
            val p = d.plan ?: continue
            if (only != null && !d.level.name.startsWith(only)) continue
            val pre = 4.0
            val s = stems(dir, x, y, p, pre, 4.0, cache, tmp)
            val win = (p.span.lengthUs / 1e6 * RATE).toInt()
            val locked = p.pulse.state == PulseState.LOCKED
            val lag = if (locked) SegueMetrics.lagMs(envelope(s.dryA, s.preFrames, s.preFrames + win), envelope(s.dryB, s.preFrames, s.preFrames + win), Features.FPS) else null
            val r = SegueMetrics.of(mix(s), s.preFrames, win, RATE, p.pulse.residualUs?.let { it / 1000.0 }, lag)
            results += d.level to r
            println(String.format(L, "%s,%s\t%s\t%.3f\t%.3f\t%d\t%.2f\t%.1f\t%.1f\t%s\t%s", x, y, d.level, win.toDouble() / RATE, r.peak, r.clipped, r.jumpDb, r.click, r.gapMs,
                r.residualMs?.let { String.format(L, "%.1f", it) } ?: "-", r.measuredMs?.let { String.format(L, "%.1f", it) } ?: "-"))
        }
        tmp.deleteRecursively()
        fun line(label: String, rs: List<SegueMetrics.Result>) {
            if (rs.isEmpty()) return
            fun q(f: (SegueMetrics.Result) -> Double?, v: Double) = at(rs.mapNotNull(f).sorted(), v)
            println(String.format(L, "summary\t%s\tn=%d\tpeak_p95=%.2f\twith_clipping=%d\tjump_median=%.2f\tjump_p95=%.2f\tclick_p95=%.2f\tgap_p95_ms=%.1f\tresidual_median_ms=%.1f\tmeasured_median_ms=%.1f\tmeasured_p95_ms=%.1f",
                label, rs.size, q({ it.peak }, 0.95), rs.count { it.clipped > 0 }, q({ it.jumpDb }, 0.5), q({ it.jumpDb }, 0.95), q({ it.click }, 0.95), q({ it.gapMs }, 0.95),
                q({ it.residualMs }, 0.5), q({ it.measuredMs?.let(::abs) }, 0.5), q({ it.measuredMs?.let(::abs) }, 0.95)))
        }
        line("all", results.map { it.second })
        for (l in SegueLevel.values()) line(l.name, results.filter { it.first == l }.map { it.second })
    }

    private fun render(dir: File, asked: List<String>) {
        val tmp = File(dir, ".segue-tmp").apply { mkdirs() }
        val cache = HashMap<String, FloatArray>()
        for (pair in asked) {
            val (x, y) = pair.split(',')
            val d = SeguePolicy.decide(request(BenchCache.analysis(dir, x).analysis, BenchCache.analysis(dir, y).analysis))
            val p = d.plan
            println("$x,$y ${d.level} ${d.fingerprint ?: ""} | ${d.why}")
            if (p == null) continue
            Render.writeWav(File(dir, "mix-$x-$y.wav"), mix(stems(dir, x, y, p, 12.0, 15.0, cache, tmp)))
        }
        tmp.deleteRecursively()
    }

    // ── Stability: each parameter ±STABILITY_RANGE ──

    private fun stability(dir: File, fold: String?, limit: Int) {
        val names = names(dir, fold)
        val songs = analyses(dir, names)
        val sample = pairs(names, limit)
        val base = sample.map { (x, y) -> SeguePolicy.decide(request(songs.getValue(x), songs.getValue(y))) }
        println("parameter\tfactor\tlevel_changed\tplan_changed\tpairs")
        val inert = ArrayList<String>()
        for ((name, tuned) in SegueCorpus.variations(tuning)) {
            var levels = 0; var plans = 0
            for (i in sample.indices) {
                val d = SeguePolicy.decide(request(songs.getValue(sample[i].first), songs.getValue(sample[i].second), tuned))
                if (d.level != base[i].level) levels++
                if (d.fingerprint != base[i].fingerprint) plans++
            }
            println("$name\t$levels\t$plans\t${sample.size}")
            if (plans == 0) inert += name
        }
        println("summary\tinert_variations=${inert.size}\t${inert.joinToString(",")}")
    }
}

/** What is measured on a render: its integrity, then its timing against the plan's own expectation. */
object SegueMetrics {
    class Result(val peak: Double, val clipped: Int, val jumpDb: Double, val click: Double, val gapMs: Double, val residualMs: Double?, val measuredMs: Double?)

    fun of(mix: FloatArray, preFrames: Int, windowFrames: Int, rate: Int, residualMs: Double?, measuredMs: Double?): Result {
        var peak = 0.0; var clipped = 0
        for (v in mix) { val m = abs(v).toDouble(); if (m > peak) peak = m; if (m > 1.0) clipped++ }
        val w = (0.4 * rate).toInt()
        val before = rms(mix, preFrames - w, preFrames); val after = rms(mix, preFrames + windowFrames, preFrames + windowFrames + w)
        return Result(peak, clipped, abs(db(after) - db(before)), click(mix, preFrames, windowFrames, rate), longestGapMs(mix, preFrames, preFrames + windowFrames + w, rate), residualMs, measuredMs)
    }

    private fun db(x: Double) = 20 * log10(max(x, 1e-6))

    private fun rms(stereo: FloatArray, from: Int, to: Int): Double {
        val a = max(0, from); val b = min(stereo.size / 2, to)
        if (b <= a) return 0.0
        var s = 0.0
        for (i in 2 * a until 2 * b) s += stereo[i] * stereo[i]
        return sqrt(s / (2 * (b - a)))
    }

    /** The largest sample step at the window's edges over the song's own largest step just before them. */
    fun click(stereo: FloatArray, preFrames: Int, windowFrames: Int, rate: Int): Double {
        fun step(from: Int, to: Int): Double {
            var m = 0.0
            for (f in max(1, from) until min(stereo.size / 2, to)) for (c in 0..1) m = max(m, abs(stereo[2 * f + c] - stereo[2 * (f - 1) + c]).toDouble())
            return m
        }
        val edge = (0.01 * rate).toInt()
        val song = step(preFrames - rate, preFrames - edge)
        val edges = max(step(preFrames - edge, preFrames + edge), step(preFrames + windowFrames - edge, preFrames + windowFrames + edge))
        return if (song > 0) edges / song else if (edges > 0) Double.POSITIVE_INFINITY else 0.0
    }

    /** The longest stretch under −60 dBFS: a mix that falls silent is broken, however good its score. */
    fun longestGapMs(stereo: FloatArray, from: Int, to: Int, rate: Int): Double {
        var run = 0; var top = 0
        for (f in max(0, from) until min(stereo.size / 2, to)) {
            if (abs(stereo[2 * f]) < 1e-3f && abs(stereo[2 * f + 1]) < 1e-3f) { run++; top = max(top, run) } else run = 0
        }
        return top * 1000.0 / rate
    }

    /** The lag of [b]'s onsets on [a]'s, in ms, within ±100 ms; null without onsets to compare. */
    fun lagMs(a: FloatArray, b: FloatArray, fps: Double): Double? {
        val n = min(a.size, b.size)
        val reach = (0.1 * fps).toInt().coerceAtLeast(1)
        if (n < 4 * reach) return null
        val scores = DoubleArray(2 * reach + 1)
        for (k in -reach..reach) {
            var s = 0.0
            for (i in max(0, -k) until min(n, n - k)) s += a[i] * b[i + k]
            scores[k + reach] = s
        }
        val best = scores.indices.maxBy { scores[it] }
        if (scores[best] <= 0) return null
        val shift = if (best in 1 until scores.size - 1) {
            val l = scores[best - 1]; val c = scores[best]; val r = scores[best + 1]
            val curve = l - 2 * c + r
            if (curve < 0) 0.5 * (l - r) / curve else 0.0
        } else 0.0
        return (best - reach + shift) * 1000.0 / fps
    }
}

/** The corpus's split and the variations of the parameters, apart from any audio so they can be tested. */
object SegueCorpus {
    class Row(val id: String, val title: String, val artist: String, val album: String)

    /** `id|title - artist.ext` or `id|title - artist - album.ext`. */
    fun read(file: File): List<Row> = file.readLines().filter { it.contains('|') }.map { parse(it) }

    fun parse(line: String): Row {
        val (id, name) = line.split('|', limit = 2)
        val parts = name.substringBeforeLast('.').split(" - ").map { it.trim() }
        return Row(id, parts.first(), parts.getOrElse(1) { "" }.ifEmpty { "unknown:$id" }, parts.getOrElse(2) { "" })
    }

    /**
     * Whole artists and albums go to one fold, so a tuned value is never tested on the songs it was
     * tuned on: artists sharing an album are one group, and a stable hash of the group picks its fold.
     */
    fun split(rows: List<Row>): Map<String, String> {
        val parent = HashMap<String, String>()
        fun find(x: String): String { var r = x; while (parent.getOrPut(r) { r } != r) r = parent.getValue(r); return r }
        for (r in rows) {
            val artist = "artist:" + r.artist.lowercase()
            find(artist)
            if (r.album.isNotEmpty()) parent[find("album:" + r.album.lowercase())] = find(artist)
        }
        return rows.associate { r -> r.id to fold(find("artist:" + r.artist.lowercase())) }
    }

    private fun fold(group: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(group.toByteArray())
        val x = ((d[0].toInt() and 0xff) * 256 + (d[1].toInt() and 0xff)) / 65536.0
        return when {
            x < SegueTuning.SPLIT_TUNING_SHARE -> "tuning"
            x < SegueTuning.SPLIT_TUNING_SHARE + SegueTuning.SPLIT_VALIDATION_SHARE -> "validation"
            else -> "test"
        }
    }

    /** [base] with one numeric parameter set to [value]. */
    fun withValue(base: SegueTuning, field: String, value: Double): SegueTuning {
        val fields = SegueTuning::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        val ctor = SegueTuning::class.java.declaredConstructors.first { it.parameterCount == fields.size }
        val i = fields.indexOfFirst { it.name == field }
        require(i >= 0) { "no parameter $field" }
        val values = fields.map { it.get(base) }.toMutableList()
        values[i] = when (values[i]) { is Long -> Math.round(value); is Int -> Math.round(value).toInt(); is Double -> value; else -> throw IllegalArgumentException("$field is not a number") }
        return ctor.newInstance(*values.toTypedArray()) as SegueTuning
    }

    /** Each numeric parameter at ±[SegueTuning.STABILITY_RANGE], named `field×factor`, the others unchanged. */
    fun variations(base: SegueTuning): List<Pair<String, SegueTuning>> {
        val fields = SegueTuning::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        val ctor = SegueTuning::class.java.declaredConstructors.first { it.parameterCount == fields.size }
        val values = fields.map { it.get(base) }
        val out = ArrayList<Pair<String, SegueTuning>>()
        for (i in fields.indices) {
            val v = values[i]
            if (v !is Number) continue
            for (f in listOf(1 - SegueTuning.STABILITY_RANGE, 1 + SegueTuning.STABILITY_RANGE)) {
                val scaled: Any = when (v) {
                    is Long -> Math.round(v * f)
                    is Int -> Math.round(v * f).toInt()
                    else -> v.toDouble() * f
                }
                if (scaled == v) continue
                out += String.format(Locale.ROOT, "%s\t%.1f", fields[i].name, f) to (ctor.newInstance(*values.toMutableList().also { it[i] = scaled }.toTypedArray()) as SegueTuning)
            }
        }
        return out
    }
}
