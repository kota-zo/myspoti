/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: only the portrait layout is taken: the artwork with its step back on pause, the move
 * of the artwork to a small header (title beside it) when the lyrics or the queue open, the
 * title row with its round glyphs, the scrubber, transport, volume and bottom rows, and the
 * sheet's drag to close. The full-bleed banner, Canvas clips, track-swipe on the cover, version
 * switching, the lyric strip and the dock are left out. The landscape layout (cover beside the
 * controls) is Myspoti's own.
 */
package com.music.spotui.ui.immersive.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.State
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.ui.components.DevicesSheet
import com.music.spotui.ui.immersive.sheets.ImmersiveSongOptionsSheet
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.lyrics.ImmersiveLyricsPanel
import com.music.spotui.ui.immersive.lyrics.ImmersiveQueuePanel
import com.music.spotui.ui.look.MeshGradientBackground
import com.music.spotui.ui.look.MeshPalette
import com.music.spotui.ui.look.lerp
import com.music.spotui.ui.components.SongBlend
import com.music.spotui.ui.components.SongBlendCover
import com.music.spotui.ui.look.rememberArtworkColors
import com.music.spotui.ui.look.rememberPauseScale

// Sizes and gaps of the portrait layout, as the player this follows has them.
private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
// Covers.large is 640px: the cover is always decoded at that, whatever size the box has right now.
private const val COVER_DECODE_PX = 640
private val ART_TITLE_GAP = 20.dp
private val ART_BOX_TOP_PAD = 8.dp
private val DISMISS_STRIP_HEIGHT = 32.dp

/** The player's side margin. Scrollable panels reach back across it. */
internal val PLAYER_GUTTER = 30.dp

/**
 * How wide the player's content is ever allowed to get: a sleeve and a volume slider stretched
 * across a tablet aren't a bigger player, just a coarser one.
 */
internal val PLAYER_MAX_WIDTH = 560.dp

/** How long the sleeve takes to travel between the full player and the header. */
private const val PANEL_TRAVEL_MS = com.music.spotui.ui.immersive.motion.ImmersiveMotion.LONG_MS

private val ArtCorner = RoundedCornerShape(8.dp)

/** What the middle of the player shows. */
private object Panel {
    const val NONE = 0
    const val LYRICS = 1
    const val QUEUE = 2
}

/**
 * The sheet the player sits in, as the player sees it: dragged down from the top of the player
 * (the handle, the artwork, the credits) it follows the finger, and lets go with its speed.
 */
internal interface PlayerSheetDrag {
    fun drag(delta: Float)
    fun release(velocity: Float)
}

internal val LocalPlayerSheetDrag = staticCompositionLocalOf<PlayerSheetDrag?> { null }

/**
 * The downward half of a vertical drag on this element, handed to the sheet. Upward is not ours:
 * the crossing of the touch slop is left alone and nothing is consumed, so a tap, a scroll or a
 * child's own gesture keeps working.
 */
@Composable
internal fun Modifier.dragToClose(): Modifier {
    val sheet = LocalPlayerSheetDrag.current ?: return this
    val current by rememberUpdatedState(sheet)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var pulled = 0f
            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop > 0f) {
                    pulled = overSlop
                    change.consume()
                }
            }
            if (drag == null || pulled <= 0f) return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPointerInputChange(drag)
            current.drag(pulled)
            val finished = verticalDrag(drag.id) { change ->
                velocity.addPointerInputChange(change)
                current.drag(change.positionChange().y)
                change.consume()
            }
            current.release(if (finished) velocity.calculateVelocity().y else 0f)
        }
    }
}

