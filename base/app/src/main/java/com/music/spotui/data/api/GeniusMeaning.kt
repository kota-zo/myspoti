package com.music.spotui.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * What a song means, from Genius: its "About" text and the explanations written on its lines.
 * Read from the JSON genius.com's own pages use (no key needed): a song search, then the song
 * and its annotations. The last few songs asked for are kept in memory (small texts).
 */
object GeniusMeaning {
    /** One line's explanation; [url] is its own page on Genius (empty when Genius gave none). */
    data class Note(val fragment: String, val explanation: String, val votes: Int = 0, val url: String = "")
    data class Meaning(val url: String, val title: String, val artist: String, val about: String?, val notes: List<Note>) {
        val isEmpty get() = about.isNullOrBlank() && notes.isEmpty()
    }

    private const val BASE = "https://genius.com/api"
    private const val KEEP = 12
    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy { OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build() }
    /** title|artist → the meaning, or null when Genius has none (not asked again this session). */
    private val recent = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Meaning?>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Meaning?>?) = size > KEEP
    })

    fun cached(title: String, artist: String): Meaning? = recent[key(title, artist)]

    /** null: no song on Genius matches, or it says nothing about it. Throws when Genius can't be reached. */
    suspend fun find(title: String, artist: String): Meaning? = withContext(Dispatchers.IO) {
        val k = key(title, artist)
        if (recent.containsKey(k)) return@withContext recent[k]
        val cleanTitle = clean(title)
        val artists = artist.split(',', '&').map { norm(it) }.filter { it.isNotBlank() }
        val q = "$cleanTitle ${artist.substringBefore(',')}"
        val hits = get("$BASE/search/song?per_page=5&q=${java.net.URLEncoder.encode(q, "UTF-8")}")
            ?.obj("response")?.get("sections")?.jsonArray?.firstOrNull()?.jsonObject?.get("hits")?.jsonArray.orEmpty()
        // The right song: one of its artists, and its title (without "feat." / "Remastered" asides).
        val song = hits.map { it.jsonObject.obj("result") }.firstOrNull { r ->
            r ?: return@firstOrNull false
            val names = norm(r.str("artist_names").orEmpty())
            val t = norm(clean(r.str("title").orEmpty()))
            artists.any { names.contains(it) } && (t == norm(cleanTitle) || t.startsWith(norm(cleanTitle)) || norm(cleanTitle).startsWith(t))
        } ?: run { recent[k] = null; return@withContext null }
        val id = song.str("id") ?: run { recent[k] = null; return@withContext null }
        val meaning = coroutineScope {
            val about = async { get("$BASE/songs/$id?text_format=plain")?.obj("response")?.obj("song")?.obj("description")?.str("plain") }
            val notes = async {
                get("$BASE/referents?song_id=$id&text_format=plain&per_page=50")?.obj("response")?.get("referents")?.jsonArray.orEmpty()
                    .mapNotNull { e ->
                        val r = e.jsonObject
                        // Genius's own "?" (empty) and the song-wide notes are left out: lines only.
                        val fragment = r.str("fragment")?.trim().orEmpty()
                        // The best liked explanation of the line; one voted down (negative) is never shown.
                        val best = r["annotations"]?.jsonArray.orEmpty().map { it.jsonObject }
                            .map { a -> Triple(a.obj("body")?.str("plain")?.trim().orEmpty(), a.str("votes_total")?.toIntOrNull() ?: 0, a.str("url").orEmpty()) }
                            .filter { (body, votes) -> body.isNotBlank() && body != "?" && votes >= 0 }
                            .maxByOrNull { it.second }
                        if (fragment.isBlank() || best == null) null
                        else Note(fragment, best.first, best.second, best.third.ifBlank { r.str("url").orEmpty() })
                    }
            }
            Meaning(
                url = song.str("url").orEmpty(),
                title = song.str("title").orEmpty(),
                artist = song.str("artist_names").orEmpty(),
                about = about.await()?.trim()?.takeIf { it.isNotBlank() && it != "?" },
                notes = notes.await(),
            )
        }.takeUnless { it.isEmpty }
        recent[k] = meaning
        meaning
    }

    // ── Ready before it is asked for: the song playing, and its translation ──

    /** The meaning of the song playing (its track id), looked up as soon as it starts. */
    val current = androidx.compose.runtime.mutableStateOf<Pair<String, Meaning>?>(null)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: kotlinx.coroutines.Job? = null
    @Volatile private var prefetchFor = ""

    /**
     * [trackId] started: its Genius page is read in the background (and translated, on Wi-Fi), so
     * the light bulb and the underlined lines are there the moment the lyrics open.
     */
    fun prefetch(trackId: String, title: String, artist: String, lang: String) {
        if (trackId.isBlank() || title.isBlank() || current.value?.first == trackId) return
        if (prefetchFor == trackId && prefetchJob?.isActive == true) return
        prefetchFor = trackId
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val m = runCatching { find(title, artist) }.getOrNull() ?: return@launch
            current.value = trackId to m
            if (!com.music.spotui.data.DataSaver.metered) pretranslate(m, lang)
        }
    }

    /** Translations already done ("lang|text" → text), shown as each one arrives. */
    private val translated = androidx.compose.runtime.mutableStateMapOf<String, String>()
    /** Meanings (url|lang) Genius wrote in the reader's own language: nothing to translate. */
    private val sameLanguage = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val inFlight = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val translateSlots = Dispatchers.IO.limitedParallelism(4)

    /** null: in the reader's language already. true/false: translation known to be needed (or not yet known). */
    fun isSameLanguage(m: Meaning, lang: String): Boolean? = sameLanguage["${m.url}|$lang"]

    /** [text] in [lang] when its translation has arrived; null until then. */
    fun translation(text: String, lang: String): String? = translated["$lang|$text"]

    /**
     * Asks for [text] in [lang] now (the one on screen goes first); the answer lands in
     * [translation]. The first answer for [m] also says whether Genius wrote it in [lang] already.
     */
    fun requestTranslation(m: Meaning, text: String, lang: String) {
        scope.launch(translateSlots) { translateNow(m, text, lang) }
    }

    /** Whether [text] is being translated into [lang] right now. */
    fun isTranslating(text: String, lang: String): Boolean = inFlight["$lang|$text"] == true

    private fun translateNow(m: Meaning, text: String, lang: String) {
        val k = "$lang|$text"
        synchronized(inFlight) {
            if (translated.containsKey(k) || sameLanguage["${m.url}|$lang"] == true || inFlight[k] == true) return
            inFlight[k] = true
        }
        try {
            val r = LyricTranslate.translateLines(text.split('\n'), lang) ?: return
            val same = r.sourceLanguage?.substringBefore('-')?.equals(lang.substringBefore('-'), ignoreCase = true) == true
            if (sameLanguage["${m.url}|$lang"] == null) sameLanguage["${m.url}|$lang"] = same
            if (!same) translated[k] = r.lines.joinToString("\n")
        } finally { inFlight.remove(k) }
    }

    /** Everything of [m] translated in the background, the "About" first, then the lines in order. */
    fun pretranslate(m: Meaning, lang: String) {
        val texts = listOfNotNull(m.about) + m.notes.map { it.explanation }
        scope.launch(translateSlots) {
            // The first answer says the language: nothing more is sent when it is the reader's.
            translateNow(m, texts.firstOrNull() ?: return@launch, lang)
            if (sameLanguage["${m.url}|$lang"] == false) texts.drop(1).forEach { requestTranslation(m, it, lang) }
        }
    }

    private fun get(url: String): JsonObject? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            .header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { if (r.code == 404) return null; error("Genius ${r.code}") }
            return json.parseToJsonElement(r.body.string()).jsonObject
        }
    }

    private fun key(title: String, artist: String) = "$title|$artist"
    private fun JsonObject.obj(name: String): JsonObject? = (this[name] as? JsonObject)
    private fun JsonObject.str(name: String): String? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /** The title without its asides: "(feat. …)", "- Remastered 2011", "[Live]". */
    private fun clean(title: String) = title
        .replace(Regex("""\s*[(\[](feat|ft|with|prod)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|live|mix|mono|stereo).*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    /** Lower case, no accents, letters and digits only: "Beyoncé" and "beyonce" match. */
    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
