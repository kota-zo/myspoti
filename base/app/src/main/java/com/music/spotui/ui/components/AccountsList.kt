package com.music.spotui.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.host.android.SavedAccount
import com.music.spotui.di.Graph
import com.music.spotui.di.SessionActions
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.navigation.isImmersive
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone

/** The colours the account rows are drawn in: the Classic look's or the Immersive one's. */
private class AccountsLook(val text: Color, val muted: Color, val accent: Color, val destructive: Color, val dialog: Color)

@Composable
private fun accountsLook(): AccountsLook =
    if (isImmersive()) AccountsLook(Immersive.Text, Immersive.TextSecondary, Immersive.Accent, Immersive.Destructive, Color.Transparent)
    else AccountsLook(AppText, AppMuted, AppAccent, Color(0xFFFF6B6B), tone(0xFF282828))

/**
 * The Spotify accounts kept on this phone, in both looks (Settings, and the sheet behind the
 * avatar): each with its name, picture and plan, the active one ticked. Another one is switched to
 * after a word that playback stops; "Add account" opens Spotify's sign-in in a new WebView profile;
 * Remove deletes an account's sign-in from the phone. A WebView without profiles keeps one account
 * at a time, and says so: adding signs this one out.
 */
@Composable
fun AccountsList(onLeave: () -> Unit = {}) {
    val look = accountsLook()
    val ctx = LocalContext.current
    val host = MyspotiRuntime.host
    val list by host.accounts.list.collectAsStateWithLifecycle()
    val livePlan by app.myspoti.spotify.AccountPlan.premium.collectAsStateWithLifecycle()
    var switchTo by remember { mutableStateOf<SavedAccount?>(null) }
    var remove by remember { mutableStateOf<SavedAccount?>(null) }
    var addSingle by remember { mutableStateOf(false) }
    // The account in use first, then the one used last; one not signed in yet (an "Add account" left) last.
    val shown = remember(list) {
        val active = list.current ?: SavedAccount(profile = list.active)
        listOf(active) + list.accounts.filter { it.profile != list.active }.sortedWith(compareBy<SavedAccount> { !it.known }.thenByDescending { it.usedAt })
    }
    Column(Modifier.fillMaxWidth()) {
        shown.forEach { account ->
            val active = account.profile == list.active
            // The account in use: its name and picture as read now, before they are saved.
            val name = (if (active) com.music.spotui.data.api.ProfileCache.name else null) ?: account.name
            val image = (if (active) com.music.spotui.data.api.ProfileCache.imageUrl else null) ?: account.imageUrl
            val premium = if (active) livePlan ?: account.premium else account.premium
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !active, role = Role.Button) { switchTo = account }
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                ProfileAvatar(url = image, name = name, size = 40.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        name?.takeIf { it.isNotBlank() } ?: stringResource(if (account.known || active) R.string.spotify_account else R.string.accounts_not_signed_in),
                        color = look.text, fontSize = 16.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val plan = when (premium) {
                        true -> stringResource(R.string.accounts_plan_premium)
                        false -> stringResource(R.string.accounts_plan_free)
                        null -> null
                    }
                    val detail = listOfNotNull(plan, if (active) stringResource(R.string.accounts_in_use) else null).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, color = if (active) look.accent else look.muted, fontSize = 12.sp)
                }
                if (active) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.accounts_in_use), tint = look.accent,
                    modifier = Modifier.padding(horizontal = 12.dp).size(22.dp))
                // The account in use is removed by "Log out"; the others from here.
                else IconButton(onClick = { remove = account }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.accounts_remove), tint = look.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button) {
                    if (host.multiProfile) { onLeave(); SessionActions.addAccount(Graph.app) } else addSingle = true
                }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = look.text, modifier = Modifier.size(40.dp).padding(8.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(R.string.accounts_add), color = look.text, fontSize = 16.sp)
                Text(stringResource(if (host.multiProfile) R.string.accounts_add_detail else R.string.accounts_add_single_detail), color = look.muted, fontSize = 12.sp)
            }
        }
        Text(
            stringResource(if (host.multiProfile) R.string.accounts_footer else R.string.accounts_single_footer),
            color = look.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
    switchTo?.let { account ->
        val name = account.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.spotify_account)
        LookAlertDialog(
            onDismissRequest = { switchTo = null },
            containerColor = look.dialog,
            title = { Text(stringResource(R.string.accounts_switch_title, name), color = look.text) },
            text = { Text(stringResource(R.string.accounts_switch_text), color = look.muted) },
            confirmButton = {
                TextButton(onClick = { switchTo = null; onLeave(); SessionActions.switchAccount(Graph.app, account.profile) }) {
                    Text(stringResource(R.string.accounts_switch_confirm), color = look.accent)
                }
            },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = look.text) } },
        )
    }
    remove?.let { account ->
        val name = account.name?.takeIf { it.isNotBlank() } ?: stringResource(if (account.known) R.string.spotify_account else R.string.accounts_not_signed_in)
        LookAlertDialog(
            onDismissRequest = { remove = null },
            containerColor = look.dialog,
            title = { Text(stringResource(R.string.accounts_remove_title, name), color = look.text) },
            text = { Text(stringResource(R.string.accounts_remove_text), color = look.muted) },
            confirmButton = {
                TextButton(onClick = { remove = null; SessionActions.removeAccount(ctx, account.profile) }) {
                    Text(stringResource(R.string.accounts_remove), color = look.destructive)
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = look.text) } },
        )
    }
    if (addSingle) LookAlertDialog(
        onDismissRequest = { addSingle = false },
        containerColor = look.dialog,
        title = { Text(stringResource(R.string.accounts_add), color = look.text) },
        text = { Text(stringResource(R.string.accounts_single_footer), color = look.muted) },
        confirmButton = {
            TextButton(onClick = { addSingle = false; onLeave(); SessionActions.addAccount(Graph.app) }) {
                Text(stringResource(R.string.connect_device_menu_logout), color = look.accent)
            }
        },
        dismissButton = { TextButton(onClick = { addSingle = false }) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = look.text) } },
    )
}

/** The detail of the "Switch account" row behind the avatar: the other accounts kept, by name. */
@Composable
fun switchAccountDetail(): String {
    val list by MyspotiRuntime.host.accounts.list.collectAsStateWithLifecycle()
    return list.others.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.joinToString(", ")
        .ifEmpty { stringResource(R.string.accounts_switch_row_detail) }
}

/** [AccountsList] in a sheet of the current look, from the sheet behind the avatar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSheet(onDismiss: () -> Unit, onLeave: () -> Unit = onDismiss) {
    val look = accountsLook()
    LookSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = tone(0xFF282828)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.accounts_title), color = look.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            // Switching or adding leaves the screens: the sheet goes, and whatever opened it.
            AccountsList(onLeave = onLeave)
        }
    }
}
