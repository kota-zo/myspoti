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
        wanted = uris.map(::key).toSet()
        for (uri in uris) {
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
            val s = runCatching { ExtensionAudio.resolve(uri, ahead = true) }.getOrNull()
            if (s == null || s.manifest != null) { synchronized(memory) { failed += key }; journal("not analysed (${if (s == null) "no stream" else "DASH stream"}): ${uri.lastPathSegment}"); return null }
        }
        val t0 = System.currentTimeMillis()
        return try {
            val cancelled = { key !in wanted }
            val pcm = TrackDecoder.decode(uri, cancelled)
            val t1 = System.currentTimeMillis()
            val a = Analyzer.analyze(pcm.mid, pcm.side, model(), cancelled, THREADS)
            save(key, a)
            journal(String.format("analysed %s: %.1f BPM%s, %d bars, key %d, decode %d ms, analysis %d ms", label(uri), a.bpm,
                if (a.steady) " steady" else (if (a.tail != null) " (tail grid)" else " (no grid)"), a.bars, a.key, t1 - t0, System.currentTimeMillis() - t1))
            a
        } catch (e: InterruptedException) {
            null
        } catch (e: Throwable) {
            synchronized(memory) { failed += key }
            journal("analysis failed for ${label(uri)}: ${e.javaClass.simpleName} ${e.message?.take(120)}")
            null
        }
    }

    /** Cores for one analysis: all but two (the player and the screen keep theirs). */
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

    fun planFor(a: TrackAnalysis, b: TrackAnalysis): MixPlan = MixPlanner.plan(a, b)

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