/**
 * The Immersive look's player: the cover's colours drifting behind a large cover, the credits,
 * a scrubber, the transport, the volume and a row of lyrics, devices and queue.
 *
 * Opening the lyrics or the queue moves the cover up into a small header with the credits beside
 * it; [ImmersiveLyricsPanel] or [ImmersiveQueuePanel] takes the middle and the controls below
 * stay where they are. [navController] is for the "more" sheet (artist, album, playlists…):
 * without it the button is not shown.
 */
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun ImmersivePlayer(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    modifier: Modifier = Modifier,
    navController: NavController? = null,
) {
    val context = LocalContext.current
    val palette = rememberArtworkColors(state.coverUrl.takeIf { it.isNotBlank() })
    // A crossfade: the colours of the song going out, as they were when it began (the new cover's
    // own come once it is loaded, and rememberArtworkColors keeps the old ones until then).
    val blend = state.blend
    val outgoingPalette = remember(blend) { palette }
    // The tint is the words' colour: it changes with them, half way.
    val tintPalette = if (blend?.outgoingWords == true) outgoingPalette else palette
    val tint by animateColorAsState(playerTintOf(tintPalette), tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.TINT_MS), label = "playerTint")

    var panel by rememberSaveable { mutableIntStateOf(Panel.NONE) }
    var showMenu by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    val lyricsOpen = panel == Panel.LYRICS
    val queueOpen = panel == Panel.QUEUE

    // 0 with the full player, 1 with the cover in the header. Read at measure, placement and draw.
    val collapse = animateFloatAsState(
        targetValue = if (panel == Panel.NONE) 0f else 1f,
        animationSpec = tween(PANEL_TRAVEL_MS, easing = FastOutSlowInEasing),
        label = "playerCollapse",
    )
    val pauseScale = rememberPauseScale(state.playing)
    val volume = rememberPlayerVolume()

    // Back leaves the panel first; the sheet (outside) closes on the next one.
    BackHandler(enabled = panel != Panel.NONE) { panel = Panel.NONE }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        // Wider than tall (a phone on its side, a tablet): the cover beside the controls.
        val landscape = maxWidth > maxHeight
        PlayerBackdrop(palette, outgoingPalette, blend, state.coverUrl)
        CompositionLocalProvider(LocalPlayerTint provides tint) {
            if (landscape) LandscapePlayer(
                state = state,
                actions = actions,
                tint = tint,
                panel = panel,
                pauseScale = pauseScale,
                volume = volume,
                onToggleLyrics = { panel = if (lyricsOpen) Panel.NONE else Panel.LYRICS },
                onToggleQueue = { panel = if (queueOpen) Panel.NONE else Panel.QUEUE },
                onOpenDevices = { showDevices = true },
                onMore = if (navController != null && state.song != null) ({ showMenu = true }) else null,
                onSource = { showQuality = true },
            ) else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // The handle: always hands its drags to the sheet.
                Box(
                    Modifier.fillMaxWidth().height(DISMISS_STRIP_HEIGHT).dragToClose()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.close() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(tint.copy(alpha = 0.35f)))
                }

                // Everything that changes between the full player and the panels lives in this
                // one weighted box, so the controls below it never move.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val contentWidth = minOf(maxWidth - PLAYER_GUTTER * 2, PLAYER_MAX_WIDTH)
                    val left = (maxWidth - contentWidth) / 2
                    val fullArt = minOf(contentWidth, maxHeight - ART_BOX_TOP_PAD - ART_TITLE_GAP - HEADER_HEIGHT - 16.dp)
                        .coerceAtLeast(THUMB_SIZE)
                    // The cover and the credits sit centred as a pair while the player is whole.
                    val groupTop = (maxHeight - ART_BOX_TOP_PAD - fullArt - ART_TITLE_GAP - HEADER_HEIGHT - 16.dp)
                        .coerceAtLeast(0.dp) / 2
                    // Functions of the collapse, called at placement and measure: composition never reads them.
                    fun p() = collapse.value
                    fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
                    fun artTop(): Dp = ART_BOX_TOP_PAD + lerp(groupTop, 0.dp, p())
                    fun artStart(): Dp = left + lerp((contentWidth - fullArt) / 2, 0.dp, p())
                    fun titleTop(): Dp = ART_BOX_TOP_PAD + lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, p())
                    fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p())
                    // The credits only crawl while they are the screen: over a list they would pull the eye.
                    val scrolls by remember { derivedStateOf { collapse.value < 0.01f } }

                    // ---- The panel, under the header ----
                    val panelAlpha = { ((p() - 0.6f) / 0.4f).coerceIn(0f, 1f) }
                    Crossfade(
                        targetState = panel,
                        animationSpec = tween(250),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = ART_BOX_TOP_PAD + HEADER_HEIGHT)
                            .graphicsLayer { alpha = panelAlpha() },
                        label = "playerPanel",
                    ) { shown ->
                        val inset = PaddingValues(start = left, end = left, top = 12.dp, bottom = 12.dp)
                        when (shown) {
                            Panel.LYRICS -> ImmersiveLyricsPanel(Modifier.fillMaxSize(), inset)
                            Panel.QUEUE -> ImmersiveQueuePanel(Modifier.fillMaxSize(), inset)
                            else -> Box(Modifier.fillMaxSize())
                        }
                    }

                    // ---- The cover: the whole sleeve, then the header's thumbnail ----
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                            .layout { measurable, _ ->
                                val side = artSize().roundToPx()
                                val placeable = measurable.measure(Constraints.fixed(side, side))
                                layout(side, side) { placeable.place(0, 0) }
                            }
                            .graphicsLayer {
                                // The pause step back, let go of as the cover collapses into the header.
                                val scale = pauseScale.value + (1f - pauseScale.value) * p()
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = 10.dp.toPx() * (1f - p())
                                shape = ArtCorner
                                clip = true
                            }
                            .background(Color.Black.copy(alpha = 0.18f))
                            .dragToClose()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (panel != Panel.NONE) panel = Panel.NONE
                            },
                    ) {
                        GlideImage(
                            model = state.coverUrl.takeIf { it.isNotBlank() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            transition = CrossFade,
                            loading = placeholder(R.drawable.placeholder),
                            failure = placeholder(R.drawable.placeholder),
                            modifier = Modifier.fillMaxSize(),
                            // The box shrinks to the 60dp header thumbnail and grows back: Glide sizes the
                            // request from the constraints it first sees, so without a fixed size a cover
                            // first measured (or reloaded) while collapsed stays a small bitmap.
                            requestBuilderTransform = { it.override(COVER_DECODE_PX) },
                        )
                        SongBlendCover(blend, Modifier.fillMaxSize()) { it.override(COVER_DECODE_PX) }
                    }

                    // ---- Title + round glyphs ----
                    Row(
                        modifier = Modifier
                            .offset {
                                // Collapsed, the row shares the header with the thumbnail and the two
                                // are not the same height: centred on it.
                                IntOffset(
                                    left.roundToPx(),
                                    (titleTop() - lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, p())).roundToPx(),
                                )
                            }
                            .width(contentWidth)
                            .layout { measurable, constraints ->
                                val start = titleStart().roundToPx()
                                val placeable = measurable.measure(constraints.offset(horizontal = -start))
                                layout(
                                    constraints.constrainWidth(placeable.width + start),
                                    constraints.constrainHeight(placeable.height),
                                ) { placeable.placeRelative(start, 0) }
                            }
                            .height(HEADER_HEIGHT)
                            .dragToClose(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerCredits(
                            state = state, actions = actions, tint = tint, scrolls = scrolls, collapse = { p() },
                            onMore = if (navController != null && state.song != null) ({ showMenu = true }) else null,
                            onSource = { showQuality = true },
                        )
                    }
                }

                // ---- Bottom: scrubber, transport, volume, toggles ----
                // One block, measured at its natural height and pinned to the foot of the player.
                Column(
                    modifier = Modifier
                        .widthIn(max = PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2)
                        .fillMaxWidth()
                        .padding(horizontal = PLAYER_GUTTER)
                        .align(Alignment.CenterHorizontally),
                ) {
                    PlayerControlsBlock(
                        state = state, actions = actions, volume = volume, lyricsOpen = lyricsOpen, queueOpen = queueOpen, showVolume = true,
                        // Lyrics and the queue are two things to show in one place: opening one closes the other.
                        onToggleLyrics = { panel = if (lyricsOpen) Panel.NONE else Panel.LYRICS },
                        onToggleQueue = { panel = if (queueOpen) Panel.NONE else Panel.QUEUE },
                        onOpenDevices = { showDevices = true },
                    )
                }
            }
        }
    }

    if (showDevices) DevicesSheet { showDevices = false }
    if (showQuality) com.music.spotui.ui.components.QualitySheet { showQuality = false }
    val song = state.song
    if (showMenu && song != null && navController != null) {
        ImmersiveSongOptionsSheet(
            song = song,
            navController = navController,
            context = context,
            onDismiss = { showMenu = false },
            nowPlaying = true,
            onOpenQueue = { panel = Panel.QUEUE },
        )
    }
}

