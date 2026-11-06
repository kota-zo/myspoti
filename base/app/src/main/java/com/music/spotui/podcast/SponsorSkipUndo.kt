package com.music.spotui.podcast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone

/** "Skipped: sponsor", with its Undo, after [SponsorSkip] jumped over a part of an episode. Drawn by the app's root over both looks. */
@Composable
fun SponsorSkipUndo() {
    val context = LocalContext.current
    val host = remember { SnackbarHostState() }
    val skipped = SponsorSkip.justSkipped
    LaunchedEffect(skipped) {
        val s = skipped ?: return@LaunchedEffect
        val what = context.getString(when (s.segment.category) {
            SponsorSegments.SELF_PROMO -> R.string.sponsorblock_cat_selfpromo
            SponsorSegments.INTRO, SponsorSegments.OUTRO -> R.string.sponsorblock_cat_intro_outro
            SponsorSegments.INTERACTION -> R.string.sponsorblock_cat_interaction
            else -> R.string.sponsorblock_cat_sponsor
        })
        val chosen = host.showSnackbar(
            message = context.getString(R.string.sponsorblock_skipped, what),
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (chosen == SnackbarResult.ActionPerformed) SponsorSkip.undo(s)
        if (SponsorSkip.justSkipped === s) SponsorSkip.justSkipped = null
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 140.dp, start = 8.dp, end = 8.dp), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(host) { data ->
            Snackbar(snackbarData = data, containerColor = tone(0xFF282828), contentColor = AppText, actionContentColor = AppAccent)
        }
    }
}
