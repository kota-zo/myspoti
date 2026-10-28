package com.music.spotui.ui.screens

import com.music.spotui.ui.components.secondaryClick
import com.music.spotui.ui.components.pressable
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import com.music.spotui.data.entity.LyricWord
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.preferences.getLyricTranslateLang
import com.music.spotui.data.preferences.isLyricsExtraSources
import com.music.spotui.data.preferences.setLyricsExtraSources
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.rememberFrameClock
import com.music.spotui.ui.viewmodel.LyricsViewModel
import app.myspoti.engine.R
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Spotify's lyrics, as the official Android app shows them: the lyrics card in the
 * Now Playing scroll and the full-screen view, both on the track's own colour
 * (color-lyrics `colors`). Sung and current lines are in the highlight colour,
 * upcoming lines in the text colour; tapping a line seeks to it.
 */

/** The three colours of a lyrics view: background, upcoming text, sung / current text. */
private data class LyricsPalette(val background: Color, val text: Color, val highlight: Color)

private fun paletteFor(lyrics: Lyrics?, accent: Color): LyricsPalette {
    lyrics?.colors?.let { c ->
        // Spotify's colours for the song, with the sung white kept readable: on some songs the
        // background is light enough for white words to fade into it (reported on one song,
        // fine on another). The background is darkened only as much as that takes.
        val hi = Color(c.highlight)
        var bg = Color(c.background)
        var step = 0
        while (contrast(hi, bg) < MIN_CONTRAST && step < 8) { bg = lerp(bg, if (hi.luminance() > 0.5f) Color.Black else Color.White, 0.12f); step++ }
        // Upcoming lines (often black in Spotify's colours) stay readable on that background too.
        val text = Color(c.text).let { t -> if (contrast(t.copy(alpha = 1f), bg) < 1.8f) hi.copy(alpha = 0.55f) else t }
        return LyricsPalette(bg, text, hi)
    }
    // No Spotify colours (fallback source): the artwork colour, like Spotify's own fallback.
    val bg = lerp(accent, Color.Black, 0.15f)
    // B23: upcoming lines were black at 55 % — invisible on a dark (AMOLED) card. On a dark
    // background they are drawn in a slightly dimmed white instead, the sung lines staying
    // the full highlight colour.
    val text = if (bg.luminance() > 0.35f) Color.Black.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.72f)
    return LyricsPalette(bg, text, Color.White)
}

/** WCAG contrast ratio of two colours (1 = the same, 21 = black on white). */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** What the sung (and karaoke) words need against the background to read at a glance. */
private const val MIN_CONTRAST = 3.2f

/** Playback position, re-read on every frame while playing (smooth word highlight). */
@Composable
private fun rememberPosition(running: Boolean): () -> Long {
    val frame = rememberFrameClock(running, fps = 30)
    return remember(frame) {
        // What is heard: Bluetooth plays it a little later than the player is.
        { frame.value; (SongPlayer.getCurrentPosition() - com.music.spotui.di.OutputLag.ms()).coerceAtLeast(0L) }
    }
}

private fun activeIndexFor(lyrics: Lyrics, positionMs: Long): Int {
    if (!lyrics.synced) return -1
    // Binary search: last line starting at or before the position.
    var lo = 0
    var hi = lyrics.lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lyrics.lines[mid].timeMs <= positionMs + 150) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/**
 * How many characters of a word-timed line are lit at [p] (ms): each word lights up whole the
 * moment it is sung (asked for instead of a fill sliding through the words).
 */
private fun sungChars(words: List<LyricWord>, starts: List<Int>, p: Long): Float {
    var out = 0f
    for (i in words.indices) {
        if (p < words[i].startMs) break
        out = (starts[i] + words[i].text.length).toFloat()
    }
    return out
}

/** Seek to a tapped synced line and make sure we're playing. */
private fun jumpTo(timeMs: Long) {
    com.music.spotui.di.Haptics.click()
    SongPlayer.logLyricJump(timeMs)
    SongPlayer.seekTo(timeMs)
    if (!SongPlayer.isPlaying()) SongPlayer.play()
}

