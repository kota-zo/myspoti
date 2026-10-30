package com.music.spotui.ui.screens

import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.music.spotui.ui.components.pressable
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myspoti.power.EnergySettings
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.getLikedSongIds
import com.music.spotui.data.preferences.getSongsByIds
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.viewmodel.confirmedTrackIdForDisplay
import com.music.spotui.ui.viewmodel.confirmedTrackIdFromUri
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(navController: NavController) {
    val playerViewModel : PlayerViewModel = viewModel()
    val songTitle = playerViewModel.currentSongTitle.value
    val songSinger = playerViewModel.currentSongSinger.value
    val songCoverUri = playerViewModel.currentSongCoverUri.value
    val songPlayingState = playerViewModel.currentSongPlayingState.value
    val audioBuffering = SongPlayer.isBuffering
    // T4: the page shows no track during an ad break; an explicit ad state is shown instead.
    val adPlaying = SongPlayer.adPlaying
    val songId = playerViewModel.currentSongId.value
    val context = LocalContext.current
    val energy by EnergySettings.state.collectAsStateWithLifecycle()
    // Liked state is Spotify's (LibraryAPI), pushed by the engine.
    val isLiked = playerViewModel.likeState
    val titleScope = androidx.compose.runtime.rememberCoroutineScope()
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showSavedIn by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    // Immersive view (official app: a tap on the artwork): artwork full-bleed, no top bar,
    // no title / controls block, only one compact strip. A second tap leaves it.
    var immersive by remember { mutableStateOf(false) }
    if (showDevices) com.music.spotui.ui.components.DevicesSheet { showDevices = false }
    if (showQueue) com.music.spotui.ui.components.QueueSheet(
        // B26: the list's own name (the page's label is blank for some lists), not the album's.
        contextName = playerContextName(playerViewModel),
        onDismiss = { showQueue = false },
    )

    BackHandler(enabled = showLyrics) {
        showLyrics = false
    }
    // Back leaves the immersive view first; the lyrics (handled above) keep priority.
    BackHandler(enabled = immersive && !showLyrics) {
        immersive = false
    }

    if (showMenu) {
        PlayerOptionsSheet(
            navController = navController,
            playerViewModel = playerViewModel,
            context = context,
            isLiked = isLiked,
            onDismiss = { showMenu = false },
            onOpenQueue = { showQueue = true },
        )
    }

    if (showSavedIn) {
        playerViewModel.queue.value.firstOrNull { it.id == songId }?.let { track ->
            com.music.spotui.ui.components.SavedInSheet(
                song = track,
                context = context,
                onDismiss = { showSavedIn = false },
                onLikedChanged = { isLiked.value = it },
            )
        } ?: run { showSavedIn = false }
    }


    // Known cover (warmed by the mini player): the colour is there from the first frame.
    var coverColor by remember {
        mutableStateOf(Palette.cachedSecond(songCoverUri) ?: AppBackground)
    }
    LaunchedEffect(songCoverUri) {
        // Known: kept as it is (extracted again it could come out a shade off, and the background
        // shifted after the player had opened).
        Palette.cachedSecond(songCoverUri)?.let { coverColor = it; return@LaunchedEffect }
        if (songCoverUri.isNotBlank()) Palette().extractSecondColorFromCoverUrl(context = context, songCoverUri) { color ->
            coverColor = color
        }
    }
    // Track changes cross-fade the background instead of snapping.
    val dominentColor by androidx.compose.animation.animateColorAsState(coverColor, androidx.compose.animation.core.tween(450), label = "cover")
    // The page's own gradient uses the mini player's colour (same cache entry, so no second
    // extraction when the mini player has already seen the cover); the muted colour above stays
    // for the lyrics and as a fallback for covers without a dark vibrant swatch.
    var tintColor by remember { mutableStateOf(Palette.cachedFirst(songCoverUri)) }
    LaunchedEffect(songCoverUri) {
        Palette.cachedFirst(songCoverUri)?.let { tintColor = it; return@LaunchedEffect }
        if (songCoverUri.isNotBlank()) Palette().extractFirstColorFromImageUrl(context, songCoverUri) { tintColor = it }
    }
    val backdropColor by androidx.compose.animation.animateColorAsState(tintColor ?: coverColor, androidx.compose.animation.core.tween(450), label = "backdrop")
    // A real crossfade (see SongBlend): the cover and the backdrop go from the outgoing song to
    // this one with the fade's progress, the words and times switch once, half way.
    val blend = com.music.spotui.ui.components.rememberSongBlend()
    val outgoing = blend?.takeIf { it.outgoingWords }?.song
    // The backdrop's colour when the fade began, before this song's took over.
    val outgoingBackdrop = remember(blend) { backdropColor }

    val shuffle = playerViewModel.shuffleState.value
    val repeat = playerViewModel.repeatState.value
    val queueSongs = playerViewModel.queue.value
    val np by SongPlayer.nowPlaying
    val currentTrack = queueSongs.firstOrNull { it.id == songId }
    val actualTrackId = confirmedTrackIdFromUri(np.uri)
    val displayedTrackId = confirmedTrackIdForDisplay(np.uri, songId)
    // The artists are looked up ahead: a tap on the artist line opens the page (or the choice) at once.
    LaunchedEffect(displayedTrackId) { com.music.spotui.ui.components.ArtistChooser.prefetch(displayedTrackId) }

    // Smooth progress: redrawn every frame from the engine clock (see SpotifySeekBar).
    // As many ticks as the knob has pixels to move: ~5 a second on a 3-minute song, up to 15 on
    // a short one. Each tick redraws the whole window, so 15 on every song was ~3x the work for
    // steps nobody can see.
    val barFps = run {
        val d = com.music.spotui.video.MusicVideoController.duration()
        val widthPx = context.resources.displayMetrics.widthPixels
        if (d > 0) (widthPx * 1000L / d).toInt().coerceIn(4, 15) else 15
    }
    val frame = com.music.spotui.ui.components.rememberFrameClock(songPlayingState && !audioBuffering, fps = barFps)

    // ── Swipe the artwork to change track: [previous, current, next] from Spotify ──
    val artworkPagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    LaunchedEffect(songId) {
        if (artworkPagerState.currentPage != 1) artworkPagerState.scrollToPage(1)
    }
    // Only a swipe changes track: the pager also lands on another page by itself when it is
    // laid out again at another size (the clip going full screen turns the phone landscape),
    // which skipped the song.
    var artworkSwiped by remember { mutableStateOf(false) }
    LaunchedEffect(artworkPagerState) {
        artworkPagerState.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) artworkSwiped = true
        }
    }
    LaunchedEffect(artworkPagerState) {
        snapshotFlow { artworkPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page == 1) return@collect
                if (!artworkSwiped) { artworkPagerState.scrollToPage(1); return@collect }
                artworkSwiped = false
                when (page) {
                    2 -> SongPlayer.swipeNext()
                    0 -> SongPlayer.previous()
                }
            }
    }

    // The confirmed URI can change after an optimistic songId already changed.
    // Hide a stale result in this composition, before the loading effect runs.
    LaunchedEffect(displayedTrackId, energy.policy.allowCanvas, audioBuffering) {
        playerViewModel.loadCanvas(
            if (energy.policy.allowCanvas && !com.music.spotui.data.DataSaver.active &&
                !(com.music.spotui.data.DataSaver.metered && com.music.spotui.data.preferences.isCanvasWifiOnly(context))) displayedTrackId else "",
            allowFetch = !audioBuffering,
        )
    }
    val canvas = playerViewModel.canvasState.value
    // Read from the cache in this very frame when already looked up (the view model's state
    // only follows one frame later): the screen then opens on the Canvas, not on the cover.
    val knownCanvas = remember(displayedTrackId) { com.music.spotui.di.Graph.api.knownCanvas(displayedTrackId)?.getOrNull() }
    val canvasAllowed = energy.policy.allowCanvas && !com.music.spotui.data.DataSaver.active &&
            !(com.music.spotui.data.DataSaver.metered && com.music.spotui.data.preferences.isCanvasWifiOnly(context))
    val canvasUrl = (if (canvas.trackId == displayedTrackId) canvas.url else knownCanvas).takeIf {
        canvasAllowed && displayedTrackId.isNotBlank()
    }
    // The cover stays until the Canvas has drawn its first frame: hiding it earlier showed
    // the bare background for a moment (a flash) between the cover and the clip.
    // Prepared beforehand by the mini player: the clip is shown from the first frame, no cover.
    var canvasShown by remember(canvasUrl) { mutableStateOf(canvasUrl != null && com.music.spotui.ui.components.CanvasPlayer.isReady(canvasUrl)) }
    // Cover to Canvas as one cross-fade: the clip (full screen) fades in while the cover fades
    // out and grows a little into it, instead of the background switching at once.
    val canvasOn = canvasUrl != null && canvasShown
    // The immersive view is the Canvas's, but a skip must not collapse it while the next clip
    // loads: it is left only once the new song is known to have no Canvas (lookup done with no
    // url, or Canvases off). A lookup that never answers gives up after a few seconds.
    LaunchedEffect(immersive, canvasUrl, canvasAllowed, displayedTrackId) {
        if (!immersive || canvasUrl != null || displayedTrackId.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(8000) {
            while (canvasAllowed && com.music.spotui.di.Graph.api.knownCanvas(displayedTrackId) == null) kotlinx.coroutines.delay(250)
        }
        immersive = false
    }
    // Opening on a clip that is already showing starts at the end of the fade (the only
    // time it is not played); every later change animates, so a skip never cuts it short.
    val canvasProgress = remember { androidx.compose.animation.core.Animatable(if (canvasOn) 1f else 0f) }
    // Keyed on the target alone: a new song with the same target leaves the running fade
    // alone, and a real change continues from where it is, over the part still to travel.
    LaunchedEffect(canvasOn) {
        val target = if (canvasOn) 1f else 0f
        val left = kotlin.math.abs(target - canvasProgress.value)
        if (left > 0f) canvasProgress.animateTo(
            target,
            androidx.compose.animation.core.tween((CANVAS_FADE_MS * left).toInt(), easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
    }
    val canvasAlpha = canvasProgress.value
    val coverAlpha = 1f - canvasProgress.value
    // A tap on the Canvas no longer needs its own mode: the immersive view below handles both
    // cases (Canvas playing or plain cover), so the Canvas-only fade ("chrome") is gone.
    // Music video (YouTube, synced to the Spotify playback): looked up for every track
    // shown here, offered once its time map is known, shown in place of the artwork.
    val mv = com.music.spotui.video.MusicVideoController
    androidx.compose.runtime.DisposableEffect(mv) {
        mv.attachPlayerScreen()
        SongPlayer.refreshLikedIfStale()
        onDispose { mv.detachPlayerScreen() }
    }
    LaunchedEffect(actualTrackId, songTitle, audioBuffering) {
        // Settings, "Music videos" off: no lookup, no button, no clip.
        if (!audioBuffering && com.music.spotui.data.preferences.MusicVideoPref.on) mv.prepare(actualTrackId, songTitle, songSinger, SongPlayer.getDuration())
    }
    val remote = SongPlayer.activeDevice.value != null
    val readyVideo = mv.state.value as? com.music.spotui.video.MusicVideoController.State.Ready
    val videoMatchesTrack = displayedTrackId.isNotBlank() && actualTrackId == displayedTrackId && readyVideo?.trackId == actualTrackId
    val videoReady = videoMatchesTrack && !remote && !audioBuffering && !com.music.spotui.data.DataSaver.active &&
            com.music.spotui.data.preferences.MusicVideoPref.on
    // Switching to mobile data with the data saver on stops a clip that is playing.
    LaunchedEffect(com.music.spotui.data.DataSaver.active) {
        if (com.music.spotui.data.DataSaver.active && mv.on.value) mv.setOn(false)
    }
    val videoOn = mv.on.value && videoMatchesTrack && !remote && com.music.spotui.data.preferences.MusicVideoPref.on
    // Canvas / clip players are built once the sheet has finished rising (then kept):
    // creating them in the first frame stalled it and the slide jumped.
    val settled by com.music.spotui.ui.navigation.LocalPageSettled.current
    var mediaReady by remember { mutableStateOf(false) }
    LaunchedEffect(settled) { if (settled) mediaReady = true }
    val showVideo = videoOn && !showLyrics && !mv.fullscreen.value && mediaReady
    // Buffer the clip as soon as it is known for the track on screen.
    LaunchedEffect(mv.state.value, remote, audioBuffering, displayedTrackId) { if (videoReady && !audioBuffering) mv.warm() }
    // Pulled down from the top, the player closes, like the back button (issue #4).
    val pullToClose = com.music.spotui.ui.components.rememberPullToDismiss { com.music.spotui.ui.navigation.PlayerSheet.close() }
    pullToClose.enabled = !showLyrics && !mv.fullscreen.value
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .graphicsLayer { translationY = pullToClose.offset }
            .fillMaxSize()
            // Drawn, not composed: the colour moves every frame of a crossfade (and of a change),
            // and only this draw follows it.
            .drawBehind {
                // The tint follows the cover in BOTH modes: AMOLED used to be pure black, and the
                // colour was asked for anyway (that replaces the halo idea). The screen keeps its
                // gradient, the toggle still governs the rest of the app.
                // Official look: the cover's colour at the top, fading to near-black by the
                // bottom (still faintly tinted halfway), so white text stays readable.
                val top = blend?.let { androidx.compose.ui.graphics.lerp(outgoingBackdrop, backdropColor, it.progress()) } ?: backdropColor
                drawRect(Brush.verticalGradient(
                    0f to top,
                    0.55f to androidx.compose.ui.graphics.lerp(top, PLAYER_BASE, 0.40f),
                    0.80f to androidx.compose.ui.graphics.lerp(top, PLAYER_BASE, 0.78f),
                    1f to PLAYER_BASE,
                ))
            }
    ) {
        // The window's height (the inner constraints below shadow `maxHeight`) and the status
        // bar's, to size the page item itself: a LazyColumn item cannot ask for "parent minus x".
        val pageHeight = maxHeight
        val statusTop = androidx.compose.foundation.layout.WindowInsets.Companion.statusBars.asPaddingValues().calculateTopPadding()
        // The lyrics card peeks in under the buttons only where it will show (it stays hidden for an
        // ad and for a song without lyrics): otherwise that strip is empty space below the controls.
        // The state is read here too, as the card sits below the page and is not composed to load it.
        val lyricsState = rememberLyricsState(viewModel<com.music.spotui.ui.viewmodel.LyricsViewModel>(), songTitle, songSinger)
        val lyricsPeek = if (adPlaying || lyricsState is com.music.spotui.ui.viewmodel.LyricsViewModel.State.NotFound) 0 else PLAYER_PEEK_DP
        val pillAboveTitle = canvasUrl != null && !videoOn
        if (canvasUrl != null && !videoOn) {
            CanvasVideo(
                url = canvasUrl,
                onFirstFrame = { canvasShown = true },
                playing = songPlayingState && !showLyrics && !audioBuffering,
                modifier = Modifier.fillMaxSize().alpha(canvasAlpha),
            )
            // Darker than the clip itself while the player shows over it, so the text reads;
            // gone when the Canvas is shown alone.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Lighter dim in the immersive view: the clip is the page there, only the
                    // compact strip needs something to sit on.
                    .alpha(canvasAlpha * if (immersive) 0.35f else 1f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.60f),
                                Color.Black.copy(alpha = 0.25f),
                                Color.Black.copy(alpha = 0.50f),
                                Color.Black.copy(alpha = 0.85f),
                            )
                        )
                    )
            )
        }
        // The content comes in after the sheet's background (see PlayerOverlay).
        val reveal = com.music.spotui.ui.navigation.LocalPlayerReveal.current
        val revealShift = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { val r = reveal(); alpha = r; translationY = (1f - r) * revealShift }
                .nestedScroll(pullToClose.connection)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        item {
        // Sized from the real space (foldables, tablets), not the screen: wide windows centre
        // the content in a capped column and the cover fits both the width and the height.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = (if (immersive) Modifier.fillParentMaxHeight()
                // The page stops short of the screen's bottom so the lyrics card peeks in under
                // the buttons, as in the official app (scroll to open it).
                else Modifier.height(
                    (pageHeight - statusTop - lyricsPeek.dp).coerceAtLeast(300.dp)
                )).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
        val wide = maxWidth > 600.dp && maxWidth > maxHeight
        // Large windows wider than tall (tablets, foldables, DeX) and large portrait tablets: the
        // cover on the left, the title and controls on the right, each in a capped half.
        val twoPane = !immersive && com.music.spotui.ui.components.windowSize().twoPane
        val contentWidth = if (twoPane) ((maxWidth - 72.dp) / 2).coerceIn(0.dp, 560.dp)
            else if (wide) maxWidth.coerceAtMost(520.dp) else maxWidth
        // Immersive: the artwork takes the whole height instead of leaving room for controls.
        val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        val topBar: @Composable () -> Unit = {
            // Immersive mode has no top bar: only the artwork and the compact strip remain.
            if (!immersive) {
                PlayerTopBar(
                    navController,
                    onMenuClick = { showMenu = true },
                    np = np,
                    fallbackName = playerViewModel.currentSongAlbum.value,
                )
                // Official: the cover starts closer to the header.
                Spacer(modifier = Modifier.height(14.dp))
            }
        }
        val mediaArea: @Composable (Modifier, Alignment) -> Unit = { mediaModifier, mediaAlignment ->
            // Media area: artwork (swipe to change track) or the music video, with
            // Spotify's "Passer à la vidéo" pill over it (black, like the official app).
            // Square, but never taller than what the controls leave (foldables, landscape-ish
            // screens): otherwise play / pause ends up under the lyrics card.
            // A clear upward drag on the artwork opens the lyrics (observed on the initial pass,
            // never consumed, so the pager and the pull-to-close keep working).
            // The media slot takes whatever height the controls below leave (they are measured
            // first, so they are never squeezed): the Connect row always stays above the lyrics
            // peek, with or without Canvas / clip pill, on short screens too. The art shrinks, the
            // controls do not; on tall screens the slot's spare height sits under the art.
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = mediaModifier,
                contentAlignment = mediaAlignment,
            ) {
            val artSide = if (immersive) screenHeight
                else minOf(contentWidth, maxHeight).coerceAtLeast(0.dp)
            // The cover: the width less its 24dp margins, or all the height when that is shorter.
            // The margins used to be taken off the height-bound side too, a cover 48dp smaller
            // than the room it had on most phones (reported: it looks small).
            val coverInset = ((artSide - minOf(contentWidth - 48.dp, maxHeight)) / 2).coerceAtLeast(0.dp)
            Box(modifier = Modifier.size(artSide).pointerInput(immersive, videoOn, canvasOn) {
                detectTapGestures(onTap = {
                    // A tap on a Canvas hides the rest to show the whole clip. A plain cover has
                    // nothing more to show: blown up to the screen's height it was only cropped
                    // on both sides (reported: the tap "broke" the image), so it does nothing.
                    if (!videoOn && canvasOn) immersive = !immersive
                })
            }.pointerInput(Unit) {
                val threshold = 80.dp.toPx()
                // A deliberate flick, judged on release: an upward drag that crosses the threshold in
                // under [maxMs]. Reported bug: scrolling up through the player opened the lyrics —
                // that gesture crosses a distance threshold too, but never that fast, so distance
                // alone was not a gesture.
                val maxMs = 350L
                while (true) awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val d = change.positionChange()
                        dx += d.x; dy += d.y
                        if (!change.pressed) {
                            val quick = change.uptimeMillis - down.uptimeMillis <= maxMs
                            if (quick && -dy > threshold && -dy > kotlin.math.abs(dx) * 2f) showLyrics = true
                            break
                        }
                    }
                }
            }) {
            if (showVideo) Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                // The full-screen button in the clip's corner: under it, it overlapped the pills.
                Box {
                    com.music.spotui.ui.components.VideoSurface(onTap = { mv.fullscreen.value = true })
                    if (!immersive) com.music.spotui.ui.components.VideoFullscreenButton(Modifier.align(Alignment.BottomEnd))
                }
            } else androidx.compose.runtime.key("artwork") {
            // Back on screen after the clip (or its full screen, laid out landscape): the pager
            // can come back on the page it had or a neighbour, which showed the next song's
            // cover (or the one before) over the song playing. It opens on the song playing.
            LaunchedEffect(songId) {
                if (artworkPagerState.currentPage != 1) { artworkSwiped = false; artworkPagerState.scrollToPage(1) }
            }
            HorizontalPager(
                state = artworkPagerState,
                // Immersive: the artwork fills the screen instead of sitting in a square.
                modifier = if (immersive) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().aspectRatio(1f),
                // Nothing to swipe to without a previous/next track (e.g. Live Radio, whose
                // "queue" is always a single station): without this the pager still let the
                // artwork be dragged, landing on a page that falls back to the very same cover
                // (same code path as the real previous/next pages), which looked like every
                // station had the picture twice.
                userScrollEnabled = np.prev != null || np.next != null,
            ) { page ->
                val cover = when (page) {
                    0 -> np.prev?.coverUri
                    2 -> np.next?.coverUri
                    else -> null
                } ?: songCoverUri
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    GlideImage(
                        transition = CrossFade,
                        modifier = (if (immersive) Modifier.fillMaxSize()
                            // Normal layout keeps the inset, rounded cover.
                            else Modifier.padding(horizontal = coverInset).aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
                            .graphicsLayer {
                                alpha = coverAlpha
                                val grow = 1f + (1f - coverAlpha) * 0.06f
                                scaleX = grow; scaleY = grow
                            },
                        model = app.myspoti.host.android.Covers.large(cover),
                        contentScale = ContentScale.Crop,
                        contentDescription = "")
                    // The song a crossfade fades out, dissolving into this one.
                    if (page == 1) com.music.spotui.ui.components.SongBlendCover(
                        blend,
                        (if (immersive) Modifier.fillMaxSize()
                            else Modifier.padding(horizontal = coverInset).aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
                            .graphicsLayer { alpha = coverAlpha },
                    )
                }
            }
            }
            // Over the artwork / video; with a Canvas the artwork is hidden, so the
            // pill sits above the title instead of floating mid-screen.
            if (!immersive && (videoReady || videoOn) && !pillAboveTitle) {
                com.music.spotui.ui.components.VideoSwitchPill(
                    on = videoOn,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
                )
            }
            }
            }
        }
        val controls: @Composable ColumnScope.() -> Unit = {
            // The slot (pill 32dp + 20dp gap) is reserved in every non-immersive layout, Canvas or
            // not, and before the clip is even known: the title, seek bar and buttons then sit at
            // the same height on every track, and a skip between the two kinds no longer moves
            // them. Only the Canvas layout draws the pill here, fading in inside the slot.
            if (!immersive) {
                val pillIn by androidx.compose.animation.core.animateFloatAsState(
                    if (videoReady || videoOn) 1f else 0f, tween(250), label = "pillIn",
                )
                Box(modifier = Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.TopCenter) {
                    if (pillAboveTitle && pillIn > 0f) com.music.spotui.ui.components.VideoSwitchPill(
                        on = videoOn,
                        modifier = Modifier
                            .padding(bottom = 20.dp)
                            .graphicsLayer { alpha = pillIn; val k = 0.85f + 0.15f * pillIn; scaleX = k; scaleY = k },
                    )
                }
            }

            // Immersive mode keeps none of this: the compact strip at the bottom of
            // the screen replaces the title block and every control.
            if (!immersive) {
                PlayerInfo(
                    songTitle = outgoing?.title ?: songTitle,
                    songSinger = outgoing?.artist ?: songSinger,
                    wordsAlpha = { blend?.wordsAlpha() ?: 1f },
                    explicit = outgoing == null && (np.explicit || currentTrack?.explicit == true),
                    liked = isLiked.value,
                    onLikeClick = { playerViewModel.updateLikeState(!isLiked.value) },
                    likeSong = { nowPlayingSong(playerViewModel) },
                    adPlaying = adPlaying,
                    onTitleClick = {
                        // A radio's tracks may come without their album: asked from the track, else
                        // by the album's name (the album page resolves it).
                        val albumId = np.albumUri.substringAfterLast(":").ifBlank { nowPlayingSong(playerViewModel).albumId }
                        val albumName = playerViewModel.currentSongAlbum.value
                        if (albumId.isNotBlank()) navController.navigate(albumRoute(albumName, songSinger, albumId))
                        else {
                            val trackId = SongPlayer.currentTrackId
                            titleScope.launch {
                                val resolved = if (trackId.isBlank()) "" else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { app.myspoti.host.android.MyspotiRuntime.api.catalog.track("spotify:track:$trackId").album?.uri }
                                        .getOrNull()?.substringAfterLast(":").orEmpty()
                                }
                                if (resolved.isNotBlank() || albumName.isNotBlank()) navController.navigate(albumRoute(albumName, songSinger, resolved))
                            }
                        }
                    },
                    onArtistClick = {
                        playerViewModel.goToArtist(SongPlayer.currentTrackId, songSinger) { route ->
                            navController.navigate(route)
                        }
                    },
                    // One tap per artist: each name opens that artist, not the choice.
                    artistCredit = com.music.spotui.ui.components.rememberArtistCredit(displayedTrackId, outgoing?.artist ?: songSinger, Color.White.copy(alpha = 0.7f)) { a ->
                        navController.navigate(com.music.spotui.ui.components.ArtistChooser.routeOf(a))
                    },
                )

                // Seek bar: follows the finger while dragging, seeks once on release.
                var isDragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableStateOf(0f) }
                // The outgoing song's times until half way through a crossfade, as its words.
                val duration = outgoing?.durationMs ?: mv.duration()
                val outgoingAt = blend?.takeIf { outgoing != null }
                SpotifySeekBar(
                    fraction = {
                        frame.value // redraw each frame
                        if (isDragging) dragValue else {
                            val d = if (outgoingAt != null) duration else mv.duration()
                            val at = outgoingAt?.positionMs() ?: mv.position()
                            if (d > 0) (at.toFloat() / d).coerceIn(0f, 1f) else 0f
                        }
                    },
                    onDrag = { isDragging = true; dragValue = it },
                    onRelease = {
                        val target = (dragValue * mv.duration()).toLong()
                        mv.seekTo(target)
                        isDragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 11.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ElapsedLabel(songPlayingState, if (isDragging) (dragValue * duration).toLong() else null,
                        position = outgoingAt?.let { o -> { o.positionMs() } } ?: mv::position) {
                        playerViewModel.formatDuration(it)
                    }
                    Text(
                        text = playerViewModel.formatDuration(duration.coerceAtLeast(0)),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        lineHeight = 14.sp,
                    )
                }

                PlayerFull(songPlayingState, playerViewModel, context, isLiked, shuffle, repeat, queueSongs)

                PlayerConnectRow(
                    context = context,
                    title = songTitle,
                    artist = songSinger,
                    coverUri = songCoverUri,
                    onDevices = { showDevices = true },
                    onQueue = { showQueue = true },
                )
                // Buttons from extensions (player.buttons), under the player's own controls.
                val extTrack = androidx.compose.runtime.remember(songTitle, songSinger) { com.music.spotui.extensions.Extensions.currentTrack() }
                com.music.spotui.extensions.MirrorIndicator()
                com.music.spotui.extensions.ExtensionPlayerButtons(extTrack)
                // 8dp card margin + this = the official 37dp from the buttons' centre to the card.
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        if (twoPane) Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.width(contentWidth * 2 + 72.dp)) { Column { topBar() } }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(72.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                mediaArea(Modifier.width(contentWidth).fillMaxHeight(), Alignment.Center)
                Column(Modifier.width(contentWidth), horizontalAlignment = Alignment.CenterHorizontally) { controls() }
            }
        } else Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(contentWidth)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            topBar()
            mediaArea(if (immersive) Modifier else Modifier.fillMaxWidth().weight(1f), Alignment.TopCenter)
            controls()
        }
        }
        }
        // The lyrics card belongs to the scrolled layout; in immersive mode the list holds the
        // artwork only, so a swipe there cannot pull a card up under the compact strip.
        if (!immersive) item {
            // T4: no lyrics card during an ad break — the song it belongs to is not what plays.
            if (!audioBuffering && !adPlaying) InlineLyrics(
                title = songTitle,
                artist = songSinger,
                album = playerViewModel.currentSongAlbum.value,
                accentColor = dominentColor,
                onExpand = { showLyrics = true },
            )
        }
        }
        // Immersive: one compact strip at the bottom of the full-bleed artwork, the official
        // app's collapsed layout. It swallows taps, so only the artwork leaves the mode.
        if (immersive) {
            ImmersiveStrip(
                modifier = Modifier.align(Alignment.BottomCenter),
                coverUri = songCoverUri,
                title = songTitle,
                artists = songSinger,
                liked = isLiked.value,
                onLike = { playerViewModel.updateLikeState(!isLiked.value) },
                fraction = {
                    frame.value // redraw each frame
                    val d = mv.duration()
                    if (d > 0) (mv.position().toFloat() / d).coerceIn(0f, 1f) else 0f
                },
            )
        }

        // Container transform out of / back into the inline card (slide-up when it is not on screen).
        com.music.spotui.ui.screens.LyricsOverlay(
            visible = showLyrics,
            title = songTitle,
            artist = songSinger,
            album = playerViewModel.currentSongAlbum.value,
            accentColor = dominentColor,
            onClose = { showLyrics = false },
        )
        // Full-screen clip: the video over everything, turned sideways, with its own controls.
        if (videoOn && mv.fullscreen.value) VideoFullscreen()
    }
}

