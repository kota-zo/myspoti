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
import kotlinx.serialization.json.put

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
    /** The account's Connect devices, with which one plays and their volume. */
    data object Devices : CliCommand
    data class Transfer(val deviceId: String) : CliCommand
    /** The volume of the device that plays, as the volume keys set it (0 to 100). */
    data class Volume(val level: Int) : CliCommand
    data object Resume : CliCommand
    data object Next : CliCommand
    data object Previous : CliCommand
    data class Nav(val route: String) : CliCommand
    /** AutoMix on or off; null: as it is. */
    data class AutoMixSet(val on: Boolean?) : CliCommand
    /** The data-plan transitions on or off; null: as they are. */
    data class AutoMixSegue(val on: Boolean?) : CliCommand
    data object AutoMixStatus : CliCommand
    /** Read the supported AutoMix controls, or change one by its public name. */
    data class AutoMixSettings(val name: String? = null, val value: String? = null) : CliCommand
    /** Next through SongPlayer's ordinary control path, which delegates to LocalPlayer's mix-on-skip. */
    data object AutoMixNext : CliCommand
    /** Phone songs (a piece of each one's MediaStore folder + title) played as the local queue. */
    data class AutoMixFiles(val names: List<String>) : CliCommand
    data class AutoMixSeek(val ms: Long) : CliCommand
    /** Jam: join by its token or link, where it stands (read again from Spotify), leave it. */
    data class JamJoin(val token: String) : CliCommand
    data object JamStatus : CliCommand
    data object JamLeave : CliCommand
    /** The metadata catalog: its scripts (version, bundled or downloaded, last look), the providers' order. */
    data object CatalogStatus : CliCommand
    /** Times a search as the search screen runs it: the wait for the account, then the results (counts only). */
    data class Search(val query: String) : CliCommand
    /** Looks for newer signed metadata scripts now. */
    data object CatalogUpdate : CliCommand
    /** Debug builds: where the scripts are fetched from ([url]; null: the published repository). */
    data class CatalogSource(val url: String?) : CliCommand
}

/** Public, account-free subset of AutoMix settings returned by `automix.settings`. */
data class AutoMixSettingsSnapshot(
    val enabled: Boolean,
    val segue: Boolean,
    val sessionDj: Boolean,
    val albumOrder: Boolean,
    val podcasts: Boolean,
    val harmonic: Boolean,
    val eq: Boolean,
    val show: Boolean,
    val mobileData: Boolean,
    val onNext: String,
    val style: String,
    val length: String,
    val tempo: String,
    val pulse: String,
    val durationMin: Int = 0,
    val durationMax: Int = 0,
    val performancePriority: Boolean = false,
    /** The Mix Lab's planner switch and the ideas played on top of it, by their names. */
    val orpheon: Boolean = false,
    val ideas: Map<String, Boolean> = emptyMap(),
) {
    fun json(): JsonObject = buildJsonObject {
        put("enabled", enabled); put("segue", segue); put("session-dj", sessionDj)
        put("album-order", albumOrder); put("podcasts", podcasts); put("harmonic", harmonic)
        put("eq", eq); put("show", show); put("mobile-data", mobileData)
        put("on-next", onNext); put("style", style); put("length", length)
        put("tempo", tempo); put("pulse", pulse)
        put("duration-min", durationMin); put("duration-max", durationMax)
        put("performance-priority", performancePriority)
        put("orpheon", orpheon)
        for ((name, on) in ideas) put(name, on)
    }
}

class CliError(message: String) : Exception(message)

