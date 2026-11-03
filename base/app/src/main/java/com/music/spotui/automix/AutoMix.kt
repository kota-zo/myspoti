/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.automix.Analyzer
import app.myspoti.automix.BeatThis
import app.myspoti.automix.MixPlan
import app.myspoti.automix.MixPlanner
import app.myspoti.automix.TrackAnalysis
import com.music.spotui.di.Graph
import com.music.spotui.extensions.ExtensionAudio
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * AutoMix: DJ-style transitions between the local player's songs (extension streams, downloads,
 * the phone's files). Spotify songs play in the Web Player, whose audio the app never has.
 *
 * Each song is analysed once in the background (see [Analyzer]) and the result kept in the cache;
 * [planFor] gives the transition between two analysed songs. LocalPlayer plays it (see its AutoMix part).
 */
object AutoMix {
    private const val PREFS = "myspoti_prefs"
    private const val KEY = "automix_on"

    /** The setting, Compose state (off by default). */
    var on by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean(KEY, false))
        private set

    fun set(value: Boolean) {
        on = value
        if (!value) cancelRequests()
        Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, value).apply()
        // The Spotify songs' fade follows: a short one with AutoMix on (see TransitionPref).
        com.music.spotui.data.preferences.applyCrossfade(com.music.spotui.data.preferences.CrossfadePref.seconds)
        journal("AutoMix ${if (value) "on" else "off"}")
    }

    /** Next mixes the next song in on the beat (see LocalPlayer.mixOnSkip); off, Next cuts. On by default. */
    var mixOnNext by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_on_next", true))
        private set
    fun changeMixOnNext(value: Boolean) { mixOnNext = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_on_next", value).apply() }

    /** No mix between two songs that follow each other on their album. On by default. */
    var albumOrder by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_album_order", true))
        private set
    fun changeAlbumOrder(value: Boolean) { albumOrder = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_album_order", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** The mix shown in the player: its stretch on the seek bar, the cover and its glow pulsing with it. On by default. */
    var showInPlayer by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_show", true))
        private set
    /** Mix length: 0 short, 1 as the music says, 2 long. */
    var mixLength by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_length", 1).coerceIn(0, 2))
        private set
    fun changeMixLength(value: Int) { mixLength = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_length", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** A DJ session may leave at an earlier musical section; independent of overlap length. */
    var sessionDj by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_session_dj", true))
        private set
    fun changeSessionDj(value: Boolean) { sessionDj = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_session_dj", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** How far the next song's tempo may be bent to match: 0 strict (4 %), 1 normal (8 %), 2 wide (12 %). */
    var tempoRange by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_tempo", 1).coerceIn(0, 2))
        private set
    fun changeTempoRange(value: Int) { tempoRange = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_tempo", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** The DJ EQ: the bass swap and the filters (each player's DynamicsProcessing); off, only the volumes move. */
    var djEq by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_eq", true))
        private set
    fun changeDjEq(value: Boolean) { djEq = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_eq", value).apply(); com.music.spotui.di.LocalPlayer.autoMixEqChanged() }

    /** How much the player moves with the mix: 0 not at all (the stretch on the bar stays), 1 subtle, 2 strong. */
    var pulseLevel by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_pulse", 2).coerceIn(0, 2))
        private set
    fun changePulseLevel(value: Int) { pulseLevel = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_pulse", value).apply() }

    /**
     * The transition style: 0 beat blend (beat-matched where both grids allow, else a cut on the beat
     * or a fade), 1 always a cut on A's downbeat (a fade when A has no grid), 2 always a plain fade.
     */
    var style by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_style", 0).coerceIn(0, 2))
        private set
    fun changeStyle(value: Int) { style = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_style", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** Harmonic mixing: the songs' keys weigh strongly in the choice of the mix; off, they are ignored. On by default. */
    var harmonic by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_harmonic", true))
        private set
    fun changeHarmonic(value: Boolean) { harmonic = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_harmonic", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** Podcast episodes and audiobooks mixed like songs; off (the default), they start and end whole. */
    var mixPodcasts by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_podcasts", false))
        private set
    fun changeMixPodcasts(value: Boolean) { mixPodcasts = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_podcasts", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** Extension streams analysed on mobile data too (each is read a second time: about the song's size again). On by default. */
    var analyzeOnMobileData by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_mobile_data", true))
        private set
    fun changeAnalyzeOnMobileData(value: Boolean) {
        analyzeOnMobileData = value
        Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_mobile_data", value).apply()
        // The songs refused on mobile data are asked again.
        if (value) synchronized(memory) { refused.values.removeAll { it == Reason.MobileData } }
        com.music.spotui.di.LocalPlayer.autoMixSettingsChanged()
    }

    /**
     * The planner's settings from the ones above. Mix length in bars: short 4–8, balanced 8–16 (as
     * the music says), long 12–32 (up to 90 seconds); the lower end is a preference (a shorter mix only
     * when no longer one fits the songs), the upper one a limit. A plain fade lasts 4, 8 or 14 s.
     */
    fun settings() = MixPlanner.Settings(
        maxStretch = doubleArrayOf(0.04, 0.12, 0.16)[tempoRange],
        maxSec = if (mixLength == 2) 90.0 else 48.0,
        lengthBias = mixLength - 1,
        style = arrayOf(MixPlan.Style.BLEND, MixPlan.Style.CUT, MixPlan.Style.FADE)[style],
        minBars = intArrayOf(4, 8, 12)[mixLength],
        maxBars = intArrayOf(8, 16, 32)[mixLength],
        keyWeight = if (harmonic) 2.0 else 0.0,
        smoothSec = doubleArrayOf(4.0, 8.0, 14.0)[mixLength],
        filterSec = doubleArrayOf(6.0, 10.0, 14.0)[mixLength],
        djMode = sessionDj,
    )

    // ── Why a song is not mixed (for the screens) ──

    /** Why the next transition is not an AutoMix one. */
    enum class Reason {
        /** AutoMix is off. */
        Off,
        /** A Spotify song: it plays in the Web Player, whose audio the app never has. */
        SpotifyStream,
        /** The songs play on a Cast device. */
        Cast,
        /** A podcast episode or an audiobook, with "Mix podcasts" off. */
        Podcast,
        /** Two songs that follow each other on their album, with "Keep albums whole" on. */
        Album,
        /** A stream not analysed on mobile data ("Analyze on mobile data" off, or the data saver). */
        MobileData,
        /** Not analysed in time while saving energy (the phone's saver, the app's, or a low battery). */
        Frugal,
        /** Not analysed in time, or the analysis failed: the songs change with a plain fade. */
        NotAnalyzed,
    }

    /** The reason LocalPlayer found for the next pair (null: it is mixed, or still being analysed). */
    internal val pairReason = mutableStateOf<Reason?>(null)

    /**
     * Why the song playing does not mix into the next one, null when it does (or will, once
     * analysed). Compose state: a screen reading it redraws when it changes.
     */
    fun whyNot(): Reason? = when {
        !on -> Reason.Off
        com.music.spotui.cast.CastPlayback.device.value != null || com.music.spotui.cast.CastPlayback.active -> Reason.Cast
        !com.music.spotui.di.LocalPlayer.active.value -> Reason.SpotifyStream
        else -> pairReason.value
    }

    /** Why [uri] has no analysis and will get none for now (null: ready, or under way). */
    fun missing(uri: Uri): Reason? = synchronized(memory) { refused[requestId(uri)] }

    fun changeShowInPlayer(value: Boolean) { showInPlayer = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_show", value).apply() }

    // ── Journal (last lines, for the command line and the stats sheet) ──

    private val lines = ArrayDeque<String>()
    fun journal(line: String) {
        app.myspoti.host.android.Logbook.log("automix: $line")
        synchronized(lines) { lines.addLast(line); while (lines.size > 60) lines.removeFirst() }
    }
    fun journalLines(): List<String> = synchronized(lines) { lines.toList() }

    // ── Analysis ──

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "automix").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    private val memory = object : LinkedHashMap<String, TrackAnalysis>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysis>?) = size > 24
    }
    private val asked = HashSet<String>()
    private val retry = AnalysisRetry()
    private val fileVersions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val streamKeys = ArrayDeque<Pair<app.myspoti.extensions.AudioStream, String>>()
    private val baseKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var cacheEpoch = 0L
    /** Songs without an analysis for now, and why (shown through [missing]). */
    private val refused = HashMap<String, Reason>()
    @Volatile private var wanted: Set<String> = emptySet()

    /** The analysis of [uri] if it is ready (memory or cache), else null. */
    fun analysis(uri: Uri): TrackAnalysis? {
        val key = key(uri)
        synchronized(memory) { memory[key]?.let { return it } }
        return null
    }

    /**
     * Asks for the analysis of [uris] (the song playing and the next ones), dropping the asks for
     * any other song; [ready] runs on the worker when one is done.
     */
    fun request(uris: List<Uri>, ready: () -> Unit) {
        // Three songs at most; cached answers for B/C are loaded before A's expensive analysis.
        val songs = uris.distinctBy(::requestId).take(if (frugal()) 2 else 3)
        wanted = songs.map(::requestId).toSet()
        val epoch = cacheEpoch
        val jobs = songs.filter { uri ->
            val id = requestId(uri)
            synchronized(memory) { retry.allowed(key(uri), android.os.SystemClock.elapsedRealtime()) && asked.add(id) }
        }
        if (jobs.isEmpty()) return
        worker.execute {
            val pending = ArrayList<Uri>()
            try {
                for (uri in jobs) {
                    if (epoch != cacheEpoch || requestId(uri) !in wanted) continue
                    refreshFileVersion(uri)
                    prepareStreamKey(uri)
                    val k = key(uri)
                    if (synchronized(memory) { memory.containsKey(k) }) continue
                    val cached = if (uri.scheme != ExtensionAudio.SCHEME || ExtensionAudio.streamOf(uri.lastPathSegment.orEmpty()) != null) load(k) else null
                    if (cached != null) { synchronized(memory) { memory[k] = cached; refused -= requestId(uri) }; ready() }
                    else pending += uri
                }
                for (uri in pending) {
                    val id = requestId(uri)
                    if (epoch != cacheEpoch || id !in wanted) continue
                    val result = analyse(uri, epoch)
                    if (result != null && epoch == cacheEpoch && id in wanted && result.first == key(uri)) {
                        synchronized(memory) { memory[result.first] = result.second; refused -= id; retry.succeeded(result.first) }
                        ready()
                    }
                }
            } finally { synchronized(memory) { jobs.forEach { asked -= requestId(it) } } }
        }
    }

    private fun analyse(uri: Uri, epoch: Long): Pair<String, TrackAnalysis>? {
        val id = requestId(uri)
        val cancelled = { epoch != cacheEpoch || id !in wanted }
        var cacheKey = key(uri)
        try {
            if (uri.scheme == ExtensionAudio.SCHEME) {
                val saver = com.music.spotui.data.DataSaver.active
                if (com.music.spotui.data.DataSaver.metered && (saver || !analyzeOnMobileData)) {
                    synchronized(memory) { refused[id] = Reason.MobileData }
                    return null
                }
                if (ExtensionAudio.resolve(uri, ahead = true) == null) throw java.io.IOException("no stream")
                prepareStreamKey(uri)
                cacheKey = key(uri)
                // A source had not answered at the cache pass: its old analysis may now be usable.
                load(cacheKey)?.let { return cacheKey to it }
            }
            if (cancelled()) return null
            val t0 = System.currentTimeMillis()
            val pcm = TrackDecoder.decode(uri, cancelled)
            val t1 = System.currentTimeMillis()
            val a = Analyzer.analyze(pcm.mid, pcm.side, model(), cancelled, if (frugal()) 2 else if (charging()) THREADS else minOf(THREADS, 3))
            if (cancelled() || cacheKey != key(uri)) return null
            save(cacheKey, a)
            journal(String.format("analysed %s: %.1f BPM%s, %d bars, key %d, decode %d ms, analysis %d ms", label(uri), a.bpm,
                if (a.steady) " steady" else (if (a.tail != null) " (tail grid)" else " (no grid)"), a.bars, a.key, t1 - t0, System.currentTimeMillis() - t1) +
                (if (a.note.isNotEmpty()) " | grids refused: ${a.note}" else ""))
            return cacheKey to a
        } catch (e: Exception) {
            // MediaExtractor can wrap the data source's cancellation IOException.
            if (cancelled() || e is InterruptedException) return null
            synchronized(memory) { retry.failed(cacheKey, android.os.SystemClock.elapsedRealtime()); refused[id] = Reason.NotAnalyzed }
            journal("analysis deferred for ${label(uri)}: ${e.javaClass.simpleName} ${e.message?.take(120)}")
            return null
        }
    }

    private fun charging(): Boolean =
        runCatching { Graph.app.getSystemService(android.os.BatteryManager::class.java).isCharging }.getOrDefault(false)

    /**
     * Saving energy: the app's own energy saver or its data saver applying, the phone's battery
     * saver, or under 20 % and unplugged.
     */
    internal fun frugal(): Boolean = app.myspoti.power.EnergySettings.state.value.manualSaver || com.music.spotui.data.DataSaver.active || runCatching {
        val power = Graph.app.getSystemService(android.os.PowerManager::class.java)
        val battery = Graph.app.getSystemService(android.os.BatteryManager::class.java)
        power.isPowerSaveMode || (!battery.isCharging && battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0 until 20)
    }.getOrDefault(false)

    /** Cores for one analysis, plugged in: all but two (the player and the screen keep theirs); three on battery, two when saving it. */
    private val THREADS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    private var beatThis: BeatThis? = null
    private var modelFailed = false

    /** Beat This, loaded once on the worker (null if it cannot run here: the onsets then decide alone). */
    private fun model(): BeatThis? {
        if (beatThis != null || modelFailed) return beatThis
        return try {
            BeatThis(Graph.app.assets.open("automix/beat-this-small0-int8.onnx").use { it.readBytes() }, THREADS).also { beatThis = it }
        } catch (e: Throwable) {
            modelFailed = true
            journal("Beat This unavailable: ${e.javaClass.simpleName} ${e.message?.take(120)}")
            null
        }
    }

    // ── Plans ──

    fun planFor(a: TrackAnalysis, b: TrackAnalysis): MixPlan = MixPlanner.plan(a, b, settings())
    fun planAfter(a: TrackAnalysis, b: TrackAnalysis, earliestExit: Double): MixPlan? = MixPlanner.planAfter(a, b, earliestExit, settings())

    // ── Cache ──

    private fun dir() = File(Graph.app.cacheDir, "automix4").apply { mkdirs() }

    private fun load(key: String): TrackAnalysis? =
        runCatching { File(dir(), key).takeIf { it.exists() }?.inputStream()?.buffered()?.use { TrackAnalysis.read(it) } }.getOrNull()

    private fun save(key: String, a: TrackAnalysis) {
        runCatching {
            val f = File(dir(), key)
            val tmp = File(dir(), "$key.tmp")
            tmp.outputStream().buffered().use { a.write(it) }
            tmp.renameTo(f)
        }
    }

    private fun requestId(uri: Uri): String =
        if (uri.scheme == ExtensionAudio.SCHEME) "ext:" + uri.lastPathSegment.orEmpty() else uri.toString()

    /** No file I/O or large manifest hashing on the player/UI thread. */
    fun key(uri: Uri): String {
        val id = requestId(uri)
        if (uri.scheme == ExtensionAudio.SCHEME) {
            val stream = ExtensionAudio.streamOf(uri.lastPathSegment.orEmpty())
            if (stream != null) return synchronized(streamKeys) {
                streamKeys.firstOrNull { it.first === stream }?.second ?: "$id:pending:${System.identityHashCode(stream)}"
            }
        }
        return fileVersions[id] ?: baseKeys.getOrPut(id) { digest(id) }
    }

    private fun prepareStreamKey(uri: Uri) {
        if (uri.scheme != ExtensionAudio.SCHEME) return
        val stream = ExtensionAudio.streamOf(uri.lastPathSegment.orEmpty()) ?: return
        synchronized(streamKeys) { if (streamKeys.any { it.first === stream }) return }
        // Signed query strings expire; provider, recording path and audio metadata do not.
        val k = digest(AnalysisIdentity.stream(requestId(uri), stream.providerId, stream.durationMs, stream.codec, stream.source, stream.url, stream.manifest))
        synchronized(streamKeys) {
            streamKeys.addLast(stream to k)
            while (streamKeys.size > 24) streamKeys.removeFirst()
        }
    }

    private fun digest(id: String): String = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)

    /** Only the worker stats files; replacing an existing file invalidates its analysis. */
    private fun refreshFileVersion(uri: Uri) {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return)
            fileVersions[requestId(uri)] = digest(AnalysisIdentity.file(uri.toString(), file.length(), file.lastModified()))
        } else if (uri.scheme == "content") {
            runCatching {
                Graph.app.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) fileVersions[requestId(uri)] = digest(AnalysisIdentity.file(uri.toString(), cursor.getLong(0), cursor.getLong(1)))
                }
            }
        }
    }

    fun cancelRequests() { wanted = emptySet() }

    fun sourcesChanged() {
        synchronized(memory) { retry.clear(); refused.clear() }
    }

    private fun label(uri: Uri) = uri.lastPathSegment?.takeLast(48).orEmpty()

    /** Off the main thread: every song analysed is dropped (they are analysed again when played). */
    fun clearCache() {
        cacheEpoch++
        synchronized(memory) { memory.clear(); retry.clear(); refused.clear() }
        fileVersions.clear()
        dir().listFiles()?.forEach { it.delete() }
    }

    /** The space the analyses take on disk, in bytes (off the main thread). */
    fun cacheBytes(): Long = dir().listFiles()?.sumOf { it.length() } ?: 0L
}
