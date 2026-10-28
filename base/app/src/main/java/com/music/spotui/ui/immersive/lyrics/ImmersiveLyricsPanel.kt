/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: LyricsPanel's list, PanelVoice, the gap dots, the loading skeleton and the
 * scroll lead / stagger, fed by Myspoti's LyricsViewModel. Myspoti's lyrics are mostly
 * line-synced, so a line lights whole (BitChord's line-level path) and the word sweep runs
 * only where a line has words. Translation, romanization, particles, duets, backing vocals,
 * line picking and the player-controls hand-off are left out for now.
 */
package com.music.spotui.ui.immersive.lyrics

import com.music.spotui.ui.components.secondaryClick
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.myspoti.engine.R
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.GLOW_ALPHA
import com.music.spotui.ui.look.GLOW_ROOM
import com.music.spotui.ui.look.INACTIVE_SCALE
import com.music.spotui.ui.look.LINE_FALLOFF_ALPHA
import com.music.spotui.ui.look.LINE_FALLOFF_BLUR
import com.music.spotui.ui.look.LYRIC_EASING
import com.music.spotui.ui.look.LYRIC_SETTLE_MS
import com.music.spotui.ui.look.SweptLine
import com.music.spotui.ui.look.SweptLyricLine
import com.music.spotui.ui.look.UNSUNG_ALPHA
import com.music.spotui.ui.look.rememberReduceMotion
import com.music.spotui.ui.look.toSwept
import com.music.spotui.ui.viewmodel.LyricsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.music.spotui.ui.immersive.components.immersiveClickable

/**
 * The lyrics of the playing track, inside the player: big tight type, the playing line crisp
 * and everything else falling out of focus the further it is from it. Blur needs API 31+, so
 * alpha carries the same hierarchy on older devices.
 *
 * Scrolling by hand clears the blur and suspends the auto-follow, so you can read ahead; a
 * moment after you stop it snaps back to the song. Tapping a line seeks to it; holding one that Genius explains opens the note.
 *
 * The list brings its own side gutter and top margin; [contentPadding] is the extra room the
 * list keeps clear of whatever the player draws over it.
 */
@Composable
fun ImmersiveLyricsPanel(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val vm: LyricsViewModel = viewModel()
    val song = Graph.currentSongState
    val title = song.title.value
    val artist = song.singer.value
    // currentTrackId is plain data, not Compose state: a skip whose title and artist are the same
    // (or that reports its id after them) would never re-run this. The playing item's uri is state.
    val playingUri = SongPlayer.nowPlaying.value.uri
    LaunchedEffect(title, artist, playingUri, SongPlayer.currentTrackId) {
        SongPlayer.currentLyricsTrack()?.let(vm::load)
    }
    val state by vm.state.collectAsState()
    val trackKey = "$playingUri|${SongPlayer.currentTrackId}|$title|$artist"

    when (val current = state) {
        is LyricsViewModel.State.Loading -> LyricsSkeleton(
            modifier.padding(contentPadding).padding(horizontal = Immersive.Gutter),
        )
        is LyricsViewModel.State.NotFound -> Box(
            modifier.padding(contentPadding).padding(horizontal = Immersive.Gutter),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.immersive_lyrics_none),
                style = Immersive.Headline,
                color = Immersive.TextSecondary,
            )
        }
        is LyricsViewModel.State.Loaded -> {
            // A new track, or new lyrics for it, starts the list over: its scroll position and
            // handover state belong to the lines they were made for.
            key(trackKey, System.identityHashCode(current.lyrics)) {
                LyricsList(current.lyrics, trackKey, modifier, contentPadding)
            }
        }
    }
}

// ─── Rows ────────────────────────────────────────────────────────────────────────────────

/**
 * One row of the panel: a line of the song, or the break counted out between two of them.
 * [swept] carries the word timings where the source has them.
 */