/**
 * The immersive player's single control strip (official app): cover, title, artists, like
 * and a thin progress line. No transport row, no timestamps, no Connect/Share/Queue — those
 * disappear with the rest of the player when the artwork is tapped.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ImmersiveStrip(
    modifier: Modifier = Modifier,
    coverUri: String,
    title: String,
    artists: String,
    liked: Boolean,
    onLike: () -> Unit,
    fraction: () -> Float,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Swallows taps: the strip is not the artwork, so hitting it must not close the
            // mode (its own like button still works).
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .background(
                // Scrim only under the strip: the artwork above stays untouched.
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))
            )
            // After the background, so the scrim runs down behind the navigation bar too.
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlideImage(
                transition = CrossFade,
                model = coverUri,
                contentDescription = "",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (artists.isNotBlank()) Text(
                    text = artists,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            LikeButton(liked = liked, size = 26.dp, onClick = onLike)
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Read in the draw phase, so the line follows playback without recomposing.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .graphicsLayer()
                .drawBehind {
                    drawRect(Color.White.copy(alpha = 0.3f))
                    val played = fraction().coerceIn(0f, 1f)
                    drawRect(
                        color = Color.White,
                        size = androidx.compose.ui.geometry.Size(size.width * played, size.height),
                    )
                },
        )
    }
}

/** The clip filling the screen, landscape while it shows; back or the exit button leaves. */
@Composable
private fun VideoFullscreen() {
    val activity = LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = before ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        com.music.spotui.ui.components.VideoSurface(fitHeight = true)
        com.music.spotui.ui.components.VideoFullscreenOverlay()
    }
}









