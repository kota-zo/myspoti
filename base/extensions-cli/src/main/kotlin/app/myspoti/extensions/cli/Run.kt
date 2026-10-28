package app.myspoti.extensions.cli

import app.myspoti.extensions.Attestation
import app.myspoti.extensions.AudioStream
import app.myspoti.extensions.Blocks
import app.myspoti.extensions.PageContent
import app.myspoti.extensions.Surface
import app.myspoti.extensions.Catalog
import app.myspoti.extensions.ExtensionException
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.ExtensionRuntime
import app.myspoti.extensions.ExtensionStatus
import app.myspoti.extensions.Hook
import app.myspoti.extensions.HookBudgets
import app.myspoti.extensions.Outcome
import app.myspoti.extensions.Host
import app.myspoti.extensions.LyricsResult
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.MemoryStorage
import app.myspoti.extensions.SpotifyCall
import app.myspoti.extensions.SpotifyDispatcher
import app.myspoti.extensions.SpotifyRaw
import app.myspoti.extensions.DownloadFile
import app.myspoti.extensions.PlaybackEvent
import app.myspoti.extensions.Storage
import app.myspoti.extensions.TrackInfo
import app.myspoti.host.cdp.CdpHost
import app.myspoti.spotify.SpotifyApi
import app.myspoti.spotify.gql.isMutation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """myspoti-ext: build and try Myspoti extensions on a computer.

  check <folder|zip>                 package validity only: the manifest's problems, whether the script
                                     loads, the hooks it provides, the actions the user is asked (icon,
                                     level, why). It never calls a hook (use run or test for that)
  run <folder|zip> <hook> [options]  calls a hook as the app would, prints what it answered
      --title T --artist A [--album B] [--duration MS] [--id SPOTIFY_ID] [--isrc ISRC]
      --set key=value                a setting's value (repeatable)
      --cdp PORT|URL                 myspoti.spotify works on the account signed in to the Web Player
                                     of a Chrome started with --remote-debugging-port=PORT; a change
                                     that removes something (delete, remove, unfollow, clear) asks
                                     yes or no on this terminal first
      --quality hi-res|cd|small      download-source only: the quality asked (default cd)
      --ahead true                   audio-source only: asked before the song plays (ahead: true)
  run <folder|zip> track [options]   delivers the "track" event (same options, and --origin, --provider)
  run <folder|zip> playback [options] delivers a play of the song: start, a progress every 10 s,
      [--played MS] [--leave skip|end|stop] [--context URI] [--shuffle true] [--source local|spotify]
      [--origin spotify|download|audio-source|local-file] [--provider EXTENSION_ID]
  test <folder|zip> --scenario file.json [--state file.json]
                                     runs a scenario: hook calls, events and page messages in order, with
                                     the app's time limits, a request rule set that injects latency, 429 +
                                     Retry-After, 5xx, DNS failures and resets, cancellation at a time,
                                     storage kept across steps (and across runs with --state); checks each
                                     step's outcome and time (docs/extensions/API.md §8). Exit 1 on a failure
  pack <folder> [out-folder]         builds <id>-<version>.zip and prints its index entry
  index <folder-of-zips> <name>      writes index.json for a repository of packages

Hooks: lyrics, audio-source, download-source, trackinfo, home, search (--query Q), menu (--kind track|album|
playlist|artist), player, page, message (--data JSON), tap (--action ID), status (live or not). Each hook gets the
app's own time limit (HookBudgets). Everything the manifest asks is granted here. Without --cdp,
myspoti.spotify has no account and its calls fail."""

fun main(args: Array<String>) {
    if (args.isEmpty()) { println(USAGE); return }
    try {
        when (args[0]) {
            "check" -> check(File(args.getOrElse(1) { usage() }))
            "test" -> test(File(args.getOrElse(1) { usage() }), args.drop(2))
            "run" -> run(File(args.getOrElse(1) { usage() }), args.getOrElse(2) { usage() }, args.drop(3))
            "pack" -> pack(File(args.getOrElse(1) { usage() }), args.getOrNull(2)?.let(::File))
            "index" -> index(File(args.getOrElse(1) { usage() }), args.getOrElse(2) { usage() })
            else -> usage()
        }
    } catch (e: ExtensionException) {
        System.err.println("✗ ${e.message}")
        exitProcess(1)
    }
}