private class PanelLine(
    val timeMs: Long,
    val text: String,
    val isGap: Boolean,
    /** Where a break ends; unused for a sung line. */
    val untilMs: Long,
    val swept: SweptLine,
) {
    val isWordSynced: Boolean get() = !isGap && swept.isWordSynced

    /** When the line stops being sung, as far as the source says. */
    val endMs: Long get() = if (isGap) untilMs else swept.endMs
}

private fun isGapText(text: String): Boolean = text.isBlank() || text.trim() == "♪"

/** Breaks shorter than this before the first line are not worth counting out. */
private const val INTRO_GAP_MIN_MS = 3_000L

/** How long a break at the very end is counted over. */
private const val TRAILING_GAP_MS = 5_000L

private fun panelLines(lyrics: Lyrics, synced: Boolean): List<PanelLine> {
    val source = lyrics.lines
    val out = ArrayList<PanelLine>(source.size + 1)
    if (synced) {
        // An intro: the panel counts it out rather than sitting on nothing.
        val firstSung = source.firstOrNull { !isGapText(it.text) }
        if (firstSung != null && firstSung.timeMs >= INTRO_GAP_MIN_MS && source.first().timeMs > 0L) {
            out += PanelLine(0L, "", true, firstSung.timeMs, SweptLine(0L, ""))
        }
    }
    source.forEachIndexed { index, line ->
        val next = source.getOrNull(index + 1)?.timeMs
        if (synced && isGapText(line.text)) {
            out += PanelLine(line.timeMs, "", true, next ?: (line.timeMs + TRAILING_GAP_MS), SweptLine(line.timeMs, ""))
        } else {
            out += PanelLine(line.timeMs, line.text, false, 0L, line.toSwept(next))
        }
    }
    return out
}

// ─── Constants (BitChord's, at its sizes) ───────────────────────────────────────────────

/** How tall a break stands while it is playing; nothing when it is not. */
private val GAP_ROW_HEIGHT = 40.dp
private val GAP_ROW_SPACING = 16.dp

/** What a line reads at while the list is being scrolled by hand. */
private const val BROWSING_ALPHA = 0.8f

/** A line under a finger dips, the way a button does. */
private const val PRESSED_SCALE = 0.96f

/** The break between verses, counted out rather than marked. */
private const val GAP_DOTS = 3
private val GAP_DOT_SIZE = 13.dp
private val GAP_DOT_GAP = 5.dp
private const val GAP_DOT_REST = 0.25f
private const val GAP_REST_SCALE = 0.76f
private const val GAP_RUNNING = -1f

/** How long the panel takes to settle on a new line, and how far ahead it starts. */
private const val SCROLL_LEAD_MIN_MS = 350L
private const val SCROLL_LEAD_MAX_MS = 500L

/**
 * How the rows fan out as the panel moves between lines: each row after the one being
 * scrolled to sets off slightly later than the row before it, up to a few rows back.
 */
private const val STAGGER_STEPS = 3
private const val STAGGER_FRACTION = 0.06f

/** One handover: how far the panel is going, and how long it is taking. */
private class ScrollRun(val id: Int, val delta: Float, val durationMs: Int) {
    /** The last row to arrive does so this long after the panel sets off. */
    val spanMs: Float get() = durationMs * (1f + STAGGER_FRACTION * STAGGER_STEPS)
}

/**
 * How long before a line lands the panel starts moving to it — and how long the move then
 * takes, which is the same number: the silence between the last word of the line being sung
 * and the first of the next, bounded either side.
 */
private fun scrollLead(rows: List<PanelLine>, positionMs: Long): Long {
    val current = rows.indexOfLast { it.timeMs <= positionMs }
    if (current < 0) return SCROLL_LEAD_MIN_MS
    val next = rows.getOrNull(current + 1) ?: return SCROLL_LEAD_MIN_MS
    val gap = next.timeMs - rows[current].endMs
    return gap.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
}

/** The row being sung at [positionMs], or -1 before the first one. */
private fun activeRow(rows: List<PanelLine>, positionMs: Long): Int =
    rows.indexOfLast { it.timeMs <= positionMs }

// ─── The list ────────────────────────────────────────────────────────────────────────────

