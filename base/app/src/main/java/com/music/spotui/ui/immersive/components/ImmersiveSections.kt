/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt (SectionHeader, Shelf, ShelfCard, HeroCard),
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/ShelfRow.kt and
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.ui.components.spotifyImageUrl
import com.music.spotui.ui.immersive.Immersive

/**
 * Width of a card in the compact carousels (home shelves, library shelves, an artist's releases).
 * Sized so a phone-width row shows two cards whole with the edge of a third showing: enough to say
 * the row scrolls without a card being half a card.
 */
val ImmersiveShelfCardWidth = 150.dp

/** Share of the row a lead-shelf card takes, so the next one peeks in past it. */
private const val HERO_CARD_FRACTION = 0.70f

/** How wide a lead-shelf card is ever allowed to get: a caption over some artwork, not a poster. */
private val HERO_CARD_MAX_WIDTH = 320.dp

/** A lead-shelf card's proportions: a touch taller than it is wide. */
const val ImmersiveHeroCardRatio = 0.92f

/**
 * How wide a lead-shelf card should be in a row [available] wide — the shared answer for the real
 * shelf and for the skeleton standing in for it, which have to agree or the feed jumps when the
 * data lands. Give it the row's own width (`BoxWithConstraints.maxWidth`), not the window's.
 */
fun immersiveHeroCardWidth(available: Dp): Dp = minOf(available * HERO_CARD_FRACTION, HERO_CARD_MAX_WIDTH)

/**
 * A section's heading: [title] in the page's title type, an optional [subtitle] line and, when
 * [onSeeAll] is set, a "See all" link in the accent at the end.
 *
 * Put it above a [ImmersiveShelfRow] or a run of rows: `SectionHeader("Recently played", onSeeAll = ::open)`.
 */
@Composable
fun SectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    subtitle: String = "",
) {
    Row(
        modifier = modifier
            .padding(horizontal = Immersive.Gutter, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Immersive.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = Immersive.Subhead,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onSeeAll != null) {
            Text(
                text = stringResource(R.string.immersive_shell_see_all),
                style = Immersive.Subhead.copy(color = Immersive.Accent, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onSeeAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * A sideways-scrolling row. Snaps to each cell rather than stopping mid-card: a fling always
 * settles with a card aligned to the leading edge instead of leaving items cut in half.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImmersiveShelfRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Immersive.Gutter),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(14.dp),
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        content = content,
    )
}

/**
 * A titled shelf: a [SectionHeader] over a [ImmersiveShelfRow]. Fill the row with
 * [ImmersiveShelfCard]s:
 *
 * ```
 * ImmersiveShelf("New releases", onSeeAll = ::open) {
 *     items(albums, key = { it.id }) { a -> ImmersiveShelfCard(a.name, a.artist, a.imageUrl, onClick = { open(a) }) }
 * }
 * ```
 */
@Composable
fun ImmersiveShelf(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    subtitle: String = "",
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.padding(bottom = 26.dp)) {
        SectionHeader(title = title, onSeeAll = onSeeAll, subtitle = subtitle)
        ImmersiveShelfRow(state = state, content = content)
    }
}

/**
 * A square card for a shelf: a cover over a title and a subtitle. With [round] (artists) the cover
 * is a circle and the text is centred under it.
 *
 * @param playing bars over the cover and the title in the accent.
 * @param onLongClick the card's menu; null for none.
 */
@Composable
fun ImmersiveShelfCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(ImmersiveShelfCardWidth),
    round: Boolean = false,
    playing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** Drawn over the cover (a "new episode" dot). */
    badge: (@Composable BoxScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.immersiveClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ImmersiveCover(
                imageUrl,
                Modifier.fillMaxSize(),
                if (round) CircleShape else Immersive.CoverMedium,
            )
            if (playing) ImmersivePlayingBars(Modifier.align(Alignment.Center))
            badge?.invoke(this)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = Immersive.Body.copy(
                fontWeight = FontWeight.Medium,
                color = if (playing) Immersive.Accent else Immersive.Text,
            ),
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = Immersive.Subhead,
                textAlign = if (round) TextAlign.Center else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The big card of a lead shelf: the artwork with the caption laid over a scrim, as on a "Listen
 * now" row. Give it a width from [immersiveHeroCardWidth] and place several in an [ImmersiveShelfRow]
 * so the next one peeks in.
 *
 * @param playing bars over the artwork and the title in the accent.
 */
@Composable
@OptIn(ExperimentalGlideComposeApi::class)
fun ImmersiveLargeAlbumCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val model = spotifyImageUrl(imageUrl)
    Box(
        modifier = modifier
            .immersiveClickable(onClick = onClick, onLongClick = onLongClick, pressedScale = 0.98f)
            .aspectRatio(ImmersiveHeroCardRatio)
            .clip(Immersive.Card)
            .background(Immersive.Raised)
            .immersiveThumbnailBorder(Immersive.Card),
    ) {
        if (model != null) {
            GlideImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                transition = CrossFade,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (playing) ImmersivePlayingBars(Modifier.align(Alignment.Center))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = title,
                style = Immersive.Headline.copy(color = if (playing) Immersive.Accent else Color.White),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = Immersive.Subhead.copy(color = Color.White.copy(alpha = 0.72f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
