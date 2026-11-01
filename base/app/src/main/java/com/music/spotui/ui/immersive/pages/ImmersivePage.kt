package com.music.spotui.ui.immersive.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.shell.ImmersiveLargeTitle
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.look.rememberReduceMotion
import androidx.compose.foundation.selection.toggleable

/**
 * The shared scaffold of the Immersive pages (Settings, Extensions, Recap...): a large title that
 * collapses into the glass top bar as the list scrolls under it, the page colour and the
 * rubber-band overscroll, and a list the page fills with [content] (see [immersiveGroup] for the
 * iOS-style inset sections).
 *
 * @param modifier lands on the scrolling list; the place to attach a scroll connection (bars that
 * minimise while scrolling) or any other list-level modifier.
 * @param largeTitleTrailing the end of the title line (a count, a button).
 * @param actions trailing controls of the top bar, see `ImmersiveBarAction`.
 * @param onBack shows the back chevron when set.
 */
@Composable
internal fun ImmersivePage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    largeTitleTrailing: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    bottomPadding: Dp = Immersive.BottomChrome,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    ImmersivePageFrame {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = bottomPadding),
            verticalArrangement = verticalArrangement,
            modifier = modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
        ) {
            item(key = "page:title", contentType = "page:title") {
                ImmersiveLargeTitle(title = title, subtitle = subtitle, trailing = largeTitleTrailing)
            }
            content()
        }
        ImmersiveTopBar(
            title = title,
            listState = listState,
            onBack = onBack,
            actions = actions,
        )
    }
}

/** What a group collects: its rows, drawn one under the other with a separator between. */
internal class ImmersiveGroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    /** One row of the card; build it with [ImmersiveGroupRow], [ImmersiveSwitchRow]... or anything else. */
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

private val GroupSeparatorInset = 16.dp

/**
 * An inset grouped section, as in iOS Settings: a small [header] over a rounded [Immersive.Raised]
 * card whose rows are told apart by hairlines, and a [footer] note under it. Nothing is drawn
 * when [content] adds no row.
 */
internal fun LazyListScope.immersiveGroup(
    key: String,
    header: String? = null,
    footer: String? = null,
    @androidx.annotation.StringRes headerRes: Int? = null,
    @androidx.annotation.StringRes footerRes: Int? = null,
    content: ImmersiveGroupScope.() -> Unit,
) {
    item(key = "group:$key", contentType = "group") {
        val scope = ImmersiveGroupScope().apply(content)
        if (scope.rows.isEmpty()) return@item
        val header = header ?: headerRes?.let { androidx.compose.ui.res.stringResource(it) }
        val footer = footer ?: footerRes?.let { androidx.compose.ui.res.stringResource(it) }
        Column(Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter).padding(top = 14.dp)) {
            if (header != null) {
                Text(
                    header.uppercase(),
                    style = Immersive.Caption,
                    modifier = Modifier.padding(start = GroupSeparatorInset, bottom = 6.dp),
                )
            }
            Column(Modifier.fillMaxWidth().clip(Immersive.Card).background(Immersive.Raised)) {
                scope.rows.forEachIndexed { i, row ->
                    row()
                    if (i < scope.rows.lastIndex) {
                        Box(
                            Modifier
                                .padding(start = GroupSeparatorInset)
                                .fillMaxWidth()
                                .heightIn(min = 0.5.dp, max = 0.5.dp)
                                .background(Immersive.Separator),
                        )
                    }
                }
            }
            if (footer != null) {
                Text(
                    footer,
                    style = Immersive.Caption.copy(color = Immersive.TextTertiary),
                    modifier = Modifier.padding(start = GroupSeparatorInset, end = GroupSeparatorInset, top = 6.dp),
                )
            }
        }
    }
}

/** A free-standing block on the page (a message, a chart) with the page's gutters. */
internal fun LazyListScope.immersiveBlock(key: String, content: @Composable () -> Unit) {
    item(key = "block:$key", contentType = "block") {
        Box(Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter, vertical = 8.dp)) { content() }
    }
}

/**
 * One row of a group: an optional [icon], the [title] with a [detail] line, a [value] at the end
 * and a chevron when it opens something. [trailing] replaces the value and the chevron.
 */
@Composable
internal fun ImmersiveGroupRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    detailColor: Color = Immersive.TextSecondary,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tint = if (destructive) Immersive.Destructive else Immersive.Text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.immersiveClickable(onClick = onClick, pressedScale = 0.99f, enabled = enabled) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = GroupSeparatorInset, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Immersive.Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Immersive.Body.copy(color = tint), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = Immersive.Caption.copy(color = detailColor), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        } else {
            if (value != null) {
                Spacer(Modifier.width(10.dp))
                Text(value, style = Immersive.Body.copy(color = Immersive.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp))
            }
            if (chevron) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Immersive.TextTertiary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** A row with a switch at its end; the whole row toggles. */
@Composable
internal fun ImmersiveSwitchRow(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
    detailColor: Color = Immersive.TextSecondary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
            .heightIn(min = 52.dp)
            .padding(horizontal = GroupSeparatorInset, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Immersive.Body.copy(color = if (enabled) Immersive.Text else Immersive.TextTertiary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = Immersive.Caption.copy(color = detailColor), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Immersive.Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF3A3A3F),
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedTrackColor = Immersive.Accent.copy(alpha = 0.4f),
                disabledUncheckedTrackColor = Color(0xFF2A2A2E),
                disabledUncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** One choice of a list where a single row is selected: a check in the accent at its end. */
@Composable
internal fun ImmersiveChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
) {
    val reduce = rememberReduceMotion()
    val color by animateColorAsState(
        if (selected) Immersive.Accent else Color.Transparent,
        animationSpec = if (reduce) snap() else com.music.spotui.ui.immersive.motion.ImmersiveMotion.placementSpring(),
        label = "choice check",
    )
    ImmersiveGroupRow(
        title = title,
        detail = detail,
        onClick = onClick,
        trailing = { Icon(Icons.Rounded.Check, null, tint = color, modifier = Modifier.size(22.dp)) },
    )
}
