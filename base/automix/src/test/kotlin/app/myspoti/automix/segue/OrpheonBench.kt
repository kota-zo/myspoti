/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import java.io.File
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs

/**
 * `segue <dir> orpheon [with=orpheon.field:value,...]`: runs the Orpheon planner on pairs of
 * [SongMap] and compares its plans with reference plans. The bench only compares; it decides nothing.
 *
 * `<dir>/maps/<id>.json` holds one [SongMap] (the keys are its field names; `tempoBpm` and `loudness`
 * are `{start,main,end}`, `key` is the same with `{tonic,mode}` values, `vocals` a list of
 * `{from,to,strength,kind}`, `fadeIn` and `fadeOut` `{from,to}`; a missing key is unknown).
 * `<dir>/plans.jsonl` has one line per pair:
 * `{"a":id,"b":id,"family":"locked|smooth|silence|fallback","style":"...","duration":s,"out_start":s,"out_end":s,"in_start":s,"in_end":s,"rate_out":r,"rate_in":r}`.
 *
 * Speeds are compared on each side's furthest speed from 1: a ramp's end on the outgoing side, its
 * start on the incoming one.
 */
object OrpheonBench {
    private val L = Locale.ROOT
    private val EXPONENT = Regex("-?[0-9]+(\\.[0-9]+)?[eE][-+]?[0-9]+")

    /**
     * Our analyser's map of each `<name>.f32` in [dir], written as `<out>/<name>.json` in the layout [run]
     * reads: the same planner can then be run on our maps against another analyser's plans.
     */
    fun export(dir: File, out: File) {
        out.mkdirs()
        val names = dir.listFiles { f -> f.name.endsWith(".f32") && !f.name.contains(".tmp.") && !f.name.startsWith("mix-") }
            .orEmpty().map { it.nameWithoutExtension }.sorted()
        for (n in names) {
            val r = app.myspoti.automix.BenchCache.analysis(dir, n)
            val m = SongMaps.from(r.analysis)
            File(out, "$n.json").writeText(json(m))
            println(String.format(L, "songmap\t%s\t%s\t%.0f ms\t%.2f BPM\t%d bars\t%s", n, if (r.cached) "cached" else "analysed",
                r.elapsedMs, m.tempoBpm.main, m.downbeatsSec.size, m.genre.wire))
        }
    }

    /** The model's own beats and downbeats (peaks with their logits) of each `<name>.f32`, before the analyser picks a phase. */
    fun rawGrid(dir: File, out: File) {
        out.mkdirs()
        val bt = app.myspoti.automix.model ?: error("no model (AUTOMIX_MODEL)")
        val names = dir.listFiles { f -> f.name.endsWith(".f32") && !f.name.contains(".tmp.") && !f.name.startsWith("mix-") }
            .orEmpty().map { it.nameWithoutExtension }.sorted()
        for (n in names) {
            val target = File(out, "$n.json")
            if (target.isFile) continue
            val (mid, _) = app.myspoti.automix.loadPcm(File(dir, "$n.f32"))
            val act = bt.run(mid, 0, mid.size, threads = 4)
            fun peaks(l: FloatArray) = app.myspoti.automix.BeatThis.peaks(act, l).joinToString(",", "[", "]") { (t, v) -> String.format(L, "[%.4f,%.3f]", t, v) }
            target.writeText("{\"beats\":${peaks(act.beat)},\"downbeats\":${peaks(act.downbeat)}}")
            println("raw\t$n")
        }
    }

    private fun json(m: SongMap): String {
        fun n(x: Double) = if (x.isFinite()) String.format(L, "%.4f", x) else "null"
        fun arr(a: DoubleArray) = a.joinToString(",", "[", "]") { n(it) }
        fun thirds(t: SongThirds<Double>) = "{\"start\":${n(t.start)},\"main\":${n(t.main)},\"end\":${n(t.end)}}"
        fun key(k: SongKey?) = k?.let { "{\"tonic\":${it.tonic},\"mode\":\"${if (it.minor) "minor" else "major"}\"}" } ?: "null"
        fun range(r: SongRange?) = r?.let { "{\"from\":${n(it.fromSec)},\"to\":${n(it.toSec)}}" } ?: "null"
        fun opt(x: Double?) = x?.let(::n) ?: "null"
        val c = m.confidence
        val vocals = m.vocals.joinToString(",", "[", "]") { v ->
            "{\"from\":${n(v.fromSec)},\"to\":${n(v.toSec)},\"strength\":${n(v.strength)}" + (v.kind?.let { ",\"kind\":\"${it.wire}\"" } ?: "") + "}"
        }
        return "{\"durationSec\":${n(m.durationSec)},\"beatsSec\":${arr(m.beatsSec)},\"downbeatsSec\":${arr(m.downbeatsSec)}," +
            "\"tempoBpm\":${thirds(m.tempoBpm)},\"beatsPerBar\":${m.beatsPerBar},\"sections\":${arr(m.sections)}," +
            "\"loudnessStepSec\":${n(m.loudnessStepSec)},\"loudnessCurve\":${m.loudnessCurve.joinToString(",", "[", "]") { n(it.toDouble()) }}," +
            "\"loudness\":${thirds(m.loudness)},\"vocals\":$vocals," +
            "\"key\":{\"start\":${key(m.key.start)},\"main\":${key(m.key.main)},\"end\":${key(m.key.end)}}," +
            "\"fadeIn\":${range(m.fadeIn)},\"fadeOut\":${range(m.fadeOut)},\"genre\":\"${m.genre.wire}\"," +
            "\"energy\":${opt(m.energy)},\"danceability\":${opt(m.danceability)},\"acousticness\":${opt(m.acousticness)},\"melodicness\":${opt(m.melodicness)}," +
            "\"confidence\":{\"grid\":${n(c.grid)},\"sections\":${n(c.sections)},\"loudness\":${n(c.loudness)},\"vocals\":${n(c.vocals)}," +
            "\"key\":${n(c.key)},\"fades\":${n(c.fades)},\"genre\":${n(c.genre)}}}"
    }

