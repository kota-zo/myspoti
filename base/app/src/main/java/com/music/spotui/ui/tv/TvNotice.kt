package com.music.spotui.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The messages the phone shows as a toast, drawn over the TV's own screens.
 *
 * Android draws no toast over a TV (and nothing an app sends there is read): everything the app had
 * to say about playback — Spotify dropping songs it cannot play, licences refused, a WebView without
 * Widevine, a play refused — reached nobody on a television. Reported as "it keeps skipping songs
 * and nothing tells me why", which is exactly what an invisible message looks like.
 */
object TvNotice {
    /** The message on screen, or null. One at a time: the newest replaces it. */
    val current = MutableStateFlow<String?>(null)

    fun show(text: String) {
        current.value = text
    }

    fun clear() {
        current.value = null
    }
}

/** How long a message stays on its own: long enough to read a sentence across a room. */
private const val SHOWN_MS = 14_000L

/**
 * Draws [TvNotice.current] at the bottom of the screen. Not focusable: a message must never take
 * the remote away from what the user was doing. It goes by itself, and the next press on anything
 * leaves it alone.
 */
@Composable
fun TvNoticeHost(modifier: Modifier = Modifier) {
    val text by TvNotice.current.collectAsState()
    val shown = text ?: return
    LaunchedEffect(shown) {
        delay(SHOWN_MS)
        if (TvNotice.current.value == shown) TvNotice.clear()
    }
    Box(modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .widthIn(max = 900.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xF21A1A1C))
                .padding(horizontal = 26.dp, vertical = 20.dp),
        ) {
            Text(shown, color = Color.White, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
        }
    }
}
