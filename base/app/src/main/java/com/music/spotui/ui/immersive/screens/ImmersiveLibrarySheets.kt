/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/screens/LibraryPhoneParts.kt at edc1e6f.
 * The sheets' rows and actions are those of Myspoti's Classic Library screen. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import com.music.spotui.ui.immersive.sheets.ImmersiveGlassSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.data.local.LibraryOrder
import com.music.spotui.di.Graph
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.profileRoute
import com.music.spotui.ui.screens.AccountBrowser

/** The label of a library sort order. */
internal fun libraryOrderLabel(sort: LibraryOrder.Sort): Int = when (sort) {
    LibraryOrder.Sort.CUSTOM -> R.string.your_library_sort_order_custom
    LibraryOrder.Sort.RECENTS -> R.string.library_sort_recents
    LibraryOrder.Sort.RECENTLY_ADDED -> R.string.library_sort_recently_added
    LibraryOrder.Sort.ALPHABETICAL -> R.string.your_library_sort_order_alphabetical
    LibraryOrder.Sort.CREATOR -> R.string.your_library_sort_order_creator
}

/** "Sort by": custom order (moved by hand), recents, alphabetical or by creator, and a reset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImmersiveLibrarySortSheet(onDismiss: () -> Unit) {
    ImmersiveGlassSheet(onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.filter_and_sort_sort_section_title),
                style = Immersive.Title,
                modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 8.dp),
            )
            for (sort in LibraryOrder.Sort.entries) {
                val selected = LibraryOrder.sort == sort
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { LibraryOrder.choose(sort); onDismiss() }
                        .padding(horizontal = Immersive.Gutter, vertical = 14.dp),
                ) {
                    Text(
                        text = stringResource(libraryOrderLabel(sort)),
                        style = Immersive.Body.copy(color = if (selected) Immersive.Accent else Immersive.Text),
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = Immersive.Accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Text(
                text = stringResource(R.string.library_reorder_hint),
                style = Immersive.Caption,
                modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.immersive_screens_reset_order),
                style = Immersive.BodyStrong,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { LibraryOrder.reset(); onDismiss() }
                    .padding(horizontal = Immersive.Gutter, vertical = 14.dp),
            )
        }
    }
}

/** The account behind the avatar: who is signed in, the profile, settings and the account page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImmersiveAccountSheet(
    account: AccountModel,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    ImmersiveGlassSheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(Immersive.Gutter, 4.dp, Immersive.Gutter, 16.dp),
            ) {
                AvatarDisc(
                    url = account.imageUrl,
                    initial = account.name.trim().firstOrNull()?.uppercase() ?: "•",
                    contentDescription = stringResource(R.string.account_title),
                    onClick = null,
                    size = 56.dp,
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        text = account.name.ifBlank { stringResource(R.string.immersive_screens_account) },
                        style = Immersive.Headline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (account.email.isNotBlank()) {
                        Text(account.email, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (account.plan.isNotBlank()) {
                        Text(account.plan, style = Immersive.Caption.copy(color = Immersive.Accent))
                    }
                }
            }
            HorizontalDivider(color = Immersive.Separator)
            ProfileCache.id?.let { myId ->
                AccountRow(stringResource(R.string.view_profile)) {
                    onDismiss()
                    navController.navigate(profileRoute(myId, account.name))
                }
            }
            AccountRow(stringResource(R.string.settings_title)) {
                onDismiss()
                navController.navigate(Routes.Settings.route)
            }
            AccountRow(stringResource(R.string.immersive_screens_account_page) + "  ↗") {
                onDismiss()
                AccountBrowser.open(Graph.app, AccountBrowser.ACCOUNT_URL)
            }
            HorizontalDivider(color = Immersive.Separator)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccountRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = Immersive.Body,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Immersive.Gutter, 16.dp),
    )
}
