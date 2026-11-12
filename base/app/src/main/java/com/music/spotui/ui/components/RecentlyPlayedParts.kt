package com.music.spotui.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import app.myspoti.api.MediaKind
import app.myspoti.api.RecentGroup
import app.myspoti.api.RecentItem
import app.myspoti.engine.R
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The texts and navigation the Recently Played page shares between the Classic and Immersive
 * looks. Only the rows and their styling differ; these read the same groups.
 */

private const val LIKED_URI = "spotify:collection:tracks"
private const val YOUR_EPISODES_URI = "spotify:collection:your-episodes"

/** "[N] titres lus • [Type] • [creators]", with Spotify's own wording for saved rows. */
@Composable
internal fun groupSubtitle(group: RecentGroup): String {
    val parts = buildList {
        add(countLabel(group.count, group.saved))
        add(group.context.typeLabel?.takeIf { it.isNotBlank() } ?: kindLabel(group.context.kind))
        // Liked Songs and artists show no creator line on Spotify's own rows.
        if (!group.saved && group.context.kind != MediaKind.ARTIST) group.context.subtitle?.let { add(it) }
    }
    return parts.filter { it.isNotBlank() }.joinToString(" • ")
}

@Composable
internal fun countLabel(count: Int, saved: Boolean): String = when {
    saved && count == 1 -> stringResource(R.string.recents_saved_one)
    saved -> stringResource(R.string.recents_saved_many, count)
    count == 1 -> stringResource(R.string.recents_played_one)
    else -> stringResource(R.string.recents_played_many, count)
}

@Composable
internal fun dayLabel(dayMs: Long?): String {
    if (dayMs == null) return ""
    val date = Instant.ofEpochMilli(dayMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.recents_today)
        today.minusDays(1) -> stringResource(R.string.recents_yesterday)
        // Older days print as the official page does: day and short month, in the phone's language.
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }
}

@Composable
internal fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.PLAYLIST -> stringResource(R.string.assisted_curation_search_playlist)
    MediaKind.ALBUM -> stringResource(R.string.album_type_album)
    MediaKind.ARTIST -> stringResource(R.string.artist_subtitle)
    MediaKind.SHOW -> stringResource(R.string.ad_category_title_podcasts)
    MediaKind.EPISODE -> stringResource(R.string.your_episodes_title)
    else -> ""
}

/** The uri a play row hands the player: episodes are queued by id, tracks by their own uri. */
internal fun playUri(item: RecentItem): String =
    if (item.kind == MediaKind.EPISODE) "episode:" + item.uri.substringAfterLast(':') else item.uri

/**
 * Opens the context the row stands for. Liked Songs and Your Episodes are collections with their
 * own pages, the rest go to their playlist, album, artist or show page; a lone track (a row with
 * no context) plays.
 */
internal fun openContext(context: RecentItem, navController: NavController) {
    val id = context.uri.substringAfterLast(':')
    when {
        context.uri == LIKED_URI || context.kind == MediaKind.COLLECTION -> navController.navigate(Routes.Liked.route)
        context.uri == YOUR_EPISODES_URI -> navController.navigate(Routes.Episodes.route)
        context.kind == MediaKind.PLAYLIST -> navController.navigate(playlistRoute(id, context.title))
        context.kind == MediaKind.ALBUM -> navController.navigate(albumRoute(context.title, context.subtitle.orEmpty(), id))
        context.kind == MediaKind.ARTIST -> navController.navigate(artistRoute(context.title, id))
        context.kind == MediaKind.SHOW -> navController.navigate(showRoute(id, context.title))
        context.kind == MediaKind.TRACK || context.kind == MediaKind.EPISODE ->
            SongPlayer.playSong(playUri(context), contextUri = null)
        else -> Unit
    }
}
