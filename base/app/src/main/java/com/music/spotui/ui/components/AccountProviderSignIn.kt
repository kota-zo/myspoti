package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myspoti.api.account.AccountProviders
import app.myspoti.ytmusic.Ytm
import app.myspoti.engine.R
import com.music.spotui.di.YtmRuntime

/** The name of an account provider, as the service writes it (never translated), or "None". */
@Composable
fun accountProviderLabel(id: String): String = when (id) {
    AccountProviders.SPOTIFY -> "Spotify"
    AccountProviders.YTMUSIC -> "YouTube Music"
    AccountProviders.DEEZER -> "Deezer"
    else -> stringResource(R.string.settings_account_provider_none)
}

/**
 * YouTube Music's own sign-in (Google's web sign-in for YouTube), full screen, for the user to sign in
 * themself. It closes once the flow is back on YouTube Music; the session is then the page's.
 * Shared by both looks' settings.
 */
@Composable
fun YtmSignInDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_sign_in_close), tint = Color.White)
                }
                Text(stringResource(R.string.settings_ytm_sign_in_title), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    YtmRuntime.host.signInView(ctx, Ytm.SIGN_IN_URL, finished = Ytm::signInDone, signedIn = Ytm::signedIn) { ok ->
                        YtmRuntime.signInFinished(ok)
                        onDismiss()
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