object CliParser {
    val COMMANDS = listOf(
        "status", "ext.list", "ext.install <zip path>", "ext.uninstall <id>", "ext.enable <id>", "ext.disable <id>",
        "ext.setting <id> <key> <value|null>", "ext.press <id> <key>", "ext.call <id> <hook> [json]",
        "ext.log <id> [lines]", "ext.storage <id>", "ext.why [n]", "play <spotify uri> [context uri]", "pause", "resume", "next", "previous",
        "devices", "transfer <device id>", "volume <0-100>",
        "nav <route>", "automix [on|off]", "automix.segue [on|off]", "automix.status", "automix.settings [name value]", "automix.next", "automix.files <folder+title piece> <folder+title piece>...", "automix.seek <ms>", "jam.join <token|link>", "jam.status", "jam.leave",
        "catalog.status", "catalog.update", "catalog.source <https url|http://localhost:port/|default>",
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
            "automix" -> CliCommand.AutoMixSet(when (args.getOrNull(0)) { null -> null; "on" -> true; "off" -> false; else -> throw CliError("automix: on or off") }).also { most(1) }
            "automix.segue" -> CliCommand.AutoMixSegue(when (args.getOrNull(0)) { null -> null; "on" -> true; "off" -> false; else -> throw CliError("automix.segue: on or off") }).also { most(1) }
            "automix.status" -> CliCommand.AutoMixStatus.also { most(0) }
            "automix.settings" -> {
                most(2)
                if (args.isEmpty()) CliCommand.AutoMixSettings()
                else {
                    if (args.size != 2) throw CliError("automix.settings: use <name> <value>, or no arguments to read")
                    val name = arg(0, "setting name")
                    val value = arg(1, "value")
                    val allowed = when (name) {
                        "enabled", "segue", "orpheon", "ideas", "session-dj", "album-order", "podcasts", "harmonic", "eq", "show", "mobile-data" -> listOf("on", "off")
                        in app.myspoti.automix.segue.MixIdea.values().map { it.wire } -> listOf("on", "off")
                        "on-next" -> listOf("cut", "quick", "full")
                        "style" -> listOf("blend", "cut", "fade")
                        "length" -> listOf("short", "balanced", "long")
                        "tempo" -> listOf("strict", "normal", "wide")
                        "pulse" -> listOf("off", "subtle", "strong")
                        "duration-min", "duration-max" -> {
                            val seconds = value.toIntOrNull()?.takeIf { it in 0..90 }
                                ?: throw CliError("automix.settings: '$name' must be an integer from 0 to 90 seconds")
                            listOf(seconds.toString())
                        }
                        "performance-priority" -> listOf("on", "off")
                        else -> throw CliError("automix.settings: unknown setting '$name'")
                    }
                    if (value !in allowed) throw CliError("automix.settings: '$name' must be one of ${allowed.joinToString()}")
                    CliCommand.AutoMixSettings(name, value)
                }
            }
            "automix.next" -> CliCommand.AutoMixNext.also { most(0) }
            "automix.files" -> CliCommand.AutoMixFiles(args.filter { it.isNotBlank() }.also { if (it.size < 2) throw CliError("automix.files: at least two files") })
            "jam.join" -> CliCommand.JamJoin(arg(0, "token").let { a -> Regex("socialsession[:/]([A-Za-z0-9]+)").find(a)?.groupValues?.get(1) ?: a }).also { most(1) }
            "jam.status" -> CliCommand.JamStatus.also { most(0) }
            "jam.leave" -> CliCommand.JamLeave.also { most(0) }
            "catalog.status" -> CliCommand.CatalogStatus.also { most(0) }
            "search" -> CliCommand.Search(args.joinToString(" ").trim().ifEmpty { throw CliError("search: missing <query>") }.take(120))
            "catalog.update" -> CliCommand.CatalogUpdate.also { most(0) }
            "catalog.source" -> CliCommand.CatalogSource(arg(0, "url|default").let { u ->
                most(1)
                if (u == "default") null
                else u.also { if (!app.myspoti.catalog.scriptsUrlAllowed(it, allowLocalhost = true)) throw CliError("catalog.source: an https:// address, or http://localhost:<port>/ (adb reverse)") }
            })
            "automix.seek" -> CliCommand.AutoMixSeek(arg(0, "ms").toLongOrNull()?.takeIf { it >= 0 } ?: throw CliError("automix.seek: <ms> is a number")).also { most(1) }
            "pause" -> CliCommand.Pause.also { most(0) }
            "devices" -> CliCommand.Devices.also { most(0) }
            "transfer" -> { most(1); CliCommand.Transfer(arg(0, "device id").also { if (!ID.matches(it)) throw CliError("transfer: not a device id") }) }
            "volume" -> { most(1); CliCommand.Volume(arg(0, "level").toIntOrNull()?.takeIf { it in 0..100 } ?: throw CliError("volume: 0 to 100")) }
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