private fun usage(): Nothing { println(USAGE); exitProcess(2) }

private class DesktopHost(
    private val settings: Map<String, String>,
    private val track: TrackInfo?,
    private val spotify: SpotifyDispatcher? = null,
) : Host {
    private val store = MemoryStorage()
    override suspend fun spotify(extensionId: String, call: SpotifyCall): String =
        spotify?.execute(call) ?: super.spotify(extensionId, call)
    override fun spotifyMutation(name: String): Boolean = spotify?.isMutation(name) ?: true
    override suspend fun confirm(extensionId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        System.err.println("[$extensionId] $text")
        System.err.print("Allow it? [y/N] ")
        System.err.flush()
        readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
    }
    override fun log(extensionId: String, message: String) = System.err.println("[$extensionId] $message")
    override fun storage(extensionId: String): Storage = store
    override fun setting(extensionId: String, key: String): String? = settings[key]?.let { v ->
        if (v == "true" || v == "false" || v.toDoubleOrNull() != null) v else kotlinx.serialization.json.JsonPrimitive(v).toString()
    }
    override fun nowPlaying(): String? = track?.toJson()
    override fun control(extensionId: String, action: String, argument: Long?): Boolean { System.err.println("player: $action ${argument ?: ""}"); return true }
    override fun attestation(): Attestation = attestation
    private val attestation = SoftwareAttestation(File(stateDir(), "attest"), ::log)
}

/** Where the runner keeps what must outlive a run (the software attestation keys): `$MYSPOTI_EXT_HOME`, else `~/.myspoti-ext`. */
private fun stateDir() = File(System.getenv("MYSPOTI_EXT_HOME") ?: (System.getProperty("user.home") + "/.myspoti-ext"))

/** The Web Player of a Chrome on [cdp] (a port, or its http address), as a Spotify account for extensions. */
private class Account(cdp: String) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host: CdpHost
    val dispatcher: SpotifyDispatcher

    init {
        val url = if (cdp.all { it.isDigit() }) "http://localhost:$cdp" else cdp
        val api: SpotifyApi
        val chrome = runBlocking {
            val h = CdpHost.connect(url)
            api = SpotifyApi(h, scope)
            h.reload()
            h.awaitLoad()
            api.onPageLoaded()
            withTimeout(60_000) { api.awaitReady() }
            if (!api.isSignedIn()) throw ExtensionException("the Web Player in that Chrome is not signed in")
            h
        }
        host = chrome
        dispatcher = SpotifyDispatcher(api, ApiRaw(api))
    }

    override fun close() { host.close(); scope.cancel() }
}

private class ApiRaw(private val api: SpotifyApi) : SpotifyRaw {
    override suspend fun gql(operation: String, variables: JsonObject): JsonElement = api.raw.gql(operation, variables)
    override fun operations(): Set<String> = api.raw.operations
    override suspend fun service(service: String, method: String, args: List<JsonElement>): JsonElement = api.raw.service(service, method, *args.toTypedArray())
    override suspend fun get(url: String): JsonElement? = api.raw.get(url)
    override fun isMutation(operation: String): Boolean = isMutation(operation)
    override suspend fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>): JsonElement =
        api.raw.request(method, url, body, headers).let { app.myspoti.extensions.RawRequest.response(it.status, it.headers, it.body) }
}

private fun load(path: File): Pair<Manifest, String> = if (path.isDirectory) {
    val m = Manifest.parse(File(path, "manifest.json").readText())
    m to File(path, m.main).readText()
} else ExtensionPackage.read(path.readBytes()).let { it.manifest to it.script }