private fun shareLyrics(context: Context, title: String, artist: String, line: String?) {
    val id = SongPlayer.currentTrackId
    val text = buildString {
        if (!line.isNullOrBlank() && line != "♪") append("« ").append(line).append(" »\n")
        append(title).append(" · ").append(artist)
        if (id.isNotBlank()) append("\nhttps://open.spotify.com/track/").append(id)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.lyrics_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
internal fun rememberLyricsState(vm: LyricsViewModel, title: String, artist: String): LyricsViewModel.State {
    // currentTrackId is plain data, not Compose state: the playing item's uri re-runs the load on a skip.
    val playingUri = SongPlayer.nowPlaying.value.uri
    // The lyrics extensions changed (one installed, removed…): the song is read again from the new set.
    val sources by com.music.spotui.data.api.LyricsApi.sourcesTick.collectAsState()
    var seen by remember { mutableStateOf(sources) }
    LaunchedEffect(title, artist, playingUri, SongPlayer.currentTrackId, sources) {
        val again = seen != sources
        seen = sources
        SongPlayer.currentLyricsTrack()?.let { vm.load(it, force = again) }
    }
    return vm.state.collectAsState().value
}

/**
 * Keeps the active line about a third down the view, like Spotify. A manual scroll
 * pauses the follow for 3 s.
 */
@Composable
private fun AutoFollow(listState: LazyListState, activeIndex: Int, offsetPx: Int, enabled: Boolean) {
    var userScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start || it is DragInteraction.Stop) userScrollAt = System.currentTimeMillis()
        }
    }
    // The first placement is instant: the screen opens (container transform) already on the
    // current line instead of scrolling there from the top while it grows.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(activeIndex, enabled) {
        if (!enabled || activeIndex < 0) return@LaunchedEffect
        if (!placed) { placed = true; listState.scrollToItem(activeIndex, -offsetPx); return@LaunchedEffect }
        val wait = 3000 - (System.currentTimeMillis() - userScrollAt)
        if (wait > 0) delay(wait)
        listState.animateScrollToItem(activeIndex, -offsetPx)
    }
}

/**
 * One lyric line. Word-synced lines fill word by word; others switch colour as a
 * whole when they become current.
 */
