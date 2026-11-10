package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Share as image": a 1080x1920 story card (blurred cover background, large cover, title,
 * artist, Myspoti wordmark) written to cache/shared/ and handed out through the FileProvider,
 * so Instagram / WhatsApp stories and the like can pick it up.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1920

    /** Renders and shares the card; does nothing if the image cannot be produced. */
    suspend fun share(context: Context, title: String, artist: String, coverUri: String, chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val cover = loadCover(context, coverUri)
                val card = render(title, artist, cover)
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                File(dir, "myspoti_card.png").also { f ->
                    f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }.getOrNull()
        } ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun loadCover(context: Context, uri: String): Bitmap? {
        if (uri.isBlank()) return null
        val bmp = runCatching {
            Glide.with(context.applicationContext).asBitmap().load(app.myspoti.host.android.Covers.large(uri)).submit(1000, 1000).get()
        }.getOrNull() ?: return null
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bmp
        }
    }

    private fun render(title: String, artist: String, cover: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Background: the cover shrunk to a few pixels and stretched back is a cheap blur.
        c.drawColor(Color.rgb(18, 18, 18))
        if (cover != null) {
            val tiny = Bitmap.createScaledBitmap(cover, 16, 16, true)
            c.drawBitmap(tiny, null, RectF(-200f, -200f, W + 200f, H + 200f), paint)
        }
        paint.shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
            intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.argb(230, 0, 0, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null

        // Cover: large, centred, rounded.
        val side = 840f
        val left = (W - side) / 2f
        val top = 380f
        val box = RectF(left, top, left + side, top + side)
        if (cover != null) {
            val layer = c.saveLayer(box, null)
            c.drawRoundRect(box, 36f, 36f, paint.apply { color = Color.BLACK })
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            c.drawBitmap(cover, null, box, paint)
            paint.xfermode = null
            c.restoreToCount(layer)
        } else {
            paint.color = Color.rgb(40, 40, 40)
            c.drawRoundRect(box, 36f, 36f, paint)
        }

        // Title (up to two lines) and artist.
        val textW = (W - 160)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 62f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, textW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(80f, top + side + 80f); titleLayout.draw(c); c.restore()
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 46f }
        val artistLayout = StaticLayout.Builder.obtain(artist, 0, artist.length, artistPaint, textW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(80f, top + side + 80f + titleLayout.height + 24f); artistLayout.draw(c); c.restore()

        // Wordmark.
        val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 255, 255, 255); textSize = 40f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.12f
        }
        c.drawText("Myspoti", W / 2f, H - 150f, mark)
        return out
    }
}
