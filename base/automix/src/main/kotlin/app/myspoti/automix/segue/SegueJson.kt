/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import java.math.BigDecimal
import java.security.MessageDigest

class SegueFormatException(message: String) : Exception(message)

/** A strict JSON reader and a canonical writer: sorted keys, no spaces, one spelling per number. */
internal object SegueJson {
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(v: Any?, o: StringBuilder) {
        when (v) {
            null -> o.append("null")
            is Boolean -> o.append(v)
            is Int, is Long -> o.append(v)
            is BigDecimal -> o.append(canonical(v))
            is String -> string(v, o)
            is List<*> -> { o.append('['); v.forEachIndexed { i, x -> if (i > 0) o.append(','); write(x, o) }; o.append(']') }
            is Map<*, *> -> {
                o.append('{')
                v.entries.map { it.key as String to it.value }.sortedBy { it.first }.forEachIndexed { i, (k, x) ->
                    if (i > 0) o.append(','); string(k, o); o.append(':'); write(x, o)
                }
                o.append('}')
            }
            else -> throw IllegalArgumentException("not JSON: ${v.javaClass}")
        }
    }

    fun canonical(d: BigDecimal): String = if (d.signum() == 0) "0" else d.stripTrailingZeros().toPlainString()

    private fun string(s: String, o: StringBuilder) {
        o.append('"')
        for (c in s) when {
            c == '"' -> o.append("\\\"")
            c == '\\' -> o.append("\\\\")
            c == '\n' -> o.append("\\n")
            c == '\r' -> o.append("\\r")
            c == '\t' -> o.append("\\t")
            c < ' ' -> o.append(String.format("\\u%04x", c.code))
            else -> o.append(c)
        }
        o.append('"')
    }

    /** Objects become maps, numbers BigDecimal. Duplicate keys, exponents and trailing text are refused. */
    fun read(text: String): Any? = Reader(text).document()