/** B26: the list the music plays from — the page's own label, else the name the app knows
 *  (the list the play started from), else the album's name. */
private fun contextDisplayName(np: SongPlayer.NowPlaying, fallbackName: String): String =
    np.contextName.ifBlank { SongPlayer.knownContextName(np.contextUri) }.ifBlank { fallbackName }

/** The list name both the header and the queue sheet show (B26). */
private fun playerContextName(playerViewModel: PlayerViewModel): String =
    contextDisplayName(SongPlayer.nowPlaying.value, playerViewModel.currentSongAlbum.value)

/** "Lecture à partir de <type>" + context name, or the queue (like the official app). */
@Composable
private fun playingFromLabels(np: SongPlayer.NowPlaying, fallbackName: String): Pair<String, String> {
    if (np.provider == "queue") return stringResource(R.string.context_type_description_play_queue) to ""
    val type = np.contextUri.split(":").getOrNull(1).orEmpty()
    val name = contextDisplayName(np, fallbackName)
    return when {
        isLikedContext(np.contextUri) -> stringResource(R.string.context_type_description_collection_your_library) to stringResource(R.string.collection_liked_songs_title)
        type == "playlist" -> stringResource(R.string.context_type_description_playlist) to name
        type == "album" -> stringResource(R.string.context_type_description_album) to name
        type == "artist" -> stringResource(R.string.context_type_description_artist) to name
        type == "show" -> stringResource(R.string.context_type_description_show) to name
        type == "search" -> stringResource(R.string.context_type_description_search) to name
        else -> stringResource(R.string.default_notification_channel_name) to name
    }
}

