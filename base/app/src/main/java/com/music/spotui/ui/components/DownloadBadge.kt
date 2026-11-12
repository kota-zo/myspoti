package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.data.offline.OfflineStatus

private val Green: Color get() = com.music.spotui.ui.theme.AppAccent

/** A track's download state, as the official app draws it next to the title. Nothing when not downloaded. */
@Composable
fun DownloadBadge(status: OfflineStatus?, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    when (status) {
        null -> Unit
        OfflineStatus.Done -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(size).clip(CircleShape).background(Green),
        ) {
            Icon(Icons.Default.ArrowDownward, stringResource(R.string.download_badge_downloaded_content_description),
                tint = Color.Black, modifier = Modifier.size(size * 0.7f))
        }
        OfflineStatus.Queued -> Icon(Icons.Outlined.ArrowCircleDown,
            stringResource(R.string.download_badge_downloading_content_description),
            tint = AppText.copy(alpha = 0.5f), modifier = modifier.size(size))
        is OfflineStatus.Downloading -> Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
            if (status.progress < 0f) CircularProgressIndicator(color = Green, strokeWidth = 2.dp, modifier = Modifier.size(size))
            else CircularProgressIndicator(progress = { status.progress }, color = Green, trackColor = AppText.copy(alpha = 0.2f),
                strokeWidth = 2.dp, modifier = Modifier.size(size))
        }
        OfflineStatus.Failed, OfflineStatus.Unavailable -> Icon(Icons.Outlined.ErrorOutline,
            stringResource(R.string.download_badge_error_content_description),
            tint = Color(0xFFE22134), modifier = modifier.size(size))
    }
}

/**
 * The download button of an album / playlist: outlined arrow, progress ring while songs
 * download, then a bin once all of them are on the phone (tap = remove the downloads).
 */
@Composable
fun DownloadListButton(
    downloaded: Int, total: Int, busy: Boolean, kept: Boolean, modifier: Modifier = Modifier,
    /** The square that answers the finger, the 28 dp glyph centred in it. */
    touch: androidx.compose.ui.unit.Dp = 28.dp,
    onClick: () -> Unit,
) {
    // A downloaded list shows the bin (tap = stop keeping it); its progress while songs arrive.
    val all = kept && !busy
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(maxOf(touch, 28.dp)).pressable(0.92f, onClick = onClick)
        .wrapContentSize().size(28.dp)) {
        when {
            all -> Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.context_menu_undownload),
                tint = AppText.copy(alpha = 0.8f), modifier = Modifier.size(28.dp))
            busy -> DownloadBadge(OfflineStatus.Downloading(if (total > 0) downloaded.toFloat() / total else -1f), size = 24.dp)
            else -> Icon(Icons.Outlined.ArrowCircleDown, stringResource(R.string.context_menu_download),
                tint = AppText.copy(alpha = 0.8f), modifier = Modifier.size(28.dp))
        }
    }
}