@Composable
private fun LyricsList(
    lyrics: Lyrics,
    trackKey: String,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val isSynced = remember(lyrics) { lyrics.synced && lyrics.lines.any { it.timeMs > 0L } }
    val rows = remember(lyrics, isSynced) { panelLines(lyrics, isSynced) }
    val playing = Graph.currentSongState.playingState.value
    val head = rememberLyricsPlayhead(trackKey, playing && isSynced)
    val clock = head.clock

    // Genius's notes on the lines, read in the background when the song started (App.kt) and kept
    // by track id. A line it explains sits on a soft pill with a small quote mark after its last
    // word; the mark opens the note, and so does a long press anywhere on the line. Tap still seeks.
    val trackId = SongPlayer.nowPlaying.value.uri.substringAfterLast(':')
    val genTitle = Graph.currentSongState.title.value
    val genArtist = Graph.currentSongState.singer.value
    val genLang = com.music.spotui.ui.components.readerLanguage()
    val meaning = com.music.spotui.data.api.GeniusMeaning.current.value?.takeIf { it.first == trackId }?.second
    LaunchedEffect(trackId, genTitle, genArtist) {
        if (meaning == null) com.music.spotui.data.api.GeniusMeaning.prefetch(trackId, genTitle, genArtist, genLang)
    }
    val notes = remember(rows, meaning) {
        meaning?.let { com.music.spotui.ui.components.notesByLine(it, rows.map { r -> r.text }) }.orEmpty()
    }
    var noteOpen by remember(trackKey) { mutableStateOf<Int?>(null) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    noteOpen?.let { n ->
        com.music.spotui.ui.immersive.sheets.ImmersiveMeaningSheet(
            trackId, genTitle, genArtist, n, coverUrl = Graph.currentSongState.coverUri.value,
        ) { noteOpen = null }
    }
    val noteMeasurer = rememberTextMeasurer(cacheSize = 16)

    val activeRow by remember(rows, isSynced) {
        derivedStateOf { if (!isSynced) -1 else activeRow(rows, clock.longValue) }
    }
    // Where the panel is heading, which is a beat ahead of where the singing is. Movement that
    // starts on the downbeat arrives after it; started during the run-up, the words are under
    // your eye when they land. Kept apart from [activeRow] on purpose: this leads, and the
    // brightness and the sweep must not.
    val leadLine by remember(rows, isSynced) {
        derivedStateOf {
            if (!isSynced) {
                -1
            } else {
                val now = clock.longValue
                activeRow(rows, now + scrollLead(rows, now))
            }
        }
    }
    // What the stack arranges itself around. The outgoing line starts dimming as the panel
    // leaves it rather than when its last word ends, so the dim, the blur and the movement
    // are one gesture.
    val focusLine = if (leadLine >= 0) leadLine else activeRow

    val listState = rememberLazyListState()
    // LayoutInfo changes on every scroll frame. Observe only height here so the entire lyrics
    // list is not recomposed for every scrolling pixel.
    val viewportHeight by remember(listState) {
        derivedStateOf { listState.layoutInfo.viewportSize.height }
    }
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    var browsing by remember { mutableStateOf(false) }

    val reduceMotion = rememberReduceMotion()
    val blurOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !reduceMotion

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // Suspends the panel's own following, and nothing more.
            if (interaction is DragInteraction.Start) browsing = true
        }
    }

    val currentLine by rememberUpdatedState(focusLine)
    val activeOnScreen by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == currentLine } }
    }
    // Paused, there is no song to follow back to, so a hand scroll should sit wherever it was
    // left rather than snapping back on these timers.
    LaunchedEffect(browsing, activeOnScreen, listState.isScrollInProgress, playing) {
        if (playing && browsing && activeOnScreen && !listState.isScrollInProgress) {
            delay(600)
            browsing = false
        }
    }
    LaunchedEffect(browsing, listState.isScrollInProgress, playing) {
        if (playing && browsing && !listState.isScrollInProgress) {
            delay(5_000)
            browsing = false
        }
    }

    // The panel's own journey, published so each row can work out how far behind it should be
    // running. Held as a plain value plus a frame clock rather than an animation per row:
    // sixty rows each with their own Animatable is sixty animations to start and stop on every
    // handover.
    var run by remember { mutableStateOf(ScrollRun(0, 0f, LYRIC_SETTLE_MS)) }
    val since = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(run.id) {
        if (run.id == 0) return@LaunchedEffect
        animate(
            initialValue = 0f,
            targetValue = run.spanMs,
            animationSpec = tween(run.spanMs.toInt(), easing = LinearEasing),
        ) { value, _ -> since.floatValue = value }
    }
    var placed by remember { mutableStateOf(false) }
    // A newer line replaces an unfinished automatic scroll. Only a user's browsing gesture
    // should suspend following, not our own animation.
    LaunchedEffect(focusLine, browsing) {
        if (isSynced && !browsing && focusLine >= 0 && focusLine in rows.indices) {
            snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusLine }
            when {
                !placed -> {
                    listState.scrollToItem(focusLine, scrollOffset = 0)
                    placed = true
                }
                // Already on screen, which is the ordinary case of handing over to the next
                // line: its distance is known, so the move can be given the run-up's own
                // duration and curve instead of the list's default spring.
                visible != null -> {
                    val span = scrollLead(rows, clock.longValue).toInt()
                    run = ScrollRun(run.id + 1, visible.offset.toFloat(), span)
                    listState.animateScrollBy(
                        value = visible.offset.toFloat(),
                        animationSpec = tween(durationMillis = span, easing = LYRIC_EASING),
                    )
                }
                // Somewhere off screen — after a seek, or a long instrumental scrolled past.
                else -> listState.animateScrollToItem(focusLine, scrollOffset = 0)
            }
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    LazyColumn(
        state = listState,
        modifier = modifier
            .nestedScroll(keepScroll)
            .fadingEdges(),
        // Each row carries GLOW_ROOM of its own inset for the halo, so the list hands that
        // much back — otherwise the lines would sit a glow's width further apart and further in.
        contentPadding = PaddingValues(
            start = (Immersive.Gutter + contentPadding.calculateStartPadding(layoutDirection) - GLOW_ROOM)
                .coerceAtLeast(0.dp),
            top = contentPadding.calculateTopPadding() + 40.dp - GLOW_ROOM,
            end = (Immersive.Gutter + contentPadding.calculateEndPadding(layoutDirection) - GLOW_ROOM)
                .coerceAtLeast(0.dp),
            bottom = contentPadding.calculateBottomPadding() +
                if (isSynced) with(density) { viewportHeight.toDp() } * 0.8f else 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(rows) { index, row ->
            PanelRow(
                index = index,
                row = row,
                synced = isSynced,
                activeRow = activeRow,
                focusLine = focusLine,
                run = run,
                since = since,
                browsing = browsing,
                blurOk = blurOk,
                glowing = blurOk,
                clock = clock,
                noteMeasurer = noteMeasurer,
                onNote = notes[index]?.let { n -> { noteOpen = n } },
                onHold = notes[index]?.let { n ->
                    {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        noteOpen = n
                    }
                },
                onSeek = { timeMs ->
                    SongPlayer.logLyricJump(timeMs)
                    head.announceJump()
                    SongPlayer.seekTo(timeMs)
                    SongPlayer.play()
                },
            )
        }
    }
}

private const val BLUR_SWITCH_MS = 200

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelRow(
    index: Int,
    row: PanelLine,
    synced: Boolean,
    activeRow: Int,
    focusLine: Int,
    run: ScrollRun,
    since: FloatState,
    browsing: Boolean,
    blurOk: Boolean,
    glowing: Boolean,
    clock: MutableLongState,
    noteMeasurer: TextMeasurer,
    /** Opens the Genius note on this line; null when Genius has none. */
    onNote: (() -> Unit)?,
    onHold: (() -> Unit)?,
    onSeek: (Long) -> Unit,
) {
    if (!synced && row.isGap) {
        Spacer(Modifier.height(14.dp))
        return
    }

    // Off the line being sung, not off the line the panel is heading for. Brightness is what
    // says "these are the words right now", so it cannot run ahead of them.
    val offset = if (activeRow < 0) 0 else index - activeRow
    val isActive = synced && index == activeRow
    // Symmetric either side of the playing line, and shallow: the two rows around it stay
    // readable, and everything past that recedes to the same floor rather than fading to nothing.
    val step = abs(offset).coerceAtMost(LINE_FALLOFF_ALPHA.lastIndex)
    // Blur follows the line switch (focusLine), not the first word's start: the new line
    // clears and the old one blurs back in the same short tween. Reduce motion drops blur.
    val focusStep = (if (focusLine < 0) 0 else abs(index - focusLine))
        .coerceAtMost(LINE_FALLOFF_BLUR.lastIndex)
    val blur by animateDpAsState(
        targetValue = when {
            !synced || !blurOk || browsing || (focusLine >= 0 && index == focusLine) -> 0.dp
            else -> LINE_FALLOFF_BLUR[focusStep]
        },
        animationSpec = tween(BLUR_SWITCH_MS, easing = LYRIC_EASING),
        label = "lyricBlur",
    )
    val lineAlpha by animateFloatAsState(
        targetValue = when {
            !synced -> 0.95f
            isActive -> 1f
            // Reading by hand is not following along: the stack flattens to one brightness.
            browsing -> BROWSING_ALPHA
            else -> LINE_FALLOFF_ALPHA[step]
        },
        animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
        label = "lyricAlpha",
    )

    if (row.isGap) {
        GapRow(row, clock, isActive, blur, lineAlpha, onSeek)
        return
    }

    val style = if (synced) {
        Immersive.Lyric
    } else {
        Immersive.Lyric.copy(fontSize = 26.sp, lineHeight = 33.sp)
    }
    val lineStyle = remember(style, row.text) { voiceStyle(style, row.text) }
    // The stack sits fractionally back and the playing line comes forward to meet you. Rows
    // behind the one being scrolled to are the ones that fan out; the ones it is moving away
    // from arrive together.
    val sung = offset < 0
    val behind = if (run.delta >= 0f) index - focusLine else focusLine - index
    val staggerDelay = behind.coerceIn(0, STAGGER_STEPS) * STAGGER_FRACTION * run.durationMs
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> PRESSED_SCALE
            isActive || !synced -> 1f
            else -> INACTIVE_SCALE
        },
        animationSpec = tween(
            durationMillis = if (pressed) 120 else LYRIC_SETTLE_MS,
            easing = LYRIC_EASING,
        ),
        label = "lyricScale",
    )
    // The bloom on the line being sung fades in and out with the line rather than switching.
    val glow by animateFloatAsState(
        targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "lyricGlow",
    )
    val shape = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            // Anchored to the left edge, so the words don't slide sideways under the highlight.
            transformOrigin = TransformOrigin(0f, 0.5f)
            alpha = lineAlpha
            // Held back against the list's own movement: the list has already taken this row
            // part of the way, so giving back what it has not earned yet is what leaves it
            // trailing. Rows with nothing to catch up on never read the clock at all.
            translationY = if (staggerDelay <= 0f) {
                0f
            } else {
                val elapsed = since.floatValue
                run.delta * (
                    LYRIC_EASING.transform((elapsed / run.durationMs).coerceIn(0f, 1f)) -
                        LYRIC_EASING.transform(((elapsed - staggerDelay) / run.durationMs).coerceIn(0f, 1f))
                    )
            }
        }
        .blur(blur, BlurredEdgeTreatment.Unbounded)
        .clip(RoundedCornerShape(10.dp))
        .then(if (onNote != null) Modifier.drawWithCache {
            // Genius explains this line: each row of its words sits on a faint pill, as Genius
            // marks the lines it explains. Drawn behind the words, inside the row, so the sweep
            // and the glow are untouched and the pill dims and blurs with its line.
            val text = measureNoteLine(noteMeasurer, row.text, lineStyle, size.width.toInt(), GLOW_ROOM.roundToPx())
            val room = GLOW_ROOM.toPx()
            val padH = NOTE_PILL_PAD_H.toPx()
            val inset = NOTE_PILL_INSET.toPx()
            val radius = CornerRadius(NOTE_PILL_RADIUS.toPx())
            val pills = (0 until text.lineCount).mapNotNull { i ->
                val left = text.getLineLeft(i)
                val right = text.getLineRight(i)
                if (right - left < 1f) null
                else Rect(room + left - padH, room + text.getLineTop(i) + inset, room + right + padH, room + text.getLineBottom(i) - inset)
            }
            onDrawBehind {
                pills.forEach { r -> drawRoundRect(NOTE_PILL_COLOR, r.topLeft, r.size, radius) }
            }
        } else Modifier)
        .secondaryClick(onHold)
        .combinedClickable(
            enabled = synced || onHold != null, interactionSource = interaction, indication = null,
            onLongClick = onHold,
            onClick = { if (synced) onSeek(row.timeMs) },
        )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        NoteLayout(
            text = row.text,
            style = lineStyle,
            measurer = noteMeasurer,
            onNote = onNote,
            modifier = shape,
        ) {
            PanelVoice(
                row = row,
                clock = clock,
                style = lineStyle,
                isActive = isActive,
                sung = sung,
                synced = synced,
                browsing = browsing,
                glowAlpha = glow,
                room = GLOW_ROOM,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ─── Genius notes on the lines ───────────────────────────────────────────────────────────

private val NOTE_PILL_COLOR = Color.White.copy(alpha = 0.09f)
private val NOTE_PILL_PAD_H = 7.dp
private val NOTE_PILL_INSET = 3.dp
private val NOTE_PILL_RADIUS = 12.dp
/** The quote mark after an explained line, and the gap before it. */
private val NOTE_MARK_SIZE = 22.dp
private val NOTE_MARK_GAP = 6.dp
/** What the finger has to hit to open the note: larger than the mark it shows. */
private val NOTE_MARK_TOUCH = 40.dp

/**
 * The line's words laid out as the row lays them out: the row's width less the glow room on
 * each side, left to right. The measurer keeps the result, so the pills and the mark share it.
 */
private fun measureNoteLine(measurer: TextMeasurer, text: String, style: TextStyle, rowWidth: Int, room: Int): TextLayoutResult =
    measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = (rowWidth - room * 2).coerceAtLeast(0)),
        layoutDirection = LayoutDirection.Ltr,
    )

