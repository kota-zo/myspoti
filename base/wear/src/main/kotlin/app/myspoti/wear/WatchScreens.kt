@file:OptIn(com.google.android.horologist.annotations.ExperimentalHorologistApi::class)

package app.myspoti.wear

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.google.android.horologist.audio.ui.VolumeUiState
import com.google.android.horologist.audio.ui.material3.VolumeLevelIndicator
import com.google.android.horologist.audio.ui.material3.components.actions.VolumeButton
import com.google.android.horologist.audio.ui.material3.volumeRotaryBehavior
import com.google.android.horologist.images.base.paintable.Paintable
import com.google.android.horologist.images.coil.CoilPaintable
import com.google.android.horologist.media.model.MediaPositionPredictor
import com.google.android.horologist.media.ui.material3.components.animated.AnimatedMediaControlButtons
import com.google.android.horologist.media.ui.material3.components.animated.MarqueeTextMediaDisplay
import com.google.android.horologist.media.ui.material3.components.background.RadialBackground
import com.google.android.horologist.media.ui.material3.components.display.MessageMediaDisplay
import com.google.android.horologist.media.ui.material3.screens.player.PlayerScreen
import com.google.android.horologist.media.ui.state.model.TrackPositionUiModel
import kotlinx.coroutines.flow.Flow

/**
 * The watch's screens, drawn from plain state (the singletons are read in [WatchApp]): a player
 * page (Horologist's media UI on Wear Material 3) and, a swipe to the left, the library page.
 */

/** What plays, from the extensions ([source] set) or from the account. [atElapsedMs]: [positionMs]'s time on SystemClock.elapsedRealtime. */
internal class WatchPlaying(
    val name: String, val artists: String, val playing: Boolean, val source: String?, val cover: String?,
    val positionMs: Long = 0L, val durationMs: Long = 0L, val atElapsedMs: Long = 0L, val buffering: Boolean = false,
)

/** The watch's Material 3 theme: the system's dynamic colours where the watch has them. */
@Composable
internal fun WatchTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = remember(context) { runCatching { dynamicColorScheme(context) }.getOrNull() ?: ColorScheme() }
    MaterialTheme(colorScheme = colors, content = content)
}

/** The volume as the player shows it: its level, the moments the level indicator shows, and its changes. */
internal class WatchVolumeUi(
    val state: () -> VolumeUiState,
    val shown: Flow<Unit>?,
    val set: (Int) -> Unit,
    val open: () -> Unit,
)

@Composable
internal fun WatchPager(
    playing: WatchPlaying?,
    error: String?,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    volume: WatchVolumeUi,
    lists: Result<List<WatchList>>?,
    onPlay: (WatchList) -> Unit,
    onRetry: () -> Unit,
    extensionsNote: String,
    onSyncExtensions: () -> Unit,
    initialPage: Int = 0,
    artwork: Paintable? = playing?.cover?.let { remember(it) { CoilPaintable(it) } },
) {
    val pager = rememberPagerState(initialPage = initialPage) { 2 }
    AppScaffold {
        HorizontalPagerScaffold(pagerState = pager) {
            HorizontalPager(state = pager) { page ->
                if (page == 0) {
                    WatchPlayer(playing, error, artwork, onPrevious, onToggle, onNext, volume, active = pager.currentPage == 0)
                } else {
                    WatchLibrary(lists, onPlay, onRetry, extensionsNote, onSyncExtensions)
                }
            }
        }
    }
}

