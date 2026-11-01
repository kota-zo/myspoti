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

@kotlinx.serialization.Serializable
data class HomeSection(
    val title: String,
    val items: List<HomeItem>,
)

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