/**
 * A lyric row, and when Genius explains it ([onNote] set) the small quote mark that opens the
 * note: just after the last word, centred on that row of text (before it for right-to-left
 * lines). When the last row of words runs too close to the edge for it, the mark goes on a row of
 * its own under the words rather than over them, so nothing the singing lights is ever covered.
 */
@Composable
private fun NoteLayout(
    text: String,
    style: TextStyle,
    measurer: TextMeasurer,
    onNote: (() -> Unit)?,
    modifier: Modifier = Modifier,
    voice: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            voice()
            if (onNote != null) NoteMark(onNote)
        },
    ) { measurables, constraints ->
        val line = measurables[0].measure(constraints)
        val markMeasurable = measurables.getOrNull(1)
        if (markMeasurable == null) {
            return@Layout layout(line.width, line.height) { line.place(0, 0) }
        }
        val mark = markMeasurable.measure(Constraints())
        val room = GLOW_ROOM.roundToPx()
        val laid = measureNoteLine(measurer, text, style, line.width, room)
        val last = (laid.lineCount - 1).coerceAtLeast(0)
        val radius = NOTE_MARK_SIZE.toPx() / 2f
        val lead = NOTE_PILL_PAD_H.toPx() + NOTE_MARK_GAP.toPx() + radius
        val rtl = isRtl(text)
        val inlineX = if (rtl) room + laid.getLineLeft(last) - lead else room + laid.getLineRight(last) + lead
        val fits = if (rtl) inlineX - radius >= 0f else inlineX + radius <= line.width
        val centerX: Float
        val centerY: Float
        val height: Int
        if (fits) {
            centerX = inlineX
            centerY = room + (laid.getLineTop(last) + laid.getLineBottom(last)) / 2f
            height = line.height
        } else {
            centerX = if (rtl) line.width - room - radius else room + radius
            centerY = room + laid.size.height + NOTE_MARK_GAP.toPx() + radius
            height = maxOf(line.height, (centerY + radius + room / 2f).toInt())
        }
        layout(line.width, height) {
            line.place(0, 0)
            mark.place((centerX - mark.width / 2f).toInt(), (centerY - mark.height / 2f).toInt())
        }
    }
}

