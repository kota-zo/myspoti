// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.spotify

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.FriendActivity
import app.myspoti.api.TimeRange
import app.myspoti.api.TopContent
import app.myspoti.api.Track
import app.myspoti.api.UserRef
import kotlinx.serialization.json.JsonElement

/**
 * The `timeRange` word of `userTopContent`'s `AffinityInput`. The Web Player's enum is
 * `SHORT_TERM`, `MID_TERM`, `LONG_TERM`; `MEDIUM_TERM` (the public Web API's word) is refused
 * with an HTTP error (checked live 2026-10-23).
 */
internal val TimeRange.gqlName: String
    get() = when (this) {
        TimeRange.SHORT_TERM -> "SHORT_TERM"
        TimeRange.MEDIUM_TERM -> "MID_TERM"
        TimeRange.LONG_TERM -> "LONG_TERM"
    }

/** `userTopContent`'s `data.me.profile`: `topArtists` (ArtistPageV2) and `topTracks` (TrackPageV2). */
internal fun topContent(profile: JsonElement?): TopContent = TopContent(
    artists = profile.list("topArtists.items").mapNotNull { artist(it.at("data")) },
    tracks = profile.list("topTracks.items").mapNotNull { track(it.at("data")) },
    artistTotal = profile.int("topArtists.totalCount"),
    trackTotal = profile.int("topTracks.totalCount"),
)

/**
 * The desktop client's `presence-view/v1/buddylist` answer: `{friends: [{timestamp, user{uri,
 * name, imageUrl}, track{uri, name, imageUrl, album{uri, name}, artist{uri, name}, context{uri,
 * name, index}}}]}`, as the Web Player's own (desktop-only) friend feed reads it. Latest first.
 */
internal fun buddyList(r: JsonElement?): List<FriendActivity> = r.list("friends").mapNotNull { f ->
    val userUri = f.str("user.uri") ?: return@mapNotNull null
    val t = f.obj("track")
    val track = t?.str("uri")?.let { uri ->
        Track(
            uri = uri,
            name = t.str("name") ?: "",
            durationMs = 0,
            artists = listOfNotNull(t.obj("artist")?.let { a -> a.str("uri")?.let { ArtistRef(it, a.str("name") ?: "") } }),
            album = t.obj("album")?.let { a -> a.str("uri")?.let { AlbumRef(it, a.str("name") ?: "") } },
        )
    }
    FriendActivity(
        user = UserRef(userUri, f.str("user.name") ?: userUri.substringAfterLast(':'), f.str("user.imageUrl")?.takeIf { it.isNotBlank() }),
        track = track,
        contextUri = t?.str("context.uri"),
        timestampMs = f.long("timestamp") ?: 0,
        contextName = t?.str("context.name")?.takeIf { it.isNotBlank() },
        trackImageUrl = t?.str("imageUrl")?.takeIf { it.isNotBlank() },
    )
}.sortedByDescending { it.timestampMs }

/** One row of the mobile friend feed, before its uris are looked up. */
internal data class FeedEntry(val userUri: String, val itemUri: String, val contextUri: String?, val playing: Boolean, val timestampMs: Long)

/**
 * The mobile client's `listening-activity/v1/feed` answer (protobuf `GetFeedResponse`): entities
 * (1), each a friend (1), someone followed (2) or a group (3, its members in 3), with their
 * `FeedActivity` (2: item uri 1, context uri 2, playing 3, timestamp 4). One row per person,
 * their latest; latest first.
 */
internal fun listeningFeed(body: ByteArray): List<FeedEntry> =
    parseProto(body).messages(1).flatMap { w ->
        listOfNotNull(w.message(1), w.message(2)) + w.message(3)?.messages(3).orEmpty()
    }.mapNotNull { e ->
        val user = e.string(1)?.takeIf { it.startsWith("spotify:user:") } ?: return@mapNotNull null
        val a = e.message(2) ?: return@mapNotNull null
        val item = a.string(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        FeedEntry(user, item, a.string(2)?.takeIf { it.isNotEmpty() && it != item }, a.long(3) == 1L, a.message(4)?.timestampMs() ?: 0)
    }.groupBy { it.userUri }.values.map { rows -> rows.maxBy { it.timestampMs } }.sortedByDescending { it.timestampMs }

/**
 * Names and covers of any uris (tracks, episodes, playlists, albums, artists, users) in one
 * `fetchEntitiesForRecentlyPlayed` call per 50, by uri: each its `*ResponseWrapper`.
 */
internal suspend fun lookupEntities(gql: app.myspoti.spotify.gql.Gql, uris: Collection<String>): Map<String, JsonElement> =
    uris.distinct().chunked(50).flatMap { chunk ->
        gql.query("fetchEntitiesForRecentlyPlayed", vars("uris" to chunk)).list("data.lookup")
    }.mapNotNull { w -> (w.str("_uri") ?: w.str("data.uri"))?.let { it to w } }.toMap()

/** Users' profile pictures in one `lookupEntityImages` call per 50 (the lookup above has none), by uri. */
internal suspend fun lookupAvatars(gql: app.myspoti.spotify.gql.Gql, userUris: Collection<String>): Map<String, String> =
    userUris.distinct().chunked(50).flatMap { chunk ->
        gql.query("lookupEntityImages", vars("uris" to chunk)).list("data.lookupEntities")
    }.mapNotNull { e ->
        val url = e.list("visualIdentityTrait.squareCoverImage.image.data.sources").firstOrNull().str("url") ?: return@mapNotNull null
        e.str("uri")?.let { it to url }
    }.toMap()

/** A looked-up user's display name, or their id. */
internal fun userName(entities: Map<String, JsonElement>, userUri: String): String =
    entities[userUri].str("data.name")?.takeIf { it.isNotBlank() } ?: userUri.substringAfterLast(':')