/** Spotify's uri of the user's Liked Songs: spotify:collection:tracks, or the older spotify:user:<name>:collection (by one artist: ...:artist:<id>). */
private val LIKED_CONTEXT = Regex("spotify:(collection(:tracks)?|user:[^:]+:collection(:artist:[^:]+)?)")

/** Only Liked Songs itself, not another collection (episodes) nor a radio that mentions one. */
private fun isLikedContext(uri: String) = LIKED_CONTEXT.matches(uri)

/**
 * Route of the page the music is playing from (playlist, album, artist, liked); null when there
 * is none to open. A radio (spotify:station:...) has no page of its own: it never falls back to
 * Liked Songs.
 */
private fun contextRoute(np: SongPlayer.NowPlaying): String? {
    val parts = np.contextUri.split(":")
    val id = parts.getOrNull(2).orEmpty()
    // B26: opening that page again must carry the name the header shows.
    val name = contextDisplayName(np, "")
    return when {
        isLikedContext(np.contextUri) -> Routes.Liked.route
        parts.getOrNull(1) == "station" -> null
        // The older form of a playlist's uri: spotify:user:<name>:playlist:<id>.
        parts.getOrNull(1) == "user" && parts.getOrNull(3) == "playlist" && parts.getOrNull(4).orEmpty().isNotBlank() ->
            com.music.spotui.ui.navigation.playlistRoute(parts[4], name)
        parts.getOrNull(1) == "playlist" && id.isNotBlank() -> com.music.spotui.ui.navigation.playlistRoute(id, name)
        parts.getOrNull(1) == "album" && id.isNotBlank() -> albumRoute(name, "", id)
        parts.getOrNull(1) == "artist" && id.isNotBlank() -> com.music.spotui.ui.navigation.artistRoute(name, id)
        else -> null
    }
}

