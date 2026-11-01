package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppAccent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import com.music.spotui.di.Haptics
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myspoti.engine.R
import com.music.spotui.data.BetaLogs

/**
 * Beta builds: a small bug icon in the very bottom-right corner, under the Library tab. Holding
 * it makes it grow; held until full size, it opens the app's logs of the last
 * [BetaLogs.MINUTES] minutes, ready to copy into a bug report. A short touch does nothing.
 */
@Composable
fun BetaLogButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The full player covers the tabs: no bug icon over it.
    if (!com.music.spotui.ui.navigation.PlayerSheet.open.value) Box(
        modifier
            .navigationBarsPadding()
            .padding(end = 2.dp, bottom = 2.dp)
            .size(30.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        grow.animateTo(1f, androidx.compose.animation.core.tween(HOLD_MS, easing = androidx.compose.animation.core.LinearEasing))
                        Haptics.thud()
                        open = true
                    }
                    // Ticks that get stronger and closer while the finger stays down.
                    val buzz = scope.launch {
                        while (grow.value < 1f) {
                            Haptics.ramp(grow.value)
                            kotlinx.coroutines.delay((140 - 100 * grow.value).toLong())
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    buzz.cancel()
                    scope.launch { grow.animateTo(0f, androidx.compose.animation.core.tween(150)) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val g = grow.value
        BugIcon(
            color = androidx.compose.ui.graphics.lerp(AppMuted, AppAccent, g),
            modifier = Modifier.size((16 + 12 * g).dp),
        )
    }
    if (open) LogsDialog(onDismiss = { open = false })
}

/** A plain beetle, drawn here: rounded body split in two, head, antennae, three legs a side. */
@Composable
private fun BugIcon(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val line = w * 0.09f
        // Body.
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.32f),
            size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.22f), style = stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.36f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.86f), line)
        // Head.
        drawArc(color, 180f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.37f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.26f, h * 0.26f), style = stroke)
        // Antennae.
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.2f), androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.08f), line, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.2f), androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.08f), line, androidx.compose.ui.graphics.StrokeCap.Round)
        // Legs.
        for ((y, dy) in listOf(0.45f to -0.08f, 0.6f to 0f, 0.75f to 0.08f)) {
            drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.28f, h * y), androidx.compose.ui.geometry.Offset(w * 0.12f, h * (y + dy)), line, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.72f, h * y), androidx.compose.ui.geometry.Offset(w * 0.88f, h * (y + dy)), line, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

private const val HOLD_MS = 900

@Composable
private fun LogsDialog(onDismiss: () -> Unit) {
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.REPORT)
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { text = BetaLogs.recent() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.beta_logs_title, BetaLogs.MINUTES), color = AppText, fontSize = 17.sp,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.beta_logs_close), color = AppMuted) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                val logs = text
                if (logs == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = AppAccent)
                else SelectionContainer {
                    // The newest lines matter most: the view opens scrolled to the end.
                    val scroll = rememberScrollState()
                    LaunchedEffect(logs) { scroll.scrollTo(scroll.maxValue) }
                    Text(logs, color = Color(0xFFDDDDDD), fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll))
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { text?.let { share(context, it) } }, enabled = text != null) {
                    Text(stringResource(R.string.beta_logs_share), color = AppText)
                }
                TextButton(onClick = { text?.let { copy(context, it) } }, enabled = text != null,
                    modifier = Modifier.background(AppAccent, RoundedCornerShape(20.dp))) {
                    Text(stringResource(R.string.beta_logs_copy), color = Color.Black)
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Myspoti logs", text))
    Toast.makeText(context, R.string.beta_logs_copied, Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
