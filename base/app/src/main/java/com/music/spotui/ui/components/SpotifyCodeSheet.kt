package com.music.spotui.ui.components

import app.myspoti.host.android.Covers as HostCovers
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.myspoti.engine.R
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.di.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a share sheet is about: a Spotify uri (track, album, playlist, artist, show, episode). */
data class ShareTarget(val uri: String, val title: String, val subtitle: String = "", val coverUri: String = "") {
    /** The open.spotify.com link of [uri]. */
    val link: String get() = uri.split(':').let { p -> if (p.size >= 3 && p[0] == "spotify") "https://open.spotify.com/${p[1]}/${p[2]}" else uri }
}

/**
 * Sharing, as in the official app: the Spotify Code first (the bars under the cover that
 * Spotify's camera scans), the link under it, then copy / share the link / share the code as
 * an image. Shown by the app's root (App.kt) so a menu can close itself right after asking.
 */
object SpotifyCode {
    val request = MutableStateFlow<ShareTarget?>(null)

    fun show(target: ShareTarget) { request.value = target }

    /** Copies the open.spotify.com link of [uri] (a menu's "Copy link", as in the official app). */
    fun copyLink(context: Context, uri: String) = copy(context, ShareTarget(uri, "").link)

    /**
     * Spotify's own image of the code of [uri]: its logo and bars, [bars] ("white" or "black") on
     * [background] (RRGGBB). The same service the Spotify apps and web codes use.
     */
    fun imageUrl(uri: String, background: Color, bars: String): String {
        val hex = String.format("%06X", background.toArgb() and 0xFFFFFF)
        return "https://scannables.scdn.co/uri/plain/png/$hex/$bars/640/$uri"
    }

    fun barsOn(background: Color) = if (background.luminance() > 0.45f) "black" else "white"

    /** The cover and its code on the cover's colour, as one picture (1080 wide), shared through the FileProvider. */
    suspend fun shareImage(context: Context, target: ShareTarget, background: Color, chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val app = context.applicationContext
                val code = Glide.with(app).asBitmap().load(imageUrl(target.uri, background, barsOn(background))).submit().get()
                val cover = target.coverUri.takeIf { it.isNotBlank() }?.let { runCatching { Glide.with(app).asBitmap().load(HostCovers.large(it)).submit(840, 840).get() }.getOrNull() }
                val w = 1080; val side = 840; val pad = 120
                val codeH = side * code.height / code.width.coerceAtLeast(1)
                val h = pad + (if (cover != null) side + 60 else 0) + codeH + pad
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                c.drawColor(background.toArgb())
                var y = pad.toFloat()
                if (cover != null) {
                    val src = if (cover.config == Bitmap.Config.HARDWARE) cover.copy(Bitmap.Config.ARGB_8888, false) else cover
                    c.drawBitmap(src, null, RectF(((w - side) / 2).toFloat(), y, ((w + side) / 2).toFloat(), y + side), paint)
                    y += side + 60
                }
                val codeSrc = if (code.config == Bitmap.Config.HARDWARE) code.copy(Bitmap.Config.ARGB_8888, false) else code
                c.drawBitmap(codeSrc, null, RectF(((w - side) / 2).toFloat(), y, ((w + side) / 2).toFloat(), y + codeH), paint)
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                File(dir, "spotify_code.png").also { f -> f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }.getOrNull()
        } ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, target.link)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareLink(context: Context, target: ShareTarget) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, target.link)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share_label)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun SpotifyCodeSheet(target: ShareTarget, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var background by remember(target.coverUri) { mutableStateOf(Palette.cachedSecond(target.coverUri) ?: Color(0xFF535353)) }
    LaunchedEffect(target.coverUri) {
        if (target.coverUri.isNotBlank()) Palette().extractSecondColorFromCoverUrl(context, target.coverUri) { background = it }
    }
    val bars = SpotifyCode.barsOn(background)
    val text = if (bars == "black") Color.Black else AppText
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tone(0xFF121212)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The card: cover, name, and the code under them, all on the cover's colour.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(0.82f).clip(RoundedCornerShape(16.dp)).background(background).padding(20.dp),
            ) {
                if (target.coverUri.isNotBlank()) GlideImage(
                    model = app.myspoti.host.android.Covers.large(target.coverUri), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)),
                )
                Text(target.title, color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                if (target.subtitle.isNotBlank()) Text(target.subtitle, color = text.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                GlideImage(
                    model = SpotifyCode.imageUrl(target.uri, background, bars),
                    contentDescription = stringResource(R.string.spotify_code_description),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth().aspectRatio(4f),
                )
            }
            Text(stringResource(R.string.spotify_code_hint), color = AppMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp))
            // The link itself, under the code.
            Text(target.link, color = AppText.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tone(0xFF2A2A2A))
                    .clickable { copy(context, target.link) }.padding(horizontal = 14.dp, vertical = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ShareAction(Icons.Outlined.ContentCopy, stringResource(R.string.copy_link)) { copy(context, target.link); onDismiss() }
                ShareAction(Icons.Outlined.Share, stringResource(R.string.share_link)) { SpotifyCode.shareLink(context, target); onDismiss() }
                val chooser = stringResource(R.string.action_share_label)
                ShareAction(Icons.Outlined.Image, stringResource(R.string.spotify_code_share_image)) {
                    scope.launch { SpotifyCode.shareImage(context, target, background, chooser); onDismiss() }
                }
            }
        }
    }
}

private fun copy(context: Context, link: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Spotify", link))
    // Android 13+ shows its own confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33)
        android.widget.Toast.makeText(context, context.getString(R.string.link_copied), android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun ShareAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp).clip(CircleShape).background(tone(0xFF2A2A2A))) {
            Icon(icon, contentDescription = null, tint = AppText, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = AppText, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}