@Composable
fun PlayerTopBar(
    navController: NavController,
    onMenuClick: () -> Unit,
    np: SongPlayer.NowPlaying,
    fallbackName: String = "",
) {
    val (line1, line2) = playingFromLabels(np, fallbackName)
    val route = contextRoute(np)
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 9.dp)
    ) {
        Icon(modifier = Modifier
            .size(26.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                       com.music.spotui.ui.navigation.PlayerSheet.close()
            },
            // Official chevron: wide and thin (the old drawable was a short, heavy one).
            imageVector = PlayerIcons.chevronDown,
            tint = Color.White,
            contentDescription = stringResource(R.string.a11y_close_player))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clickable(
                    enabled = route != null,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { route?.let { navController.navigate(it) } },
        ) {
            Text(
                text = line1,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (line2.isNotBlank()) Text(
                text = line2,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Default.MoreVert,
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onMenuClick() },
            contentDescription = stringResource(R.string.content_description_context_menu))
    }
}

/** Small grey "E" badge for explicit tracks. */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(16.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.65f)),
    ) {
        // No font padding + trimmed line height so the "E" sits dead centre.
        Text(
            stringResource(R.string.explicit_badge),
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 10.sp,
                lineHeight = 10.sp,
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}

/** Liked = green disc with a check; not liked = outlined plus (official app). */
@Composable
fun LikeButton(liked: Boolean, size: androidx.compose.ui.unit.Dp = 28.dp, onClick: () -> Unit) {
    // Pop on change (tap or sync), as the official heart/check: shrink, then spring back.
    // Nothing runs on first display, so long lists do not animate while scrolling.
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val firstShown = remember { booleanArrayOf(true) }
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    LaunchedEffect(liked) {
        if (firstShown[0]) { firstShown[0] = false; return@LaunchedEffect }
        if (reduce) return@LaunchedEffect
        scale.snapTo(0.6f)
        scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 600f))
    }
    val mod = Modifier
        .size(size)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() }
    // The disc fills and the glyphs cross-fade, instead of one swapping for the other.
    val disc by androidx.compose.animation.animateColorAsState(
        if (liked) com.music.spotui.ui.theme.AppAccent else Color.Transparent,
        if (reduce) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(180),
        label = "likeDisc",
    )
    Box(contentAlignment = Alignment.Center, modifier = mod.background(disc, CircleShape)) {
        androidx.compose.animation.Crossfade(
            liked,
            animationSpec = if (reduce) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(180),
            label = "likeGlyph",
        ) { on ->
            if (on) {
                // The official check is bolder, with round ends.
                Icon(PlayerIcons.check, contentDescription = stringResource(R.string.remove_from_liked), tint = Color.Black, modifier = Modifier.size(size * 0.7f))
            } else {
                Icon(painterResource(id = R.drawable.ic_add), contentDescription = stringResource(R.string.aligned_curation_context_menu_item_add_to_liked_songs), tint = Color.White, modifier = Modifier.size(size))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerInfo(
    songTitle: String,
    songSinger: String,
    explicit: Boolean,
    liked: Boolean,
    onLikeClick: () -> Unit,
    /** The track behind the +: with it, the + opens "Saved in" once the track is saved. */
    likeSong: (() -> SongsModel)? = null,
    onArtistClick: (() -> Unit)? = null,
    /** The artist line with each artist its own tap target (rememberArtistCredit), when known. */
    artistCredit: androidx.compose.ui.text.AnnotatedString? = null,
    onTitleClick: (() -> Unit)? = null,
    /** T4: an ad break is on: what the caller passes is the song from before it, not what plays. */
    adPlaying: Boolean = false,
    /** The words' dip through a crossfade's switch (SongBlend.wordsAlpha), read at draw. */
    wordsAlpha: () -> Float = { 1f },
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        // A new song's title and artist fade in (over 0.2 s) instead of replacing the old ones
        // in one frame, while its cover slides in above them.
        val textAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
        var shownTitle by remember { mutableStateOf(songTitle) }
        LaunchedEffect(songTitle) {
            if (songTitle == shownTitle) return@LaunchedEffect
            shownTitle = songTitle
            textAlpha.snapTo(0f)
            textAlpha.animateTo(1f, androidx.compose.animation.core.tween(200))
        }
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp).graphicsLayer { alpha = textAlpha.value * wordsAlpha() }) {
            Text(
                modifier = Modifier
                    // A few passes, after the sheet has settled (not an endless 120 Hz animation).
                    .basicMarquee(iterations = 3, initialDelayMillis = 1_500, repeatDelayMillis = 2_500)
                    // Nothing to open during an ad: the title would lead to the previous song.
                    .then(if (onTitleClick != null && !adPlaying) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTitleClick() } else Modifier),
                text = if (adPlaying) stringResource(R.string.ad_playing) else songTitle,
                color = Color.White,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                softWrap = false,
            )
            // The artist row belongs to the song: during an ad the previous one's artist is
            // what the state still holds, so it is not shown as if it were playing.
            if (!adPlaying) Row(verticalAlignment = Alignment.CenterVertically) {
                if (explicit) {
                    ExplicitBadge()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = artistCredit ?: androidx.compose.ui.text.AnnotatedString(songSinger),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = if (onArtistClick != null) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onArtistClick() } else Modifier,
                )
            }
        }
        // No like for a file of the device (not in the Spotify library; an extension's stream or a
        // download is), and none during an ad break: the + would save the song from before the break.
        if (!adPlaying && SongPlayer.canLike) {
            if (likeSong != null) AddButton(song = likeSong, liked = liked, onLike = onLikeClick)
            else LikeButton(liked = liked, onClick = onLikeClick)
        }
    }
}

