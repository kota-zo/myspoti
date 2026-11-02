/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt at edc1e6f.
 * The sheet's rows are those of Myspoti's Classic Home profile sheet. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.data.api.SupportReminder
import com.music.spotui.di.Graph
import com.music.spotui.di.SessionActions
import com.music.spotui.ui.components.AboutSheet
import com.music.spotui.ui.components.SupporterBadge
import com.music.spotui.ui.components.SupporterSheet
import com.music.spotui.ui.components.SupportSheet
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.profileRoute
import com.music.spotui.ui.screens.AccountBrowser

/**
 * The profile picture as a disc (the first letter of the name until it loads), opening [onClick];
 * null when the caller takes the tap on a wider area around it.
 */
@Composable
internal fun ProfileDisc(onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val avatar = ProfileCache.imageUrl
    val initial = ProfileCache.name?.trim()?.firstOrNull()?.uppercase() ?: "•"
    AvatarDisc(
        url = avatar,
        initial = initial,
        contentDescription = stringResource(R.string.recently_played_user_profile),
        onClick = onClick,
        modifier = modifier,
    )
}

/** A round avatar: the picture at [url], or [initial] on the accent while there is none. */
@Composable
internal fun AvatarDisc(
    url: String?,
    initial: String,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .let { if (onClick != null) it.immersiveClickable(onClick = onClick) else it }
            .clip(CircleShape)
            .background(Immersive.Accent),
    ) {
        if (!url.isNullOrBlank()) {
            ImmersiveCover(
                url = url,
                modifier = Modifier.size(size),
                shape = CircleShape,
                contentDescription = contentDescription,
                border = false,
            )
        } else {
            Text(text = initial, style = Immersive.BodyStrong.copy(color = Immersive.Page, fontWeight = FontWeight.Bold))
        }
    }
}

/**
 * The profile sheet behind the avatar: account name and picture (open the public profile),
 * settings, recap, history, the account page, support, about and sign-out. The same rows and
 * actions as the Classic Home screen's sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImmersiveProfileSheet(onOpen: (route: String) -> Unit, onDismiss: () -> Unit) {
    val name = ProfileCache.name.orEmpty()
    val avatar = ProfileCache.imageUrl
    val myId = ProfileCache.id
    // Opened in full: half-open, the sheet and its list both moved under the finger.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Immersive.Raised,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            // Name and picture open the public profile.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = myId != null, role = Role.Button) { myId?.let { onOpen(profileRoute(it, name)) } }
                    .padding(horizontal = Immersive.Gutter, vertical = 8.dp),
            ) {
                AvatarDisc(
                    url = avatar,
                    initial = name.trim().firstOrNull()?.uppercase() ?: "•",
                    contentDescription = null,
                    onClick = null,
                    size = 48.dp,
                )
                Text(
                    text = name.ifBlank { stringResource(R.string.immersive_screens_account) },
                    style = Immersive.Headline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp),
                )
                if (SupportReminder.level != SupportReminder.Level.NONE) SupporterBadge(Modifier.padding(start = 10.dp))
            }
            if (myId != null) {
                Text(
                    text = stringResource(R.string.view_profile),
                    style = Immersive.Caption,
                    modifier = Modifier.padding(start = 82.dp, bottom = 4.dp),
                )
            }
            HorizontalDivider(color = Immersive.Separator, modifier = Modifier.padding(vertical = 8.dp))
            SheetRow(stringResource(R.string.settings_title), stringResource(R.string.settings_detail)) {
                onOpen(Routes.Settings.route)
            }
            SheetRow(stringResource(R.string.recap_title), stringResource(R.string.recap_detail)) {
                onOpen(Routes.Recap.route)
            }
            SheetRow(stringResource(R.string.recents_title), stringResource(R.string.recents_detail)) {
                onOpen(Routes.Recent.route)
            }
            // Third-party extensions: installed, enabled, repositories.
            val extRegistry by com.music.spotui.extensions.Extensions.registry.collectAsState()
            val installed = extRegistry.extensions
            SheetRow(
                stringResource(R.string.extensions_title),
                if (installed.isEmpty()) stringResource(R.string.extensions_row_detail)
                else stringResource(R.string.extensions_row_count, installed.size, installed.count { it.enabled }),
            ) {
                onOpen(Routes.Extensions.route)
            }
            // The account page (plan, privacy, devices), in the app's signed-in browser.
            SheetRow(
                stringResource(R.string.immersive_screens_account_page),
                stringResource(R.string.immersive_screens_account_page_detail),
            ) {
                onDismiss()
                AccountBrowser.open(Graph.app, AccountBrowser.ACCOUNT_URL)
            }
            // Donations: always here, never in the way.
            var support by remember { mutableStateOf(false) }
            if (support) SupportSheet(onDismiss = { support = false })
            SheetRow(
                stringResource(R.string.support_title),
                stringResource(R.string.support_row_detail),
                titleColor = Immersive.Accent,
            ) { support = true }
            // About: version, Discord, source code and Ko-fi.
            var about by remember { mutableStateOf(false) }
            if (about) AboutSheet(onDismiss = { about = false })
            SheetRow(stringResource(R.string.creator_biography_section_title), stringResource(R.string.about_detail)) {
                about = true
            }
            var perks by remember { mutableStateOf(false) }
            if (perks) SupporterSheet(onDismiss = { perks = false })
            if (SupportReminder.PERKS_ENABLED) {
                SheetRow(stringResource(R.string.supporter_perks_title), stringResource(R.string.supporter_perks_detail)) {
                    perks = true
                }
            }
            SheetRow(stringResource(R.string.connect_device_menu_logout), null) {
                onDismiss()
                SessionActions.logout(Graph.app)
            }
        }
    }
}

@Composable
private fun SheetRow(
    title: String,
    detail: String?,
    titleColor: androidx.compose.ui.graphics.Color = Immersive.Text,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Immersive.Gutter, vertical = if (detail == null) 14.dp else 10.dp),
    ) {
        Text(title, style = Immersive.Body.copy(color = titleColor))
        if (detail != null) Text(detail, style = Immersive.Caption)
    }
}
