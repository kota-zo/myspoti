// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.spotify

import app.myspoti.api.Album
import app.myspoti.api.AlbumRef
import app.myspoti.api.Artist
import app.myspoti.api.ArtistRef
import app.myspoti.api.Image
import app.myspoti.api.ItemKind
import app.myspoti.api.LocalFile
import app.myspoti.api.NowPlaying
import app.myspoti.api.Page
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Playlist
import app.myspoti.api.Queue
import app.myspoti.api.Repeat
import app.myspoti.api.Track
import kotlinx.serialization.json.JsonElement

// GraphQL entities (search, catalog, playlists) -> :api models.

internal fun images(sources: List<JsonElement>): List<Image> = sources.mapNotNull { s ->
    val url = s.str("url") ?: return@mapNotNull null
    Image(url, s.int("width") ?: s.int("maxWidth")?.takeIf { it > 0 }, s.int("height") ?: s.int("maxHeight")?.takeIf { it > 0 })
}

internal fun artistRefs(node: JsonElement?): List<ArtistRef> = node.list("items").mapNotNull { a ->
    val uri = a.str("uri") ?: return@mapNotNull null
    ArtistRef(uri, a.str("profile.name") ?: a.str("name") ?: "")
}

/** A `Track` entity from search, getTrack or a playlist row (`itemV2.data`). */
internal fun track(d: JsonElement?, uid: String? = null): Track? {
    val uri = d.str("uri") ?: return null
    if (!uri.startsWith("spotify:track:")) return null
    val album = d.obj("albumOfTrack")
    return Track(
        uri = uri,
        name = d.str("name") ?: "",
        durationMs = d.long("duration.totalMilliseconds") ?: d.long("trackDuration.totalMilliseconds") ?: 0,
        artists = artistRefs(d.obj("artists")).ifEmpty { artistRefs(d.obj("firstArtist")) + artistRefs(d.obj("otherArtists")) },
        album = album?.let { a ->
            AlbumRef(a.str("uri") ?: "", a.str("name") ?: "", images(a.list("coverArt.sources")))
        },
        explicit = d.str("contentRating.label") == "EXPLICIT",
        playable = d.bool("playability.playable") ?: true,
        uid = uid,
        hasVideo = (d.long("associationsV3.videoAssociations.totalCount") ?: 0) > 0,
    )
}

/** A playlist row: a track, or an episode shaped as a `Track` (show name as the artist, show/episode art as the album). */
internal fun playlistItem(d: JsonElement?, uid: String? = null): Track? {
    val uri = d.str("uri")
    // A local file (#80): its tags are in the uri; the row's own name and duration win when it has them.
    if (uri != null && LocalFile.isLocal(uri)) {
        val local = LocalFile.track(uri, uid) ?: return null
        return local.copy(
            name = d.str("name")?.takeIf { it.isNotBlank() } ?: local.name,
            durationMs = d.long("trackDuration.totalMilliseconds") ?: d.long("duration.totalMilliseconds") ?: local.durationMs,
        )
    }
    if (uri?.startsWith("spotify:episode:") != true) return track(d, uid)
    val e = episode(d) ?: return null
    val cover = e.images.ifEmpty { e.show?.images.orEmpty() }
    return Track(
        uri = e.uri,
        name = e.name,
        durationMs = e.durationMs,
        artists = e.show?.let { listOf(ArtistRef(it.uri, it.name)) }.orEmpty(),
        album = AlbumRef("", e.show?.name ?: "", cover),
        explicit = e.explicit,
        playable = e.playable,
        uid = uid,
    )
}

internal fun artist(d: JsonElement?): Artist? {
    val uri = d.str("uri") ?: return null
    return Artist(
        uri = uri,
        name = d.str("profile.name") ?: "",
        images = images(d.list("visuals.avatarImage.sources")),
        followers = d.long("stats.followers"),
        monthlyListeners = d.long("stats.monthlyListeners"),
        verified = d.bool("profile.verified") ?: false,
    )
}