/** Elapsed time text with its own ticker (doesn't recompose the whole player). */
@Composable
private fun ElapsedLabel(playing: Boolean, override: Long?, position: () -> Long, format: (Long) -> String) {
    val mv = com.music.spotui.video.MusicVideoController
    val at by androidx.compose.runtime.rememberUpdatedState(position)
    var now by remember { mutableStateOf(position()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, mv.on.value, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = at()
                if (!playing) break
                delay(250L)
            }
        }
    }
    // Seek while paused: pick the new position up as soon as it changes.
    LaunchedEffect(override, position) { if (override == null) now = position() }
    Text(text = format(override ?: now), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 14.sp)
}

/** Thin Spotify-style seek bar: 2dp track, small white thumb, drag to scrub. */
@Composable
fun SpotifySeekBar(
    fraction: () -> Float,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(1f) }
    val seekDescription = stringResource(R.string.playback_seek_description)
    // Read only in semantics: playback ticks do not recompose the player.
    // Whole percent updates also avoid publishing a new semantic value every frame.
    val semanticProgress = remember(fraction) {
        derivedStateOf {
            val value = fraction()
            if (value.isFinite()) (value.coerceIn(0f, 1f) * 100f).roundToInt() / 100f else 0f
        }
    }
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .height(24.dp)
            // Its own layer: each tick of the clock re-records only the bar. Without it the
            // tick invalidated the player's whole layer (cover included): ~5 ms of UI thread
            // per tick at 15 ticks a second, measured with Perfetto.
            .graphicsLayer()
            .semantics {
                contentDescription = seekDescription
                progressBarRangeInfo = ProgressBarRangeInfo(semanticProgress.value, 0f..1f)
                setProgress { requested ->
                    if (!requested.isFinite()) return@setProgress false
                    onDrag(requested.coerceIn(0f, 1f))
                    onRelease()
                    true
                }
            }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)); onRelease() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)) },
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                ) { change, _ ->
                    change.consume()
                    onDrag((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            }
    ) {
        val y = size.height / 2
        // Official: a hairline track and a small knob (the old one was thicker and bigger).
        val track = 2.dp.toPx()
        val f = fraction().coerceIn(0f, 1f)
        drawLine(Color.White.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), track, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * f, y), track, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * f, y))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSlider(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: SliderColors = SliderDefaults.colors(),
) {
    Box(modifier = modifier.height(10.dp)) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
        )
    }
}

