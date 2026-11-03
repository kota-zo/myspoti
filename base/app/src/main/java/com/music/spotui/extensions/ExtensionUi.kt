package com.music.spotui.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import app.myspoti.extensions.Action
import app.myspoti.extensions.Area
import app.myspoti.extensions.Ask
import app.myspoti.extensions.Block
import app.myspoti.extensions.Conflicts
import app.myspoti.extensions.Hook
import app.myspoti.extensions.ItemKind
import app.myspoti.extensions.ProtectedSurface
import app.myspoti.extensions.Surface
import app.myspoti.extensions.SurfaceGuard
import app.myspoti.extensions.Target
import app.myspoti.extensions.TextStyle
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// What extensions add to the app's screens, drawn by the app: its fonts, colours and icons.
// Each block says which extension it comes from; while a protected surface is on screen
// (support, update, install warnings, confirmations, report, about) nothing here is drawn.

// ── Protected surfaces ──

/** Marks [surface] as on screen while this is composed: extension UI waits beneath it. */
@Composable
fun ProtectedSurfaceEffect(surface: ProtectedSurface) {
    DisposableEffect(surface) {
        SurfaceGuard.app.shown(surface)
        onDispose { SurfaceGuard.app.hidden(surface) }
    }
}

@Composable
private fun shielded(): Boolean = SurfaceGuard.app.state.collectAsState().value.isNotEmpty()

// ── Icons and labels ──

fun actionIcon(a: Action?): ImageVector = when (a) {
    Action.PLAYBACK_CONTROL -> Icons.Rounded.PlayCircle
    Action.PLAYBACK_QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
    Action.PLAYBACK_MIRROR -> Icons.Rounded.VolumeOff
    Action.PLAYBACK_EVENTS -> Icons.Rounded.GraphicEq
    Action.LYRICS_PROVIDE -> Icons.Rounded.Lyrics
    Action.LYRICS_ROMANIZE -> Icons.Rounded.Abc
    Action.TRACKINFO_PROVIDE -> Icons.Rounded.Info
    Action.AUDIO_SOURCE -> Icons.Rounded.Headphones
    Action.DOWNLOAD_SOURCE -> Icons.Rounded.Download
    Action.HOME_SECTIONS -> Icons.Rounded.Dashboard
    Action.SEARCH_PROVIDE -> Icons.AutoMirrored.Rounded.ManageSearch
    Action.MENU_ITEMS -> Icons.Rounded.MoreHoriz
    Action.PLAYER_BUTTONS -> Icons.Rounded.SmartButton
    Action.SETTINGS_PAGE -> Icons.Rounded.Tune
    Action.NOTIFY -> Icons.Rounded.Notifications
    Action.LIBRARY_READ -> Icons.Rounded.LibraryMusic
    Action.LIBRARY_WRITE -> Icons.Rounded.LibraryAdd
    Action.PLAYLISTS_WRITE -> Icons.AutoMirrored.Rounded.PlaylistAdd
    Action.SPOTIFY_CATALOG -> Icons.Rounded.Album
    Action.SPOTIFY_RAW -> Icons.Rounded.Code
    Action.NETWORK -> Icons.Rounded.Public
    Action.WEBVIEW_PAGE -> Icons.Rounded.Web
    Action.WEBVIEW_AUTH -> Icons.Rounded.Key
    Action.STORAGE -> Icons.Rounded.Save
    Action.ATTEST -> Icons.Rounded.Fingerprint
    null -> Icons.Rounded.Extension
}