/** The quote mark itself: a small frosted disc, with a finger-sized target around it. */
@Composable
private fun NoteMark(onOpen: () -> Unit) {
    val label = stringResource(R.string.immersive_genius_note_open)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(NOTE_MARK_TOUCH)
            .clip(CircleShape)
            .immersiveClickable(onClick = onOpen, pressedScale = 0.85f)
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(NOTE_MARK_SIZE)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Icon(
                Icons.Rounded.FormatQuote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

private fun isRtl(text: String): Boolean {
    var rtl = 0
    var ltr = 0
    for (ch in text) {
        if (ch.code in 0xFB1D..0xFB4F) return true
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
        }
    }
    return rtl > ltr
}

/** The style with an absolute alignment taken from the line's own text direction. */
private fun voiceStyle(base: TextStyle, text: String): TextStyle {
    val rtl = isRtl(text)
    return base.copy(
        textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
    )
}

/**
 * One voice of a row: the whole line lit at once, or swept word by word where the source has
 * word timings.
 */
@Composable
private fun PanelVoice(
    row: PanelLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    /** Whether the panel has already left this line behind. */
    sung: Boolean,
    /** Whether the source stamps its lines at all. */
    synced: Boolean,
    browsing: Boolean,
    glowAlpha: Float,
    room: Dp,
    modifier: Modifier = Modifier,
) {
    // The line being sung is white; every other one takes a quieter white. Faded across the
    // handover rather than cut, so a line warms as it is reached and cools as it is left.
    // Lyrics with no timing have no line being sung, and stay white throughout.
    val color by animateColorAsState(
        targetValue = if (isActive || !synced) Color.White else Immersive.TextSecondary.copy(alpha = 0.85f),
        label = "lyricLineColor",
    )
    if (row.isWordSynced && synced) {
        // Every word-synced line goes through the sweep, not just the playing one — a line
        // already sung is fully revealed and one still to come is not, which falls out of the
        // same arithmetic. Animating the tail lets a finished line close up as it dims away.
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = row.swept,
            clock = clock,
            style = style,
            dimAlpha = tail,
            modifier = modifier,
            // Browsing keeps the sweep but skips the bloom: a playback flourish, not a reading aid.
            glowAlpha = if (browsing) 0f else glowAlpha,
            glowRoom = room,
            feather = isActive,
            color = color,
        )
    } else {
        // No word timings, so there is no sweep to light the words as they are sung: the line
        // lights whole, the moment it starts, and holds itself back at the same dim a swept
        // line's unsung words sit at until then.
        val lit by animateFloatAsState(
            targetValue = if (!synced || sung || isActive) 1f else UNSUNG_ALPHA,
            label = "lyricLit",
        )
        Text(
            text = row.text,
            style = style,
            color = color.copy(alpha = lit),
            modifier = modifier.padding(room),
        )
    }
}