    class Reference(val a: String, val b: String, val family: String, val style: String, val duration: Double,
                    val outStart: Double, val inStart: Double, val rateOut: Double, val rateIn: Double)

    class Row(val ref: Reference, val family: String, val style: String, val outSec: Double?, val outBars: Double?,
              val inSec: Double?, val inBars: Double?, val durationSec: Double?, val rateOut: Double?, val rateIn: Double?, val why: String = "", val outStart: Double? = null, val inStart: Double? = null, val length: Double? = null) {
        val familyAgrees get() = family == ref.family
        val withinBar get() = outBars != null && inBars != null && outBars <= 1.0 && inBars <= 1.0
    }

    class Report(val rows: List<Row>, val missing: Int) {
        private fun mean(of: (Row) -> Double?): Double {
            val xs = rows.mapNotNull(of)
            return if (xs.isEmpty()) Double.NaN else xs.average()
        }
        val familyAgreement get() = if (rows.isEmpty()) 0.0 else rows.count { it.familyAgrees }.toDouble() / rows.size
        val withinBarShare get() = if (rows.isEmpty()) 0.0 else rows.count { it.withinBar }.toDouble() / rows.size
        fun summary(): String = String.format(L,
            "total\tpairs=%d\tmissing=%d\tfamily=%.3f\tout_s=%.3f\tout_bars=%.3f\tin_s=%.3f\tin_bars=%.3f\tduration_s=%.3f\trate_out=%.4f\trate_in=%.4f\twithin_1_bar=%.3f",
            rows.size, missing, familyAgreement, mean { it.outSec }, mean { it.outBars }, mean { it.inSec }, mean { it.inBars },
            mean { it.durationSec }, mean { it.rateOut }, mean { it.rateIn }, withinBarShare)
    }

    fun run(dir: File, tuning: SegueTuning) {
        val report = measure(dir, tuning)
        println("a\tb\tref\tours\tstyle\tout_s\tout_bars\tin_s\tin_bars\tduration_s\trate_out\trate_in\twithin_1_bar\tattempts\tout\tin\tlength")
        for (r in report.rows) println(listOf(r.ref.a, r.ref.b, r.ref.family, r.family, r.style, fmt(r.outSec), fmt(r.outBars), fmt(r.inSec), fmt(r.inBars),
            fmt(r.durationSec), fmt(r.rateOut), fmt(r.rateIn), r.withinBar, r.why, fmt(r.outStart), fmt(r.inStart), fmt(r.length)).joinToString("\t"))
        println(report.summary())
    }

    private fun fmt(x: Double?) = if (x == null) "-" else String.format(L, "%.3f", x)