private fun check(dir: File) {
    val (m, script) = load(dir)
    val problems = m.problems()
    problems.forEach { println("✗ $it") }
    if (m.isLegacy) println("! old format (provides + permissions): it still installs, mapped to actions; write \"actions\" instead (docs/extensions/API.md §2)")
    val granted = m.asks().map { it.key }.toSet()
    ExtensionRuntime(m, script, DesktopHost(emptyMap(), null), granted).use { rt ->
        rt.start()
        println("✓ script loads")
        println("The user is asked (icon, action, level — why):")
        m.asks().forEach { a ->
            val act = a.action
            println("  ${if (a.critical) "⚠" else "•"} [${act?.icon ?: "?"}] ${a.key} (${act?.label ?: "unknown"}, ${a.level.name.lowercase()})" +
                (if (a.hosts.isNotEmpty()) " ${a.hosts}" else "") + " — ${a.why.ifBlank { "(no reason given)" }}")
        }
        // Never a hook call here: what it answers depends on the network and the account (run, test).
        Hook.entries.filter { it != Hook.STATUS && rt.provides(it) }.takeIf { it.isNotEmpty() }
            ?.let { println("Hooks it provides: ${it.joinToString { h -> h.key }}") }
    }
    m.categoryWarning()?.let { println("! $it") }
    println("In the store: ${m.category().key}${if (m.category == null) " (set \"category\" to choose)" else ""}" +
        (m.minAppVersion?.let { ", Myspoti $it or newer" } ?: "") + (if (m.tags.isNotEmpty()) ", tags ${m.tags}" else ""))
    if (problems.isNotEmpty()) exitProcess(1)
}

/** `test <dir> --scenario file.json [--state file.json]`: each step's result; exit 1 when one failed. */
private fun test(path: File, rest: List<String>) {
    val opts = rest.chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { usage() } }
    val scenarioFile = File(opts["scenario"] ?: usage())
    val (m, script) = load(path)
    val scenario = runCatching { Scenario.parse(scenarioFile.readText()) }.getOrElse { System.err.println("✗ ${scenarioFile.name}: ${it.message}"); exitProcess(2) }
    val results = ScenarioRunner(m, script, scenario, opts["state"]?.let(::File)).run()
    results.forEach(::println)
    val failed = results.count { !it.ok }
    println(if (failed == 0) "✓ ${results.size} step(s) passed" else "✗ $failed of ${results.size} step(s) failed")
    if (failed > 0) exitProcess(1)
}

private fun run(path: File, hookName: String, rest: List<String>) {
    val (m, script) = load(path)
    val opts = HashMap<String, String>()
    val settings = HashMap<String, String>()
    var i = 0
    while (i < rest.size) {
        val k = rest[i].removePrefix("--")
        val v = rest.getOrElse(i + 1) { usage() }
        if (k == "set") settings[v.substringBefore('=')] = v.substringAfter('=') else opts[k] = v
        i += 2
    }
    val artist = opts["artist"] ?: ""
    val track = TrackInfo(
        id = opts["id"] ?: "", uri = opts["id"]?.let { "spotify:track:$it" } ?: "", title = opts["title"] ?: usage(),
        artist = artist.substringBefore(",").trim(), artists = artist.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        album = opts["album"] ?: "", durationMs = opts["duration"]?.toLongOrNull() ?: 0, isrc = opts["isrc"],
    )
    val account = opts["cdp"]?.let { Account(it) }
    ExtensionRuntime(m, script, DesktopHost(settings, track, account?.dispatcher), m.asks().map { it.key }.toSet()).use { rt ->
        account.use { runScript(rt, m, hookName, track, opts) }
    }
}