fun actionLabel(a: Action?): Int = when (a) {
    Action.PLAYBACK_CONTROL -> R.string.action_playback_control
    Action.PLAYBACK_QUEUE -> R.string.action_playback_queue
    Action.PLAYBACK_MIRROR -> R.string.action_playback_mirror
    Action.PLAYBACK_EVENTS -> R.string.action_playback_events
    Action.LYRICS_PROVIDE -> R.string.action_lyrics_provide
    Action.LYRICS_ROMANIZE -> R.string.action_lyrics_romanize
    Action.TRACKINFO_PROVIDE -> R.string.action_trackinfo_provide
    Action.AUDIO_SOURCE -> R.string.action_audio_source
    Action.DOWNLOAD_SOURCE -> R.string.action_download_source
    Action.HOME_SECTIONS -> R.string.action_home_sections
    Action.SEARCH_PROVIDE -> R.string.action_search_provide
    Action.MENU_ITEMS -> R.string.action_menu_items
    Action.PLAYER_BUTTONS -> R.string.action_player_buttons
    Action.SETTINGS_PAGE -> R.string.action_settings_page
    Action.NOTIFY -> R.string.action_notify
    Action.LIBRARY_READ -> R.string.action_library_read
    Action.LIBRARY_WRITE -> R.string.action_library_write
    Action.PLAYLISTS_WRITE -> R.string.action_playlists_write
    Action.SPOTIFY_CATALOG -> R.string.action_spotify_catalog
    Action.SPOTIFY_RAW -> R.string.action_spotify_raw
    Action.NETWORK -> R.string.action_network
    Action.WEBVIEW_PAGE -> R.string.action_webview_page
    Action.WEBVIEW_AUTH -> R.string.action_webview_auth
    Action.STORAGE -> R.string.action_storage
    Action.ATTEST -> R.string.action_attest
    null -> R.string.action_unknown
}

/** An ask's label: `network.any` has its own. */
fun askLabel(a: Ask): Int = if (a.key == Action.NETWORK_ANY) R.string.action_network_any else actionLabel(a.action)

fun areaIcon(area: Area): ImageVector = when (area) {
    Area.PLAYBACK -> Icons.Rounded.PlayCircle
    Area.LYRICS -> Icons.Rounded.Lyrics
    Area.AUDIO -> Icons.Rounded.Headphones
    Area.HOME -> Icons.Rounded.Home
    Area.MENUS -> Icons.Rounded.MoreHoriz
    Area.LIBRARY -> Icons.Rounded.LibraryMusic
    Area.OTHER -> Icons.Rounded.Extension
}

fun areaLabel(area: Area): Int = when (area) {
    Area.PLAYBACK -> R.string.area_playback
    Area.LYRICS -> R.string.area_lyrics
    Area.AUDIO -> R.string.area_audio
    Area.HOME -> R.string.area_home
    Area.MENUS -> R.string.area_menus
    Area.LIBRARY -> R.string.area_library
    Area.OTHER -> R.string.area_other
}

/** A block's icon name ([app.myspoti.extensions.Blocks.ICONS]) as the app's own icon. */
fun blockIcon(name: String): ImageVector = when (name) {
    "add" -> Icons.Rounded.Add
    "album" -> Icons.Rounded.Album
    "artist" -> Icons.Rounded.Person
    "bolt" -> Icons.Rounded.Bolt
    "bookmark" -> Icons.Rounded.Bookmark
    "chart" -> Icons.Rounded.BarChart
    "check" -> Icons.Rounded.Check
    "download" -> Icons.Rounded.Download
    "edit" -> Icons.Rounded.Edit
    "favorite" -> Icons.Rounded.Favorite
    "headphones" -> Icons.Rounded.Headphones
    "info" -> Icons.Rounded.Info
    "language" -> Icons.Rounded.Language
    "link" -> Icons.Rounded.Link
    "lyrics" -> Icons.Rounded.Lyrics
    "mic" -> Icons.Rounded.Mic
    "music" -> Icons.Rounded.MusicNote
    "open" -> Icons.AutoMirrored.Rounded.OpenInNew
    "play" -> Icons.Rounded.PlayArrow
    "playlist" -> Icons.AutoMirrored.Rounded.PlaylistPlay
    "queue" -> Icons.AutoMirrored.Rounded.QueueMusic
    "radio" -> Icons.Rounded.Radio
    "refresh" -> Icons.Rounded.Refresh
    "repeat" -> Icons.Rounded.Repeat
    "search" -> Icons.Rounded.Search
    "settings" -> Icons.Rounded.Settings
    "share" -> Icons.Rounded.Share
    "shuffle" -> Icons.Rounded.Shuffle
    "star" -> Icons.Rounded.Star
    "timer" -> Icons.Rounded.Timer
    "translate" -> Icons.Rounded.Translate
    "tune" -> Icons.Rounded.Tune
    "volume" -> Icons.AutoMirrored.Rounded.VolumeUp
    else -> Icons.Rounded.Extension
}

private fun nameOf(id: String) = Extensions.registry.value.find(id)?.name ?: id