internal fun album(d: JsonElement?): Album? {
    val uri = d.str("uri") ?: return null
    return Album(
        uri = uri,
        name = d.str("name") ?: "",
        artists = artistRefs(d.obj("artists")),
        images = images(d.list("coverArt.sources")),
        type = d.str("type") ?: "ALBUM",
        releaseDate = d.str("date.isoString") ?: d.long("date.year")?.toString(),
        trackCount = d.int("tracksV2.totalCount") ?: d.int("tracks.totalCount") ?: 0,
        label = d.str("label"),
    )
}

internal fun playlist(d: JsonElement?): Playlist? {
    val uri = d.str("uri") ?: return null
    return Playlist(
        uri = uri,
        name = d.str("name") ?: "",
        description = d.str("description")?.takeIf { it.isNotBlank() },
        owner = d.str("ownerV2.data.name"),
        images = d.list("images.items").flatMap { images(it.list("sources")) },
        trackCount = d.int("content.totalCount") ?: 0,
        editable = d.bool("currentUserCapabilities.canEditItems") ?: false,
        ownerUri = d.str("ownerV2.data.uri"),
        ownerImages = images(d.list("ownerV2.data.avatar.sources")),
        followers = d.long("followers"),
        isBlend = d.str("format") == "blend",
        canLeave = d.bool("currentUserCapabilities.canCancelMembership") ?: false,
    )
}

/** Search tab pages: `{items[], totalCount, pagingInfo{nextOffset}}`. */
internal fun <T> page(node: JsonElement?, offset: Int, map: (JsonElement) -> T?): Page<T> {
    val rows = node.list("items")
    val items = rows.mapNotNull(map)
    val total = node.int("totalCount") ?: items.size
    // Raw row count, as in SpotifyLibrary: unmappable rows still occupy positions.
    val next = node.int("pagingInfo.nextOffset") ?: (offset + rows.size).takeIf { rows.isNotEmpty() && it < total }
    return Page(items, offset, total, next)
}

// Bridge player messages -> :api models.

internal fun nowPlaying(t: JsonElement?): NowPlaying? {
    val uri = t.str("uri") ?: return null
    val kind = when (t.str("kind")) {
        "ad" -> ItemKind.AD
        "episode" -> ItemKind.EPISODE
        else -> if (uri.startsWith("spotify:ad:")) ItemKind.AD else ItemKind.TRACK
    }
    return NowPlaying(
        kind = kind,
        uri = uri,
        uid = t.str("uid"),
        name = t.str("name") ?: "",
        artists = t.list("artists").mapNotNull { a -> a.str("uri")?.let { ArtistRef(it, a.str("name") ?: "") } },
        album = t.obj("album")?.let { AlbumRef(it.str("uri") ?: "", it.str("name") ?: "") },
        imageUrl = t.str("image"),
        durationMs = t.long("durationMs") ?: 0,
        explicit = t.bool("explicit") ?: false,
        provider = t.str("provider"),
        mediaType = t.str("mediaType"),
    )
}

internal fun playerState(s: JsonElement?): PlayerState? {
    if (s == null || s.obj("restrictions") == null && s.at("isPaused") == null) return null
    val r = s.obj("restrictions")
    return PlayerState(
        item = nowPlaying(s.obj("track")),
        contextUri = s.str("contextUri"),
        contextName = s.str("contextName"),
        previous = nowPlaying(s.obj("previous")),
        next = nowPlaying(s.obj("next")),
        isPaused = s.bool("isPaused") ?: true,
        isBuffering = s.bool("isBuffering") ?: false,
        positionMs = s.long("positionMs") ?: 0,
        timestampMs = s.long("timestamp") ?: System.currentTimeMillis(),
        speed = s.double("speed") ?: 1.0,
        shuffle = s.bool("shuffle") ?: false,
        repeat = when (s.int("repeat")) { 1 -> Repeat.CONTEXT; 2 -> Repeat.TRACK; else -> Repeat.OFF },
        restrictions = PlayerRestrictions(
            canPause = r.bool("canPause") ?: true,
            canResume = r.bool("canResume") ?: true,
            canSeek = r.bool("canSeek") ?: true,
            canSkipNext = r.bool("canSkipNext") ?: true,
            canSkipPrevious = r.bool("canSkipPrevious") ?: true,
        ),
    )
}