    private class Reader(private val s: String) {
        private var i = 0
        fun document(): Any? { val v = value(0); space(); if (i != s.length) fail("trailing text"); return v }
        private fun fail(why: String): Nothing = throw SegueFormatException("JSON at $i: $why")
        private fun space() { while (i < s.length && s[i] in " \t\r\n") i++ }
        private fun value(depth: Int): Any? {
            if (depth > 16) fail("too deep")
            space()
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }
        private fun word(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) fail("bad token"); i += w.length; return v }
        private fun obj(depth: Int): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); space()
            if (peek() == '}') { i++; return out }
            while (true) {
                space(); if (peek() != '"') fail("key expected")
                val k = str(); space(); if (peek() != ':') fail("colon expected"); i++
                if (out.containsKey(k)) fail("duplicate key $k")
                out[k] = value(depth + 1); space()
                when (peek()) { ',' -> i++; '}' -> { i++; return out } else -> fail("comma expected") }
            }
        }
        private fun arr(depth: Int): List<Any?> {
            i++; val out = ArrayList<Any?>(); space()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(value(depth + 1)); space()
                when (peek()) { ',' -> i++; ']' -> { i++; return out } else -> fail("comma expected") }
            }
        }
        private fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")
        private fun str(): String {
            i++; val o = StringBuilder()
            while (true) {
                val c = peek(); i++
                when {
                    c == '"' -> return o.toString()
                    c < ' ' -> fail("control character")
                    c != '\\' -> o.append(c)
                    else -> {
                        val e = peek(); i++
                        when (e) {
                            '"' -> o.append('"'); '\\' -> o.append('\\'); '/' -> o.append('/')
                            'n' -> o.append('\n'); 'r' -> o.append('\r'); 't' -> o.append('\t')
                            'b' -> o.append('\b'); 'f' -> o.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad escape")
                                o.append((s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad escape")).toChar()); i += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                }
            }
        }
        private fun num(): BigDecimal {
            val from = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            val text = s.substring(from, i)
            if (!text.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))) fail("bad number")
            return BigDecimal(text)
        }
    }
}

/** A plan to and from its canonical text, and its fingerprint. */
object SegueCodec {
    fun encode(plan: SeguePlan): String = SegueJson.write(tree(plan, withOrigin = true))

    /** SHA-256 of the canonical JSON without `origin`: two engines writing the same plan agree on it. */
    fun fingerprint(plan: SeguePlan): String = hex(SegueJson.write(tree(plan, withOrigin = false)).toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tree(p: SeguePlan, withOrigin: Boolean): Map<String, Any?> = buildMap {
        put("schema", p.schema)
        if (withOrigin) put("origin", mapOf("engine" to p.origin.engine, "corpus" to p.origin.corpus))
        put("outgoing", side(p.outgoing)); put("incoming", side(p.incoming))
        put("span", mapOf("lengthUs" to p.span.lengthUs, "musicalUnits" to p.span.musicalUnits, "unitKind" to p.span.unitKind.wire, "clock" to p.span.clock.wire))
        put("pulse", mapOf("state" to p.pulse.state.wire, "tempoMicroPerMinute" to p.pulse.tempoMicroPerMinute, "residualUs" to p.pulse.residualUs))
        put("lanes", p.lanes.map { l ->
            mapOf("side" to l.side.wire, "target" to l.target.wire, "unit" to l.unit.wire,
                "knots" to l.knots.map { k -> listOf(k.t, k.valueMilli?.let { BigDecimal.valueOf(it, 3) }, k.form.wire) })
        })
        put("gestures", p.gestures.map { mapOf("kind" to it.kind.wire, "atUs" to it.atUs, "units" to it.units) })
        p.echoUs?.let { put("echoUs", it) }
    }

    private fun side(s: SeguePlan.Side) = mapOf("audioKey" to s.audioKey, "gridKey" to s.gridKey, "anchorUs" to s.anchorUs,
        "ratePpm" to s.ratePpm, "listenerRate" to s.listenerRate)

    /**
     * Another major version is refused whole, whatever its fields. Within this major an unknown
     * field is ignored, and so takes no part in the fingerprint.
     */
    fun decode(text: String): SeguePlan {
        val root = SegueJson.read(text) as? Map<*, *> ?: throw SegueFormatException("not an object")
        val schema = root["schema"] as? String ?: throw SegueFormatException("no schema")
        if (!schema.startsWith(SegueFormat.SCHEMA_PREFIX)) throw SegueFormatException("not a segue plan: $schema")
        val major = schema.removePrefix(SegueFormat.SCHEMA_PREFIX)
        if (!major.matches(Regex("[1-9][0-9]*"))) throw SegueFormatException("bad schema: $schema")
        if (major != SegueFormat.MAJOR.toString()) throw SegueFormatException("unsupported major version: $schema")
        val origin = obj(root, "origin")
        val span = obj(root, "span")
        val pulse = obj(root, "pulse")
        return SeguePlan(
            SeguePlan.Origin(text(origin, "engine"), text(origin, "corpus")),
            readSide(obj(root, "outgoing")), readSide(obj(root, "incoming")),
            SeguePlan.Span(long(span, "lengthUs"), longOrNull(span, "musicalUnits")?.let(::toInt),
                enum(span, "unitKind", UnitKind.values()) { it.wire }, enum(span, "clock", Clock.values()) { it.wire }),
            SeguePlan.Pulse(enum(pulse, "state", PulseState.values()) { it.wire }, longOrNull(pulse, "tempoMicroPerMinute"), longOrNull(pulse, "residualUs")),
            list(root, "lanes").map(::readLane),
            list(root, "gestures").map { g ->
                val m = g as? Map<*, *> ?: throw SegueFormatException("gesture is not an object")
                SeguePlan.Gesture(enum(m, "kind", GestureKind.values()) { it.wire }, long(m, "atUs"), toInt(long(m, "units")))
            },
            schema,
            longOrNull(root, "echoUs"),
        )
    }

    private fun readSide(m: Map<*, *>) = SeguePlan.Side(text(m, "audioKey"), m["gridKey"]?.let { it as? String ?: throw SegueFormatException("gridKey") },
        long(m, "anchorUs"), long(m, "ratePpm"), long(m, "listenerRate"))

    private fun readLane(any: Any?): SeguePlan.Lane {
        val m = any as? Map<*, *> ?: throw SegueFormatException("lane is not an object")
        val knots = list(m, "knots").map { k ->
            val a = k as? List<*> ?: throw SegueFormatException("knot is not an array")
            if (a.size != 3) throw SegueFormatException("knot needs [t, value, form]")
            val t = whole(a[0], "t")
            val form = (a[2] as? String)?.let(KnotForm::parse) ?: throw SegueFormatException("bad form ${a[2]}")
            SeguePlan.Knot(toInt(t), a[1]?.let(::milli), form)
        }
        return SeguePlan.Lane(enum(m, "side", LaneSide.values()) { it.wire }, enum(m, "target", Target.values()) { it.wire },
            enum(m, "unit", SegueUnit.values()) { it.wire }, knots)
    }

    private fun milli(v: Any?): Long {
        val d = v as? BigDecimal ?: throw SegueFormatException("knot value is not a number")
        return try { d.movePointRight(3).longValueExact() } catch (e: ArithmeticException) { throw SegueFormatException("more than 3 decimals: $d") }
    }
    private fun obj(m: Map<*, *>, k: String) = m[k] as? Map<*, *> ?: throw SegueFormatException("$k is not an object")
    private fun list(m: Map<*, *>, k: String) = m[k] as? List<*> ?: throw SegueFormatException("$k is not an array")
    private fun text(m: Map<*, *>, k: String) = m[k] as? String ?: throw SegueFormatException("$k is not a string")
    private fun long(m: Map<*, *>, k: String) = whole(m[k], k)
    private fun longOrNull(m: Map<*, *>, k: String) = m[k]?.let { whole(it, k) }
    private fun whole(v: Any?, k: String): Long {
        val d = v as? BigDecimal ?: throw SegueFormatException("$k is not a number")
        return try { d.longValueExact() } catch (e: ArithmeticException) { throw SegueFormatException("$k is not a whole number: $d") }
    }
    private fun toInt(v: Long): Int = if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else throw SegueFormatException("out of range: $v")
    private fun <T> enum(m: Map<*, *>, k: String, values: Array<T>, wire: (T) -> String): T {
        val s = text(m, k)
        return values.firstOrNull { wire(it) == s } ?: throw SegueFormatException("bad $k: $s")
    }
}