@Composable
internal fun WatchPlayer(
    playing: WatchPlaying?,
    error: String?,
    artwork: Paintable?,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    volume: WatchVolumeUi,
    active: Boolean = true,
) {
    // The crown turns the volume on the player page, the level shown at the edge meanwhile.
    val focus = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) runCatching { focus.requestFocus() } }
    ScreenScaffold(scrollIndicator = { VolumeLevelIndicator(volumeUiState = volume.state, displayIndicatorEvents = volume.shown) }) {
        PlayerScreen(
            modifier = Modifier
                .rotaryScrollable(volumeRotaryBehavior(volume.state, volume.set), focus)
                .focusRequester(focus).focusable(),
            mediaDisplay = {
                if (playing == null) {
                    MessageMediaDisplay(message = stringResource(R.string.wear_nothing_playing))
                } else {
                    MarqueeTextMediaDisplay(title = playing.name, artist = playing.artists.takeIf { it.isNotBlank() })
                }
            },
            controlButtons = {
                AnimatedMediaControlButtons(
                    onPlayButtonClick = onToggle,
                    onPauseButtonClick = onToggle,
                    playPauseButtonEnabled = playing != null,
                    playing = playing?.playing == true,
                    onSeekToPreviousButtonClick = onPrevious,
                    seekToPreviousButtonEnabled = playing != null,
                    onSeekToNextButtonClick = onNext,
                    seekToNextButtonEnabled = playing != null,
                    trackPositionUiModel = playing.position(),
                )
            },
            buttons = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top, modifier = Modifier.fillMaxSize()) {
                    // The engine's failure takes the volume button's room (the crown still sets the volume).
                    if (error != null) {
                        Text(
                            stringResource(R.string.wear_failed, error), maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth(0.66f),
                        )
                    } else {
                        playing?.source?.let {
                            Text(
                                it, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(0.62f),
                            )
                        }
                        VolumeButton(onVolumeClick = volume.open, volumeUiState = volume.state())
                    }
                }
            },
            background = {
                RadialBackground(color = MaterialTheme.colorScheme.primaryContainer)
                WatchArtwork(artwork)
            },
        )
    }
}

/**
 * The song's sleeve behind the player, dimmed and fading to black at the edge so the text and
 * buttons stay readable; nothing (the radial background) until it is loaded.
 */
@Composable
private fun WatchArtwork(artwork: Paintable?) {
    Crossfade(targetState = artwork, label = "artwork") { current ->
        val painter = current?.rememberPainter() ?: return@Crossfade
        if (painter.intrinsicSize == Size.Unspecified) return@Crossfade
        val background = MaterialTheme.colorScheme.background
        Image(
            painter = painter, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.55f,
            modifier = Modifier.fillMaxSize().drawWithCache {
                val scrim = Brush.radialGradient(0f to background.copy(alpha = 0.35f), 0.7f to background.copy(alpha = 0.55f), 1f to background)
                onDrawWithContent { drawRect(background); drawContent(); drawRect(scrim) }
            },
        )
    }
}

/** The song's place for the ring round play and pause: predicted from its last known position while it plays. */
private fun WatchPlaying?.position(): TrackPositionUiModel = when {
    this == null -> TrackPositionUiModel.Hidden
    durationMs <= 0L -> if (buffering) TrackPositionUiModel.Loading(shouldAnimate = true) else TrackPositionUiModel.Hidden
    else -> TrackPositionUiModel.Predictive(
        MediaPositionPredictor(atElapsedMs, durationMs, positionMs.coerceIn(0L, durationMs), if (playing) 1f else 0f),
        shouldAnimate = true, isLoading = buffering,
    )
}

@Composable
internal fun WatchLibrary(
    lists: Result<List<WatchList>>?,
    onPlay: (WatchList) -> Unit,
    onRetry: () -> Unit,
    extensionsNote: String,
    onSyncExtensions: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.wear_library))
                }
            }
            when {
                lists == null -> item {
                    Text(stringResource(R.string.wear_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lists.isFailure -> {
                    item {
                        Text(
                            stringResource(R.string.wear_no_phone), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        )
                    }
                    item {
                        Button(
                            onClick = onRetry, colors = ButtonDefaults.filledTonalButtonColors(),
                            icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                        ) { Text(stringResource(R.string.wear_retry)) }
                    }
                }
                else -> items(lists.getOrThrow(), key = { it.uri }) { list ->
                    Button(
                        onClick = { onPlay(list) }, colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(WatchIcons.QueueMusic, contentDescription = null) },
                        secondaryLabel = list.subtitle.takeIf { it.isNotBlank() }?.let { s -> { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                    ) { Text(list.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
            // The phone's extensions, copied to run here (an audio source plays lists with no phone).
            item {
                Button(
                    onClick = onSyncExtensions, colors = ButtonDefaults.filledVariantButtonColors(),
                    icon = { Icon(WatchIcons.Extension, contentDescription = null) },
                    secondaryLabel = { Text(extensionsNote, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_extensions_sync), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