/**
 * The mesh behind the player. During a crossfade its colours go from [outgoing] to [palette] with
 * the fade's progress, read here so only the backdrop follows it frame by frame, not the player.
 */
@Composable
private fun PlayerBackdrop(palette: MeshPalette, outgoing: MeshPalette, blend: SongBlend?, trackKey: String) {
    MeshGradientBackground(
        palette = if (blend == null) palette else lerp(outgoing, palette, blend.progress()),
        trackKey = trackKey,
        // Following the fade, a short smoothing only: the new cover's colours, known a moment
        // into it, do not jump in.
        colorFadeMillis = if (blend == null) 1400 else 250,
    )
}

/**
 * The credits row of the player: title (with its explicit badge), artist and the source line,
 * then the like glyph and, when [onMore] is given, the more glyph. [collapse] is the cover's
 * travel to the header (0 whole, 1 collapsed); the credits shrink with it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.PlayerCredits(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    tint: Color,
    scrolls: Boolean,
    collapse: () -> Float,
    onMore: (() -> Unit)?,
    onSource: () -> Unit,
) {
    // The header is a fixed HEADER_HEIGHT: with a source line the three lines are
    // set tighter, so none is clipped at the bottom.
    val sourceLabel = com.music.spotui.ui.components.playingSourceLabel()
    val hasSource = sourceLabel.isNotBlank() && !state.adPlaying
    val lineH = if (hasSource) 22.sp else 24.sp
    Column(
        Modifier
            .weight(1f)
            // Scaling the already measured type is a draw-only operation.
            .graphicsLayer {
                val scale = 1f - 0.2f * collapse()
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                // A crossfade: the outgoing song's words fade out, the new one's in, once.
                alpha = state.blend?.wordsAlpha() ?: 1f
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.adPlaying) stringResource(R.string.immersive_player_ad) else state.title,
                style = Immersive.Title.copy(fontSize = 20.sp, lineHeight = lineH),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (scrolls) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.openAlbum() },
            )
            if (state.explicit && !state.adPlaying) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(tint.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.explicit_badge),
                        style = Immersive.Caption.copy(fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold),
                        color = Color.Black,
                    )
                }
            }
        }
        if (!state.adPlaying) {
            // One tap per artist when the song's artists are known; the whole line otherwise.
            val credit = com.music.spotui.ui.components.rememberArtistCredit(state.trackId, state.artist) { a -> actions.openArtistRef(a) }
            Text(
                text = credit ?: androidx.compose.ui.text.AnnotatedString(state.artist),
                style = Immersive.Title.copy(fontSize = 20.sp, lineHeight = lineH, fontWeight = FontWeight.W500),
                color = tint.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(if (scrolls) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.openArtist() },
            )
        }
        // What the sound is when Myspoti's own player plays it (an extension's stream, a
        // download); a tap opens what plays and its quality. Same state as the Classic player.
        if (hasSource) Text(
            text = sourceLabel,
            style = Immersive.Caption.copy(lineHeight = 14.sp),
            color = tint.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSource),
        )
    }
    Spacer(Modifier.width(10.dp))
    // Beside the credits rather than in the toggle row: liking is about this song.
    CircleGlyph(
        icon = if (state.liked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
        contentDescription = stringResource(if (state.liked) R.string.immersive_player_unlike else R.string.immersive_player_like),
        onClick = actions.toggleLike,
        active = state.liked,
    )
    if (onMore != null) {
        Spacer(Modifier.width(8.dp))
        CircleGlyph(
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = stringResource(R.string.immersive_player_more),
            onClick = onMore,
        )
    }
}

/** Scrubber, transport, volume, the bottom row and the extensions' buttons: the controls under the cover. */
@Composable
private fun PlayerControlsBlock(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    volume: PlayerVolume,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    showVolume: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    PlayerScrubber(state = state, onSeek = actions.seekTo)
    Spacer(Modifier.height(8.dp))
    TransportRow(
        isPlaying = state.playing,
        isLoading = state.buffering,
        onPrevious = actions.previous,
        onPlayPause = actions.playPause,
        onNext = actions.next,
    )
    Spacer(Modifier.height(if (showVolume) 12.dp else 4.dp))
    if (showVolume) {
        VolumeRow(volume)
        // The volume slider already has room below its drawn track.
        Spacer(Modifier.height(6.dp))
    }
    PlayerActionRow(
        lyricsOpen = lyricsOpen,
        queueOpen = queueOpen,
        shuffleEnabled = state.shuffle || state.smart,
        smartEnabled = state.smart,
        repeatMode = state.repeatMode,
        deviceActive = state.deviceName != null,
        onToggleLyrics = onToggleLyrics,
        onToggleQueue = onToggleQueue,
        onToggleShuffle = actions.toggleShuffle,
        onCycleRepeat = actions.cycleRepeat,
        onOpenDevices = onOpenDevices,
    )
    // Buttons from extensions (player.buttons), under the player's own controls.
    val extTrack = remember(state.title, state.artist) { com.music.spotui.extensions.Extensions.currentTrack() }
    com.music.spotui.extensions.MirrorIndicator()
    com.music.spotui.extensions.ExtensionPlayerButtons(extTrack)
    Spacer(Modifier.height(18.dp))
}