@Composable
fun PlayerFull(
    songPlayingState: Boolean,
    playerViewModel: PlayerViewModel,
    context: Context,
    isLiked: MutableState<Boolean>,
    shuffle: Boolean,
    repeat: Boolean,
    queueSongs: List<SongsModel>
) {
    val smartOn = SmartShuffle.on.value != null && SmartShuffle.on.value == SongPlayer.nowPlaying.value.contextUri
    val smartScope = androidx.compose.runtime.rememberCoroutineScope()




    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            // Official spacing: the icons' centres sit 36dp from the edges, the row 57dp under
            // the time labels and 74dp above the device row.
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 26.dp)
            .height(64.dp)
    ) {
        Icon(
            modifier = Modifier
                .size(28.dp)
                .drawWithContent {
                    drawContent()
                    if (shuffle || smartOn) drawCircle(com.music.spotui.ui.theme.AppAccent, radius = 2.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height + 7.dp.toPx()))
                }
                .pressable {
                    // As in Spotify: off → shuffle → smart shuffle (playlists, Liked Songs) → off.
                    val ctx = SongPlayer.nowPlaying.value.contextUri
                    when {
                        smartOn -> { SmartShuffle.on.value = null; playerViewModel.updateShuffleState(false) }
                        shuffle && SmartShuffle.canPlay(ctx) -> {
                            SmartShuffle.on.value = ctx
                            android.widget.Toast.makeText(context, context.getString(R.string.smart_shuffle_on), android.widget.Toast.LENGTH_SHORT).show()
                            smartScope.launch {
                                if (!SmartShuffle.startFor(ctx, SongPlayer.currentTrackId)) {
                                    SmartShuffle.on.value = null
                                    android.widget.Toast.makeText(context, context.getString(R.string.page_load_failed), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        else -> playerViewModel.updateShuffleState(!shuffle)
                    }
                }
            ,
            tint = if (shuffle || smartOn){
                com.music.spotui.ui.theme.AppAccent
            }
            else{
                Color.White
            },
            // Smart shuffle has its own glyph (shuffle + sparkle), so it reads differently from plain shuffle.
            painter = painterResource(id = if (smartOn) R.drawable.ic_shuffle_smart_active else R.drawable.ic_player_shuffle),
            contentDescription = stringResource(when { smartOn -> R.string.notif_shuffle_disable_smart; shuffle -> R.string.notif_shuffle_disable; else -> R.string.notif_shuffle_enable }))
        
        Icon(
            modifier = Modifier
                .size(40.dp)
                .pressable {
                    // The queue itself is already in shuffled order when shuffle
                    // is on (reordered once at toggle) — never re-shuffle per tap.
                    playerViewModel.playPreviousSong(queueSongs, context)
                }
            ,
            tint = Color.White,
            painter = painterResource(id = R.drawable.ic_player_back),
            contentDescription = stringResource(R.string.np_content_desc_prev))
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                // requiredSize forces an exact 64×64 square even if the parent Column
                // constrains height — .size() alone let it get squished into an ellipse.
                .requiredSize(64.dp)  // never below 56dp
                .pressable(pressedScale = 0.92f) {
                    if (songPlayingState) {
                        SongPlayer.pause()
                        playerViewModel.updateSongState(
                            playerViewModel.currentSongCoverUri.value,
                            playerViewModel.currentSongTitle.value,
                            playerViewModel.currentSongSinger.value,
                            false,
                            playerViewModel.currentSongId.value,
                            playerViewModel.currentSongIndex.value,
                            playerViewModel.currentSongAlbum.value
                        )
                    } else {
                        SongPlayer.play()
                        playerViewModel.updateSongState(
                            playerViewModel.currentSongCoverUri.value,
                            playerViewModel.currentSongTitle.value,
                            playerViewModel.currentSongSinger.value,
                            true,
                            playerViewModel.currentSongId.value,
                            playerViewModel.currentSongIndex.value,
                            playerViewModel.currentSongAlbum.value
                        )
                    }
                }
                .clip(CircleShape)
                .background(Color.White)
            ,
            contentAlignment = Alignment.Center
        ) {
            com.music.spotui.ui.components.PlayPauseIcon(
                modifier = Modifier.size(36.dp),
                tint = Color.Black,
                playing = songPlayingState,
                contentDescription = stringResource(if (songPlayingState) R.string.notif_action_pause else R.string.notif_action_play))
        }

        Icon(
            modifier = Modifier
                .size(40.dp)
                .pressable {

                    playerViewModel.playNextSongs(queueSongs, context)
                }
            ,
            tint = Color.White,
            painter = painterResource(id = R.drawable.ic_player_skip),
            contentDescription = stringResource(R.string.np_content_desc_next))
        Icon(
            modifier = Modifier
                .size(28.dp)
                .pressable {
                    SongPlayer.cycleRepeat()


                }
            ,
            tint = if (repeat){
                com.music.spotui.ui.theme.AppAccent
            }
            else{
                Color.White
            },
            // The official loop with one arrow (drawn here: the old drawable was the two-arrow one).
            imageVector = if (SongPlayer.repeatMode.value == 2) PlayerIcons.repeatOne else PlayerIcons.repeat,
            contentDescription = when (SongPlayer.repeatMode.value) { 1 -> stringResource(R.string.repeat_on); 2 -> stringResource(R.string.repeat_one); else -> stringResource(R.string.repeat_off) })
    }
}

@Composable
fun PlayerConnectRow(
    context: Context,
    title: String,
    artist: String,
    coverUri: String,
    onDevices: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        // Spotify Connect: current device, opens the device picker.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDevices() },
        ) {
            Icon(
                // What plays: a phone for this phone, a speaker, a computer… (as the label says).
                imageVector = com.music.spotui.ui.components.activeDeviceIcon(),
                tint = com.music.spotui.ui.theme.AppAccent,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.mr_user_route_category_name),
            )
            Column(modifier = Modifier.padding(start = 6.dp).widthIn(max = 220.dp)) {
                Text(
                    text = com.music.spotui.ui.components.activeDeviceLabel(),
                    color = com.music.spotui.ui.theme.AppAccent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                // What the sound is when Myspoti's own player plays it (an extension's stream, a
                // download); a tap opens what plays and its quality.
                val source = com.music.spotui.ui.components.playingSourceLabel()
                var quality by remember { mutableStateOf(false) }
                if (quality) com.music.spotui.ui.components.QualitySheet { quality = false }
                if (source.isNotBlank()) Text(
                    text = source,
                    color = com.music.spotui.ui.theme.AppMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { quality = true },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            var shareMenu by remember { mutableStateOf(false) }
            val shareScope = androidx.compose.runtime.rememberCoroutineScope()
            Box {
                Icon(
                    painter = painterResource(id = R.drawable.ic_share),
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { shareMenu = true },
                    contentDescription = stringResource(R.string.action_share_label),
                )
                androidx.compose.material3.DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_link)) },
                        onClick = {
                            shareMenu = false
                            // Songs and episodes alike: the link follows what plays (spotify:<kind>:<id>).
                            val parts = SongPlayer.nowPlaying.value.uri.split(':')
                            val link = if (parts.size == 3 && parts[0] == "spotify") "https://open.spotify.com/${parts[1]}/${parts[2]}"
                                else SongPlayer.currentTrackId.takeIf { it.isNotBlank() }?.let { "https://open.spotify.com/track/$it" }
                            if (link != null) com.music.spotui.ui.components.SpotifyCode.show(com.music.spotui.ui.components.ShareTarget(
                                "spotify:" + link.removePrefix("https://open.spotify.com/").replace('/', ':'),
                                title, artist, coverUri,
                            ))
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_as_image)) },
                        onClick = {
                            shareMenu = false
                            shareScope.launch {
                                com.music.spotui.ui.components.ShareCard.share(
                                    context, title, artist, coverUri, context.getString(R.string.action_share_label))
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.width(28.dp))
            Icon(
                // Official queue glyph: a short line over stacked wide ones.
                imageVector = PlayerIcons.queue,
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onQueue() },
                contentDescription = stringResource(R.string.action_queue_label),
            )
        }
    }
}

@Composable
fun PlayerOptionsSheet(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    context: Context,
    isLiked: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onOpenQueue: (() -> Unit)? = null,
) {
    val song = nowPlayingSong(playerViewModel)
    com.music.spotui.ui.components.SongOptionsSheet(
        song = song,
        navController = navController,
        context = context,
        onDismiss = onDismiss,
        nowPlaying = true,
        onOpenQueue = onOpenQueue,
    )
}


/** The track that plays, as Spotify reports it (the local queue may not have it). */
fun nowPlayingSong(playerViewModel: PlayerViewModel): SongsModel {
    val np = SongPlayer.nowPlaying.value
    val songId = playerViewModel.currentSongId.value
    val known = playerViewModel.queue.value.firstOrNull { it.id == songId }
    return SongsModel(
        id = songId,
        title = playerViewModel.currentSongTitle.value,
        album = known?.album?.ifBlank { null } ?: playerViewModel.currentSongAlbum.value,
        singer = playerViewModel.currentSongSinger.value,
        coverUri = playerViewModel.currentSongCoverUri.value,
        url = known?.url.orEmpty(),
        spotifyTrackId = SongPlayer.currentTrackId,
        explicit = np.explicit,
        albumId = np.albumUri.substringAfterLast(":", "").ifBlank { known?.albumId.orEmpty() },
    )
}