private fun runScript(rt: ExtensionRuntime, m: Manifest, hookName: String, track: TrackInfo, opts: Map<String, String> = emptyMap()) {
    rt.start()
    if (hookName == ExtensionRuntime.EVENT_TRACK) {
        rt.emit(hookName, PlaybackEvent.trackEventJson(track, opts["origin"] ?: PlaybackEvent.ORIGIN_SPOTIFY, opts["provider"]), HookBudgets.TRACK)
        println("✓ delivered"); return
    }
    if (hookName == ExtensionRuntime.EVENT_PLAYBACK) { playback(rt, track, opts); return }
    val hook = Hook.of(hookName) ?: usage()
    if (!rt.provides(hook)) throw ExtensionException("this extension does not provide $hookName (or its action is not in the manifest)")
    val started = System.currentTimeMillis()
    val input = hookInput(hook, track, opts) ?: usage()
    if (hook == Hook.STATUS) {
        // As the app asks it: only live: false is inactive; a failure or a late answer proves nothing.
        val s = ExtensionStatus.probe(rt, HookBudgets.STATUS)
        val ms = System.currentTimeMillis() - started
        println(when (s.state) {
            "live" -> "✓ live${s.detail?.let { ": $it" } ?: ""} ($ms ms)"
            "inactive" -> "— inactive: ${s.detail ?: "no detail"} ($ms ms): the app skips it for songs' audio and downloads"
            else -> "— no answer: ${s.detail ?: "no detail"} ($ms ms): the app keeps using it"
        })
        return
    }
    val out = rt.call(hook, input, HookBudgets.of(hook))
    val ms = System.currentTimeMillis() - started
    if (out == null) { println("— no answer ($ms ms): the app goes on without this extension"); return }
    // `{ outcome }` (feature `results`): why it has nothing, as the app reads it.
    Outcome.parse(hook, out)?.let { o -> println("— outcome ${o.describe()} ($ms ms)"); return }
    val surface = when (hook) {
        Hook.HOME -> Surface.HOME; Hook.SEARCH -> Surface.SEARCH; Hook.PLAYER -> Surface.PLAYER; Hook.TRACK_INFO -> Surface.TRACK_INFO
        Hook.MENU -> when (opts["kind"]) { "album" -> Surface.ALBUM_MENU; "playlist" -> Surface.PLAYLIST_MENU; "artist" -> Surface.ARTIST_MENU; else -> Surface.TRACK_MENU }
        else -> null
    }
    if (surface != null) {
        val blocks = Blocks.parse(out, surface)
        println("✓ ${blocks.size} block(s) for ${surface.key} ($ms ms)")
        blocks.forEach { println("  $it") }
        return
    }
    when (hook) {
        Hook.LYRICS -> LyricsResult.parse(out, m.name).let { l ->
            if (l == null) println("— empty lyrics ($ms ms)")
            else {
                println("✓ ${l.lines.size} lines from ${l.provider}, synced=${l.synced}, word by word=${l.wordSynced} ($ms ms)")
                l.lines.take(8).forEach { println("  [${it.startMs}] ${it.text}") }
            }
        }
        Hook.AUDIO_SOURCE -> AudioStream.parse(out).let { a ->
            if (a == null) println("— no audio ($ms ms)") else println(
                "✓ ${a.url.ifEmpty { "manifest (${a.manifest?.length} chars)" }}\n  headers=${a.headers} type=${a.mimeType} duration=${a.durationMs}" +
                    " expiresInMs=${a.expiresInMs ?: "-"} quality=${a.qualityLabel() ?: "-"} ($ms ms)",
            )
        }
        Hook.DOWNLOAD_SOURCE -> DownloadFile.parseList(out).let { files ->
            if (files.isEmpty()) println("— no file ($ms ms)")
            else {
                println("✓ ${files.size} file(s), best first ($ms ms)")
                files.forEach { f ->
                    println("  ${f.format} ${f.bitDepth ?: "-"}-bit ${f.sampleRate ?: "-"} Hz ${f.provider ?: ""} isrcMatched=${f.isrcMatched} size=${f.sizeHint ?: "-"}")
                    println("    ${f.url ?: "manifest (${f.manifest!!.length} chars)"}${if (f.headers.isNotEmpty()) " headers=${f.headers}" else ""}")
                }
            }
        }
        Hook.PAGE -> PageContent.parse(out, m.name).let { println("✓ page \"${it.title}\" ($ms ms)\n${it.html.take(2_000)}") }
        else -> println("✓ $out ($ms ms)")
    }
}

/**
 * Plays [track] for `--played` ms (default 40000) as the app reports it: start, a progress
 * every 10 s, then `--leave skip|end|stop` (default skip), one event a step, each delivered
 * at once with the timestamp it would have had.
 */
