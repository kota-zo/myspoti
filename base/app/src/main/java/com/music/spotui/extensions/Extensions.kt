package com.music.spotui.extensions

import android.app.Application
import android.os.Handler
import android.os.Looper
import app.myspoti.extensions.Action
import app.myspoti.extensions.ActivityLog
import app.myspoti.extensions.Area
import app.myspoti.extensions.AudioQuery
import app.myspoti.extensions.AudioStream
import app.myspoti.extensions.Blocks
import app.myspoti.extensions.Clash
import app.myspoti.extensions.Conflicts
import app.myspoti.extensions.PageContent
import app.myspoti.extensions.PlaybackArbiter
import app.myspoti.extensions.Surface
import app.myspoti.extensions.SurfaceGuard
import app.myspoti.extensions.mode
import app.myspoti.extensions.BasicHttp
import app.myspoti.extensions.Catalog
import app.myspoti.extensions.DownloadFile
import app.myspoti.extensions.DownloadQuery
import app.myspoti.extensions.PlaybackEvent
import app.myspoti.extensions.ExtensionException
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.ExtensionRuntime
import app.myspoti.extensions.Hook
import app.myspoti.extensions.HookBudgets
import app.myspoti.extensions.Outcome
import app.myspoti.extensions.PlayAttempt
import app.myspoti.extensions.PlayAttempts
import app.myspoti.extensions.SourceCooldown
import app.myspoti.extensions.Host
import app.myspoti.extensions.HttpRequest
import app.myspoti.extensions.HttpResponse
import app.myspoti.extensions.LyricsResult
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Snapshot
import app.myspoti.extensions.SpotifyCall
import app.myspoti.extensions.SpotifyDispatcher
import app.myspoti.extensions.SpotifyRaw
import app.myspoti.extensions.Storage
import app.myspoti.extensions.TrackInfo
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * The extensions installed on this phone: their packages, the rights the user granted, one
 * sandbox ([ExtensionRuntime]) each, on its own thread, for the enabled ones; and the fixed
 * points of the app that ask them (lyrics, a song's audio, a song's download files, the `track`
 * and `playback` events).
 *
 * Files: `files/extensions/registry.json`, and per extension `files/extensions/<id>/`
 * (`package.zip`, the running version, complete: it runs offline and outlives its repository;
 * `previous.zip`, the version before, to revert to; `staging.zip`, an update waiting for the
 * user's OK; `storage.json`, `settings.json`, `log.txt`). GitHub sources, updates and the kill
 * switch: [ExtensionUpdates].
 *
 * Nothing here runs on the main thread except posting to it; a failing extension is turned off
 * with its reason (it failed to load, failed [FailureCounter] times in a row, or threw something
 * the sandbox does not expect), never the app.
 */
object Extensions {
    // Every hook's time is in [HookBudgets], shared with the desktop runner (`myspoti-ext`).
    /** A lyrics hook call. */
    const val LYRICS_MS = HookBudgets.LYRICS
    /** An audio-source hook call. */
    const val AUDIO_MS = HookBudgets.AUDIO
    /** A download-source hook call (an extension may try several places). */
    const val DOWNLOAD_MS = HookBudgets.DOWNLOAD
    private const val TRACK_MS = HookBudgets.TRACK
    /** A block, tap or page-message hook call: the screen waits for it. */
    private const val BLOCKS_MS = HookBudgets.BLOCKS
    private const val PAGE_MS = HookBudgets.PAGE
    /** An `online` event: the extension sends what waited. */
    private const val ONLINE_MS = HookBudgets.ONLINE
    /** A `setting` event: a button may pair again with a server. */
    private const val SETTING_MS = HookBudgets.SETTING
    private const val SETTING_PAUSE_MS = 800L
    /** An `auth` event. */
    internal const val AUTH_MS = HookBudgets.AUTH
    private const val MAX_INDEX = 1024 * 1024

    private lateinit var ctx: Application
    internal lateinit var dir: File
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _registry = MutableStateFlow(Registry())
    val registry: StateFlow<Registry> = _registry.asStateFlow()

    /** The last failure of each extension, for its page. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    /** Bumped when an extension's log gets a line, so an open log view reads it again. */
    val logTick = MutableStateFlow(0L)

    /**
     * The running extensions giving songs' audio, download files and lyrics, in the order they are
     * asked; [ready]: the ones on at launch have all been started. [refreshProvided] keeps it in step
     * when one loads or stops (installed, updated, turned on or off, removed), its status changes or
     * the user's order changes; what depends on it (the player's routing, downloads, lyrics) reacts
     * at once, without a restart ([providersChanged]).
     */
    data class Provided(
        val audio: List<String> = emptyList(), val download: List<String> = emptyList(),
        val lyrics: List<String> = emptyList(), val ready: Boolean = false,
    )
    private val _provided = MutableStateFlow(Provided())
    val provided: StateFlow<Provided> = _provided.asStateFlow()
    /** The same, read by [hasAudioSource], [hasDownloadSource] and [hasLyrics]: a screen calling them is drawn again when it changes. */
    private val providedState = androidx.compose.runtime.mutableStateOf(Provided())
    private val providedLock = Any()

    private val runtimes = ConcurrentHashMap<String, ExtensionRuntime>()
    internal val packages = ConcurrentHashMap<String, ExtensionPackage>()
    private val stores = ConcurrentHashMap<String, FileStore>()
    private val settings = ConcurrentHashMap<String, FileStore>()
    private val logs = ConcurrentHashMap<String, ExtensionLog>()
    private val failures = FailureCounter()

    val isReady: Boolean get() = ::dir.isInitialized

    fun init(application: Application) {
        if (isReady) return
        ctx = application
        dir = File(application.filesDir, "extensions")
        _registry.value = Registry.read(File(dir, "registry.json")).let { r ->
            // Always-allow grants without an operation on a multiplexed endpoint (GraphQL) covered every
            // operation: dropped, the user is asked again (per operation from now on).
            if (r.extensions.none { e -> e.rawGrants.any { it.tooBroad } }) r
            else r.extensions.fold(r) { acc, e -> if (e.rawGrants.any { it.tooBroad }) acc.put(e.copy(rawGrants = e.rawGrants.filterNot { it.tooBroad })) else acc }
        }
        // A protected surface just went: what extensions draw is asked again.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { guard.state.collect { if (it.isEmpty()) blocksTick.value++ } }
        scope.launch {
            // Nothing is ever installed without the prompt: a folder the old debug inbox left is dropped.
            File(dir, "inbox").takeIf { it.exists() }?.deleteRecursively()
            ExtensionUpdates.readCachedBlocks()
            // One after the other, their sources asked only once all are loaded: a cold start is busy enough.
            _registry.value.extensions.filter { it.enabled }.forEach { start(it, probe = false) }
            started.complete(Unit)
            refreshProvided()
            probeAll()
            ExtensionUpdates.daily()
        }
        watchTracks()
        watchPlayback()
        watchNetwork()
        watchStatus()
        watchProvided()
    }

    /**
     * Reads again which extensions give audio, downloads and lyrics; when that changed, the songs
     * none had lately are asked again, and [providersChanged] runs.
     */
    private fun refreshProvided() {
        if (!isReady) return
        synchronized(providedLock) {
            val now = Provided(
                providing(Hook.AUDIO_SOURCE).map { it.first }, providing(Hook.DOWNLOAD_SOURCE).map { it.first },
                providing(Hook.LYRICS).map { it.first }, started.isCompleted,
            )
            if (now == _provided.value) return
            if (now.audio != _provided.value.audio || now.download != _provided.value.download) misses.clear()
            _provided.value = now
            providedState.value = now
        }
    }

    private fun watchProvided() = scope.launch {
        var before = Provided()
        _provided.collect { now -> if (now != before) { val was = before; before = now; providersChanged(was, now) } }
    }

    /**
     * What depends on which extensions give what, brought in step at once: the player (the song
     * playing and the ones after it), the lyrics cache, the downloads waiting for a source. Not at
     * launch (the extensions on before load then, nothing was asked of them yet) but for the misses.
     */
    private fun providersChanged(before: Provided, now: Provided) {
        Logbook.log("ext: sources now: audio ${now.audio.ifEmpty { "none" }}, downloads ${now.download.ifEmpty { "none" }}, lyrics ${now.lyrics.ifEmpty { "none" }}")
        if (now.audio != before.audio && before.ready) {
            val added = now.audio.filter { it !in before.audio }
            main.post { runCatching { com.music.spotui.di.SongPlayer.audioSourcesChanged(added, now.audio.isNotEmpty()) }
                .onFailure { Logbook.log("audio: sources changed, player not updated: $it") } }
        }
        if (now.lyrics != before.lyrics) com.music.spotui.data.api.LyricsApi.sourcesChanged(keepFound = !before.ready)
        // Lists kept downloaded skipped their songs for want of a source: synced now.
        if (before.ready && before.download.isEmpty() && now.download.isNotEmpty())
            com.music.spotui.data.offline.OfflineCollections.refreshAll(ctx, force = true)
    }

    // ── Writes allowed for good ──

    /** Keeps [g] for [id]: its `raw.request` writes [g] covers run without asking. */
    fun addRawGrant(id: String, g: app.myspoti.extensions.RawGrant) = update { r ->
        r.find(id)?.let { e -> if (g in e.rawGrants) r else r.put(e.copy(rawGrants = e.rawGrants + g)) } ?: r
    }.also { log(id, "always allowed: $g") }

    fun revokeRawGrant(id: String, g: app.myspoti.extensions.RawGrant) = update { r ->
        r.find(id)?.let { e -> r.put(e.copy(rawGrants = e.rawGrants - g)) } ?: r
    }.also { log(id, "no longer allowed: $g") }

    // ── Work that waits for the network ──

    /** Whether the device has a network now. */
    private fun isOnline(): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** The marker of an `online` event asked and not yet delivered: it survives a restart. */
    private fun onlineMarker(id: String) = File(dir, "$id/online")

    private fun askOnline(id: String) {
        runCatching { onlineMarker(id).apply { parentFile?.mkdirs() }.createNewFile() }
        if (isOnline()) deliverOnline(id)
    }

    /** Sends `online` to [id] if it asked for it and runs; the marker goes first, so it is sent once. */
    private fun deliverOnline(id: String) {
        val rt = runtimes[id] ?: return
        if (!onlineMarker(id).delete()) return
        scope.launch { runCatching { runInterruptible { rt.emit(ExtensionRuntime.EVENT_ONLINE, "{}", ONLINE_MS) } }.onFailure { failed(id, it.toString()) } }
    }

    /** The network went (and the extensions' sources are asked again once it is back). */
    @Volatile private var networkLost = false

    private fun watchNetwork() {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                    runtimes.keys.forEach(::deliverOnline)
                    if (networkLost) { networkLost = false; probeAll() }
                }

                override fun onLost(network: android.net.Network) { networkLost = true }
            })
        }.onFailure { Logbook.log("ext: no network callback: ${it.message}") }
    }

    // ── Installed extensions ──

    fun manifest(id: String): Manifest? = pkg(id)?.manifest

    fun pkg(id: String): ExtensionPackage? = packages[id] ?: runCatching {
        ExtensionPackage.read(File(dir, "$id/package.zip").readBytes())
    }.getOrNull()?.let { packages.putIfAbsent(id, it) ?: it } // an install's newer package is never replaced by an older read

    fun isRunning(id: String) = runtimes.containsKey(id)

    /**
     * What installing [p] would do, given what is on the phone: an update is weighed against
     * what the user accepted ([InstalledExtension.accepted]), not the version running.
     */
    fun plan(p: ExtensionPackage): InstallPlan {
        val installed = _registry.value.find(p.manifest.id)
        return InstallPlan.of(p.manifest, installed, installed?.let { it.accepted?.toManifest() ?: manifest(it.id) })
    }

    /**
     * What [m] would collide with among the enabled extensions: an exclusive action another one
     * provides now, `conflictsWith`, `replaces` (see [Conflicts.clashes]).
     */
    fun clashes(m: Manifest): List<Clash> = _registry.value.let { r ->
        val enabled = r.byPriority(Area.PLAYBACK.key, r.extensions.filter { it.enabled && it.id != m.id }) { it.id }.mapNotNull { manifest(it.id) }
        Conflicts.clashes(m, enabled, r.active)
    }

    /**
     * Installs (or updates to) [p] with [granted]; its sandbox starts at once. [keepNew]: how the
     * user settled each of [clashes] (see [Clashes.apply]); [noticeAccepted]: the third-party notice.
     * [origin]: the GitHub repository it came from (else the one it came from before is kept).
     * [accepted]: the user saw what it asks (install prompt, update card), so it becomes the
     * snapshot later updates are weighed against; false for an update applied by itself.
     * The version it replaces is kept as `previous.zip`.
     */
    suspend fun install(
        zip: ByteArray, p: ExtensionPackage, granted: Set<String>, source: String,
        clashes: List<Clash> = emptyList(), keepNew: (Clash) -> Boolean = { true }, noticeAccepted: Boolean = true,
        origin: app.myspoti.extensions.Origin? = null, accepted: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val id = p.manifest.id
        ExtensionUpdates.blocked(id, p.sha256)?.let { throw ExtensionException("blocked by Myspoti: ${it.reason.ifBlank { "no reason given" }}") }
        // Under the extension's start lock: a start racing this update never sees the new package
        // beside the old entry (it read as "changed on the phone" and turned the extension off).
        val (e, before) = synchronized(startLocks.getOrPut(id) { Any() }) {
            val before = _registry.value.find(id)
            val beforeManifest = before?.let { manifest(id) }
            stop(id)
            val folder = File(dir, id).apply { mkdirs() }
            val current = File(folder, "package.zip")
            if (before != null && current.isFile && before.sha256 != p.sha256) current.copyTo(File(folder, "previous.zip"), overwrite = true)
            writeBytesAtomically(current, zip)
            File(folder, "staging.zip").delete()
            packages[id] = p
            var startOff = false
            update { r ->
                val (settled, off) = Clashes.apply(r, id, clashes, keepNew)
                startOff = off
                settled.copy(noticeAccepted = if (noticeAccepted) settled.noticeAccepted + id else settled.noticeAccepted)
            }
            clashes.filter { keepNew(it) && it.kind != Clash.Kind.EXCLUSIVE }.forEach { stop(it.otherId); log(it.otherId, "turned off: replaced by $id") }
            val e = InstalledExtension(
                id = id, name = p.manifest.name, version = p.manifest.version, versionCode = p.manifest.versionCode,
                author = p.manifest.author, sha256 = p.sha256, granted = granted, enabled = !startOff,
                disabledReason = if (startOff) "kept the extension it conflicts with" else null,
                installedAt = before?.installedAt ?: System.currentTimeMillis(), source = source,
                origin = origin ?: before?.origin,
                accepted = if (accepted) Snapshot.of(p.manifest, (origin ?: before?.origin)?.repo, p.script, origin ?: before?.origin)
                    else before?.accepted ?: beforeManifest?.let { Snapshot.of(it, before?.origin?.repo) },
                previous = if (before != null && before.sha256 != p.sha256) KeptVersion(before.version, before.versionCode, before.sha256, before.origin) else before?.previous,
                declinedVersionCode = before?.declinedVersionCode ?: 0,
                lastCheck = before?.lastCheck ?: 0,
                // The writes it declared are allowed with the install (shown under spotify.raw before it).
                rawGrants = (before?.rawGrants.orEmpty() +
                    if (app.myspoti.extensions.Action.SPOTIFY_RAW.key in granted) p.manifest.declaredWrites() else emptyList()).distinct(),
            )
            update { it.put(e) }
            e to before
        }
        failures.reset(id)
        clearError(id)
        log(id, if (before == null) "installed ${p.manifest.version} from $source" else "updated ${before.version} → ${p.manifest.version}")
        start(e)
        if (before == null) p.manifest.welcome?.takeIf { p.manifest.problems().isEmpty() }?.let { welcome.value = id to it }
    }

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        stop(id)
        update { it.remove(id) }
        packages.remove(id); stores.remove(id); settings.remove(id); logs.remove(id)
        clearError(id)
        runningSha.remove(id)
        ExtensionAudio.forgetAll()
        ExtensionAuth.forget(id)
        ExtensionAttestation.reset(id)
        File(dir, id).deleteRecursively()
        Logbook.log("ext $id: uninstalled")
    }

    fun setEnabled(id: String, on: Boolean, clashes: List<Clash> = emptyList(), keepNew: (Clash) -> Boolean = { true }) = scope.launch {
        val e = _registry.value.find(id) ?: return@launch
        if (on) {
            ExtensionUpdates.blocked(id, e.sha256)?.let { return@launch disable(id, "blocked by Myspoti: ${it.reason.ifBlank { "no reason given" }}") }
            // The user kept the one it conflicts with: it stays off.
            if (Clashes.apply(_registry.value, id, clashes, keepNew).second) return@launch
            update { Clashes.apply(it, id, clashes, keepNew).first }
            clashes.filter { keepNew(it) && it.kind != Clash.Kind.EXCLUSIVE }.forEach { stop(it.otherId); log(it.otherId, "turned off: replaced by $id") }
            arbiter.resume(id)
            val now = e.copy(enabled = true, disabledReason = null)
            update { it.put(now) }
            failures.reset(id)
            clearError(id)
            start(now)
        } else {
            stop(id)
            update { it.put(e.copy(enabled = false)) }
            log(id, "turned off")
        }
    }

    /**
     * The user accepted, once, the actions they had unticked before installs granted everything
     * ([Grants.refused]): it is granted all it lists. Turning it back on is the caller's (clashes).
     */
    suspend fun acceptAll(id: String) = withContext(Dispatchers.IO) {
        val e = _registry.value.find(id) ?: return@withContext
        val m = manifest(id) ?: return@withContext
        update { it.put(e.copy(granted = Grants.keys(m), disabledReason = null)) }
        log(id, "accepted all its actions")
    }

    fun settingValues(id: String): Map<String, String> = settingsOf(id).all()

    /** [json]: the value as JSON text, null to go back to the manifest's default. */
    fun setSetting(id: String, key: String, json: String?) {
        val s = settingsOf(id)
        if (json == null) s.remove(key) else s.set(key, json)
        // Typing changes a text field at each key: the extension hears of it once the user pauses.
        settingJobs.remove("$id\u0000$key")?.cancel()
        settingJobs["$id\u0000$key"] = scope.launch { kotlinx.coroutines.delay(SETTING_PAUSE_MS); sendSetting(id, key, pressed = false) }
    }

    /** A button setting was pressed: the extension does what it says (pair again…). */
    fun pressSetting(id: String, key: String) {
        log(id, "setting $key pressed")
        scope.launch { sendSetting(id, key, pressed = true) }
    }

    private val settingJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private suspend fun sendSetting(id: String, key: String, pressed: Boolean) {
        val rt = runtimes[id]?.takeIf { it.listens(ExtensionRuntime.EVENT_SETTING) } ?: return
        val json = kotlinx.serialization.json.buildJsonObject {
            put("key", kotlinx.serialization.json.JsonPrimitive(key))
            if (pressed) put("pressed", kotlinx.serialization.json.JsonPrimitive(true))
        }.toString()
        runCatching { runInterruptible { rt.emit(ExtensionRuntime.EVENT_SETTING, json, SETTING_MS) } }.onFailure { failed(id, it.toString()) }
    }

    fun logLines(id: String): List<String> = logOf(id).lines()

    fun clearLog(id: String) { logOf(id).clear(); logTick.value++ }

    // ── The hooks, at their fixed points ──

    /**
     * The running extensions providing [hook], in their area's priority order (a source: in the
     * user's order for it, [sources]). An exclusive action ([app.myspoti.extensions.Mode.EXCLUSIVE])
     * has one: the one the user made active, else the first.
     */
    private fun providing(hook: Hook): List<Pair<String, ExtensionRuntime>> = _registry.value.let { r ->
        val action = hook.action
        val area = (action?.area ?: Area.OTHER).key
        // A source whose last `status` said it does not answer is skipped: the next one, or Spotify, plays.
        val skip = { id: String -> hook in SOURCE_HOOKS && health.inactive(id) }
        // A source: the extensions above Spotify only (all of them while Spotify is last).
        val ordered = if (action?.mode == app.myspoti.extensions.Mode.ORDERED) line(r, action).takeWhile { it != SPOTIFY_SOURCE }.mapNotNull(r::find)
            else r.byPriority(area, r.extensions) { it.id }
        val all = ordered.mapNotNull { e -> runtimes[e.id]?.takeIf { e.enabled && it.provides(hook) && !skip(e.id) }?.let { e.id to it } }
        if (action == null || action.mode != app.myspoti.extensions.Mode.EXCLUSIVE) all
        else Conflicts.activeFor(all.map { it.first }, r.active[action.key]).let { one -> all.filter { it.first == one } }
    }

    /** The installed extensions per area, in the user's order (the Installed tab). */
    fun byArea(): Map<Area, List<InstalledExtension>> = Areas.group(_registry.value, ::manifest)

    /** Moves [id] up (negative [delta]) or down in [area]'s order. */
    fun movePriority(area: Area, id: String, delta: Int) = update { r ->
        r.move(area.key, Areas.group(r, ::manifest).getValue(area).map { it.id }, id, delta)
    }

    /** The installed extensions giving the source [action] (audio, downloads), in the order they are asked. */
    fun sources(action: Action): List<InstalledExtension> = _registry.value.let { r -> line(r, action).mapNotNull(r::find) }

    /**
     * The order the source [action] asks in, as ids: its extensions and, for audio, Spotify
     * ([SPOTIFY_SOURCE], last until the user moves it up; the extensions after it are not asked).
     */
    fun sourceLine(action: Action): List<String> = line(_registry.value, action)

    private fun line(r: Registry, action: Action): List<String> {
        val ids = r.extensions.filter { action in manifest(it.id)?.actionSet().orEmpty() }.map { it.id }
        return r.bySourceOrder(action.key, Area.AUDIO.key, if (action == Action.AUDIO_SOURCE) ids + SPOTIFY_SOURCE else ids) { it }
    }

    /** Moves [id] (an extension, or [SPOTIFY_SOURCE]) up (negative [delta]) or down in the order the source [action] asks in. */
    fun moveSource(action: Action, id: String, delta: Int) = update { r -> r.move(action.key, line(r, action), id, delta) }

    /** Makes [id] the one acting for the exclusive [action]; the others stay installed, idle. */
    fun setActive(action: Action, id: String) {
        update { it.copy(active = it.active + (action.key to id)) }
        log(id, "now active for ${action.key}")
    }

    /** Who acts for the exclusive [action] now (null: nobody). */
    fun activeFor(action: Action): String? = _registry.value.let { r ->
        Conflicts.activeFor(action.area?.let { byArea()[it] }.orEmpty().filter { it.enabled && action in manifest(it.id)?.actionSet().orEmpty() }.map { it.id }, r.active[action.key])
    }

    // ── Silent Spotify mirror (player.setLocalMute, playback.mirror) ──

    /** The host's rules for the mute; the app feeds it the local player's state. */
    val mirror = app.myspoti.extensions.MirrorGuard()

    /** Name of the extension keeping Spotify muted now (the players show "Spotify mirrored by <name>"). */
    val mirrorBy = MutableStateFlow<String?>(null)
    private var mirrorTimer: kotlinx.coroutines.Job? = null

    private fun mirrorStarted(id: String) {
        val name = _registry.value.find(id)?.name ?: id
        mirrorBy.value = name
        com.music.spotui.di.LocalPlayer.setMirrorFocus(true)
        did(Area.PLAYBACK, id, "mirrors Spotify silently")
        log(id, "mirror on: Spotify plays unheard on this phone")
        mirrorTimer?.cancel()
        mirrorTimer = scope.launch {
            while (mirror.active != null) {
                delay((mirror.remainingMs() ?: 0L) + 500L)
                val s = SongPlayer.localSnapshot(); mirrorTick(s.first, s.second)
            }
        }
    }

    private fun mirrorEnded(id: String, reason: String) {
        mirrorBy.value = null
        com.music.spotui.di.LocalPlayer.setMirrorFocus(false)
        mirrorTimer?.cancel()
        did(Area.PLAYBACK, id, "mirror ended: $reason")
        log(id, "mirror cleared: $reason")
        SongPlayer.unmuteMirrorNow()
    }

    /** The local player's state changed: the mute ends when the track changes, pauses or runs out. */
    fun mirrorTick(localPlaying: Boolean, trackUri: String?) {
        val a = mirror.active ?: return
        mirror.check(localPlaying, trackUri)?.let { mirrorEnded(a.extensionId, it) }
    }

    /** The local player stopped (or any other reason that is not about one extension). */
    fun mirrorReset(reason: String) {
        val a = mirror.active ?: return
        mirror.disable(a.extensionId)
        mirrorEnded(a.extensionId, reason)
    }

    internal fun mirrorStartedBy(id: String) = mirrorStarted(id)
    internal fun mirrorEndedBy(id: String) = mirrorEnded(id, "the extension turned it off")

    private fun mirrorGone(id: String) { if (mirror.extensionGone(id)) mirrorEnded(id, "the extension stopped, was turned off or removed") }

    // ── Who did what, and the playback arbiter ──

    /** The last things extensions did, per area (the Installed tab's activity). */
    val activity = ActivityLog()

    private fun did(area: Area, id: String, text: String) = activity.add(area, id, text)

    /** Protected surfaces on screen (support, update, install, confirmations, report, about). */
    val guard: SurfaceGuard get() = SurfaceGuard.app

    /** An extension the arbiter suspended (it fought another over playback): the app asks to turn it off. */
    val suspendedNotice = MutableStateFlow<Pair<String, String>?>(null)

    /** An extension just installed for the first time, and its welcome (`welcome` in its manifest). */
    val welcome = MutableStateFlow<Pair<String, app.myspoti.extensions.Welcome>?>(null)

    private val arbiter = PlaybackArbiter({ id -> byArea()[Area.PLAYBACK].orEmpty().indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it } })

    /** Lets a suspended extension send playback commands again. */
    fun resumePlayback(id: String) = arbiter.resume(id)

    /** One playback command from [id] through the arbiter: whether it may run. */
    private fun arbitrate(id: String, command: String): Boolean {
        val v = arbiter.submit(id, command)
        val name = { x: String -> _registry.value.find(x)?.name ?: x }
        when {
            v.allowed -> did(Area.PLAYBACK, id, "${commandLabel(command)} by ${name(id)}")
            v.reason == "rate" -> log(id, "playback: too many commands, $command dropped")
            v.reason == "overridden" -> { did(Area.PLAYBACK, id, "${commandLabel(command)} by ${name(id)} overridden by ${name(v.winner.orEmpty())}"); log(id, "playback: $command overridden by ${v.winner}") }
        }
        v.suspend?.let { lower ->
            val other = if (lower == id) v.winner.orEmpty() else id
            did(Area.PLAYBACK, lower, "${name(lower)} suspended: it kept undoing ${name(other)}")
            log(lower, "playback commands suspended: it kept contradicting $other")
            suspendedNotice.value = lower to other
        }
        return v.allowed
    }

    private fun commandLabel(command: String) = when (command) {
        "next" -> "Skipped"; "previous" -> "Went back"; "pause" -> "Paused"; "play", "resume" -> "Played"; "seek" -> "Moved in the song"
        else -> command.replaceFirstChar { it.uppercase() }
    }

    // ── Blocks, pages and taps ──

    /** Bumped when an extension asks for its blocks to be asked again (`myspoti.ui.refresh`). */
    val blocksTick = MutableStateFlow(0L)

    /** The blocks the extensions draw on [surface] for [input], merged (de-duplicated, quotas) in their area's order. */
    suspend fun blocks(hook: Hook, surface: Surface, input: String): Conflicts.Shown<String> {
        if (!isReady || guard.suspended) return Conflicts.Shown(emptyList(), emptyList())
        val providers = providing(hook)
        if (providers.isEmpty()) return Conflicts.Shown(emptyList(), emptyList())
        val answers = kotlinx.coroutines.coroutineScope {
            providers.map { (id, rt) -> async { id to (callHook(id, rt, hook, input, BLOCKS_MS) { Blocks.parse(it, surface) } ?: emptyList()) } }.map { it.await() }
        }
        return Conflicts.merge(answers, surface)
    }

    /** A block of [id] with an action was tapped on [surface]; [context]: what the place is about (JSON). */
    fun tap(id: String, surface: Surface, action: String, context: String) {
        if (guard.suspended) return
        val rt = runtimes[id] ?: return
        val input = """{"action":${kotlinx.serialization.json.JsonPrimitive(action)},"surface":"${surface.key}","context":$context}"""
        scope.launch { callHook(id, rt, Hook.TAP, input, BLOCKS_MS) { it } }
    }

    /** [id]'s page, or null. */
    suspend fun page(id: String): PageContent? {
        val rt = runtimes[id]?.takeIf { it.provides(Hook.PAGE) } ?: return null
        return callHook(id, rt, Hook.PAGE, "{}", PAGE_MS) { PageContent.parse(it, rt.manifest.name) }
    }

    /** Where [id]'s open page receives messages (its WebView), or nothing. */
    private val pageSinks = ConcurrentHashMap<String, (String) -> Unit>()

    fun attachPage(id: String, sink: ((String) -> Unit)?) { if (sink == null) pageSinks.remove(id) else pageSinks[id] = sink }

    /** A message [id]'s page posted: its extension's answer goes back to that page only. */
    fun pageMessage(id: String, data: String) {
        if (data.length > 64 * 1024 || guard.suspended) return
        val rt = runtimes[id] ?: return
        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data).toString() }.getOrElse { kotlinx.serialization.json.JsonPrimitive(data).toString() }
        scope.launch { callHook(id, rt, Hook.MESSAGE, json, BLOCKS_MS) { it }?.let { reply -> main.post { pageSinks[id]?.invoke(reply) } } }
    }

    /** The extensions that have a page (webview.page granted). */
    fun hasPage(id: String): Boolean = runtimes[id]?.provides(Hook.PAGE) == true

    fun hasLyrics(): Boolean { providedState.value; return isReady && providing(Hook.LYRICS).isNotEmpty() }

    /**
     * An audio-source extension is asked for songs: one runs and is not inactive, or one that is on
     * is still being started at launch (the song then waits for it, see [audio]).
     */
    fun hasAudioSource(): Boolean { providedState.value; return isReady && (providing(Hook.AUDIO_SOURCE).isNotEmpty() || startingAudioSource()) }

    /** The app is still starting the extensions on at launch, an audio source among them. */
    private fun startingAudioSource(): Boolean =
        !started.isCompleted && _registry.value.extensions.any { it.enabled && Action.AUDIO_SOURCE.key in it.granted }

    /**
     * Why no audio-source extension is asked for a song while some are on ("x skipped (inactive:
     * …)", "x not running"), for the log; null when none is on (Spotify plays as it always did).
     */
    fun audioSkipped(): String? = _registry.value.extensions.filter { it.enabled && Action.AUDIO_SOURCE.key in it.granted }
        .takeIf { it.isNotEmpty() }?.joinToString { e ->
            when {
                runtimes[e.id] == null -> "${e.id} not running"
                health.inactive(e.id) -> "${e.id} skipped (inactive: ${health.last(e.id)?.detail ?: "no detail"})"
                else -> "${e.id} not the active one"
            }
        }

    /** What [id]'s last `status` probe said (null: not probed yet), for the adb CLI. */
    fun statusOf(id: String): app.myspoti.extensions.ExtensionStatus? = health.last(id)

    /** An enabled extension gives songs' download files (without one, downloads say to install one). */
    fun hasDownloadSource(): Boolean { providedState.value; return isReady && (providing(Hook.DOWNLOAD_SOURCE).isNotEmpty() || startingDownloadSource()) }

    /** The app is still starting the extensions on at launch, a download source among them. */
    private fun startingDownloadSource(): Boolean =
        !started.isCompleted && _registry.value.extensions.any { it.enabled && Action.DOWNLOAD_SOURCE.key in it.granted }

    /**
     * The files [track] can be downloaded from in [quality] (`hi-res`, `cd`, `small`), best first,
     * from the first extension (priority order) that has any, with that extension's id and name.
     * A source set aside for a while ([SourceCooldown]) is not asked.
     */
    suspend fun downloadFiles(track: TrackInfo, quality: String): Triple<String, String, List<DownloadFile>>? {
        if (!isReady) return null
        // Asked right after launch (downloads resumed from the last session): the extensions still
        // loading are waited for, rather than the song marked unavailable for want of a source.
        if (!started.isCompleted) {
            val t0 = System.currentTimeMillis()
            val loaded = withTimeoutOrNull(START_WAIT_MS) { started.await() } != null
            Logbook.log("download: extensions not loaded yet, waited ${System.currentTimeMillis() - t0} ms${if (loaded) "" else " (still loading)"}")
        }
        val input = DownloadQuery.toJson(track, quality)
        probeStale(Hook.DOWNLOAD_SOURCE)
        return HookDispatch.firstInOrder(available(Hook.DOWNLOAD_SOURCE)) { (id, rt) ->
            ask(id, rt, Hook.DOWNLOAD_SOURCE, input, DOWNLOAD_MS) { DownloadFile.parseList(it).takeIf { l -> l.isNotEmpty() } }.value
        }?.let { (c, files) ->
            log(c.first, "download files for ${track.title}: ${files.joinToString { "${it.format}/${it.bitDepth ?: "-"}" }}")
            did(Area.AUDIO, c.first, "Download of ${track.title} from ${c.second.manifest.name}")
            Triple(c.first, c.second.manifest.name, files)
        }
    }

    /** Lyrics from the extensions (the best, ties to the higher priority), or null. */
    suspend fun lyrics(track: TrackInfo): Lyrics? {
        if (!isReady) return null
        val input = track.toJson()
        return HookDispatch.bestInOrder(available(Hook.LYRICS), LyricsMerge::rank) { (id, rt) ->
            ask(id, rt, Hook.LYRICS, input, LYRICS_MS) { LyricsResult.parse(it, rt.manifest.name) }.value?.let(LyricsMerge::toLyrics)
        }?.let { (c, l) -> log(c.first, "lyrics for ${track.title}: ${l.lines.size} lines"); did(Area.LYRICS, c.first, "Lyrics of ${track.title} from ${c.second.manifest.name}"); l }
    }

    /**
     * Where [track]'s audio is, from the first extension (in priority order) that knows. [ahead]:
     * asked before the song plays (the next one of the queue), which the extension is told. Each
     * call is one [PlayAttempt] ([attempts]): the sources asked or skipped, and the one chosen.
     */
    suspend fun audio(track: TrackInfo, timeoutMs: Long = AUDIO_MS, ahead: Boolean = false): AudioStream? {
        if (!isReady) return null
        val attempt = attempts.start(track.id, track.title, ahead)
        // None had it a moment ago: not asked again (a queue reaching it, a replay, a resume).
        if (misses.skips(track.id, ahead)) {
            Logbook.log("audio: ${track.title}${if (ahead) " (ahead)" else ""} not asked again: no extension had it under a minute ago")
            attempts.update(attempt) { it.copy(fallback = "Spotify: no extension had it under a minute ago, not asked again") }
            return null
        }
        // Picked right after launch: the extensions still loading are waited for, not skipped.
        if (!started.isCompleted) {
            val t0 = System.currentTimeMillis()
            val loaded = withTimeoutOrNull(START_AUDIO_MS) { started.await() } != null
            Logbook.log("audio: extensions not loaded yet, waited ${System.currentTimeMillis() - t0} ms${if (loaded) "" else " (still loading)"}")
        }
        // The same song as a download asks with: its ISRC, which an extension matches recordings by.
        val input = AudioQuery.toJson(withIsrc(track), ahead)
        probeStale(Hook.AUDIO_SOURCE)
        val skipped = mutableListOf<PlayAttempt.Ask>()
        _registry.value.extensions.filter { it.enabled && health.inactive(it.id) && runtimes[it.id]?.provides(Hook.AUDIO_SOURCE) == true }
            .forEach {
                val why = "inactive: ${health.last(it.id)?.detail ?: "no detail"}"
                Logbook.log("audio: ${it.id} skipped ($why)")
                skipped += PlayAttempt.Ask(it.id, Outcome.Skipped().key, 0, why)
            }
        val asked = available(Hook.AUDIO_SOURCE, skipped)
        if (asked.isEmpty()) {
            attempts.update(attempt) { it.copy(asked = skipped.toList(), fallback = "Spotify: no source to ask") }
            return null
        }
        val label = "${track.title}${if (ahead) " (ahead)" else ""}"
        Logbook.log("audio: asked ${asked.joinToString { it.first }} for $label")
        val t0 = System.currentTimeMillis()
        val answers = ConcurrentHashMap<String, PlayAttempt.Ask>()
        val found = HookDispatch.firstInOrder(asked) { (id, rt) ->
            val a = ask(id, rt, Hook.AUDIO_SOURCE, input, timeoutMs, AudioStream::parse)
            answers[id] = PlayAttempt.Ask(id, a.outcome.key, a.ms, detail = a.outcome.detail)
            a.value
        }
        val ms = System.currentTimeMillis() - t0
        // A source cut short because one above it answered was not needed.
        val all = skipped + asked.map { (id, _) -> answers[id] ?: PlayAttempt.Ask(id, Outcome.Skipped().key, ms, "not needed: a source above answered") }
        if (found == null) {
            Logbook.log("audio: no extension had $label ($ms ms)"); misses.record(track.id, ahead)
            attempts.update(attempt) { it.copy(asked = all, fallback = "Spotify: no extension had it") }
            return null
        }
        misses.forget(track.id)
        val (c, a) = found
        log(c.first, "audio for ${track.title} ($ms ms)")
        did(Area.AUDIO, c.first, "Audio of ${track.title} from ${c.second.manifest.name}")
        attempts.update(attempt) { it.copy(asked = all, chosen = c.first, announced = a.qualityLabel()) }
        return a.copy(providerId = c.first)
    }

    /** The last [PlayAttempt]s through the extensions (20), for "Why this source?" and the diagnostic report. */
    val attempts = PlayAttempts(capacity = 20)

    /** Notes what the player did with [trackId]'s latest attempt (its address opened, its format, an error). */
    fun noteAttempt(trackId: String?, change: (PlayAttempt) -> PlayAttempt) {
        if (!trackId.isNullOrBlank()) runCatching { attempts.updateLatest(trackId, change) }
    }

    /** The songs no extension had audio for lately, by Spotify id: forgotten when one is live again or changes. */
    private val misses = AudioMisses()

    /** No extension had [trackId] under a minute ago, when it was to start: it is not asked again. */
    fun missedLately(trackId: String): Boolean = misses.skips(trackId, ahead = false)

    private val isrcs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [track] with its ISRC from Spotify's catalogue when it lacks one (kept; at most 3 s to ask). */
    private suspend fun withIsrc(track: TrackInfo): TrackInfo {
        if (!track.isrc.isNullOrBlank() || track.id.isBlank()) return track
        val known = isrcs[track.id] ?: runCatching {
            kotlinx.coroutines.withTimeout(3_000) { app.myspoti.host.android.MyspotiRuntime.api.catalog.isrc("spotify:track:${track.id}") }
        }.getOrNull()?.let(com.music.spotui.data.offline.OfflineDownloader::normalizeIsrc)?.also { isrcs[track.id] = it }
        return if (known == null) track else track.copy(isrc = known)
    }

    /** For the player's loading thread (never the main one). */
    fun audioBlocking(track: TrackInfo, ahead: Boolean = false): AudioStream? = runBlocking(Dispatchers.IO) { audio(track, ahead = ahead) }

    // ── How a source call ended: outcomes, cooldowns, sign-in ──

    /** The sources set aside after a rate limit, a server down or network failures in a row. */
    private val cooldown = SourceCooldown()

    /** The extensions whose source said the user must sign in, with what it said ("" if nothing). */
    private val _signIns = MutableStateFlow<Map<String, String>>(emptyMap())
    val signIns: StateFlow<Map<String, String>> = _signIns.asStateFlow()

    /** The user dismissed [id]'s sign-in notice (it comes back at its next `sign-in` answer). */
    fun dismissSignIn(id: String) { _signIns.value = _signIns.value - id }

    /** Why [id] is set aside now ("rate-limited, 42 s left"), or null. */
    fun cooldownOf(id: String): String? = cooldown.reason(id)

    /** [providing] without the sources set aside ([cooldown]); each one skipped is logged and added to [skipped]. */
    private fun available(hook: Hook, skipped: MutableList<PlayAttempt.Ask>? = null): List<Pair<String, ExtensionRuntime>> =
        providing(hook).filter { (id, _) ->
            val why = cooldown.reason(id) ?: return@filter true
            Logbook.log("${hook.key}: $id skipped (set aside: $why)")
            skipped?.add(PlayAttempt.Ask(id, Outcome.Skipped().key, 0, "set aside: $why"))
            false
        }

    /** What a hook call gave: its answer (null: none), how it ended, in how many ms. */
    private class Answer<T>(val value: T?, val outcome: Outcome, val ms: Long)

    private suspend fun <T : Any> callHook(id: String, rt: ExtensionRuntime, hook: Hook, input: String, timeoutMs: Long, parse: (String) -> T?): T? =
        ask(id, rt, hook, input, timeoutMs, parse).value

    /**
     * Calls [hook] of [id] and classifies how it ended ([Outcome]). A source hook's outcome is
     * logged and may set the source aside ([cooldown]) or ask the user to sign in ([signIns]).
     */
    private suspend fun <T : Any> ask(id: String, rt: ExtensionRuntime, hook: Hook, input: String, timeoutMs: Long, parse: (String) -> T?): Answer<T> {
        val t0 = System.currentTimeMillis()
        val (value, outcome) = runInterruptible(Dispatchers.IO) { callNow(id, rt, hook, input, timeoutMs, parse) }
        val ms = System.currentTimeMillis() - t0
        if (hook in Outcome.HOOKS) noted(id, hook, outcome, ms)
        return Answer(value, outcome, ms)
    }

    private fun <T : Any> callNow(id: String, rt: ExtensionRuntime, hook: Hook, input: String, timeoutMs: Long, parse: (String) -> T?): Pair<T?, Outcome> {
        busy.merge(id, 1, Int::plus)
        try {
            val raw = rt.call(hook, input, timeoutMs)
            // An outcome it states (`{ outcome }`, feature `results`): its script ran well, nothing is counted.
            raw?.let { Outcome.parse(hook, it) }?.let { stated -> failures.succeeded(id); return null to stated }
            // An answer the app cannot use is a failure too, said in its log (the sandbox logs its own).
            val out = try { raw?.let(parse) } catch (e: ExtensionException) { log(id, "failed: ${e.message}"); throw e }
            failures.succeeded(id)
            return out to (if (out != null) Outcome.Found else Outcome.NotFound)
        } catch (e: app.myspoti.extensions.ExtensionBusy) {
            // It was still busy (a slow lookup before): not its fault, nothing counted.
            log(id, "${hook.key} skipped: ${e.message}")
            return null to Outcome.of(e)
        } catch (e: app.myspoti.extensions.ExtensionFault) {
            // Out of memory, stack overflow, engine crash: turned off at once, the app goes on.
            if (!rt.closed) disable(id, e.message ?: "crashed the sandbox")
            return null to Outcome.Bug(e.message)
        } catch (e: ExtensionException) {
            val o = Outcome.of(e)
            // Stopped meanwhile (updated, turned off): its cut-short call is not counted. A timeout
            // or a network error of its requests is the server's, not the script's: not counted either.
            if (!rt.closed) {
                if (o.counts) failed(id, e.message ?: "failed") else log(id, "${hook.key}: ${o.key}, not counted as a failure")
            }
            return null to o
        } catch (e: InterruptedException) {
            throw e
        } catch (t: Throwable) {
            // Not a failure the sandbox knows: the extension is stopped before it does it again.
            disable(id, t.toString())
            return null to Outcome.Bug(t.toString())
        } finally {
            busy.computeIfPresent(id) { _, n -> (n - 1).takeIf { it > 0 } }
        }
    }

    /** Logs [o] of [id]'s [hook] call; sets the source aside or asks for a sign-in when it says so. */
    private fun noted(id: String, hook: Hook, o: Outcome, ms: Long) {
        log(id, "${hook.key}: ${o.describe()} ($ms ms)")
        cooldown.note(id, o)?.let { log(id, "set aside for ${it / 1000} s (${o.key}): not asked meanwhile") }
        when (o) {
            is Outcome.SignIn -> signInNeeded(id, o.detail)
            Outcome.Found -> if (id in _signIns.value) dismissSignIn(id)
            else -> {}
        }
    }

    private fun signInNeeded(id: String, detail: String?) {
        val first = id !in _signIns.value
        _signIns.value = _signIns.value + (id to detail.orEmpty())
        if (!first) return
        val name = _registry.value.find(id)?.name ?: id
        main.post {
            android.widget.Toast.makeText(ctx, ctx.getString(app.myspoti.engine.R.string.extension_sign_in_needed, name), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ── Whether each extension's source answers: the `status` hook ──

    /** A `status` call: a probe never holds a song's audio longer than this. */
    private const val STATUS_MS = HookBudgets.STATUS
    /** How long a command waits for the extensions the app is still starting. */
    private const val START_WAIT_MS = 20_000L
    /** How long a song picked right after launch waits for them (then plays from Spotify). */
    private const val START_AUDIO_MS = 5_000L
    /** How old a probe may be: asked again after it while the app is in sight or plays, and before a source is asked. */
    private const val STATUS_EVERY_MS = 5 * 60_000L
    private val SOURCE_HOOKS = setOf(Hook.AUDIO_SOURCE, Hook.DOWNLOAD_SOURCE)
    private val health = app.myspoti.extensions.StatusBook()
    /** The hook calls each extension is answering now: a probe would wait behind them, it is not sent. */
    private val busy = ConcurrentHashMap<String, Int>()

    /** The inactive extensions and what their `status` said (their rows and pages show it). */
    private val _statuses = MutableStateFlow<Map<String, app.myspoti.extensions.ExtensionStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, app.myspoti.extensions.ExtensionStatus>> = _statuses.asStateFlow()

    /**
     * Asks [id]'s `status` (at most [STATUS_MS]). `live: false`: it is inactive, skipped for songs'
     * audio and downloads, still on and not counted as failing; live again at the next probe that
     * says so. No answer in time (a busy start), a throw or no `{ live }`: unknown, still used.
     */
    private suspend fun probe(id: String, rt: ExtensionRuntime) {
        if ((busy[id] ?: 0) > 0) return
        // One probe at a time per extension: a second (the one at load, then one before a song) waits for the first.
        val mine = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { probeNow(id, rt) }
        val running = probing.putIfAbsent(id, mine)
        if (running != null) { mine.cancel(); running.join(); return }
        mine.invokeOnCompletion { probing.remove(id, mine) }
        mine.await()
    }

    private val probing = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Unit>>()

    private suspend fun probeNow(id: String, rt: ExtensionRuntime) {
        val t0 = System.currentTimeMillis()
        val s = runInterruptible(Dispatchers.IO) { app.myspoti.extensions.ExtensionStatus.probe(rt, STATUS_MS) }
        if (runtimes[id] !== rt) return
        val ms = System.currentTimeMillis() - t0
        val changed = health.record(id, s)
        if (changed && s.live) misses.clear()
        if (changed) refreshProvided()
        if (changed) log(id, when (s.state) {
            "live" -> "status: live${s.detail?.let { " ($it)" } ?: ""} in $ms ms"
            "inactive" -> "status: inactive: ${s.detail ?: "no detail"} in $ms ms"
            else -> "status: no answer (${s.detail ?: "no detail"}) in $ms ms, still used"
        })
        _statuses.value = health.inactiveNow()
    }

    private fun probeAll() = runtimes.forEach { (id, rt) -> scope.launch { probe(id, rt) } }

    /**
     * Before [hook] is asked: its extensions probed [STATUS_EVERY_MS] ago or more are probed again,
     * together, within [STATUS_MS]. One never probed (its probe at launch is on its way) is not
     * waited for: it is in use until a probe says otherwise.
     */
    private suspend fun probeStale(hook: Hook) {
        val due = _registry.value.extensions.mapNotNull { e ->
            runtimes[e.id]?.takeIf { e.enabled && it.provides(hook) && health.last(e.id) != null && health.stale(e.id, STATUS_EVERY_MS) }?.let { e.id to it }
        }
        if (due.isEmpty()) return
        withTimeoutOrNull(STATUS_MS + 500) { kotlinx.coroutines.coroutineScope { due.map { (id, rt) -> async { probe(id, rt) } }.forEach { it.await() } } }
    }

    /** Every minute, the probes [STATUS_EVERY_MS] old are asked again while the app is in sight or plays. */
    private fun watchStatus() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                if (!com.music.spotui.video.MusicVideoController.inForeground && !SongPlayer.isPlaying()) continue
                // This loop runs on the main thread for the life of the app: one failed round must not end it nor crash it.
                try {
                    runtimes.forEach { (id, rt) -> if (health.stale(id, STATUS_EVERY_MS)) scope.launch { probe(id, rt) } }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logbook.log("ext: status round failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** Each new song to the extensions that listen (`player.read` granted: the sandbox checks). */
    @OptIn(FlowPreview::class)
    private fun watchTracks() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            // Not while a song is shown loading (the extensions asked for it): it is told once it plays.
            androidx.compose.runtime.snapshotFlow { if (SongPlayer.routing) "" else SongPlayer.nowPlaying.value.uri }
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                // The title and the length follow the uri a moment later.
                .debounce(1_500)
                .collect {
                    // A song whose details cannot be read tells nothing, it does not end the watch (nor crash the app).
                    try {
                        val track = currentTrack() ?: return@collect
                        val (origin, providerId) = currentOrigin()
                        val json = PlaybackEvent.trackEventJson(track, origin, providerId)
                        runtimes.forEach { (id, rt) ->
                            scope.launch { runCatching { runInterruptible { rt.emit(ExtensionRuntime.EVENT_TRACK, json, TRACK_MS) } }.onFailure { failed(id, it.toString()) } }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Logbook.log("ext: track event failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
        }
    }

    private val playback = PlaybackTracker()
    private val playbackOut = kotlinx.coroutines.channels.Channel<String>(256, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** The user tapped next ("forward") or previous ("backward"): a `skip` event says which. */
    fun skipAsked(direction: String) = playback.skipAsked(direction, System.currentTimeMillis())

    /**
     * The `playback` events: both players read once a second (on the main thread, where they
     * live), only while an extension listens; delivered in order, one at a time.
     */
    private fun watchPlayback() {
        scope.launch {
            for (json in playbackOut) {
                runtimes.forEach { (id, rt) ->
                    if (rt.listens(ExtensionRuntime.EVENT_PLAYBACK)) runCatching {
                        runInterruptible { rt.emit(ExtensionRuntime.EVENT_PLAYBACK, json, TRACK_MS) }
                    }.onFailure { failed(id, it.toString()) }
                }
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var idle = 0
            var listening = false
            while (true) {
                kotlinx.coroutines.delay(1_000)
                if (runtimes.values.none { it.listens(ExtensionRuntime.EVENT_PLAYBACK) }) {
                    if (listening) { playback.observe(null, System.currentTimeMillis()); listening = false }
                    continue
                }
                listening = true
                val snap = runCatching { playbackSnapshot() }.getOrNull()
                // A blank moment between two songs is not a stop: three seconds of nothing is.
                idle = if (snap == null) idle + 1 else 0
                if (snap == null && idle < 3) continue
                playback.observe(snap, System.currentTimeMillis()).forEach { playbackOut.trySend(it.toJson()) }
            }
        }
    }

    /** What is playing now, in the player that plays (Myspoti's own, or the Web Player); null: nothing. */
    private fun playbackSnapshot(): PlaybackTracker.Snapshot? {
        // A song shown loading while the extensions are asked for it does not play yet.
        if (SongPlayer.routing) return null
        val local = com.music.spotui.di.LocalPlayer
        if (local.active.value) {
            val t = local.current() ?: return null
            val spotifyId = local.spotifyIdOf(t)
            val duration = local.duration()
            val (origin, providerId) = originOf(t)
            return PlaybackTracker.Snapshot(
                key = "local:${t.id}:${t.uri}",
                track = trackInfo(spotifyId, t.title, t.artist, t.album, duration, t.artUri.toString()),
                source = PlaybackEvent.LOCAL, playing = local.isPlaying(), positionMs = local.position(), durationMs = duration,
                contextUri = local.contextUri, shuffle = local.shuffled, origin = origin, providerId = providerId,
            )
        }
        val np = SongPlayer.nowPlaying.value
        if (np.uri.isBlank()) return null
        val info = currentTrack() ?: return null
        return PlaybackTracker.Snapshot(
            key = "spotify:${np.uri}", track = info.copy(uri = np.uri), source = PlaybackEvent.SPOTIFY,
            playing = SongPlayer.isPlaying(), positionMs = SongPlayer.getCurrentPosition(), durationMs = SongPlayer.getDuration(),
            contextUri = np.contextUri, shuffle = SongPlayer.isShuffleOn(),
        )
    }

    /**
     * Where the song playing comes from, for the `track` and `playback` events: `origin` (see
     * [PlaybackEvent.ORIGINS]) and the id of the extension that supplied it (null for Spotify).
     */
    private fun currentOrigin(): Pair<String, String?> {
        val local = com.music.spotui.di.LocalPlayer
        val t = if (local.active.value) local.current() else null
        return if (t == null) PlaybackEvent.ORIGIN_SPOTIFY to null else originOf(t)
    }

    private fun originOf(t: com.music.spotui.data.local.LocalTrack): Pair<String, String?> {
        if (t.uri.scheme == ExtensionAudio.SCHEME) return PlaybackEvent.ORIGIN_AUDIO_SOURCE to ExtensionAudio.providerOf(t.uri.lastPathSegment.orEmpty())
        val offline = com.music.spotui.data.offline.OfflineStore.byLocalId(t.id)
        return if (offline != null) PlaybackEvent.ORIGIN_DOWNLOAD to offline.providerId else PlaybackEvent.ORIGIN_LOCAL_FILE to null
    }

    /** The song playing in Myspoti, as hooks receive it. */
    fun currentTrack(): TrackInfo? {
        val t = runCatching { SongPlayer.currentLyricsTrack() }.getOrNull() ?: return null
        return trackInfo(t.trackId, t.title, t.artist, t.album, t.durationMs, t.coverUrl)
    }

    fun trackInfo(trackId: String, title: String, artist: String, album: String, durationMs: Long, cover: String?): TrackInfo {
        val artists = artist.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        return TrackInfo(
            id = trackId, uri = if (trackId.isBlank()) "" else "spotify:track:$trackId", title = title,
            artist = artists.firstOrNull().orEmpty(), artists = artists, album = album, durationMs = durationMs,
            cover = cover?.takeIf { it.startsWith("http") },
        )
    }

    // ── Repositories ──

    fun addRepository(url: String) = update { r -> if (url in r.repositories) r else r.copy(repositories = r.repositories + url) }

    fun removeRepository(url: String) = update { r -> r.copy(repositories = r.repositories - url) }

    /** Reads a repository's index, and turns off what it blocks. */
    suspend fun catalog(url: String, fresh: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        // Refresh: a throwaway query makes the CDN in front of raw.githubusercontent.com (cached for minutes) answer anew.
        val at = if (fresh) url + (if ('?' in url) "&" else "?") + "t=" + System.currentTimeMillis() else url
        val c = Catalog.parse(download(at, MAX_INDEX).decodeToString())
        _registry.value.extensions.forEach { e ->
            c.blocks(e.id, e.sha256)?.let { b -> if (e.enabled) disable(e.id, "blocked by ${c.name}: ${b.reason.ifBlank { "no reason given" }}") }
        }
        c
    }

    /**
     * The package of a catalogue entry: from GitHub when the entry names a repository (with the
     * index's sha256 checked when it gives one), else from its address, sha256 checked.
     */
    suspend fun fetch(indexUrl: String, entry: Catalog.Entry): ExtensionUpdates.Fetched = withContext(Dispatchers.IO) {
        val repo = entry.repo?.let { app.myspoti.extensions.GitHubRepo.parse(it) }
        if (repo == null) return@withContext download(indexUrl, entry).let { (zip, p) -> ExtensionUpdates.Fetched(zip, p, null) }
        val f = ExtensionUpdates.fromGitHub(repo, expectSha = entry.sha256.ifBlank { null })
        if (f.pkg.manifest.id != entry.id) throw ExtensionException("the package is ${f.pkg.manifest.id}, the store lists ${entry.id}")
        f
    }

    /** The package of a catalogue entry, its sha256 checked before anything of it is read. */
    suspend fun download(indexUrl: String, entry: Catalog.Entry): Pair<ByteArray, ExtensionPackage> = withContext(Dispatchers.IO) {
        val bytes = download(Catalog.resolve(indexUrl, entry.url), ExtensionPackage.MAX_ZIP)
        if (ExtensionPackage.sha256(bytes) != entry.sha256.lowercase()) throw ExtensionException("the package is not the one the repository lists (sha256 differs)")
        val p = ExtensionPackage.read(bytes)
        if (p.manifest.id != entry.id) throw ExtensionException("the package is ${p.manifest.id}, the repository lists ${entry.id}")
        bytes to p
    }

    /** A package at an https address. */
    suspend fun downloadPackage(url: String): Pair<ByteArray, ExtensionPackage> = withContext(Dispatchers.IO) {
        val bytes = download(url, ExtensionPackage.MAX_ZIP)
        bytes to ExtensionPackage.read(bytes)
    }

    /** https only, no cookies, at most [max] bytes. */
    private fun download(url: String, max: Int): ByteArray {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) throw ExtensionException("an https address is needed")
        if (com.music.spotui.data.DataSaver.active) throw ExtensionException("the data saver is on")
        val c = uri.toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000; c.useCaches = false
        c.setRequestProperty("User-Agent", "Myspoti-Extensions/1")
        c.setRequestProperty("Cache-Control", "no-cache"); c.setRequestProperty("Pragma", "no-cache")
        try {
            if (c.responseCode !in 200..299) throw ExtensionException("the server answered ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { s ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw ExtensionException("larger than ${max / 1024} KB")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    // ── Sandboxes ──

    private const val SLOW_LOAD_MS = 10_000L

    /** The sha256 of the package each runtime runs. */
    private val runningSha = ConcurrentHashMap<String, String>()
    private val startLocks = ConcurrentHashMap<String, Any>()
    /** The extensions on at launch have all been started (or failed to). */
    private val started = kotlinx.coroutines.CompletableDeferred<Unit>()

    /**
     * Starts [e] unless that very package already runs. One start of an extension at a time: an
     * update arriving while the app starts the version before waits for it, and the version
     * before, loaded meanwhile, is closed instead of replacing the new one. [probe]: its `status`
     * is asked in the background once it is loaded.
     */
    internal fun start(asked: InstalledExtension, probe: Boolean = true): Unit = synchronized(startLocks.getOrPut(asked.id) { Any() }) {
        // What is installed now: an update that landed while this start waited its turn is the one
        // to run (the entry it was asked with would no longer match the package on the phone).
        val e = _registry.value.find(asked.id) ?: return
        if (!e.enabled) return
        if (runtimes.containsKey(e.id)) { if (runningSha[e.id] == e.sha256) return else stop(e.id) }
        val p = pkg(e.id) ?: return disable(e.id, "its package is missing")
        if (p.sha256 != e.sha256) return disable(e.id, "its package changed on the phone")
        ExtensionUpdates.blocked(e.id, e.sha256)?.let { return disable(e.id, "blocked by Myspoti: ${it.reason.ifBlank { "no reason given" }}") }
        // Unticked back when the prompt allowed it: it waits for the user's OK on its page.
        if (Grants.refused(e, p.manifest).isNotEmpty()) return disable(e.id, "it waits for your OK on its page")
        var rt = ExtensionRuntime(p.manifest, p.script, AndroidHost, e.granted)
        try {
            try {
                rt.start()
            } catch (slow: app.myspoti.extensions.ExtensionTimeout) {
                // Right after an update or a cold start the phone may be busy: once more, with more time.
                rt.close()
                log(e.id, "slow to load: trying once more")
                rt = ExtensionRuntime(p.manifest, p.script, AndroidHost, e.granted, ExtensionRuntime.Limits(loadMs = SLOW_LOAD_MS))
                rt.start()
            }
            // Turned off, removed or updated while it loaded: what loaded is not what should run.
            val now = _registry.value.find(e.id)
            if (now == null || !now.enabled || now.sha256 != e.sha256) { rt.close(); return }
            runtimes[e.id] = rt
            runningSha[e.id] = e.sha256
            misses.clear()
            refreshProvided()
            log(e.id, "loaded ${p.manifest.version} (granted: ${e.granted.sorted().joinToString().ifBlank { "nothing" }})")
            if (onlineMarker(e.id).exists() && isOnline()) deliverOnline(e.id)
            if (probe) scope.launch { probe(e.id, rt) }
        } catch (t: Throwable) {
            rt.close()
            disable(e.id, "did not load: ${t.message ?: t}")
        }
    }

    internal fun stop(id: String) {
        mirrorGone(id)
        runningSha.remove(id)
        runtimes.remove(id)?.close(); ExtensionAuth.cancel(id)
        health.forget(id); _statuses.value = health.inactiveNow()
        cooldown.forget(id); dismissSignIn(id)
        misses.clear()
        refreshProvided()
    }

    /** Delivers a sign-in page's outcome to [id] ([ExtensionAuth]). */
    internal fun authDone(id: String, result: app.myspoti.extensions.AuthResult) {
        val rt = runtimes[id] ?: return
        log(id, "sign-in ${result.id}: ${if (result.url != null) "reached the callback" else result.error}")
        if (result.url != null) dismissSignIn(id)
        val json = kotlinx.serialization.json.Json.encodeToString(app.myspoti.extensions.AuthResult.serializer(), result)
        scope.launch { runCatching { runInterruptible { rt.emit(ExtensionRuntime.EVENT_AUTH, json, AUTH_MS) } }.onFailure { failed(id, it.toString()) } }
    }

    private fun failed(id: String, message: String) {
        setError(id, message)
        if (failures.failed(id)) disable(id, "failed 3 times in a row: $message")
    }

    /** Turns [id] off with [reason], shown on its page and in a short message. */
    fun disable(id: String, reason: String) {
        stop(id)
        val e = _registry.value.find(id) ?: return
        update { it.put(e.copy(enabled = false, disabledReason = reason)) }
        setError(id, reason)
        log(id, "turned off: $reason")
        main.post {
            android.widget.Toast.makeText(ctx, ctx.getString(app.myspoti.engine.R.string.extension_turned_off, e.name, reason), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun setError(id: String, message: String) { _errors.value = _errors.value + (id to message) }
    private fun clearError(id: String) { _errors.value = _errors.value - id }

    internal fun update(change: (Registry) -> Registry) {
        synchronized(lock) {
            val r = change(_registry.value)
            _registry.value = r
            runCatching { Registry.write(File(dir, "registry.json"), r) }.onFailure { Logbook.log("ext: registry not saved: ${it.message}") }
        }
        // Turned on or off, the order of the sources, the active one: who gives what may have changed.
        refreshProvided()
    }

    fun log(id: String, message: String) {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logOf(id).add("$stamp $message")
        logTick.value++
        Logbook.log("ext $id: $message")
    }

    private fun logOf(id: String) = logs.getOrPut(id) { ExtensionLog(File(dir, "$id/log.txt")) }
    private fun settingsOf(id: String) = settings.getOrPut(id) { FileStore(File(dir, "$id/settings.json")) }
    private fun storeOf(id: String) = stores.getOrPut(id) { FileStore(File(dir, "$id/storage.json")) }

    internal fun writeBytesAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    // ── What the sandbox reaches of the app ──

    private object AndroidHost : Host {
        private val dispatcher by lazy {
            SpotifyDispatcher(MyspotiRuntime.api, AppRaw, app.myspoti.extensions.PlayerMedia { app.myspoti.spotify.bridge.MediaManifests.of(it) })
        }

        override fun http(extensionId: String, request: HttpRequest): HttpResponse {
            if (com.music.spotui.data.DataSaver.active) throw IllegalStateException("the data saver is on")
            return BasicHttp.send(request, userAgent(extensionId))
        }

        override fun log(extensionId: String, message: String) = Extensions.log(extensionId, message)

        override fun storage(extensionId: String): Storage = storeOf(extensionId)

        override fun setting(extensionId: String, key: String): String? = settingsOf(extensionId).get(key)

        override fun nowPlaying(): String? = currentTrack()?.toJson()

        override fun control(extensionId: String, action: String, argument: Long?): Boolean {
            if (!arbitrate(extensionId, action)) return false
            main.post {
                when (action) {
                    "play" -> SongPlayer.play(by = "extension")
                    "pause" -> SongPlayer.pause(by = "extension")
                    "next" -> SongPlayer.next()
                    "previous" -> SongPlayer.previous()
                    "seek" -> argument?.let(SongPlayer::seekTo)
                }
            }
            return true
        }

        override fun notify(extensionId: String, text: String) {
            val name = _registry.value.find(extensionId)?.name ?: extensionId
            Extensions.log(extensionId, "notify: $text")
            main.post { android.widget.Toast.makeText(ctx, "$name: $text", android.widget.Toast.LENGTH_SHORT).show() }
        }

        override suspend fun spotify(extensionId: String, call: SpotifyCall): String {
            if (!MyspotiRuntime.isStarted) throw IllegalStateException("no Spotify account")
            // Playback commands go through the arbiter like myspoti.player's; a dropped one answers null.
            if (call.area == "player" && call.method == "setLocalMute") return setLocalMute(extensionId, call)
            if (call.area == "player" && call.method in ARBITRATED && !arbitrate(extensionId, call.method)) return "null"
            if (call.area == "devices" && call.method != "list" && !arbitrate(extensionId, "transfer")) return "null"
            return dispatcher.execute(call)
        }

        private val ARBITRATED = setOf("play", "pause", "resume", "seek", "next", "previous", "setShuffle", "setRepeat", "skipTo", "setLocalMute")

        /** The host's side of the mute: only while the local player plays, one extension, rate limited, auto-cleared. */
        private suspend fun setLocalMute(extensionId: String, call: SpotifyCall): String {
            val on = kotlinx.serialization.json.Json.parseToJsonElement(call.argsJson).let { it as? kotlinx.serialization.json.JsonArray }
                ?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "true"
            if (!on) {
                if (!Extensions.mirror.disable(extensionId)) return "null"
                try { return dispatcher.execute(call) } finally { Extensions.mirrorEndedBy(extensionId) }
            }
            // Myspoti's own Connect mirror has the page already ("Count extension plays on Spotify"): it wins.
            if (com.music.spotui.di.ConnectMirror.running) {
                val why = "setLocalMute: Myspoti already plays this song on Spotify (Count extension plays on Spotify)"
                Extensions.log(extensionId, "refused: $why"); throw IllegalStateException(why)
            }
            if (!arbitrate(extensionId, "setLocalMute")) return "null"
            val (playing, uri, dur) = SongPlayer.localSnapshot3()
            Extensions.mirror.enable(extensionId, playing, uri, dur)?.let { Extensions.log(extensionId, "refused: $it"); throw IllegalStateException(it) }
            return try { dispatcher.execute(call).also { Extensions.mirrorStartedBy(extensionId) } }
            catch (t: Throwable) { Extensions.mirror.disable(extensionId); throw t }
        }

        override fun refresh(extensionId: String, surface: String) { blocksTick.value++ }

        override fun postToPage(extensionId: String, json: String) { main.post { pageSinks[extensionId]?.invoke(json) } }

        override fun spotifyMutation(name: String): Boolean = app.myspoti.spotify.gql.isMutation(name)

        override suspend fun confirm(extensionId: String, text: String): Boolean {
            val ok = ExtensionConfirm.ask(extensionId, text)
            Extensions.log(extensionId, if (ok) "the user allowed: ${text.lineSequence().first()}" else "the user refused: ${text.lineSequence().first()}")
            return ok
        }

        override fun rawGrants(extensionId: String) = _registry.value.find(extensionId)?.rawGrants.orEmpty()

        override suspend fun confirmWrite(extensionId: String, text: String, offer: app.myspoti.extensions.RawGrant): app.myspoti.extensions.Consent {
            val c = ExtensionConfirm.ask(extensionId, text, offer)
            if (c == app.myspoti.extensions.Consent.ALWAYS) addRawGrant(extensionId, offer)
            else Extensions.log(extensionId, "the user ${if (c == app.myspoti.extensions.Consent.ONCE) "allowed once" else "refused"}: $offer")
            return c
        }

        override fun openAuth(extensionId: String, request: app.myspoti.extensions.AuthRequest): String = ExtensionAuth.open(extensionId, request)

        override fun whenOnline(extensionId: String) = askOnline(extensionId)

        override fun attestation(): app.myspoti.extensions.Attestation = ExtensionAttestation
    }

    /** The raw door: the Web Player's GraphQL, page services and Spotify URLs, as the account. */
    private object AppRaw : SpotifyRaw {
        private val api get() = MyspotiRuntime.spotify
        override suspend fun gql(operation: String, variables: JsonObject): JsonElement = api.raw.gql(operation, variables)
        override fun operations(): Set<String> = api.raw.operations
        override suspend fun service(service: String, method: String, args: List<JsonElement>): JsonElement = api.raw.service(service, method, *args.toTypedArray())
        override suspend fun get(url: String): JsonElement? = api.raw.get(url)
        override fun isMutation(operation: String): Boolean = app.myspoti.spotify.gql.isMutation(operation)
        override suspend fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>): JsonElement =
            api.raw.request(method, url, body, headers).let { app.myspoti.extensions.RawRequest.response(it.status, it.headers, it.body) }
    }

    /** Lyrics asked with a time limit of their own, for the app's lyrics resolver. */
    suspend fun lyricsFor(trackId: String, title: String, artist: String, album: String, durationMs: Long, cover: String?): Lyrics? =
        if (!hasLyrics()) null
        else withTimeoutOrNull(LYRICS_MS + 1_000) { lyrics(trackInfo(trackId, title, artist, album, durationMs, cover)) }

    // ── Debug and beta command line (com.music.spotui.cli) ──

    /** [hook] of [id] alone, with [input]: its answer as JSON text, null when it has none. */
    internal suspend fun callHook(id: String, hook: Hook, input: String, timeoutMs: Long = DOWNLOAD_MS): String? {
        // Asked while the app starts (a command can be what starts it): its extensions load first.
        if (!runtimes.containsKey(id) && _registry.value.find(id)?.enabled == true) withTimeoutOrNull(START_WAIT_MS) { started.await() }
        val rt = runtimes[id] ?: throw ExtensionException("$id is not running")
        if (!rt.provides(hook)) throw ExtensionException("$id does not provide ${hook.key}")
        return runInterruptible(Dispatchers.IO) { rt.call(hook, input, timeoutMs) }
    }

    /** [id]'s storage as stored (key → JSON text), for the command line's masked view. */
    internal fun storageValues(id: String): Map<String, String> = storeOf(id).all()
}