    internal fun measure(dir: File, tuning: SegueTuning): Report {
        val maps = HashMap<String, SongMap?>()
        fun map(id: String) = maps.getOrPut(id) { File(dir, "maps/$id.json").takeIf { it.exists() }?.let { SongMapJson.parse(it.readText(), id) } }
        val rows = ArrayList<Row>()
        var missing = 0
        File(dir, "plans.jsonl").forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val ref = reference(line)
            val a = map(ref.a); val b = map(ref.b)
            if (a == null || b == null) { missing++; return@forEachLine }
            rows += compare(ref, a, b, OrpheonPolicy.plan(a, b, OrpheonRequest(tuning = tuning,
                minBars = System.getenv("ORPHEON_MIN_BARS")?.toInt(), fadeSec = System.getenv("ORPHEON_FADE_SEC")?.toDouble()),
                System.getenv("ORPHEON_ONLY")))
        }
        return Report(rows, missing)
    }

    private fun compare(ref: Reference, a: SongMap, b: SongMap, o: OrpheonOutcome): Row {
        val p = o.decision.plan ?: return Row(ref, "none", "-", null, null, null, null, null, null, null)
        val out = p.outgoing.anchorUs / 1e6; val inn = p.incoming.anchorUs / 1e6
        val nominal = SegueFormat.RATE_NOMINAL.toDouble()
        val rateOut = (p.outgoing.ramp?.endPpm ?: p.outgoing.ratePpm) / nominal
        val rateIn = (p.incoming.ramp?.startPpm ?: p.incoming.ratePpm) / nominal
        val why = (o.decision.attempts.map { "${it.level}:${it.cause}:${it.detail}" } + String.format(L, "score %.3f of %d", o.decision.score ?: 0.0, o.survivors)).joinToString("; ")
        return Row(ref, o.family?.wire ?: "none", o.style?.id ?: "-",
            abs(out - ref.outStart), abs(out - ref.outStart) / a.barSecAt(ref.outStart),
            abs(inn - ref.inStart), abs(inn - ref.inStart) / b.barSecAt(ref.inStart),
            abs(p.span.lengthUs / 1e6 - ref.duration), abs(rateOut - ref.rateOut), abs(rateIn - ref.rateIn), why, out, inn, p.span.lengthUs / 1e6)
    }

    private fun reference(line: String): Reference {
        val m = SongMapJson.read(line)
        fun num(k: String) = (m[k] as? BigDecimal)?.toDouble() ?: 0.0
        fun rate(k: String) = (m[k] as? BigDecimal)?.toDouble() ?: 1.0
        fun text(k: String) = m[k] as? String ?: ""
        return Reference(text("a"), text("b"), text("family"), text("style"), num("duration"), num("out_start"), num("in_start"), rate("rate_out"), rate("rate_in"))
    }

    /** The strict plan reader refuses exponents; analysers written elsewhere print them. */
    internal fun plainNumbers(text: String): String = EXPONENT.replace(text) { BigDecimal(it.value).toPlainString() }
}

/** A [SongMap] from JSON: for the bench only, nothing shipped reads this. */
internal object SongMapJson {
    fun read(text: String): Map<*, *> = SegueJson.read(OrpheonBench.plainNumbers(text)) as? Map<*, *> ?: throw SegueFormatException("not an object")

    private fun number(v: Any?): Double? = (v as? BigDecimal)?.toDouble()

    private fun doubles(v: Any?): DoubleArray = (v as? List<*>)?.mapNotNull(::number)?.toDoubleArray() ?: DoubleArray(0)

    private fun thirds(v: Any?, default: Double): SongThirds<Double> {
        val m = v as? Map<*, *> ?: return SongThirds(default, default, default)
        val main = number(m["main"]) ?: default
        return SongThirds(number(m["start"]) ?: main, main, number(m["end"]) ?: main)
    }

    private fun songKey(v: Any?): SongKey? {
        val m = v as? Map<*, *> ?: return null
        val tonic = number(m["tonic"]) ?: return null
        return SongKey(tonic.toInt(), m["mode"] == "minor")
    }

    private fun range(v: Any?): SongRange? {
        val m = v as? Map<*, *> ?: return null
        return SongRange(number(m["from"]) ?: return null, number(m["to"]) ?: return null)
    }

    fun parse(text: String, id: String): SongMap {
        val m = read(text)
        val bpm = thirds(m["tempoBpm"], 120.0)
        val key = m["key"] as? Map<*, *>
        val conf = m["confidence"] as? Map<*, *>
        fun trust(k: String) = number(conf?.get(k)) ?: 1.0
        return SongMap(
            durationSec = number(m["durationSec"]) ?: throw SegueFormatException("$id: no durationSec"),
            beatsSec = doubles(m["beatsSec"]),
            downbeatsSec = doubles(m["downbeatsSec"]),
            tempoBpm = bpm,
            beatsPerBar = number(m["beatsPerBar"])?.toInt() ?: 4,
            sections = doubles(m["sections"]),
            loudnessStepSec = number(m["loudnessStepSec"]) ?: 0.5,
            loudnessCurve = doubles(m["loudnessCurve"]).let { c -> FloatArray(c.size) { c[it].toFloat() } },
            loudness = thirds(m["loudness"], -14.0),
            vocals = (m["vocals"] as? List<*>).orEmpty().mapNotNull { v ->
                val x = v as? Map<*, *> ?: return@mapNotNull null
                SongVocal(number(x["from"]) ?: return@mapNotNull null, number(x["to"]) ?: return@mapNotNull null, number(x["strength"]) ?: 1.0, VocalKind.parse(x["kind"] as? String))
            },
            key = SongThirds(songKey(key?.get("start")), songKey(key?.get("main")), songKey(key?.get("end"))),
            fadeIn = range(m["fadeIn"]),
            fadeOut = range(m["fadeOut"]),
            genre = GenreFamily.parse(m["genre"] as? String),
            energy = number(m["energy"]),
            danceability = number(m["danceability"]),
            acousticness = number(m["acousticness"]),
            melodicness = number(m["melodicness"]),
            confidence = SongConfidence(trust("grid"), trust("sections"), trust("loudness"), trust("vocals"), trust("key"), trust("fades"), trust("genre")),
            audioKey = id,
            gridKey = id,
        )
    }
}