internal fun queue(q: JsonElement?): Queue? {
    if (q == null) return null
    return Queue(
        current = nowPlaying(q.obj("current")),
        queued = q.list("queued").mapNotNull(::nowPlaying),
        nextUp = q.list("nextUp").mapNotNull(::nowPlaying),
    )
}

// Cards and podcast entities.

/** Any `*ResponseWrapper` (or its `data`) -> a card for shelves and search tabs. */
internal fun card(wrapper: JsonElement?): app.myspoti.api.MediaCard? {
    val d = wrapper.obj("data") ?: wrapper
    val uri = d.str("uri") ?: wrapper.str("_uri") ?: wrapper.str("uri") ?: return null
    val type = d.typename()
    var kind = app.myspoti.api.MediaKind.of(uri)
    if (type == "Audiobook") kind = app.myspoti.api.MediaKind.AUDIOBOOK
    val title = d.str("name") ?: d.str("profile.name") ?: d.str("displayName") ?: d.str("title.transformedLabel") ?: d.str("title") ?: return null
    val subtitle = when (type) {
        "Playlist" -> d.str("ownerV2.data.name") ?: d.str("description")
        "Album" -> artistRefs(d.obj("artists")).joinToString(", ") { it.name }.ifBlank { null }
        "Track" -> artistRefs(d.obj("artists")).joinToString(", ") { it.name }.ifBlank { null }
        "Podcast" -> d.str("publisher.name")
        "Audiobook" -> d.list("authorsV2").mapNotNull { it.str("name") }.joinToString(", ").ifBlank { null }
        "Episode", "Chapter" -> d.str("podcastV2.data.name")
        "PseudoPlaylist" -> d.int("count")?.toString()
        else -> null
    }
    val images = images(
        d.list("coverArt.sources").ifEmpty { d.list("visuals.avatarImage.sources") }
            .ifEmpty { d.list("images.items").flatMap { it.list("sources") } }
            .ifEmpty { d.list("avatar.sources") }.ifEmpty { d.list("image.sources") }
            .ifEmpty { d.list("albumOfTrack.coverArt.sources") }
    )
    return app.myspoti.api.MediaCard(uri, kind, title, subtitle, images)
}

internal fun episode(d: JsonElement?): app.myspoti.api.Episode? {
    val uri = d.str("uri") ?: return null
    val show = d.obj("podcastV2.data")
    return app.myspoti.api.Episode(
        uri = uri,
        name = d.str("name") ?: "",
        description = d.str("description"),
        durationMs = d.long("duration.totalMilliseconds") ?: 0,
        releaseDate = d.str("releaseDate.isoString"),
        images = images(d.list("coverArt.sources")),
        show = show?.let { AlbumRef(it.str("uri") ?: "", it.str("name") ?: "", images(it.list("coverArt.sources"))) },
        playedPositionMs = d.long("playedState.playPositionMilliseconds") ?: 0,
        played = d.str("playedState.state") == "COMPLETED",
        explicit = d.str("contentRating.label") == "EXPLICIT",
        playable = d.bool("playability.playable") ?: true,
        previewUrl = d.str("previewPlayback.audioPreview.cdnUrl"),
    )
}

internal fun show(d: JsonElement?): app.myspoti.api.Show? {
    val uri = d.str("uri") ?: return null
    return app.myspoti.api.Show(
        uri = uri,
        name = d.str("name") ?: "",
        publisher = d.str("publisher.name") ?: "",
        description = d.str("description"),
        images = images(d.list("coverArt.sources")),
        saved = d.bool("saved") ?: false,
        rating = d.double("rating.averageRating.average"),
        topics = d.list("topics.items").mapNotNull { it.str("title") },
    )
}

/** Discography and "appears on" rows hold `releases.items[0]`. */
internal fun release(row: JsonElement?): Album? = album(row.list("releases.items").firstOrNull() ?: row)
