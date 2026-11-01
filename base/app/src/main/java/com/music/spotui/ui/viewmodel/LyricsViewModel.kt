package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.LyricTranslate
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.data.entity.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsViewModel constructor() : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Loaded(
            val lyrics: Lyrics,
            val showTranslation: Boolean = false,
            val translations: List<String>? = null,
            val translationLanguage: String? = null,
            val sourceLanguage: String? = null,
            val translating: Boolean = false,
            val translationChecked: Boolean = false,
            val translationRequested: Boolean = false,
            /** [translations] are the source's own (human, from the lyrics), not a machine translation. */
            val translationBySource: Boolean = false,
        ) : State()
        object NotFound : State()
    }

    companion object {
        /** How many consecutive lines one share takes (Spotify's own card takes five). */
        const val MAX_SHARED_LINES = 6

        /** Whether [source] is [target]'s language, regions apart: nothing to translate then. */
        fun sameLanguage(source: String?, target: String): Boolean =
            source?.substringBefore('-')
                ?.equals(target.substringBefore('-'), ignoreCase = true) == true
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var loadedKey: String? = null

    // Lines picked to share (both looks): a run of consecutive lines, by index in the lyrics' lines.
    // Kept here so the song options sheet of the Immersive look can open the same mode as a long press.
    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting
    private val _picked = MutableStateFlow<IntRange?>(null)
    val picked: StateFlow<IntRange?> = _picked

    /** Enters the selection mode, on [line] when it came from a long press on one. */
    fun startSelecting(line: Int? = null) {
        _picked.value = line?.let { it..it }
        _selecting.value = true
    }

    fun stopSelecting() {
        _selecting.value = false
        _picked.value = null
    }

    /**
     * A tap on [line] while selecting: outside the run it extends it to there, on its first line
     * it drops that one, on any other it cuts the run just before it. False when the run would
     * go past [MAX_SHARED_LINES].
     */
    fun pick(line: Int): Boolean {
        val run = _picked.value
        when {
            run == null -> _picked.value = line..line
            line in run -> _picked.value = when {
                line == run.first -> if (run.first == run.last) null else (run.first + 1)..run.last
                else -> run.first..(line - 1)
            }
            else -> {
                val wider = minOf(run.first, line)..maxOf(run.last, line)
                if (wider.last - wider.first + 1 > MAX_SHARED_LINES) return false
                _picked.value = wider
            }
        }
        return true
    }

    // "Try another source": the provider being asked, and the one that had nothing (until the next try).
    private val _trying = MutableStateFlow<String?>(null)
    val trying: StateFlow<String?> = _trying
    private val _triedNothing = MutableStateFlow<String?>(null)
    val triedNothing: StateFlow<String?> = _triedNothing

    /** The providers that can be asked for [track] by hand ([loadFrom]); [LyricsApi.sourceOf] names the one shown. */
    fun sources(track: LyricsApi.Track): List<LyricsApi.Source> = LyricsApi.sources(track)

    /**
     * Shows the lyrics of [track] from the provider [sourceId] ([LyricsApi.Source.id]). The lyrics
     * shown stay until the new ones arrive; when the provider has none they stay ([triedNothing]).
     */
    fun loadFrom(track: LyricsApi.Track, sourceId: String) {
        if (_trying.value != null) return
        _trying.value = sourceId
        _triedNothing.value = null
        val key = track.key
        viewModelScope.launch {
            val lyrics = runCatching { LyricsApi.fetchFrom(track, sourceId) }.getOrNull()
            // A skip meanwhile: the answer is for the previous song.
            if (loadedKey == key) {
                if (lyrics == null) _triedNothing.value = sourceId
                else _state.value = loadedState(lyrics)
            }
            _trying.value = null
        }
    }

    fun load(track: LyricsApi.Track, force: Boolean = false) {
        val key = track.key
        if (!force && loadedKey == key && _state.value !is State.NotFound) return
        loadedKey = key
        stopSelecting()
        _triedNothing.value = null
        if (force) LyricsApi.forget(track)
        // Already resolved (prefetched while the track started): no loading flash.
        LyricsApi.cached(track)?.let { _state.value = loadedState(it); return }
        // Read again (the setting changed): the lyrics shown stay until the new ones replace them.
        if (!(force && _state.value is State.Loaded)) _state.value = State.Loading
        // Spotify's own lines shown as soon as they are there; the word-timed ones replace them.
        val earlyJob = viewModelScope.launch {
            LyricsApi.early.collect { e ->
                if (e != null && e.first == key && loadedKey == key && _state.value is State.Loading) _state.value = loadedState(e.second)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val lyrics = LyricsApi.fetch(track)
            earlyJob.cancel()
            withContext(Dispatchers.Main) {
                // A skip during the fetch: don't show the previous song's lyrics.
                if (loadedKey != key) return@withContext
                val shown = (_state.value as? State.Loaded)?.lyrics
                _state.value = when {
                    lyrics == null || lyrics.isEmpty -> if (shown != null) _state.value else State.NotFound
                    shown == lyrics -> _state.value
                    else -> loadedState(lyrics)
                }
            }
        }
    }

    private fun loadedState(lyrics: com.music.spotui.data.entity.Lyrics) = State.Loaded(
        lyrics = lyrics,
        sourceLanguage = lyrics.language,
        translationChecked = lyrics.language != null,
    )

    fun toggleTranslation(targetLang: String) {
        val cur = _state.value as? State.Loaded ?: return
        if (cur.showTranslation) {
            _state.value = cur.copy(showTranslation = false)
            return
        }
        if (cur.translationLanguage == targetLang) {
            _state.value = cur.copy(showTranslation = true)
            return
        }
        if (cur.translating) {
            _state.value = cur.copy(translationRequested = true)
            return
        }
        translate(cur, targetLang, showWhenDone = true)
    }

    fun prepareTranslation(targetLang: String) {
        val cur = _state.value as? State.Loaded ?: return
        if (cur.translating || cur.translationLanguage == targetLang) return
        if (sameLanguage(cur.sourceLanguage, targetLang)) return
        translate(cur, targetLang, showWhenDone = false)
    }

    private fun translate(cur: State.Loaded, targetLang: String, showWhenDone: Boolean) {
        _state.value = cur.copy(
            translating = true,
            translationRequested = showWhenDone,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val lines = cur.lyrics.lines.map { it.text }
            // The lyrics' own translation (from the source, in this language) first; the machine only fills what it lacks.
            cur.lyrics.translationsFor(targetLang)?.let { own ->
                val missing = lines.indices.filter { own[it] == null && lines[it].isNotBlank() && lines[it].trim() != "♪" }
                val filled: Map<Int, String> = if (missing.isEmpty()) emptyMap()
                else LyricTranslate.translateLines(missing.map { lines[it] }, targetLang)?.lines?.let { missing.zip(it).toMap() } ?: emptyMap()
                withContext(Dispatchers.Main) {
                    val latest = _state.value as? State.Loaded ?: return@withContext
                    if (latest.lyrics != cur.lyrics) return@withContext
                    _state.value = latest.copy(
                        translating = false,
                        translationChecked = true,
                        showTranslation = latest.translationRequested,
                        translationRequested = false,
                        translations = lines.indices.map { own[it] ?: filled[it].orEmpty() },
                        translationLanguage = targetLang,
                        translationBySource = true,
                    )
                }
                return@launch
            }
            val detectedLanguage = cur.sourceLanguage
                ?: LyricTranslate.detectLanguage(lines, targetLang)
            if (detectedLanguage != null) {
                val matchesTarget = sameLanguage(detectedLanguage, targetLang)
                withContext(Dispatchers.Main) {
                    val latest = _state.value as? State.Loaded ?: return@withContext
                    if (latest.lyrics == cur.lyrics) {
                        _state.value = latest.copy(
                            translationChecked = true,
                            sourceLanguage = detectedLanguage,
                            translating = !matchesTarget,
                            translationRequested = latest.translationRequested && !matchesTarget,
                        )
                    }
                }
                if (matchesTarget) return@launch
            }
            val result = LyricTranslate.translateLines(lines, targetLang)
            withContext(Dispatchers.Main) {
                val latest = _state.value as? State.Loaded ?: return@withContext
                if (latest.lyrics != cur.lyrics) return@withContext
                if (result == null) {
                    _state.value = latest.copy(
                        translating = false,
                        translationChecked = true,
                        showTranslation = false,
                        translationRequested = false,
                    )
                    return@withContext
                }
                _state.value = latest.copy(
                    translating = false,
                    translationChecked = true,
                    showTranslation = latest.translationRequested &&
                        !sameLanguage(result.sourceLanguage, targetLang),
                    translationRequested = false,
                    translations = result.lines,
                    translationLanguage = targetLang,
                    translationBySource = false,
                    sourceLanguage = result.sourceLanguage,
                )
            }
        }
    }
}
