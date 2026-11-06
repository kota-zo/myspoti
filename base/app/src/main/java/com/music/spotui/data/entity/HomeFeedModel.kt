package com.music.spotui.data.entity

/**
 * Personalized Spotify home feed (the `home` GQL operation): a greeting plus an
 * ordered list of titled sections ("Your top mixes", "Jump back in", "Your
 * favorite artists", …), each holding albums / artists / playlists.
 */
@kotlinx.serialization.Serializable
data class HomeFeedModel(
    val greeting: String = "",
    val sections: List<HomeSection> = emptyList(),
)

/**
 * The chips Spotify opens beside a home chip once it is picked (Music > Following, Podcasts >
 * Following), by the parent's facet, as the last feeds told: (facet, label). Compose state.
 */
object HomeSubChips {
    val byParent = androidx.compose.runtime.mutableStateMapOf<String, List<Pair<String, String>>>()

    /** The chip [facet] opens under, itself when it has sub-chips; null for "All" and a chip with none. */
    fun parentOf(facet: String): String? =
        if (facet in byParent) facet else byParent.entries.firstOrNull { e -> e.value.any { it.first == facet } }?.key

    fun learn(chips: List<app.myspoti.api.HomeChip>) {
        val found = chips.filter { it.subChips.isNotEmpty() }.associate { c -> c.id to c.subChips.map { it.id to it.label } }
        if (found.isEmpty()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post { found.forEach { (k, v) -> if (byParent[k] != v) byParent[k] = v } }
    }
}

@kotlinx.serialization.Serializable
data class HomeSection(
    val title: String,
    val items: List<HomeItem>,
)

/**
 * The shelves as the feed shows them: with podcasts off, their shows and episodes leave
 * (and a shelf left empty goes).
 */
fun List<HomeSection>.withoutPodcastShows(): List<HomeSection> =
    if (com.music.spotui.data.api.Features.PODCASTS) this
    else map { s -> s.copy(items = s.items.filterNot { it is HomeItem.Show }) }.filter { it.items.isNotEmpty() }

/** The shelves without Spotify's DJ (a playlist card named "DJ"); a shelf left empty goes. */
fun List<HomeSection>.withoutDj(): List<HomeSection> =
    map { s -> s.copy(items = s.items.filterNot { it is HomeItem.Playlist && (it.id == DJ_PLAYLIST_ID || it.name.trim().equals("DJ", ignoreCase = true)) }) }
        .filter { it.items.isNotEmpty() }

/**
 * The home shortcuts with Liked Songs first, named [likedName]: Spotify's own grid does not always
 * carry it. Left as it is when the grid already has it (no tile twice), and kept to eight tiles.
 */
fun List<HomeItem>.withLikedFirst(likedName: String): List<HomeItem> {
    if (any { it is HomeItem.Playlist && it.id == com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID }) return take(8)
    val liked = HomeItem.Playlist(
        name = likedName,
        imageUrl = com.music.spotui.data.api.Api.HomeCache.LIKED_COVER,
        subtitle = "",
        id = com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID,
    )
    return (listOf<HomeItem>(liked) + this).take(8)
}

private const val DJ_PLAYLIST_ID = "37i9dQZF1EYkqdzj48dyYq"

@kotlinx.serialization.Serializable
sealed class HomeItem {
    abstract val name: String
    abstract val imageUrl: String

    @kotlinx.serialization.Serializable
    data class Album(
        val id: String = "",
        override val name: String,
        override val imageUrl: String,
        val subtitle: String,
        val artists: String = "",
    ) : HomeItem()

    @kotlinx.serialization.Serializable
    data class Artist(
        override val name: String,
        override val imageUrl: String,
        val id: String = "",
    ) : HomeItem()

    /** Podcast show / audiobook, or a single episode ([isEpisode]). */
    @kotlinx.serialization.Serializable
    data class Show(
        val id: String,
        override val name: String,
        override val imageUrl: String,
        val subtitle: String,
        val isEpisode: Boolean,
    ) : HomeItem()

    @kotlinx.serialization.Serializable
    data class Playlist(
        override val name: String,
        override val imageUrl: String,
        val subtitle: String,
        val id: String = "",
    ) : HomeItem()
}
