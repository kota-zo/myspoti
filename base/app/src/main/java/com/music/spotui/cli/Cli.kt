package com.music.spotui.cli

import app.myspoti.extensions.Hook
import app.myspoti.extensions.Setting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Debug and beta builds: the commands `tools/myspoti-cli` sends over adb ([CliReceiver]), so a test
 * drives the app's own paths without a screenshot or a tap. Nothing here knows a provider: the
 * commands name the app's own parts (extensions, hooks, player, routes).
 */
sealed interface CliCommand {
    data object Status : CliCommand
    data object ExtList : CliCommand
    /** A package zip on the phone, installed with all it asks, without the prompt. */
    data class ExtInstall(val path: String) : CliCommand
    data class ExtUninstall(val id: String) : CliCommand
    data class ExtEnable(val id: String, val on: Boolean) : CliCommand
    /** [json]: the value as JSON text, null to go back to the default. */
    data class ExtSetting(val id: String, val key: String, val json: String?) : CliCommand
    data class ExtPress(val id: String, val key: String) : CliCommand
    data class ExtCall(val id: String, val hook: Hook, val input: String) : CliCommand
    data class ExtLog(val id: String, val lines: Int) : CliCommand
    data class ExtStorage(val id: String) : CliCommand
    /** The last [n] play attempts through the extensions (debug builds only). */
    data class ExtWhy(val n: Int) : CliCommand
    data class Play(val uri: String, val context: String?) : CliCommand
    data object Pause : CliCommand
    data object Resume : CliCommand
    data object Next : CliCommand
    data object Previous : CliCommand
    data class Nav(val route: String) : CliCommand
}

class CliError(message: String) : Exception(message)

object CliParser {
    val COMMANDS = listOf(
        "status", "ext.list", "ext.install <zip path>", "ext.uninstall <id>", "ext.enable <id>", "ext.disable <id>",
        "ext.setting <id> <key> <value|null>", "ext.press <id> <key>", "ext.call <id> <hook> [json]",
        "ext.log <id> [lines]", "ext.storage <id>", "ext.why [n]", "play <spotify uri> [context uri]", "pause", "resume", "next", "previous",
        "nav <route>",
    )

    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val KEY = Regex("[A-Za-z0-9_.-]{1,64}")
    private val URI = Regex("spotify:[a-z]+(:[A-Za-z0-9_.-]+)+")

    /** The arguments the script sends: a JSON array of strings, base64 in [args64] (no shell quoting). */
    fun args(args: String?, args64: String?): List<String> {
        val text = args64?.takeIf { it.isNotBlank() }?.let {
            runCatching { String(java.util.Base64.getDecoder().decode(it.trim()), Charsets.UTF_8) }.getOrElse { throw CliError("args64 is not base64") }
        } ?: args?.takeIf { it.isNotBlank() } ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrElse { throw CliError("args must be a JSON array of strings") }
        return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw CliError("args must be a JSON array of strings") }
    }

    fun parse(cmd: String, args: List<String>): CliCommand {
        fun arg(i: Int, what: String): String = args.getOrNull(i)?.takeIf { it.isNotBlank() } ?: throw CliError("$cmd: missing <$what>")
        fun id(i: Int = 0) = arg(i, "id").also { if (!ID.matches(it)) throw CliError("$cmd: bad extension id '$it'") }
        fun key(i: Int) = arg(i, "key").also { if (!KEY.matches(it)) throw CliError("$cmd: bad key '$it'") }
        fun most(n: Int) { if (args.size > n) throw CliError("$cmd: too many arguments (${args.size}, at most $n)") }
        return when (cmd.trim()) {
            "status" -> CliCommand.Status.also { most(0) }
            "ext.list" -> CliCommand.ExtList.also { most(0) }
            "ext.install" -> CliCommand.ExtInstall(arg(0, "zip path").also {
                if (!it.startsWith("/") || ".." in it.split('/')) throw CliError("ext.install: give an absolute path on the phone")
                most(1)
            })
            "ext.uninstall" -> CliCommand.ExtUninstall(id()).also { most(1) }
            "ext.enable" -> CliCommand.ExtEnable(id(), true).also { most(1) }
            "ext.disable" -> CliCommand.ExtEnable(id(), false).also { most(1) }
            "ext.setting" -> CliCommand.ExtSetting(id(), key(1), settingJson(args.getOrNull(2) ?: throw CliError("ext.setting: missing <value>"))).also { most(3) }
            "ext.press" -> CliCommand.ExtPress(id(), key(1)).also { most(2) }
            "ext.call" -> {
                most(3)
                val hook = arg(1, "hook").let { h -> Hook.of(h) ?: throw CliError("ext.call: unknown hook '$h' (${Hook.entries.joinToString { it.key }})") }
                val input = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "{}"
                runCatching { Json.parseToJsonElement(input) }.getOrElse { throw CliError("ext.call: the input is not JSON") }
                CliCommand.ExtCall(id(), hook, input)
            }
            "ext.log" -> CliCommand.ExtLog(id(), args.getOrNull(1)?.let { it.toIntOrNull()?.coerceIn(1, 1000) ?: throw CliError("ext.log: [lines] is a number") } ?: 50).also { most(2) }
            "ext.storage" -> CliCommand.ExtStorage(id()).also { most(1) }
            "ext.why" -> CliCommand.ExtWhy(args.getOrNull(0)?.let { it.toIntOrNull()?.coerceIn(1, 20) ?: throw CliError("ext.why: [n] is a number") } ?: 1).also { most(1) }
            "play" -> {
                most(2)
                val uri = arg(0, "spotify uri").also { if (!URI.matches(it)) throw CliError("play: not a spotify: uri") }
                CliCommand.Play(uri, args.getOrNull(1)?.takeIf { it.isNotBlank() }?.also { if (!URI.matches(it)) throw CliError("play: the context is not a spotify: uri") })
            }
            "pause" -> CliCommand.Pause.also { most(0) }
            "resume" -> CliCommand.Resume.also { most(0) }
            "next" -> CliCommand.Next.also { most(0) }
            "previous" -> CliCommand.Previous.also { most(0) }
            "nav" -> CliCommand.Nav(arg(0, "route").also { if ("://" in it || it.startsWith("/")) throw CliError("nav: a route of the app, like settings or extension/<id>") ; most(1) })
            else -> throw CliError("unknown command '$cmd' (${COMMANDS.joinToString(", ")})")
        }
    }

    /** A setting's value as typed: JSON when it reads as JSON (true, 3, "x"), else a string; `null` resets it. */
    fun settingJson(value: String): String? {
        val v = value.trim()
        if (v == "null") return null
        val parsed = runCatching { Json.parseToJsonElement(v) }.getOrNull()
        // The JSON reader takes a bare word as a literal: only true, false and numbers stay unquoted.
        val json = when (parsed) {
            is JsonPrimitive -> parsed.isString || v == "true" || v == "false" || v.toDoubleOrNull() != null
            is JsonArray, is JsonObject -> true
            else -> false
        }
        return if (json) parsed.toString() else JsonPrimitive(value).toString()
    }
}