/**
 * The landscape layout: the cover on the left (the lyrics or the queue take its place when they
 * are up), on the right the credits, the scrubber, every transport control and the bottom row.
 * The right side scrolls if a small window cannot hold it, so no control is out of reach.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun LandscapePlayer(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    tint: Color,
    panel: Int,
    pauseScale: State<Float>,
    volume: PlayerVolume,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenDevices: () -> Unit,
    onMore: (() -> Unit)?,
    onSource: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // The handle: always hands its drags to the sheet.
        Box(
            Modifier.fillMaxWidth().height(DISMISS_STRIP_HEIGHT).dragToClose()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.close() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(tint.copy(alpha = 0.35f)))
        }
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(start = PLAYER_GUTTER, end = PLAYER_GUTTER, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(PLAYER_GUTTER),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                Crossfade(targetState = panel, animationSpec = tween(250), modifier = Modifier.fillMaxSize(), label = "playerLandscapePanel") { shown ->
                    when (shown) {
                        Panel.LYRICS -> ImmersiveLyricsPanel(Modifier.fillMaxSize(), PaddingValues(vertical = 4.dp))
                        Panel.QUEUE -> ImmersiveQueuePanel(Modifier.fillMaxSize(), PaddingValues(vertical = 4.dp))
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(side)
                                    .graphicsLayer {
                                        // The pause step back, as in the portrait layout.
                                        scaleX = pauseScale.value
                                        scaleY = pauseScale.value
                                        shadowElevation = 10.dp.toPx()
                                        shape = ArtCorner
                                        clip = true
                                    }
                                    .background(Color.Black.copy(alpha = 0.18f))
                                    .dragToClose(),
                            ) {
                                GlideImage(
                                    model = state.coverUrl.takeIf { it.isNotBlank() },
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    transition = CrossFade,
                                    loading = placeholder(R.drawable.placeholder),
                                    failure = placeholder(R.drawable.placeholder),
                                    modifier = Modifier.fillMaxSize(),
                                    requestBuilderTransform = { it.override(COVER_DECODE_PX) },
                                )
                                SongBlendCover(state.blend, Modifier.fillMaxSize()) { it.override(COVER_DECODE_PX) }
                            }
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                // The volume bar is the first thing to go when the window is low: the hardware keys do it too.
                val showVolume = maxHeight >= LANDSCAPE_VOLUME_MIN_HEIGHT
                Column(Modifier.widthIn(max = PLAYER_MAX_WIDTH).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                        PlayerCredits(state, actions, tint, scrolls = true, collapse = { 0f }, onMore = onMore, onSource = onSource)
                    }
                    Spacer(Modifier.height(8.dp))
                    PlayerControlsBlock(
                        state = state, actions = actions, volume = volume,
                        lyricsOpen = panel == Panel.LYRICS, queueOpen = panel == Panel.QUEUE, showVolume = showVolume,
                        onToggleLyrics = onToggleLyrics, onToggleQueue = onToggleQueue, onOpenDevices = onOpenDevices,
                    )
                }
            }
        }
    }
}

/** The right-hand column needs about this much height to show the volume bar as well. */
private val LANDSCAPE_VOLUME_MIN_HEIGHT = 400.dp