/**
 * A break counts itself out rather than being marked: three dots lighting in turn across the
 * interlude, so a long one reads as time running down. The row itself opens and closes with
 * the break, so the list carries no dead space through the verses either side of it.
 */
@Composable
private fun GapRow(
    row: PanelLine,
    clock: MutableLongState,
    isActive: Boolean,
    blur: Dp,
    lineAlpha: Float,
    onSeek: (Long) -> Unit,
) {
    val until = row.untilMs
    // How far through the break the song is, held at 0 before it and 1 after: the dots read
    // the clock only while it is running, so a break that is not playing never redraws.
    val gapFill = remember(row, until, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now <= row.timeMs -> 0f
                now >= until -> 1f
                else -> GAP_RUNNING
            }
        }
    }
    val swell by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(durationMillis = if (isActive) 400 else 350, easing = LYRIC_EASING),
        label = "gapSwell",
    )
    val instrumental = stringResource(R.string.immersive_lyrics_instrumental)
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .height((GAP_ROW_HEIGHT + GAP_ROW_SPACING) * swell)
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .blur(blur, BlurredEdgeTreatment.Unbounded)
                .clip(RoundedCornerShape(10.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onSeek(row.timeMs)
                }
                // Matches the inset every sung line carries, so the rhythm of the list doesn't
                // break at a break.
                .padding(GLOW_ROOM)
                .size(width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2, height = GAP_DOT_SIZE)
                .graphicsLayer {
                    val grow = GAP_REST_SCALE + (1f - GAP_REST_SCALE) * swell
                    scaleX = grow
                    scaleY = grow
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    alpha = lineAlpha * swell
                }
                .drawBehind {
                    val span = (until - row.timeMs).coerceAtLeast(1L)
                    val through = gapFill.value.takeUnless { it == GAP_RUNNING }
                        ?: ((clock.longValue - row.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    val radius = GAP_DOT_SIZE.toPx() / 2f
                    val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                    repeat(GAP_DOTS) { dot ->
                        // Each dot owns its share of the break and fills across it.
                        val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = GAP_DOT_REST + (1f - GAP_DOT_REST) * lit),
                            radius = radius,
                            center = Offset(radius + dot * stride, size.height / 2f),
                        )
                    }
                }
                .semantics { contentDescription = instrumental },
        )
    }
}