/** What leaves the phone: no value of a secret setting, of the storage, nor anything that reads as a token. */
object CliMask {
    const val MASK = "***"

    private val SENSITIVE = Regex("(?i)(token|secret|passw|cookie|auth(?!or)|session|bearer|credential|signature|^sig$|^key$|apikey|api_key|private|jwt|sid$|policy|key-pair-id)")
    private val BEARER = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}")
    private val HEADER = Regex("(?i)\\b(cookie|set-cookie|authorization)\\s*[:=]\\s*[^\\n,}]+")
    private val QUERY = Regex("([?&;])([^=&#\\s\"]+)=([^&#\\s\"]*)")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*")

    fun sensitiveName(name: String): Boolean = SENSITIVE.containsMatchIn(name)

    /** [text] with every [known] value (secrets, stored values, 6 characters or more) and token-looking part replaced. */
    fun text(text: String, known: Collection<String> = emptyList()): String {
        var out = text
        known.filter { it.length >= 6 }.sortedByDescending { it.length }.forEach { out = out.replace(it, MASK) }
        out = BEARER.replace(out) { "${it.groupValues[1]} $MASK" }
        out = HEADER.replace(out) { "${it.groupValues[1]}: $MASK" }
        out = JWT.replace(out, MASK)
        out = QUERY.replace(out) { m -> if (sensitiveName(m.groupValues[2]) && m.groupValues[3].isNotEmpty()) "${m.groupValues[1]}${m.groupValues[2]}=$MASK" else m.value }
        return out
    }

    /** [e] with the values under sensitive names masked and every string run through [text]. */
    fun json(e: JsonElement, known: Collection<String> = emptyList()): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (sensitiveName(k) && v !is JsonNull && v !is JsonObject && v !is JsonArray) JsonPrimitive(MASK) else json(v, known) })
        is JsonArray -> JsonArray(e.map { json(it, known) })
        is JsonPrimitive -> if (e.isString) JsonPrimitive(text(e.content, known)) else e
    }

    /**
     * An extension's settings as shown: a secret one (or one its manifest does not list) reads
     * [MASK] once set, a button has no value. [values]: key → JSON text, as stored.
     */
    fun settings(declared: List<Setting>, values: Map<String, String>): JsonObject = buildJsonObject {
        declared.filter { it.type != "button" }.forEach { s ->
            val v = values[s.key]
            put(s.key, when {
                v == null -> s.default ?: JsonNull
                s.type == "secret" || sensitiveName(s.key) -> JsonPrimitive(MASK)
                else -> runCatching { Json.parseToJsonElement(v) }.getOrDefault(JsonPrimitive(MASK))
            })
        }
        values.keys.filter { k -> declared.none { it.key == k } }.forEach { put(it, JsonPrimitive(MASK)) }
    }

    /** The values to hide wherever they show up: secret settings and the storage, as plain strings. */
    fun knownSecrets(declared: List<Setting>, settings: Map<String, String>, storage: Map<String, String>): List<String> {
        val secretSettings = declared.filter { it.type == "secret" || sensitiveName(it.key) }.mapNotNull { settings[it.key] }
        return (secretSettings + storage.values).flatMap { strings(it) }.distinct()
    }

    /** A stored JSON value's strings (the whole text too, and an object's values one by one). */
    private fun strings(json: String): List<String> {
        val e = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return listOf(json)
        fun walk(x: JsonElement): List<String> = when (x) {
            is JsonObject -> x.values.flatMap(::walk)
            is JsonArray -> x.flatMap(::walk)
            is JsonPrimitive -> listOfNotNull(x.contentOrNull)
        }
        return walk(e) + json
    }
}

/** One answer, written to the reply file and the log. */
object CliReply {
    fun ok(cmd: String, result: JsonElement = JsonNull): String =
        JsonObject(mapOf("ok" to JsonPrimitive(true), "cmd" to JsonPrimitive(cmd), "result" to result)).toString()

    fun error(cmd: String, message: String): String =
        JsonObject(mapOf("ok" to JsonPrimitive(false), "cmd" to JsonPrimitive(cmd), "error" to JsonPrimitive(message))).toString()

    /** A request id the script chose: the reply file's name, so nothing else. */
    fun safeRequestId(id: String?): String? = id?.takeIf { Regex("[A-Za-z0-9_-]{1,64}").matches(it) }
}