/** A Spotify page an extension's block opens: a song plays, a list or artist opens over the app. */
fun openFromExtension(uri: String) {
    val parts = uri.split(':')
    when (parts.getOrNull(1)) {
        "track" -> com.music.spotui.di.SongPlayer.playSong(uri)
        "episode" -> com.music.spotui.di.SongPlayer.playSong("episode:${parts[2]}")
        else -> com.music.spotui.data.api.InviteLinks.page.value = uri
    }
}

/** What a place is about, for the blocks' `tap`: `{ kind, uri, title, subtitle }`. */
fun placeJson(kind: String, uri: String, title: String, subtitle: String = ""): String = buildJsonObject {
    put("kind", JsonPrimitive(kind)); put("uri", JsonPrimitive(uri)); put("title", JsonPrimitive(title)); put("subtitle", JsonPrimitive(subtitle))
}.toString()

// ── Blocks on a page (Home, search, a song's credits) ──

/**
 * The extensions' blocks for [hook] on [surface], asked with [input] (and tapped with [context]),
 * each group under the name of the extension it comes from. Nothing while a protected surface shows.
 */
@Composable
fun ExtensionBlocks(hook: Hook, surface: Surface, input: String, context: String = input, modifier: Modifier = Modifier, delayMs: Long = 0) {
    if (!Extensions.isReady) return
    val tick by Extensions.blocksTick.collectAsState()
    val registry by Extensions.registry.collectAsState()
    val shown by produceState(Conflicts.Shown<String>(emptyList(), emptyList()), input, tick, registry) {
        // Typing: asked once the text rests.
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        value = runCatching { Extensions.blocks(hook, surface, input) }.getOrDefault(Conflicts.Shown(emptyList(), emptyList()))
    }
    if (shielded() || shown.shown.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        var last: String? = null
        shown.shown.forEach { (owner, block) ->
            if (owner != last) { From(owner); last = owner }
            BlockView(owner, block, surface, context)
        }
    }
}