// ─── Loading skeleton ────────────────────────────────────────────────────────────────────

/**
 * The shape of the page the lyrics are going to fill: one entry per line of the song, and one
 * number per row that line wraps to. Text is blocks with ragged bottoms.
 */
private val SKELETON_BLOCKS = listOf(
    floatArrayOf(0.97f, 0.54f),
    floatArrayOf(0.92f, 0.99f, 0.41f),
    floatArrayOf(0.68f),
    floatArrayOf(0.95f, 0.73f),
    floatArrayOf(0.89f, 0.96f, 0.37f),
)
private val SKELETON_BAR = 26.dp
private val SKELETON_LEADING = 15.dp
private val SKELETON_BLOCK_GAP = 35.dp
private const val SKELETON_PERIOD_MS = 1_400

@Composable
private fun LyricsSkeleton(modifier: Modifier = Modifier) {
    // Under "reduce motion" the band stays off the bars: flat placeholders, nothing endless running.
    val sweep = if (rememberReduceMotion()) null else rememberInfiniteTransition(label = "lyricsSkeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SKELETON_PERIOD_MS, easing = LinearEasing)),
        label = "sweep",
    )
    BoxWithConstraints(modifier.padding(top = 40.dp)) {
        // Every bar sweeps against the width of the column rather than its own, so one band
        // crosses the whole page.
        val column = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(SKELETON_BLOCK_GAP)) {
            SKELETON_BLOCKS.forEach { rows ->
                Column(verticalArrangement = Arrangement.spacedBy(SKELETON_LEADING)) {
                    rows.forEach { fraction ->
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(SKELETON_BAR)
                                .clip(RoundedCornerShape(4.dp))
                                // Read in the draw block, not the body: all any bar needs per
                                // frame is a fresh gradient.
                                .drawWithCache {
                                    val full = column.toPx()
                                    val band = full * 0.45f
                                    val startX = -band + (sweep?.value ?: 0f) * (full + band * 2)
                                    val brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.10f),
                                            Color.White.copy(alpha = 0.26f),
                                            Color.White.copy(alpha = 0.10f),
                                        ),
                                        startX = startX,
                                        endX = startX + band,
                                    )
                                    onDrawBehind { drawRect(brush) }
                                },
                        )
                    }
                }
            }
        }
    }
}
