/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.ytmusic

import app.myspoti.api.Album
import app.myspoti.api.AlbumRef
import app.myspoti.api.Artist
import app.myspoti.api.ArtistRef
import app.myspoti.api.Image
import app.myspoti.api.Playlist
import app.myspoti.api.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the web app's answers (browse, search, watch-next) into the app's models, leniently: the
 * answers are walked for the renderers that carry items (`musicResponsiveListItemRenderer` rows,
 * `musicTwoRowItemRenderer` cards, `playlistPanelVideoRenderer` queue rows), so a moved shelf or a new
 * wrapper does not break the read. Uris are the catalog's: `ytmusic:track:{videoId}`,
 * `ytmusic:album:{MPREb_…}`, `ytmusic:artist:{UC…}`, `ytmusic:playlist:{id}`.
 */
object YtmParse {
    sealed interface Item {
        data class Song(val track: Track) : Item
        data class AlbumItem(val album: Album) : Item
        data class ArtistItem(val artist: Artist) : Item
        data class PlaylistItem(val playlist: Playlist) : Item
    }

    // ── JSON helpers ──

    private fun JsonElement?.o(): JsonObject? = this as? JsonObject
    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.a(k: String): JsonArray? = this[k] as? JsonArray
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Every object found under [key], anywhere in [e], in document order. */
    fun deep(e: JsonElement?, key: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun walk(x: JsonElement?) {
            when (x) {
                is JsonObject -> for ((k, v) in x) { if (k == key && v is JsonObject) out += v; walk(v) }
                is JsonArray -> x.forEach(::walk)
                else -> {}
            }
        }
        walk(e)
        return out
    }

    private fun runs(o: JsonObject?): List<JsonObject> = o?.a("runs")?.mapNotNull { it.o() }.orEmpty()

    /** The text of a `{runs: [...]}` or `{simpleText}` field. */
    fun text(o: JsonObject?): String? = o?.s("simpleText") ?: runs(o).joinToString("") { it.s("text").orEmpty() }.takeIf { it.isNotEmpty() }

    private fun browseOf(o: JsonObject?): Pair<String, String?>? {
        val b = o?.o("navigationEndpoint")?.o("browseEndpoint") ?: return null
        val id = b.s("browseId") ?: return null
        val type = b.o("browseEndpointContextSupportedConfigs")?.o("browseEndpointContextMusicConfig")?.s("pageType")
        return id to type
    }

    private fun watchOf(o: JsonObject?): JsonObject? = o?.o("navigationEndpoint")?.o("watchEndpoint")

    private fun videoType(watch: JsonObject?): String? =
        watch?.o("watchEndpointMusicSupportedConfigs")?.o("watchEndpointMusicConfig")?.s("musicVideoType")

