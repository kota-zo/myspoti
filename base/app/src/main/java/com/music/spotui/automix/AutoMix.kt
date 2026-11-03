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
        Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, value).apply()
        journal("AutoMix ${if (value) "on" else "off"}")
    }

    /** Next mixes the next song in on the beat (see LocalPlayer.mixOnSkip); off, Next cuts. On by default. */
    var mixOnNext by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_on_next", true))
        private set
    fun changeMixOnNext(value: Boolean) { mixOnNext = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_on_next", value).apply() }

    /** No mix between two songs that follow each other on their album. On by default. */
    var albumOrder by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_album_order", true))
        private set
    fun changeAlbumOrder(value: Boolean) { albumOrder = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_album_order", value).apply() }

    /** The mix shown in the player: its stretch on the seek bar, the cover and its glow pulsing with it. On by default. */
    var showInPlayer by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_show", true))
        private set
    /** Mix length: 0 short, 1 as the music says, 2 long. */
    var mixLength by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_length", 1).coerceIn(0, 2))
        private set
    fun changeMixLength(value: Int) { mixLength = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_length", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** How far the next song's tempo may be bent to match: 0 strict (4 %), 1 normal (8 %), 2 wide (12 %). */
    var tempoRange by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_tempo", 1).coerceIn(0, 2))
        private set
    fun changeTempoRange(value: Int) { tempoRange = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_tempo", value).apply(); com.music.spotui.di.LocalPlayer.autoMixSettingsChanged() }

    /** The DJ EQ: the bass swap and the filters (each player's DynamicsProcessing); off, only the volumes move. */
    var djEq by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getBoolean("automix_eq", true))
        private set
    fun changeDjEq(value: Boolean) { djEq = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putBoolean("automix_eq", value).apply() }

    /** How much the player moves with the mix: 0 not at all (the stretch on the bar stays), 1 subtle, 2 strong. */
    var pulseLevel by mutableStateOf(Graph.app.getSharedPreferences(PREFS, 0).getInt("automix_pulse", 2).coerceIn(0, 2))
        private set
    fun changePulseLevel(value: Int) { pulseLevel = value; Graph.app.getSharedPreferences(PREFS, 0).edit().putInt("automix_pulse", value).apply() }

    /** The planner's settings from the ones above. */
    fun settings() = MixPlanner.Settings(maxStretch = doubleArrayOf(0.04, 0.08, 0.12)[tempoRange], lengthBias = mixLength - 1)

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
    private val failed = HashSet<String>()
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
        // Saving battery (the phone's saver, or under 20 % unplugged): the song playing and the next only.
        val songs = if (frugal()) uris.take(2) else uris
        wanted = songs.map(::key).toSet()
        for (uri in songs) {
            val key = key(uri)
            synchronized(memory) { if (memory.containsKey(key) || key in failed || !asked.add(key)) continue }
            worker.execute {
                try {
                    if (key !in wanted) return@execute
                    val a = load(key) ?: analyse(uri, key)
                    if (a != null) { synchronized(memory) { memory[key] = a }; ready() }
                } finally { synchronized(memory) { asked -= key } }
            }
        }
    }

    private fun analyse(uri: Uri, key: String): TrackAnalysis? {
        // A stream is read a second time for the analysis: on unmetered networks only.
        if (uri.scheme == ExtensionAudio.SCHEME) {
            if (com.music.spotui.data.DataSaver.metered) { journal("not analysed (metered network): ${uri.lastPathSegment}"); return null }
            // DASH answers too: the data source reads them as one file, as the player does.
            val s = runCatching { ExtensionAudio.resolve(uri, ahead = true) }.getOrNull()
            if (s == null) { synchronized(memory) { failed += key }; journal("not analysed (no stream): ${uri.lastPathSegment}"); return null }
        }
        val t0 = System.currentTimeMillis()
        return try {
            val cancelled = { key !in wanted }
            val pcm = TrackDecoder.decode(uri, cancelled)
            val t1 = System.currentTimeMillis()
            val a = Analyzer.analyze(pcm.mid, pcm.side, model(), cancelled, if (frugal()) 2 else if (charging()) THREADS else minOf(THREADS, 3))
            save(key, a)
            journal(String.format("analysed %s: %.1f BPM%s, %d bars, key %d, decode %d ms, analysis %d ms", label(uri), a.bpm,
                if (a.steady) " steady" else (if (a.tail != null) " (tail grid)" else " (no grid)"), a.bars, a.key, t1 - t0, System.currentTimeMillis() - t1) +
                (if (a.note.isNotEmpty()) " | grids refused: ${a.note}" else ""))
            a
        } catch (e: InterruptedException) {
            null
        } catch (e: Throwable) {
            synchronized(memory) { failed += key }
            journal("analysis failed for ${label(uri)}: ${e.javaClass.simpleName} ${e.message?.take(120)}")
            null
        }
    }

    private fun charging(): Boolean =
        runCatching { Graph.app.getSystemService(android.os.BatteryManager::class.java).isCharging }.getOrDefault(false)

    /** The phone saving battery: its saver on, or under 20 % and unplugged. */
    private fun frugal(): Boolean = runCatching {
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

    /** An extension stream is keyed by its track (its address changes); a file by its address. */
    fun key(uri: Uri): String {
        val id = if (uri.scheme == ExtensionAudio.SCHEME) "ext:" + uri.lastPathSegment.orEmpty() else uri.toString()
        return MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun label(uri: Uri) = uri.lastPathSegment?.takeLast(48).orEmpty()

    fun clearCache() {
        synchronized(memory) { memory.clear(); failed.clear() }
        dir().listFiles()?.forEach { it.delete() }
    }
}
