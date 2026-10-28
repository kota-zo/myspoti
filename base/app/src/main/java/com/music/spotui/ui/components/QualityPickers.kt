package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppAccent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.pages.ImmersiveChoiceRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.preferences.DownloadQuality
import com.music.spotui.data.preferences.StreamingQuality
import com.music.spotui.data.preferences.getDownloadQuality
import com.music.spotui.data.preferences.getStreamingQuality
import com.music.spotui.data.preferences.setDownloadQuality
import com.music.spotui.data.preferences.setStreamingQuality

/** The streaming levels of the Web Player, one choice; saved and applied at once. */
@Composable
fun StreamingQualityPicker(modifier: Modifier = Modifier, cellular: Boolean = false, title: Int = R.string.quality_streaming_title) {
    val ctx = LocalContext.current
    var current by remember(cellular) { mutableStateOf(getStreamingQuality(ctx, cellular)) }
    QualityGroup(
        title = stringResource(title),
        detail = stringResource(R.string.quality_streaming_detail),
        options = listOf(
            Option(StreamingQuality.AUTO, R.string.quality_streaming_auto, R.string.quality_streaming_auto_detail),
            Option(StreamingQuality.LOW, R.string.quality_streaming_low, R.string.quality_streaming_low_detail),
            Option(StreamingQuality.NORMAL, R.string.quality_streaming_normal, R.string.quality_streaming_normal_detail),
            Option(StreamingQuality.HIGH, R.string.quality_streaming_high, R.string.quality_streaming_high_detail),
        ),
        selected = current,
        onSelect = { current = it; setStreamingQuality(ctx, it, cellular) },
        modifier = modifier,
    )
}

/** Downloads: off, FLAC CD quality or FLAC hi-res; saved at once. */
@Composable
fun DownloadQualityPicker(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(getDownloadQuality(ctx)) }
    QualityGroup(
        title = stringResource(R.string.quality_download_title),
        detail = stringResource(R.string.quality_download_detail),
        options = listOf(
            Option(DownloadQuality.OFF, R.string.quality_download_off, R.string.quality_download_off_detail),
            Option(DownloadQuality.SMALL, R.string.quality_download_small, R.string.quality_download_small_detail),
            Option(DownloadQuality.CD, R.string.quality_download_cd, R.string.quality_download_cd_detail),
            Option(DownloadQuality.HI_RES, R.string.quality_download_hires, R.string.quality_download_hires_detail),
            Option(DownloadQuality.ASK, R.string.quality_download_ask, R.string.quality_download_ask_detail),
        ),
        selected = current,
        onSelect = { current = it; setDownloadQuality(ctx, it) },
        modifier = modifier,
    )
}

private class Option<T>(val value: T, val label: Int, val detail: Int)

@Composable
private fun <T> QualityGroup(
    title: String, detail: String, options: List<Option<T>>, selected: T,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier,
) {
    // Immersive: the same choices as its Settings pages, a grouped list with a check.
    if (com.music.spotui.ui.navigation.isImmersive()) {
        Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).selectableGroup()) {
            Text(title, style = Immersive.Headline, modifier = Modifier.padding(top = 4.dp))
            Text(detail, style = Immersive.Caption.copy(color = Color.White.copy(alpha = 0.72f)), modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            Column(Modifier.fillMaxWidth().clip(Immersive.Card).background(Immersive.Raised.copy(alpha = 0.92f))) {
                options.forEachIndexed { i, o ->
                    ImmersiveChoiceRow(stringResource(o.label), selected = o.value == selected, detail = stringResource(o.detail), onClick = { onSelect(o.value) })
                    if (i < options.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Immersive.Separator))
                }
            }
        }
        return
    }
    Column(modifier.fillMaxWidth().selectableGroup()) {
        Text(title, color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Text(detail, color = AppMuted, fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
        for (o in options) {
            val on = o.value == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(o.value) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                RadioButton(selected = on, onClick = null, modifier = Modifier.padding(horizontal = 8.dp),
                    colors = RadioButtonDefaults.colors(selectedColor = AppAccent, unselectedColor = AppMuted))
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(o.label), color = if (on) AppAccent else AppText, fontSize = 16.sp)
                    Text(stringResource(o.detail), color = AppMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * "Ask each time": a download asks its quality first, once for a whole list. The
 * question is drawn by [DownloadAskHost], at the root, so it outlives the menu that
 * started the download.
 */
object DownloadAsk {
    internal var pending by mutableStateOf<((DownloadQuality) -> Unit)?>(null)
    /** No download extension is installed: the pointer to the Extensions store is shown instead of a download that cannot run. */
    internal var needSource by mutableStateOf(false)
    /** Route the pointer's button asks the app to open (taken by [com.music.spotui.App]). */
    val openRoute = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Runs [start] now with the Settings quality (null), or after the user picked one. */
    fun start(context: android.content.Context, start: (DownloadQuality?) -> Unit) {
        if (com.music.spotui.data.preferences.isDownloadsEnabled(context) && !com.music.spotui.extensions.Extensions.hasDownloadSource()) {
            needSource = true
            return
        }
        if (getDownloadQuality(context) == DownloadQuality.ASK) pending = { start(it) } else start(null)
    }
}

@Composable
fun DownloadAskHost() {
    if (DownloadAsk.needSource) NeedSourceDialog()
    val action = DownloadAsk.pending ?: return
    val choices = listOf(
        Option(DownloadQuality.SMALL, R.string.quality_download_small, R.string.quality_download_small_detail),
        Option(DownloadQuality.CD, R.string.quality_download_cd, R.string.quality_download_cd_detail),
        Option(DownloadQuality.HI_RES, R.string.quality_download_hires, R.string.quality_download_hires_detail),
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { DownloadAsk.pending = null },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.quality_download_title), color = AppText) },
        text = {
            Column {
                for (o in choices) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(selected = false, role = Role.Button, onClick = {
                                DownloadAsk.pending = null
                                action(o.value)
                            })
                            .padding(vertical = 8.dp),
                    ) {
                        Text(stringResource(o.label), color = AppText, fontSize = 16.sp)
                        Text(stringResource(o.detail), color = AppMuted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { DownloadAsk.pending = null }) {
                Text(stringResource(R.string.add_to_playlist_button_cancel), color = AppText)
            }
        },
    )
}

/** Downloads come from extensions: say so where the tap happened, and lead to the store. */
@Composable
private fun NeedSourceDialog() {
    val immersive = com.music.spotui.ui.navigation.isImmersive()
    val close = { DownloadAsk.needSource = false }
    val ink = if (immersive) Immersive.Text else AppText
    val accent = if (immersive) Immersive.Accent else AppAccent
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        containerColor = if (immersive) Color(0xFF2C2C2E) else tone(0xFF282828),
        shape = if (immersive) androidx.compose.foundation.shape.RoundedCornerShape(20.dp) else androidx.compose.material3.AlertDialogDefaults.shape,
        title = { Text(stringResource(R.string.downloads_need_source_title), color = ink) },
        text = { Text(stringResource(R.string.downloads_need_source_detail), color = if (immersive) Immersive.TextSecondary else AppMuted, fontSize = 14.sp) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                close()
                DownloadAsk.openRoute.tryEmit(com.music.spotui.ui.navigation.Routes.Extensions.route)
            }) { Text(stringResource(R.string.setup_ext_browse), color = accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = close) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = ink) }
        },
    )
}
