package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.myspoti.api.ArtistRef
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import app.myspoti.engine.R
import com.music.spotui.ui.navigation.artistRoute
import kotlinx.coroutines.launch

/**
 * "Go to artist" on a song by several artists, as in the official app: a sheet lists each of
 * them, and a tap on one opens that artist's page. A song by one artist opens it at once.
 * Shared by the player's artist line and the song menu (one sheet, at the navigation root, so
 * it outlives the menu that asked for it).
 */
object ArtistChooser {
    internal var artists by mutableStateOf<List<ArtistRef>>(emptyList())
    internal var open: (String) -> Unit = {}

    /** Songs whose artists are known: the player looks the playing one up ahead ([prefetch]), so a tap opens at once. */
    private val known = object : LinkedHashMap<String, List<ArtistRef>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ArtistRef>>) = size > 32
    }

    /** The song's artists, from its catalog entry; the displayed names (no ids) when it is out of reach. */
    suspend fun artistsOf(trackId: String, fallbackNames: String): List<ArtistRef> {
        synchronized(known) { known[trackId] }?.let { return it }
        val found = if (trackId.isBlank()) null else runCatching {
            MyspotiRuntime.api.catalog.track("spotify:track:$trackId").artists
        }.getOrNull()?.filter { it.name.isNotBlank() }?.takeIf { it.isNotEmpty() }
        if (found != null) synchronized(known) { known[trackId] = found }
        return found ?: fallbackNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { ArtistRef("", it) }
    }

    /** Looks a song's artists up before anyone asks (the playing song). */
    fun prefetch(trackId: String) {
        if (trackId.isBlank() || synchronized(known) { trackId in known }) return
        MyspotiRuntime.scope.launch { artistsOf(trackId, "") }
    }

    /** Opens the one artist, or the sheet to pick among several. */
    fun go(artists: List<ArtistRef>, navigate: (String) -> Unit) {
        when (artists.size) {
            0 -> return
            1 -> navigate(routeOf(artists[0]))
            else -> { open = navigate; this.artists = artists }
        }
    }

    /** [go] for a song, its artists looked up first. */
    fun goForTrack(trackId: String, fallbackNames: String, navigate: (String) -> Unit) {
        MyspotiRuntime.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            go(artistsOf(trackId, fallbackNames), navigate)
        }
    }

    internal fun routeOf(a: ArtistRef) = artistRoute(a.name, a.uri.substringAfterLast(':'))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun ArtistChooserHost(navController: NavHostController) {
    val artists = ArtistChooser.artists
    if (artists.isEmpty()) return
    val dismiss = { ArtistChooser.artists = emptyList() }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = dismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.choose_artist), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 12.dp))
            artists.forEach { a ->
                // The picture comes in after the name: the sheet opens at once.
                val picture by produceState<String?>(null, a.uri) {
                    value = if (a.uri.isBlank()) null
                        else runCatching { MyspotiRuntime.api.catalog.artist(a.uri).images.minByOrNull { it.width ?: Int.MAX_VALUE }?.url }.getOrNull()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        val go = ArtistChooser.open
                        dismiss()
                        go(ArtistChooser.routeOf(a))
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(tone(0xFF3E3E3E))) {
                        if (picture != null) GlideImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(a.name, color = AppText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The artist line of a song ([line], as shown) with each artist its own tap target, which calls
 * [open] with that artist: the song's real artists (name and uri, see [ArtistChooser.artistsOf]),
 * never the line cut at its commas ("Tyler, The Creator" is one artist). Null until they are
 * known, or when they are not what the line shows (a crossfade's outgoing song, an extension's
 * credit): the line is then a plain text, opened as before.
 */
@Composable
fun rememberArtistCredit(trackId: String, line: String, color: androidx.compose.ui.graphics.Color, open: (ArtistRef) -> Unit): AnnotatedString? {
    val artists by produceState<List<ArtistRef>?>(null, trackId) {
        value = if (trackId.isBlank()) null else ArtistChooser.artistsOf(trackId, "").takeIf { list -> list.isNotEmpty() && list.all { it.uri.isNotBlank() } }
    }
    val onOpen by rememberUpdatedState(open)
    return remember(artists, line, color) {
        val list = artists ?: return@remember null
        // The names keep the line's own colour (the theme would paint links in the accent); a press underlines one.
        val styles = androidx.compose.ui.text.TextLinkStyles(
            style = androidx.compose.ui.text.SpanStyle(color = color),
            pressedStyle = androidx.compose.ui.text.SpanStyle(color = color, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        )
        // Each name, in order, with only separators between them.
        var at = 0
        val starts = IntArray(list.size)
        for ((i, a) in list.withIndex()) {
            val found = line.indexOf(a.name, at)
            if (found < 0 || line.substring(at, found).any { it != ',' && it != '&' && it != '/' && !it.isWhitespace() }) return@remember null
            starts[i] = found
            at = found + a.name.length
        }
        if (line.substring(at).any { it != ',' && it != '&' && it != '/' && !it.isWhitespace() }) return@remember null
        buildAnnotatedString {
            var last = 0
            for ((i, a) in list.withIndex()) {
                append(line.substring(last, starts[i]))
                withLink(LinkAnnotation.Clickable("artist", styles) { onOpen(a) }) { append(a.name) }
                last = starts[i] + a.name.length
            }
            append(line.substring(last))
        }
    }
}