    fun images(o: JsonObject?): List<Image> {
        val list = o?.let { deep(it, "thumbnail").firstNotNullOfOrNull { t -> t.a("thumbnails") } ?: it.a("thumbnails") }
        return list?.mapNotNull { it.o() }?.mapNotNull { t ->
            t.s("url")?.let { Image(it, (t["width"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(), (t["height"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()) }
        }.orEmpty()
    }

    /** "3:45" or "1:02:03" in ms; null when it is not a length. */
    fun lengthMs(s: String?): Long? {
        val parts = s?.trim()?.takeIf { LENGTH.matches(it) }?.split(':')?.map { it.toLong() } ?: return null
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }

    private val LENGTH = Regex("""\d{1,2}(:\d{2}){1,2}""")
    private val YEAR = Regex("""(19|20)\d{2}""")

    private const val ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    private const val ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    private const val CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private const val PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
    private const val EPISODE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"

    private fun uri(kind: String, id: String) = "${Ytm.PROVIDER}:$kind:$id"
    fun playlistUri(browseId: String) = uri("playlist", browseId.removePrefix("VL"))

    private fun artistsIn(rs: List<JsonObject>): List<ArtistRef> = rs.mapNotNull { r ->
        val (id, type) = browseOf(r) ?: return@mapNotNull null
        if (type == ARTIST || type == CHANNEL) ArtistRef(uri("artist", id), r.s("text").orEmpty()) else null
    }

    private fun albumIn(rs: List<JsonObject>, images: List<Image> = emptyList()): AlbumRef? = rs.firstNotNullOfOrNull { r ->
        val (id, type) = browseOf(r) ?: return@firstNotNullOfOrNull null
        if (type == ALBUM) AlbumRef(uri("album", id), r.s("text").orEmpty(), images) else null
    }

    private fun explicit(o: JsonObject): Boolean = deep(o, "musicInlineBadgeRenderer").any { b ->
        b.o("icon")?.s("iconType") == "MUSIC_EXPLICIT_BADGE"
    }

    // ── Rows ──

    /** One `musicResponsiveListItemRenderer`: a song, or an album, artist or playlist row (search, library). */
    fun row(r: JsonObject): Item? {
        val flex = r.a("flexColumns")?.mapNotNull { it.o()?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text") }.orEmpty()
        val fixed = r.a("fixedColumns")?.mapNotNull { it.o()?.o("musicResponsiveListItemFixedColumnRenderer")?.o("text") }.orEmpty()
        val titleRuns = runs(flex.getOrNull(0))
        val title = titleRuns.firstOrNull()?.s("text") ?: return null
        val rest = flex.drop(1).flatMap(::runs)
        val imgs = images(r.o("thumbnail"))
        val watch = watchOf(titleRuns.firstOrNull())
            ?: r.o("overlay")?.let { deep(it, "playNavigationEndpoint").firstOrNull()?.o("watchEndpoint") }
        val videoId = r.o("playlistItemData")?.s("videoId") ?: watch?.s("videoId")
        val browse = browseOf(r)
        if (videoId != null && (browse == null || browse.second == null)) {
            if (videoType(watch) == EPISODE) return null
            val length = (fixed.mapNotNull(::text) + rest.mapNotNull { it.s("text") }).firstNotNullOfOrNull(::lengthMs) ?: 0L
            var artists = artistsIn(rest)
            // A row of an album page or a playlist made by the account may name its artist without a link.
            if (artists.isEmpty()) rest.firstOrNull()?.s("text")?.takeIf { it.isNotBlank() && lengthMs(it) == null }?.let { artists = listOf(ArtistRef("", it)) }
            val greyed = r.s("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
            return Item.Song(Track(uri("track", videoId), title, length, artists, albumIn(rest, imgs), explicit(r), playable = !greyed,
                uid = r.o("playlistItemData")?.s("playlistSetVideoId")))
        }
        val (id, type) = browse ?: return null
        return card(title, id, type, rest, imgs)
    }

    // ── Cards ──

    /** One `musicTwoRowItemRenderer`: an album, artist, playlist or song card (carousels, grids). */
    fun twoRow(c: JsonObject): Item? {
        val title = text(c.o("title")) ?: return null
        val sub = runs(c.o("subtitle"))
        val imgs = images(c.o("thumbnailRenderer"))
        val (id, type) = browseOf(c) ?: run {
            val watch = watchOf(c) ?: return null
            val videoId = watch.s("videoId") ?: return null
            if (videoType(watch) == EPISODE) return null
            return Item.Song(Track(uri("track", videoId), title, 0, artistsIn(sub), albumIn(sub, imgs), explicit(c)))
        }
        return card(title, id, type, sub, imgs)
    }

    private fun card(title: String, id: String, type: String?, sub: List<JsonObject>, imgs: List<Image>): Item? {
        val words = sub.mapNotNull { it.s("text")?.trim() }.filter { it.isNotEmpty() && it != "•" }
        return when {
            type == ALBUM -> Item.AlbumItem(Album(
                uri("album", id), title, artistsIn(sub), imgs,
                type = words.firstOrNull()?.lowercase()?.takeIf { it in ALBUM_KINDS } ?: "album",
                releaseDate = words.firstOrNull { YEAR.matches(it) }, trackCount = 0,
            ))
            type == ARTIST || type == CHANNEL -> Item.ArtistItem(Artist(uri("artist", id), title, imgs))
            type == PLAYLIST || id.startsWith("VL") -> Item.PlaylistItem(Playlist(
                playlistUri(id), title, description = null,
                owner = words.firstOrNull { !it.equals("Playlist", ignoreCase = true) && countOf(it) == null },
                images = imgs, trackCount = words.firstNotNullOfOrNull(::countOf) ?: 0,
            ))
            else -> null
        }
    }

    private val ALBUM_KINDS = setOf("album", "single", "ep")
    private val COUNT = Regex("""([\d,.\s]+)\s+(songs?|tracks?|titres?|chansons?)""", RegexOption.IGNORE_CASE)
    private fun countOf(s: String): Int? = COUNT.find(s)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull()

    // ── Whole answers ──

    private val ROW_CONTAINERS = listOf(
        "musicPlaylistShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfContinuation", "musicShelfContinuation",
        "appendContinuationItemsAction",
    )

    /** The rows of an answer's lists (not those of its carousels of suggestions), in order, once each. */
    fun rows(answer: JsonElement): List<Item> {
        val containers = ROW_CONTAINERS.flatMap { deep(answer, it) }
        val found = LinkedHashMap<JsonObject, Unit>()
        // Nested containers hold the same rows: identity keeps each row once.
        val seen = java.util.IdentityHashMap<JsonObject, Boolean>()
        for (c in containers) for (r in deep(c, "musicResponsiveListItemRenderer")) if (seen.put(r, true) == null) found[r] = Unit
        return found.keys.mapNotNull(::row)
    }

    /** Every card of an answer (grids and carousels), in order. */
    fun cards(answer: JsonElement): List<Item> = deep(answer, "musicTwoRowItemRenderer").mapNotNull(::twoRow)

    /** The token of the answer's next page, when there is one. */
    fun continuation(answer: JsonElement): String? =
        deep(answer, "nextContinuationData").firstNotNullOfOrNull { it.s("continuation") }
            ?: deep(answer, "continuationCommand").firstNotNullOfOrNull { it.s("token") }

    /** The top result card of a search (`musicCardShelfRenderer`), as an item. */
    private fun topResult(answer: JsonElement): List<Item> = deep(answer, "musicCardShelfRenderer").mapNotNull { c ->
        val t = runs(c.o("title")).firstOrNull() ?: return@mapNotNull null
        val title = t.s("text") ?: return@mapNotNull null
        val sub = runs(c.o("subtitle"))
        val imgs = images(c.o("thumbnail"))
        val browse = browseOf(t)
        if (browse != null) card(title, browse.first, browse.second, sub, imgs)
        else watchOf(t)?.s("videoId")?.let { Item.Song(Track(uri("track", it), title, sub.mapNotNull { r -> r.s("text") }.firstNotNullOfOrNull(::lengthMs) ?: 0, artistsIn(sub), albumIn(sub, imgs))) }
    }

    /** A search answer: its top result, then its shelves' rows and cards, each item once. */
    fun search(answer: JsonElement): List<Item> {
        val all = topResult(answer) + deep(answer, "musicShelfRenderer").flatMap { s -> deep(s, "musicResponsiveListItemRenderer").mapNotNull(::row) } + cards(answer)
        return all.distinctBy { key(it) }
    }

    private fun key(i: Item) = when (i) {
        is Item.Song -> i.track.uri
        is Item.AlbumItem -> i.album.uri
        is Item.ArtistItem -> i.artist.uri
        is Item.PlaylistItem -> i.playlist.uri
    }

    /** A page's header (album, artist, playlist): its title, its subtitle and strapline runs, its images. */
    class Header(val title: String?, val subtitle: List<String>, val artists: List<ArtistRef>, val images: List<Image>, val description: String?)

    fun header(answer: JsonElement): Header? {
        val h = HEADERS.firstNotNullOfOrNull { deep(answer, it).firstOrNull() } ?: return null
        val sub = runs(h.o("subtitle")) + runs(h.o("secondSubtitle"))
        val strap = runs(h.o("straplineTextOne"))
        val desc = h.o("description")?.let { d -> text(d) ?: deep(d, "description").firstNotNullOfOrNull(::text) }
        return Header(
            text(h.o("title")),
            sub.mapNotNull { it.s("text")?.trim() }.filter { it.isNotEmpty() && it != "•" },
            artistsIn(strap).ifEmpty { artistsIn(sub) },
            images(h.o("thumbnail") ?: h.o("foregroundThumbnail")),
            desc,
        )
    }

    private val HEADERS = listOf(
        "musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
    )

    /** An album page: the album and its songs (their album and cover are the page's). */
    fun album(browseId: String, answer: JsonElement): Pair<Album, List<Track>> {
        val h = header(answer)
        val name = h?.title.orEmpty()
        val images = h?.images.orEmpty()
        val ref = AlbumRef(uri("album", browseId), name, images)
        val artists = h?.artists.orEmpty()
        val songs = rows(answer).filterIsInstance<Item.Song>().map { s ->
            val t = s.track
            t.copy(album = t.album ?: ref, artists = t.artists.filter { it.uri.isNotEmpty() }.ifEmpty { artists.ifEmpty { t.artists } })
        }
        val words = h?.subtitle.orEmpty()
        val album = Album(
            ref.uri, name, artists, images,
            type = words.firstOrNull()?.lowercase()?.takeIf { it in ALBUM_KINDS } ?: "album",
            releaseDate = words.firstOrNull { YEAR.matches(it) }, trackCount = songs.size,
        )
        return album to songs
    }

    /** An artist page: the artist, its top songs, its releases and the artists like it. */
    class ArtistPage(val artist: Artist, val songs: List<Track>, val albums: List<Album>, val related: List<Artist>, val about: String?)

    fun artist(channelId: String, answer: JsonElement): ArtistPage {
        val h = header(answer)
        val artist = Artist(uri("artist", channelId), h?.title.orEmpty(), h?.images.orEmpty())
        val songs = deep(answer, "musicShelfRenderer").firstOrNull()?.let { s -> deep(s, "musicResponsiveListItemRenderer").mapNotNull(::row) }
            .orEmpty().filterIsInstance<Item.Song>().map { it.track }
        val cards = cards(answer)
        return ArtistPage(
            artist, songs,
            cards.filterIsInstance<Item.AlbumItem>().map { a -> a.album.copy(artists = a.album.artists.ifEmpty { listOf(ArtistRef(artist.uri, artist.name)) }) },
            cards.filterIsInstance<Item.ArtistItem>().map { it.artist }.filter { it.uri != artist.uri },
            h?.description,
        )
    }

    /** A song's own row in its watch-next list (`playlistPanelVideoRenderer`), or null. */
    fun watchNextTrack(videoId: String, answer: JsonElement): Track? {
        val r = deep(answer, "playlistPanelVideoRenderer").firstOrNull { it.s("videoId") == videoId } ?: return null
        val byline = runs(r.o("longBylineText"))
        val imgs = images(r.o("thumbnail"))
        return Track(
            uri("track", videoId), text(r.o("title")).orEmpty(), lengthMs(text(r.o("lengthText"))) ?: 0L,
            artistsIn(byline).ifEmpty { byline.firstOrNull()?.s("text")?.let { listOf(ArtistRef("", it)) }.orEmpty() },
            albumIn(byline, imgs), explicit(r),
        )
    }
}