@Composable
private fun LyricLineText(
    line: LyricLine,
    state: Int, // -1 sung, 0 current, 1 upcoming, 2 unsynced
    palette: LyricsPalette,
    fontSize: TextUnit,
    position: () -> Long,
    translation: String?,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    annotated: Boolean = false,
    onHold: (() -> Unit)? = null,
) {
    val target = when (state) {
        1 -> palette.text
        else -> palette.highlight
    }
    val color by animateColorAsState(target, tween(220), label = "lyricColor")
    val lineModifier = modifier
        .fillMaxWidth()
        .then(
            if (onTap != null || onHold != null) Modifier.secondaryClick(onHold).combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onHold,
                onClick = { onTap?.invoke() },
            ) else Modifier
        )
    // Explained by Genius: a light dotted line under each row of the text.
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val underline = if (!annotated) Modifier else Modifier.drawBehind {
        val t = layout ?: return@drawBehind
        val dot = 2.dp.toPx()
        val effect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.01f, dot * 2.5f))
        for (r in 0 until t.lineCount) {
            val y = t.getLineBaseline(r) + 5.dp.toPx()
            drawLine(color.copy(alpha = color.alpha * 0.55f), androidx.compose.ui.geometry.Offset(t.getLineLeft(r), y),
                androidx.compose.ui.geometry.Offset(t.getLineRight(r), y), strokeWidth = dot, cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = effect)
        }
    }
    Column(lineModifier) {
        val words = line.words
        if (state == 0 && words != null) {
            // Karaoke: each word goes straight from the upcoming colour to white as it is sung (drawn
            // on each frame, the line is not recomposed). Words in another colour were hard to tell apart.
            val shown = remember(line) { words.joinToString("") { it.text }.trimEnd() }
            val starts = remember(line) { var c = 0; words.map { w -> c.also { c += w.text.length } } }
            var fill by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            Box {
                Text(
                    text = shown,
                    // The words to come keep the colour of the lines to come; sung, they turn white.
                    color = palette.text,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.25f,
                    fontWeight = FontWeight.Bold,
                    onTextLayout = { layout = it },
                    modifier = underline,
                )
                Text(
                    text = shown,
                    color = palette.highlight,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.25f,
                    fontWeight = FontWeight.Bold,
                    onTextLayout = { fill = it },
                    modifier = Modifier.drawWithContent {
                        val l = fill ?: return@drawWithContent
                        val off = sungChars(words, starts, position()).coerceAtMost(shown.length.toFloat())
                        if (off <= 0f) return@drawWithContent
                        if (off >= shown.length) { drawContent(); return@drawWithContent }
                        val whole = off.toInt()
                        val row = l.getLineForOffset(whole)
                        val x0 = l.getHorizontalPosition(whole, true)
                        val x1 = if (l.getLineForOffset(whole + 1) == row) l.getHorizontalPosition(whole + 1, true) else l.getLineRight(row)
                        val x = x0 + (x1 - x0) * (off - whole)
                        val path = androidx.compose.ui.graphics.Path()
                        for (r in 0 until row) path.addRect(androidx.compose.ui.geometry.Rect(0f, l.getLineTop(r), size.width, l.getLineBottom(r)))
                        path.addRect(androidx.compose.ui.geometry.Rect(0f, l.getLineTop(row), x, l.getLineBottom(row)))
                        clipPath(path) { this@drawWithContent.drawContent() }
                    },
                )
            }
        } else {
            Text(
                text = line.text,
                color = color,
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f,
                fontWeight = FontWeight.Bold,
                onTextLayout = { layout = it },
                modifier = underline,
            )
        }
        AnimatedVisibility(
            visible = !translation.isNullOrBlank(),
            enter = expandVertically(tween(260)) + fadeIn(tween(260)),
            exit = shrinkVertically(tween(260)) + fadeOut(tween(260)),
        ) {
            Text(
                text = translation.orEmpty(),
                color = color.copy(alpha = color.alpha * 0.7f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun lineState(lyrics: Lyrics, index: Int, active: Int): Int = when {
    !lyrics.synced -> 2
    index < active -> -1
    index == active -> 0
    else -> 1
}

/**
 * Fade + small rise of one part of the screen while the container settles. [reveal] is the
 * open progress (0..1), read in the draw phase only: nothing recomposes per frame.
 */
private fun Modifier.revealIn(reveal: (() -> Float)?, start: Float, riseDp: Float, span: Float = 0.3f): Modifier =
    if (reveal == null) this else graphicsLayer {
        val a = ((reveal() - start) / span).coerceIn(0f, 1f)
        alpha = a
        translationY = (1f - a) * riseDp.dp.toPx()
    }

/** Full-screen lyrics (Spotify's "Lyrics" view). [reveal] drives the staggered entrance, null = all shown. */
@Composable
fun LyricsScreen(
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onClose: () -> Unit,
    reveal: (() -> Float)? = null,
) {
    val context = LocalContext.current
    val vm: LyricsViewModel = viewModel()
    val state = rememberLyricsState(vm, title, artist)
    val lyrics = (state as? LyricsViewModel.State.Loaded)?.lyrics
    val palette = paletteFor(lyrics, accentColor)
    val bg by animateColorAsState(palette.background, tween(300), label = "lyricsBg")
    val playing = com.music.spotui.di.Graph.currentSongState.playingState.value
    val position = rememberPosition(playing)
    val targetLanguage = getLyricTranslateLang(context).ifBlank { Locale.getDefault().language.ifBlank { "en" } }
    var menu by remember { mutableStateOf(false) }
    // What the song means, from Genius: asked once the lyrics are there; the button shows only when it says something.
    // Looked up when the song started (App.kt): here at once, and still here on coming back.
    val lang = com.music.spotui.ui.components.readerLanguage()
    val trackId = SongPlayer.nowPlaying.value.uri.substringAfterLast(':')
    val meaning = com.music.spotui.data.api.GeniusMeaning.current.value?.takeIf { it.first == trackId }?.second
    var meaningOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf<Int?>(null) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hintPrefs = remember { context.getSharedPreferences("lyrics", android.content.Context.MODE_PRIVATE) }
    var hintSeen by remember { mutableStateOf(hintPrefs.getBoolean("genius_hold_hint_seen", false)) }
    LaunchedEffect(trackId, title, artist) {
        // Opened before the look-up began (or it failed): asked now.
        if (meaning == null) com.music.spotui.data.api.GeniusMeaning.prefetch(trackId, title, artist, lang)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The background comes in over the first instants so the card's own lines
            // dissolve under it instead of vanishing on the first frame.
            .drawBehind { drawRect(bg, alpha = if (reveal == null) 1f else (reveal() / 0.08f).coerceIn(0f, 1f)) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── Header: close, title / artist, more ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp).revealIn(reveal, 0.3f, 8f),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose,
                ),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.lyrics_fullscreen_close_button_readout_value), tint = palette.highlight, modifier = Modifier.size(32.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(title, color = palette.highlight, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, color = palette.highlight.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            meaning?.takeIf { !it.about.isNullOrBlank() }?.let { m ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { meaningOpen = true },
                ) {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = stringResource(R.string.lyrics_meaning_title), tint = palette.highlight)
                }
                if (meaningOpen) com.music.spotui.ui.components.AboutSheet(m, palette.background) { meaningOpen = false }
            }
            Box {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { menu = true },
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.content_description_context_menu), tint = palette.highlight)
                }
                LyricsMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    canTranslate = state is LyricsViewModel.State.Loaded,
                    translating = (state as? LyricsViewModel.State.Loaded)?.showTranslation == true,
                    onTranslate = { vm.toggleTranslation(targetLanguage) },
                    onReload = { SongPlayer.currentLyricsTrack()?.let { vm.load(it, force = true) } },
                )
            }
        }

        when (state) {
            is LyricsViewModel.State.Loading ->
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    com.music.spotui.ui.components.SkeletonLyrics(palette.highlight)
                }
            is LyricsViewModel.State.NotFound ->
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.lyrics_fullscreen_no_lyrics_error_version_two), color = palette.highlight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            is LyricsViewModel.State.Loaded -> {
                val l = state.lyrics
                // Lines Genius explains: lightly underlined, held to read the explanation.
                val notes = remember(l, meaning) { meaning?.let { m -> com.music.spotui.ui.components.notesByLine(m, l.lines.map { it.text }) }.orEmpty() }
                val m = meaning
                noteOpen?.let { n -> if (m != null) com.music.spotui.ui.components.NoteSheet(m, n, palette.background) { noteOpen = null } }
                // Translation is requested by onTranslate; do not upload lyrics on screen entry.
                val active by remember(l) { derivedStateOf { activeIndexFor(l, position()) } }
                // Other lyrics for the same song (Spotify's lines, then the word-timed ones; or the
                // setting changed): the new ones fade in over the old, already on the line being
                // sung, instead of the list jumping.
                androidx.compose.animation.Crossfade(
                    targetState = l, animationSpec = tween(450), label = "lyricsSwap",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { l ->
                val active by remember(l) { derivedStateOf { activeIndexFor(l, position()) } }
                val notes = remember(l, meaning) { meaning?.let { m -> com.music.spotui.ui.components.notesByLine(m, l.lines.map { it.text }) }.orEmpty() }
                val listState = rememberLazyListState()
                // Lines near the current one rise in one after the other, the first just
                // above it; the rest of the screen's lines have no visible entrance.
                val staggerBase = remember(l) { (active - 4).coerceAtLeast(0) }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val offsetPx = with(LocalDensity.current) { (maxHeight * 0.28f).roundToPx() }
                    AutoFollow(listState, active, offsetPx, enabled = l.synced)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 48.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (notes.isNotEmpty() && !hintSeen) item(key = "genius-hint") {
                            Text(
                                stringResource(R.string.lyrics_meaning_hint),
                                color = palette.highlight.copy(alpha = 0.8f), fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        if (!l.synced) item {
                            Text(
                                stringResource(R.string.lyrics_fullscreen_unsynced),
                                color = palette.highlight.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        itemsIndexed(l.lines, key = { i, _ -> i }) { i, line ->
                            LyricLineText(
                                line = line,
                                state = lineState(l, i, active),
                                palette = palette,
                                fontSize = 24.sp,
                                position = position,
                                translation = if (state.showTranslation) state.translations?.getOrNull(i) else null,
                                onTap = if (l.synced) ({ jumpTo(line.timeMs) }) else null,
                                annotated = i in notes,
                                onHold = notes[i]?.let { n -> {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    noteOpen = n
                                    if (!hintSeen) { hintSeen = true; hintPrefs.edit().putBoolean("genius_hold_hint_seen", true).apply() }
                                } },
                                modifier = Modifier.revealIn(reveal, 0.35f + 0.045f * (i - staggerBase).coerceIn(0, 8), 22f),
                            )
                        }
                        l.provider?.let { p ->
                            item {
                                Text(
                                    stringResource(R.string.lyrics_credential_note, p),
                                    color = palette.highlight.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 24.dp),
                                )
                            }
                        }
                    }
                }
                }
                val sameLanguage = state.sourceLanguage?.substringBefore('-')
                    ?.equals(targetLanguage.substringBefore('-'), ignoreCase = true) == true
                LyricsControls(
                    palette = palette,
                    position = position,
                    showTranslate = state.translationChecked && !sameLanguage,
                    translationActive = state.showTranslation,
                    translating = state.translating && state.translationRequested,
                    onTranslate = { vm.toggleTranslation(targetLanguage) },
                    onShare = { shareLyrics(context, title, artist, l.lines.getOrNull(active)?.text) },
                    modifier = Modifier.revealIn(reveal, 0.5f, 16f, span = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun LyricsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    canTranslate: Boolean,
    translating: Boolean,
    onTranslate: () -> Unit,
    onReload: () -> Unit,
) {
    val context = LocalContext.current
    var extra by remember { mutableStateOf(isLyricsExtraSources(context)) }
    var words by remember { mutableStateOf(com.music.spotui.data.preferences.isLyricsWordSync(context)) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Word by word (karaoke) or line by line: the app then picks the source by itself.
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.lyrics_word_sync_title))
                    Text(stringResource(if (words) R.string.lyrics_word_sync_on else R.string.lyrics_word_sync_off), fontSize = 12.sp, color = Color.Gray)
                }
            },
            trailingIcon = { if (words) Icon(Icons.Default.Check, contentDescription = null) },
            onClick = {
                words = !words
                com.music.spotui.data.preferences.setLyricsWordSync(context, words)
                LyricsApi.wordSync = words
                LyricsApi.forgetAll()
                onDismiss()
                onReload()
            },
        )
        if (canTranslate) DropdownMenuItem(
            text = { Text(stringResource(if (translating) R.string.lyrics_fullscreen_translate_button_enabled_readout_value else R.string.lyrics_fullscreen_context_menu_main_page_translate_lyrics_action)) },
            onClick = { onDismiss(); onTranslate() },
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.lyrics_extra_title))
                    Text(stringResource(R.string.lyrics_extra_summary), fontSize = 12.sp, color = Color.Gray)
                }
            },
            trailingIcon = { if (extra) Icon(Icons.Default.Check, contentDescription = null) },
            onClick = {
                extra = !extra
                setLyricsExtraSources(context, extra)
                LyricsApi.extraSources = extra
                onDismiss()
                onReload()
            },
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.lyrics_reload)) }, onClick = { onDismiss(); onReload() })
    }
}

