/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt (SearchFilterTabs) at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.Immersive

/** Rounded, but well short of a capsule: the corner reads as a cut, not a curve. */
private val ChipShape = RoundedCornerShape(12.dp)

/**
 * Filter pills rather than a tab row: squarish rounded rectangles, the selected one inverted.
 * They scroll sideways so a long label set never squeezes the text, and the gutter padding sits
 * inside the scroll so it scrolls with them. Shared by the Home facets, the Search filters and
 * the Library kinds.
 */
@Composable
internal fun ImmersiveChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = Immersive.Gutter, vertical = 6.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One pill of an [ImmersiveChipRow]. */
@Composable
internal fun ImmersiveChip(
    label: String,
    selected: Boolean,
    shape: androidx.compose.ui.graphics.Shape = ChipShape,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Immersive.Text else Immersive.Raised)
            // Only a pill that is not already selected has anything to report: re-tapping the
            // current one changes nothing, so buzzing for it would be feedback for a no-op.
            .clickable(role = Role.Button) {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = Immersive.Subhead.copy(
                color = if (selected) Immersive.Page else Immersive.Text,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/** The left half of two chips joined (a picked chip and the one it opened), and the right one. */
private val JoinedStart = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 4.dp, bottomEnd = 4.dp)
private val JoinedEnd = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)

/**
 * The Home facets as Spotify shows them: All, Music, Podcasts, Audiobooks; once one is picked, a
 * close chip, the picked one, and joined to it the chips it opens (Following: only what the user
 * follows), which slide out of it. A label Spotify sent is shown as it came (in the app's language).
 */
@Composable
internal fun ImmersiveHomeFacets(facet: String, labels: Map<String, String>, podcasts: Boolean, select: (String) -> Unit) {
    val parent = com.music.spotui.data.entity.HomeSubChips.parentOf(facet) ?: facet
    val subs = com.music.spotui.data.entity.HomeSubChips.byParent[parent].orEmpty()
    ImmersiveChipRow {
        if (facet.isEmpty()) {
            ImmersiveChip(labels[""].orEmpty(), selected = true) {}
            for (id in listOfNotNull("music-chip", "podcasts-chip".takeIf { podcasts }, "audiobooks-chip".takeIf { podcasts })) {
                ImmersiveChip(labels[id].orEmpty(), selected = false) { select(id) }
            }
            return@ImmersiveChipRow
        }
        // Back to everything.
        Box(
            Modifier.clip(ChipShape).background(Immersive.Raised).clickable(role = Role.Button) { select("") }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = labels[""],
                tint = Immersive.Text, modifier = Modifier.size(18.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ImmersiveChip(labels[parent] ?: subs.firstOrNull { it.first == parent }?.second.orEmpty(), selected = true,
                shape = if (subs.isEmpty()) ChipShape else JoinedStart) { select(parent) }
            subs.forEachIndexed { i, (id, label) ->
                // Slides out of the picked chip the first time it shows.
                val shown = androidx.compose.runtime.remember(id) { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                androidx.compose.animation.AnimatedVisibility(
                    visibleState = shown,
                    enter = androidx.compose.animation.expandHorizontally(expandFrom = Alignment.Start) + androidx.compose.animation.fadeIn(),
                ) {
                    ImmersiveChip(label, selected = facet == id,
                        shape = if (i == subs.lastIndex) JoinedEnd else RoundedCornerShape(4.dp)) { select(id) }
                }
            }
        }
    }
}
