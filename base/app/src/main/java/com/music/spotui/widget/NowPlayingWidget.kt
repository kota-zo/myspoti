package com.music.spotui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import app.myspoti.engine.R
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Home screen widget (4x1): the current track with previous, play / pause and next.
 * [show] is called with each now-playing change; the buttons drive [SongPlayer].
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREVIOUS -> SongPlayer.previous()
            ACTION_TOGGLE -> if (SongPlayer.isPlaying()) SongPlayer.pause(by = "widget") else SongPlayer.play(by = "widget")
            ACTION_NEXT -> SongPlayer.next()
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        private const val ACTION_PREVIOUS = "app.myspoti.widget.PREVIOUS"
        private const val ACTION_TOGGLE = "app.myspoti.widget.TOGGLE"
        private const val ACTION_NEXT = "app.myspoti.widget.NEXT"

        private data class Shown(val title: String, val artist: String, val coverUrl: String?, val playing: Boolean)

        @Volatile private var shown = Shown("", "", null, false)
        @Volatile private var cover: Bitmap? = null
        @Volatile private var coverUrl: String? = null
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val http by lazy { app.myspoti.host.android.TlsCompat.apply(OkHttpClient.Builder()).callTimeout(10, TimeUnit.SECONDS).build() }

        /** The track on screen changed (or its play state): redraw the widgets, if any. */
        fun show(context: Context, title: String, artist: String, coverUrl: String?, playing: Boolean) {
            val next = Shown(title, artist, coverUrl, playing)
            if (next == shown) return
            shown = next
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return
            render(app, manager, ids)
            if (coverUrl != null && coverUrl != this.coverUrl) io.launch {
                val bmp = runCatching {
                    http.newCall(Request.Builder().url(coverUrl).build()).execute().use { r ->
                        BitmapFactory.decodeStream(r.body.byteStream())?.let { Bitmap.createScaledBitmap(it, 160, 160, true) }
                    }
                }.getOrNull() ?: return@launch
                // A later track may have replaced it meanwhile.
                if (shown.coverUrl != coverUrl) return@launch
                cover = bmp
                this@Companion.coverUrl = coverUrl
                render(app, manager, ids)
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val s = shown
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
                setTextViewText(R.id.widget_title, s.title.ifBlank { context.getString(R.string.app_name) })
                setTextViewText(R.id.widget_artist, s.artist)
                val bmp = cover.takeIf { coverUrl == s.coverUrl }
                if (bmp != null) setImageViewBitmap(R.id.widget_cover, bmp) else setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
                setImageViewResource(R.id.widget_play, if (s.playing) app.myspoti.host.android.R.drawable.msp_ic_pause else app.myspoti.host.android.R.drawable.msp_ic_play)
                setOnClickPendingIntent(R.id.widget_previous, action(context, ACTION_PREVIOUS, 1))
                setOnClickPendingIntent(R.id.widget_play, action(context, ACTION_TOGGLE, 2))
                setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT, 3))
                val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (open != null) setOnClickPendingIntent(R.id.widget_root,
                    PendingIntent.getActivity(context, 4, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            manager.updateAppWidget(ids, views)
        }

        private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, NowPlayingWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