private fun playback(rt: ExtensionRuntime, track: TrackInfo, opts: Map<String, String>) {
    val played = opts["played"]?.toLongOrNull() ?: 40_000L
    val duration = track.durationMs.takeIf { it > 0 } ?: 200_000L
    val leave = opts["leave"] ?: "skip"
    val t0 = System.currentTimeMillis() - played
    val context = opts["context"] ?: ""
    val source = opts["source"] ?: PlaybackEvent.LOCAL
    fun ev(type: String, at: Long, reason: String? = null) = PlaybackEvent(
        type, track.copy(durationMs = duration), positionMs = at, durationMs = duration, timestamp = t0 + at, source = source,
        contextUri = context, shuffle = opts["shuffle"] == "true", reason = reason, playedMs = at,
        origin = opts["origin"] ?: PlaybackEvent.ORIGIN_SPOTIFY, providerId = opts["provider"],
    )
    val events = buildList {
        add(ev("start", 0, "play"))
        var p = 10_000L
        while (p <= played) { add(ev("progress", p)); p += 10_000 }
        when (leave) {
            "end" -> add(ev("end", duration, "finished"))
            "stop" -> add(ev("end", played, "stop"))
            else -> add(ev("skip", played, "forward"))
        }
    }
    events.forEach { e ->
        rt.emit(ExtensionRuntime.EVENT_PLAYBACK, e.toJson(), HookBudgets.TRACK)
        println("✓ ${e.type}${e.reason?.let { " ($it)" } ?: ""} at ${e.positionMs} ms")
    }
}

private fun pack(dir: File, outDir: File?) {
    check(dir)
    val bytes = ExtensionPackage.pack(dir)
    val p = ExtensionPackage.read(bytes)
    val out = File(outDir ?: dir.parentFile ?: File("."), "${p.manifest.id}-${p.manifest.version}.zip")
    out.parentFile.mkdirs()
    out.writeBytes(bytes)
    println("✓ ${out.path} (${bytes.size} bytes)\nsha256 ${p.sha256}\n\nIndex entry:")
    println(Catalog.encodeEntry(entry(p, out.name, bytes.size.toLong())))
}

private fun index(dir: File, name: String) {
    val zips = dir.listFiles { f -> f.name.endsWith(".zip") }.orEmpty().sortedBy { it.name }
    // The newest version of each extension.
    val old = File(dir, "index.json").takeIf { it.isFile }?.let { runCatching { Catalog.parse(it.readText()) }.getOrNull() }
    val entries = zips.map { f -> ExtensionPackage.read(f.readBytes()).let { p -> p to entry(p, f.name, f.length()) } }
        .groupBy { it.second.id }.values.map { v -> v.maxBy { it.second.versionCode } }
        .map { (p, e) ->
            // The icon next to the packages, so the store shows it before download; the date the
            // package was first listed is kept while its bytes stay the same.
            val icon = p.icon?.let { bytes -> File(dir, "icons/${e.id}.png").also { it.parentFile.mkdirs(); it.writeBytes(bytes) }; "icons/${e.id}.png" }
            val listed = old?.extensions?.firstOrNull { it.id == e.id && it.sha256 == e.sha256 }?.updatedAt
            e.copy(iconUrl = icon, updatedAt = listed ?: e.updatedAt)
        }.sortedBy { it.name.lowercase() }
    File(dir, "index.json").writeText(Catalog.encode(Catalog(name = name, extensions = entries, blocked = old?.blocked.orEmpty())) + "\n")
    println("✓ ${File(dir, "index.json").path}: ${entries.size} extensions${if (old?.blocked?.isNotEmpty() == true) ", ${old.blocked.size} blocked kept" else ""}")
}

private fun entry(p: ExtensionPackage, url: String, size: Long) = with(p.manifest) {
    Catalog.Entry(
        id, name, version, versionCode, apiVersion, author, description, actions = actions, provides = provides, permissions = permissions,
        url = url, sha256 = p.sha256, size = size, homepage = homepage,
        updatedAt = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(), category = category, tags = tags,
        minAppVersion = minAppVersion, changelog = changelog, replaces = replaces, conflictsWith = conflictsWith,
    )
}