/**
 * The + of a track, as in the official app: a first tap saves it in Liked Songs; once
 * saved (green check), a tap opens "Saved in" to add it to playlists or remove it.
 */
@Composable
fun AddButton(song: () -> SongsModel, liked: Boolean, size: androidx.compose.ui.unit.Dp = 28.dp, onLike: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var savedIn by remember { mutableStateOf<SongsModel?>(null) }
    savedIn?.let { s ->
        com.music.spotui.ui.components.SavedInSheet(song = s, context = context, onDismiss = { savedIn = null })
    }
    // A song smart shuffle mixed in is not in the playlist: its + adds it there (Spotify's sparkle +).
    val current = song()
    // Saved somewhere, as in the official app: liked, or playing from one of the user's own
    // playlists (reported: songs of one's playlists showed a bare +, as if saved nowhere).
    val contextUri = SongPlayer.nowPlaying.value.contextUri
    val inOwnList by androidx.compose.runtime.produceState(false, contextUri) {
        value = contextUri.startsWith("spotify:playlist:") &&
            contextUri.substringAfterLast(':') in com.music.spotui.data.api.EditablePlaylists.ids()
    }
    // A queued song plays with the list still as its context: only the list's own rows count.
    val fromList = SongPlayer.nowPlaying.value.provider.let { it.isBlank() || it == "context" }
    val saved = com.music.spotui.ui.components.savedSomewhere(
        liked = liked,
        isRecommendation = SmartShuffle.isRecommendation(current.spotifyTrackId),
        // The list is the player's: it says nothing of a row of a search result or an album.
        isPlayingTrack = current.spotifyTrackId.isNotBlank() && current.spotifyTrackId == SongPlayer.currentTrackId,
        playingFromOwnList = inOwnList,
        fromList = fromList,
    )
    if (SmartShuffle.isRecommendation(current.spotifyTrackId)) {
        Icon(
            Icons.Outlined.AddCircleOutline,
            contentDescription = stringResource(R.string.smart_shuffle_add),
            tint = Color.White,
            modifier = Modifier.size(size).clip(CircleShape).clickable { SmartShuffle.keep(context, current) },
        )
        return
    }
    LikeButton(liked = saved, size = size) {
        if (saved) savedIn = song() else {
            onLike()
            android.widget.Toast.makeText(context, context.getString(R.string.snackbar_added_to_liked_songs), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun PlayerMenuRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = Color.White,
    enabled: Boolean = true,
    trailingArrow: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = icon,
            tint = if (enabled) iconTint else Color.Gray,
            modifier = Modifier.size(22.dp),
            contentDescription = null
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = label,
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        if (trailingArrow) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp),
                contentDescription = null
            )
        }
    }
}
/**
 * Plays a Spotify Canvas clip: a short, muted, looping video filling the
 * now-playing background, on the shared [CanvasPlayer] (separate from the audio engine),
 * paused when the composable leaves. [onFirstFrame]: the clip is visible.
 *
 * Loops are served from the media cache: without it every loop re-downloaded the
 * clip, and since ExoPlayer ignores the activity lifecycle it kept doing so in the
 * background (#87, gigabytes per hour). It also pauses while the app is not visible.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun CanvasVideo(url: String, playing: Boolean, onFirstFrame: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember(url) { com.music.spotui.ui.components.CanvasPlayer.get(context, url) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val requested by androidx.compose.runtime.rememberUpdatedState(playing)
    val shown by androidx.compose.runtime.rememberUpdatedState(onFirstFrame)
    LaunchedEffect(exo, playing, lifecycle) {
        exo.playWhenReady = playing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }
    androidx.compose.runtime.DisposableEffect(exo, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            exo.playWhenReady = requested && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        val removeFrame = com.music.spotui.ui.components.CanvasPlayer.onFirstFrame { shown() }
        onDispose {
            lifecycle.removeObserver(observer)
            removeFrame()
            exo.playWhenReady = false
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        // factory runs once; a new track's clip gets a new player, so rebind it here.
        update = { it.player = exo },
        factory = { ctx ->
            // Inflated: the surface type (texture view) can only be set from XML.
            (android.view.LayoutInflater.from(ctx).inflate(R.layout.canvas_player_view, null) as androidx.media3.ui.PlayerView).apply {
                setArtworkDisplayMode(androidx.media3.ui.PlayerView.ARTWORK_DISPLAY_MODE_OFF)
                setDefaultArtwork(null)
                player = exo
            }
        },
    )
}

/** Cover to Canvas cross-fade. */
private const val CANVAS_FADE_MS = 680

/** How much of the lyrics card shows under the buttons (its 8dp margin included). */
private const val PLAYER_PEEK_DP = 65
/** The gradient's bottom: near-black with a hint of blue, like the official page. */
private val PLAYER_BASE = Color(0xFF0F1114)

/**
 * Glyphs drawn to look like the official player's (outlined, round-capped strokes). Material's
 * own repeat / queue / check differ in shape, and the app may not ship Spotify's artwork.
 */
private object PlayerIcons {
    private fun stroked(name: String, w: Float = 2f, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = w,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
                pathBuilder = build,
            )
        }.build()

    val chevronDown: ImageVector = stroked("chevronDown", 1.8f) {
        moveTo(4f, 8.5f); lineTo(12f, 16.5f); lineTo(20f, 8.5f)
    }
    val check: ImageVector = stroked("check", 3f) {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }
    private fun androidx.compose.ui.graphics.vector.PathBuilder.loop() {
        // Rounded frame open at the lower left, where the arrow head sits.
        moveTo(3f, 12f); verticalLineTo(9f); arcTo(4f, 4f, 0f, false, true, 7f, 5f)
        horizontalLineTo(17f); arcTo(4f, 4f, 0f, false, true, 21f, 9f)
        verticalLineTo(12f); arcTo(4f, 4f, 0f, false, true, 17f, 16f)
        horizontalLineTo(8f)
        moveTo(11f, 12.5f); lineTo(7.5f, 16f); lineTo(11f, 19.5f)
    }
    val repeat: ImageVector = stroked("repeat") { loop() }
    val repeatOne: ImageVector = stroked("repeatOne") {
        loop()
        moveTo(10.5f, 8.8f); lineTo(12.5f, 7.8f); verticalLineTo(13f)
    }
    val device: ImageVector = stroked("device") {
        moveTo(6f, 5f); horizontalLineTo(18f); arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 6.5f)
        verticalLineTo(14f); arcTo(1.5f, 1.5f, 0f, false, true, 18f, 15.5f)
        horizontalLineTo(6f); arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 14f)
        verticalLineTo(6.5f); arcTo(1.5f, 1.5f, 0f, false, true, 6f, 5f)
        moveTo(3f, 19.5f); horizontalLineTo(21f)
    }
    val queue: ImageVector = stroked("queue", 1.8f) {
        moveTo(8.5f, 5f); horizontalLineTo(15.5f)
        moveTo(4.5f, 10f); horizontalLineTo(19.5f)
        moveTo(4.5f, 14.5f); horizontalLineTo(19.5f)
        moveTo(4.5f, 19f); horizontalLineTo(19.5f)
    }
}
