package com.music.spotui.ui.navigation

import androidx.annotation.DrawableRes
import app.myspoti.engine.R

sealed class Routes(
    @DrawableRes val icon : Int = 0,
    val label : String,
    val route : String,
    /** Official label (per system language) for the bottom navigation, 0 = none. */
    @androidx.annotation.StringRes val labelRes: Int = 0,
) {
    object Home : Routes(icon = R.drawable.ic_home_filled, label = "Accueil", route = "home", labelRes = R.string.navigationbar_musicappitems_home_title)
    object Search : Routes(icon = R.drawable.ic_search_big, label = "Rechercher", route = "search", labelRes = R.string.navigationbar_musicappitems_search_title)
    object Library : Routes(icon = R.drawable.ic_library_big, label = "Bibliothèque", route = "library", labelRes = R.string.navigationbar_musicappitems_yourlibrary_title)
    object Album : Routes(0, "Album", "album")
    object Player : Routes(0, "Player", "player")
    object Artist : Routes(0, "Artist", "artist")
    object ArtistReleases : Routes(0, "ArtistReleases", "artistreleases")
    object Playlist : Routes(0, "Playlist", "playlist")
    object Show : Routes(0, "Show", "show")
    object Episode : Routes(0, "Episode", "episode")
    object Queue : Routes(0, "Queue", "queue")
    object Liked : Routes(0, "Liked", "liked")
    object LocalFiles : Routes(0, "LocalFiles", "localfiles")
    /** Live Radio (radio/ module): search radio-browser.info, favorites, playback. */
    object LiveRadio : Routes(0, "LiveRadio", "liveradio")
    object Downloads : Routes(0, "Downloads", "downloads")
    object Folder : Routes(0, "Folder", "folder")
    object LocalPlaylist : Routes(0, "LocalPlaylist", "localplaylist")
    object Category : Routes(0, "Category", "category")
    object NewReleases : Routes(0, "NewReleases", "newreleases")
    object Episodes : Routes(0, "Episodes", "episodes")
    object Recap : Routes(0, "Recap", "recap")
    object Recent : Routes(0, "Recent", "recent")
    object Friends : Routes(0, "Friends", "friends")
    object Settings : Routes(0, "Settings", "settings")
    object Extensions : Routes(0, "Extensions", "extensions")
    object Extension : Routes(0, "Extension", "extension")
    /** A repository's extension, before it is installed (by id). */
    object ExtensionEntry : Routes(0, "ExtensionEntry", "extension-store")
    object Profile : Routes(0, "Profile", "profile")
}

/** A Spotify user's profile, by user id (the part after spotify:user:). */
fun profileRoute(userId: String, name: String = ""): String =
    "${Routes.Profile.route}/${android.net.Uri.encode(userId)}?name=${android.net.Uri.encode(name)}"

/** Builds a Browse-category route carrying the search genre and a display title. */
fun categoryRoute(genre: String, title: String): String =
    "${Routes.Category.route}/${android.net.Uri.encode(genre)}?title=${android.net.Uri.encode(title)}"

/** An artist's liked songs, their own page (see Api.getLikedByArtistPage). */
const val LIKED_BY_ARTIST_ROUTE = "liked_by_artist"
fun likedByArtistRoute(artistId: String, artistName: String): String =
    "$LIKED_BY_ARTIST_ROUTE/${android.net.Uri.encode("$artistId|$artistName")}"

/** Builds a playlist route carrying the Spotify playlist id (and a display name). */
fun playlistRoute(id: String, name: String = ""): String =
    "${Routes.Playlist.route}/${android.net.Uri.encode(id)}?name=${android.net.Uri.encode(name)}"

/** A Spotify library folder, by its full uri (and name for the header). */
fun folderRoute(uri: String, name: String): String =
    "${Routes.Folder.route}/${android.net.Uri.encode(uri)}?name=${android.net.Uri.encode(name)}"

/** A playlist kept on this phone (LocalPlaylists id). */
fun localPlaylistRoute(id: String): String = "${Routes.LocalPlaylist.route}/${android.net.Uri.encode(id)}"

/** Builds a podcast-show route carrying the Spotify show id (and a display name). */
fun showRoute(id: String, name: String = ""): String =
    "${Routes.Show.route}/${android.net.Uri.encode(id)}?name=${android.net.Uri.encode(name)}"

/** A podcast episode (or a book chapter), by raw Spotify id. */
fun episodeRoute(id: String, name: String = ""): String =
    "${Routes.Episode.route}/${android.net.Uri.encode(id)}?name=${android.net.Uri.encode(name)}"

/**
 * Builds an artist route. When the exact Spotify artist id is known it is
 * carried along so the artist page opens the exact artist instead of
 * re-resolving the name via a fuzzy search ("RAM" must not open "Rammstein").
 */
fun artistRoute(name: String, id: String = ""): String {
    val base = "${Routes.Artist.route}/${android.net.Uri.encode(name)}"
    return if (id.isBlank()) base
    else "$base?id=${android.net.Uri.encode(id)}"
}

/**
 * Builds an album route, optionally carrying the artist so same-named albums by
 * different artists resolve to the right one. The artist value is URL-encoded.
 */
fun albumRoute(name: String, artist: String = "", id: String = ""): String =
    "${Routes.Album.route}/${android.net.Uri.encode(name.ifBlank { "album" })}" +
        "?artist=${android.net.Uri.encode(artist)}&id=${android.net.Uri.encode(id)}"

/**
 * Opens a page from outside the page's own UI (a shared link): a second link to the page already
 * on top reuses it instead of stacking a duplicate.
 */
fun androidx.navigation.NavController.navigateSingleTop(route: String) =
    navigate(route) { launchSingleTop = true }