@Composable
private fun From(owner: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)) {
        Icon(Icons.Rounded.Extension, null, tint = AppMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.ext_from, nameOf(owner)), color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun tapped(owner: String, target: Target?, surface: Surface, context: String) {
    target ?: return
    target.open?.let(::openFromExtension)
    target.action?.let { Extensions.tap(owner, surface, it, context) }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun BlockView(owner: String, b: Block, surface: Surface, context: String) {
    when (b) {
        is Block.Section -> {
            if (b.title.isNotBlank()) Text(b.title, color = AppText, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            b.subtitle?.let { Text(it, color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            b.blocks.forEach { BlockView(owner, it, surface, context) }
        }
        is Block.Text -> Text(
            b.text,
            color = if (b.style == TextStyle.CAPTION) AppMuted else AppText,
            fontSize = when (b.style) { TextStyle.TITLE -> 18.sp; TextStyle.BODY -> 14.sp; TextStyle.CAPTION -> 12.sp },
            fontWeight = if (b.style == TextStyle.TITLE) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        is Block.Card -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp))
                .background(tone(0xFF242424)).clickable(enabled = b.target != null) { tapped(owner, b.target, surface, context) }.padding(10.dp),
        ) {
            b.image?.let { GlideImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))) }
            Column(Modifier.padding(start = if (b.image != null) 12.dp else 0.dp)) {
                Text(b.title, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                b.subtitle?.let { Text(it, color = AppMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        is Block.Image -> GlideImage(
            model = b.url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).aspectRatio(b.ratio).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = b.target != null) { tapped(owner, b.target, surface, context) },
        )
        is Block.Button -> ExtButton(b.label, b.icon, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Extensions.tap(owner, surface, b.action, context) }
        is Block.MenuEntry -> MenuEntryRow(owner, b, showOwner = false) { Extensions.tap(owner, surface, b.action, context) }
        is Block.Items -> {
            b.title?.let { Text(it, color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) }
            if (b.kind == ItemKind.TRACKS) b.items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { openFromExtension(item.uri) }.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    GlideImage(model = item.image, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(tone(0xFF2A2A2A)))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(item.title, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item.subtitle?.let { Text(it, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                b.items.forEach { item ->
                    Column(Modifier.width(140.dp).clickable { openFromExtension(item.uri) }.padding(6.dp)) {
                        GlideImage(model = item.image, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(128.dp).clip(if (b.kind == ItemKind.ARTISTS) CircleShape else RoundedCornerShape(8.dp)).background(tone(0xFF2A2A2A)))
                        Text(item.title, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        item.subtitle?.let { Text(it, color = AppMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtButton(label: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clip(RoundedCornerShape(50)).background(tone(0xFF2A2A2A)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(blockIcon(icon), null, tint = AppText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = AppText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── ⋯ menus ──

/**
 * The extensions' entries at the end of a ⋯ menu ([surface]: a song, album, playlist or artist
 * menu): a few on screen (quotas), the rest under "Extensions ⋯". Each entry names its extension.
 */
@Composable
fun ExtensionMenuEntries(surface: Surface, uri: String, title: String, subtitle: String = "", onDone: () -> Unit) {
    if (!Extensions.isReady || uri.isBlank()) return
    val input = remember(uri, title) { placeJson(surface.key.substringAfter('.'), uri, title, subtitle) }
    val tick by Extensions.blocksTick.collectAsState()
    val shown by produceState(Conflicts.Shown<String>(emptyList(), emptyList()), input, tick) {
        value = runCatching { Extensions.blocks(Hook.MENU, surface, input) }.getOrDefault(Conflicts.Shown(emptyList(), emptyList()))
    }
    if (shielded()) return
    var more by remember { mutableStateOf(false) }
    shown.shown.forEach { (owner, b) -> if (b is Block.MenuEntry) MenuEntryRow(owner, b) { Extensions.tap(owner, surface, b.action, input); onDone() } }
    if (shown.overflow.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(56.dp).clickable { more = !more }.padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Rounded.Extension, null, tint = AppMuted, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.ext_overflow) + " ⋯", color = AppText, fontSize = 16.sp)
        }
        if (more) shown.overflow.forEach { (owner, b) ->
            if (b is Block.MenuEntry) MenuEntryRow(owner, b, indent = true) { Extensions.tap(owner, surface, b.action, input); onDone() }
        }
    }
}

@Composable
private fun MenuEntryRow(owner: String, b: Block.MenuEntry, showOwner: Boolean = true, indent: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(start = if (indent) 32.dp else 16.dp, end = 16.dp),
    ) {
        Icon(blockIcon(b.icon), null, tint = AppMuted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(b.label, color = AppText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (showOwner) {
            Spacer(Modifier.width(8.dp))
            Text(nameOf(owner), color = AppMuted.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── The player ──

/** "Spotify mirrored by <extension>" while an extension keeps Spotify muted (playback.mirror). */
@Composable
fun MirrorIndicator() {
    val by = Extensions.mirrorBy.collectAsState().value ?: return
    Text(stringResource(R.string.mirror_indicator, by), color = AppMuted, fontSize = 12.sp,
        modifier = androidx.compose.ui.Modifier.padding(top = 6.dp))
}

/** The extensions' buttons under the player's own controls: a few on screen, the rest behind ⋯. */
@Composable
fun ExtensionPlayerButtons(track: app.myspoti.extensions.TrackInfo?) {
    if (!Extensions.isReady || track == null) return
    val input = remember(track) { track.toJson() }
    val context = remember(track) { placeJson("track", track.uri, track.title, track.artist) }
    val tick by Extensions.blocksTick.collectAsState()
    val shown by produceState(Conflicts.Shown<String>(emptyList(), emptyList()), input, tick) {
        value = runCatching { Extensions.blocks(Hook.PLAYER, Surface.PLAYER, input) }.getOrDefault(Conflicts.Shown(emptyList(), emptyList()))
    }
    if (shielded() || (shown.shown.isEmpty() && shown.overflow.isEmpty())) return
    var more by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        shown.shown.forEach { (owner, b) ->
            if (b is Block.Button) ExtButton(b.label, b.icon) { Extensions.tap(owner, Surface.PLAYER, b.action, context) }
        }
        if (shown.overflow.isNotEmpty()) Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.ext_overflow), tint = Color.White.copy(alpha = 0.8f)) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                shown.overflow.forEach { (owner, b) ->
                    if (b is Block.Button) DropdownMenuItem(
                        text = { Text("${b.label} · ${nameOf(owner)}", color = AppText) },
                        leadingIcon = { Icon(blockIcon(b.icon), null, tint = AppAccent) },
                        onClick = { more = false; Extensions.tap(owner, Surface.PLAYER, b.action, context) },
                    )
                }
            }
        }
    }
}