@Composable
private fun LyricsChip(label: String, icon: Int, active: Boolean, loading: Boolean, palette: LyricsPalette, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) palette.highlight else Color.Black.copy(alpha = 0.22f))
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        if (loading) CircularProgressIndicator(color = palette.highlight, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        else Icon(painterResource(icon), contentDescription = null, tint = if (active) palette.background else palette.highlight, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (active) palette.background else palette.highlight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LyricsControls(
    palette: LyricsPalette,
    position: () -> Long,
    showTranslate: Boolean,
    translationActive: Boolean,
    translating: Boolean,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = SongPlayer.getDuration().coerceAtLeast(0L)
    val playing = com.music.spotui.di.Graph.currentSongState.playingState.value
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            LyricsChip(stringResource(R.string.lyrics_fullscreen_chip_label_share), R.drawable.ic_share, false, false, palette, onShare)
            if (showTranslate) LyricsChip(stringResource(R.string.lyrics_fullscreen_chip_label_translate), R.drawable.ic_translate_lyrics, translationActive, translating, palette, onTranslate)
        }
        LyricsSeekBar(
            progress = { if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
            color = palette.highlight,
            onSeek = { SongPlayer.seekTo((durationMs * it).toLong()) },
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            val pos by remember { derivedStateOf { position() / 1000 } }
            Text(formatLyricsTime(pos * 1000), color = palette.highlight.copy(alpha = 0.75f), fontSize = 12.sp)
            Text(formatLyricsTime(durationMs), color = palette.highlight.copy(alpha = 0.75f), fontSize = 12.sp)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
                .size(60.dp)
                .clip(CircleShape)
                .background(palette.highlight)
                .pressable(0.92f) { if (SongPlayer.isPlaying()) SongPlayer.pause() else SongPlayer.play() },
        ) {
            com.music.spotui.ui.components.PlayPauseIcon(
                playing = playing,
                contentDescription = if (playing) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                tint = palette.background,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun LyricsSeekBar(progress: () -> Float, color: Color, onSeek: (Float) -> Unit) {
    var widthPx by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragValue = (dragValue + delta / widthPx).coerceIn(0f, 1f) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(widthPx) {
                detectTapGestures { onSeek((it.x / widthPx).coerceIn(0f, 1f)) }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { dragging = true; dragValue = progress() },
                onDragStopped = { dragging = false; onSeek(dragValue) },
            ),
    ) {
        val value = if (dragging) dragValue else progress()
        val h = 3.dp.toPx()
        val y = size.height / 2f
        drawRoundRect(color.copy(alpha = 0.3f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(color, Offset(0f, y - h / 2), Size(size.width * value, h), CornerRadius(h / 2))
        drawCircle(color, radius = 6.dp.toPx(), center = Offset(size.width * value, y))
    }
}

private fun formatLyricsTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

/** Height of the lines window in the Now Playing card. */
private val CARD_LINES_HEIGHT = 296.dp

/**
 * The lyrics card in the Now Playing scroll. Hidden while loading and when the
 * track has no lyrics, as in the official app.
 */
@Composable
fun InlineLyrics(
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current
    val vm: LyricsViewModel = viewModel()
    val state = rememberLyricsState(vm, title, artist)
    val s = state as? LyricsViewModel.State.Loaded ?: return
    val l = s.lyrics
    val palette = paletteFor(l, accentColor)
    val position = rememberPosition(com.music.spotui.di.Graph.currentSongState.playingState.value)
    val active by remember(l) { derivedStateOf { activeIndexFor(l, position()) } }
    val listState = rememberLazyListState()
    LaunchedEffect(active, l) {
        if (l.synced && active >= 0) listState.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp, 16.dp, 40.dp)
            // The card's live bounds are read when the lyrics open / close (see LyricsOverlay).
            .onGloballyPositioned { LyricsCardAnchor.coords = it }
            .clip(RoundedCornerShape(12.dp))
            .background(palette.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onExpand() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (l.uncapped) R.string.lyrics_card_title else R.string.lyrics_card_title_preview),
                color = palette.highlight,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            CardIconButton(palette, stringResource(R.string.lyrics_share), { Icon(painterResource(R.drawable.ic_share), null, tint = palette.highlight, modifier = Modifier.size(16.dp)) }) {
                shareLyrics(context, title, artist, l.lines.getOrNull(active)?.text)
            }
            Spacer(Modifier.width(8.dp))
            CardIconButton(palette, stringResource(R.string.lyrics_fullscreen), { Icon(Icons.Default.OpenInFull, null, tint = palette.highlight, modifier = Modifier.size(16.dp)) }, onExpand)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(CARD_LINES_HEIGHT),
        ) {
            itemsIndexed(l.lines, key = { i, _ -> i }) { i, line ->
                LyricLineText(
                    line = line,
                    state = lineState(l, i, active),
                    palette = palette,
                    fontSize = 22.sp,
                    position = position,
                    translation = null,
                    onTap = null,
                )
            }
        }
        if (!l.uncapped) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(palette.highlight)
                    .clickable { onExpand() }
                    .padding(16.dp, 8.dp),
            ) {
                Text(stringResource(R.string.lyrics_card_show_lyrics_button), color = palette.background, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CardIconButton(palette: LyricsPalette, description: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(onClick = onClick),
    ) { icon() }
}

/** The inline card's layout node, so the full screen can grow out of it and fold back into it. */
private object LyricsCardAnchor { var coords: LayoutCoordinates? = null }

/** The visible part of the container: a rounded rectangle that moves from the card to the screen. */
private class LyricsWindow(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

private val CARD_CORNER = 12.dp

/**
 * Hosts [LyricsScreen] as a container transform: the screen is laid out once at full size and
 * revealed through a window that grows from the inline card's bounds (position, size, corner
 * radius, same colour) to the whole page, while its lines rise in staggered; closing plays
 * it backwards into the card. Only graphicsLayer / draw state moves per frame, no recomposition.
 * Without a visible card (scrolled away) it slides up as it always did.
 */
@Composable
fun LyricsOverlay(
    visible: Boolean,
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onClose: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    // Window the container starts from, in this host's pixels; null = slide-up fallback.
    var from by remember { mutableStateOf<Rect?>(null) }
    val host = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // "Remove animations": the lyrics are there, or gone, at once.
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()

    fun cardRect(): Rect? {
        val h = host[0]?.takeIf { it.isAttached } ?: return null
        val card = LyricsCardAnchor.coords?.takeIf { it.isAttached } ?: return null
        val r = h.localBoundingBoxOf(card, clipBounds = false)
        val screen = Rect(0f, 0f, h.size.width.toFloat(), h.size.height.toFloat())
        val seen = r.intersect(screen)
        // Mostly off-screen card: growing out of it would look odd, slide up instead.
        return if (r.height > 0f && seen.height >= r.height * 0.4f && seen.width > 0f) r else null
    }

    LaunchedEffect(visible) {
        if (visible) {
            from = cardRect()
            mounted = true
            val spec = if (from != null) tween<Float>(560, easing = FastOutSlowInEasing) else tween(250, easing = EaseOut)
            if (reduce) progress.snapTo(1f) else progress.animateTo(1f, spec)
        } else if (mounted) {
            // Fold into the card where it is now; a slide-up opening slides back down.
            if (from != null) cardRect()?.let { from = it }
            val spec = if (from != null) tween<Float>(470, easing = FastOutSlowInEasing) else tween(200, easing = EaseOut)
            if (reduce) progress.snapTo(0f) else progress.animateTo(0f, spec)
            mounted = false
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { host[0] = it }) {
        if (!mounted) return@Box
        val window = from
        val cornerPx = with(LocalDensity.current) { CARD_CORNER.toPx() }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = progress.value
                if (window == null) {
                    translationY = (1f - p) * size.height
                } else {
                    shape = LyricsWindow(
                        Rect(
                            window.left * (1f - p), window.top * (1f - p),
                            window.right + (size.width - window.right) * p, window.bottom + (size.height - window.bottom) * p,
                        ),
                        cornerPx * (1f - p),
                    )
                    clip = true
                }
            },
        ) {
            LyricsScreen(
                title = title,
                artist = artist,
                album = album,
                accentColor = accentColor,
                onClose = onClose,
                reveal = if (window == null) null else ({ progress.value }),
            )
        }
    }
}
