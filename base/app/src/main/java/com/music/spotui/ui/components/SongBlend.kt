package com.music.spotui.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.look.rememberReduceMotion
import kotlin.math.abs

/**
 * The song a real crossfade (an extension's stream, a download, a file of the device) fades out
 * while the next one fades in, and how far the fade is. The players dissolve its cover and colours
 * into the current song's with the fade's own [progress], and switch the words (title, artist,
 * times) once, half way, through a dip of [wordsAlpha]: what is shown follows what is heard.
 */
@Stable
class SongBlend internal constructor(val song: SongPlayer.FadingSong) {
    internal val t = mutableFloatStateOf(0f)

    /** 0 (the song going out alone) to 1 (the new one alone): read it in a draw or layer block. */
    fun progress(): Float = t.floatValue

    /** The first half: the words still say the song going out. Changes once per fade. */
    val outgoingWords: Boolean by derivedStateOf { t.floatValue < 0.5f }

    /** The words fade out to nothing half way, and back in with the new song's. */
    fun wordsAlpha(): Float = abs(1f - 2f * t.floatValue)

    /** Where the song going out is, for the times shown while its words are. */
    fun positionMs(): Long = LocalPlayer.fadeOutPosition()
}

/**
 * The crossfade going on, or null: with no overlap (Spotify's own fades are an ordinary song
 * change) and with reduce motion, where the change is a cut. Its progress is read every frame
 * while it lasts; only the draw and the layers that read it follow, not the screen.
 */
@Composable
fun rememberSongBlend(): SongBlend? {
    val from = SongPlayer.fadingOut.value
    val reduceMotion = rememberReduceMotion()
    val blend = remember(from, reduceMotion) { from?.takeIf { !reduceMotion }?.let(::SongBlend) }
    LaunchedEffect(blend) {
        val b = blend ?: return@LaunchedEffect
        while (true) {
            // Paused mid-fade, the progress holds still: a few checks a second instead of every frame.
            if (!LocalPlayer.isPlaying()) { kotlinx.coroutines.delay(250); continue }
            withFrameNanos { b.t.floatValue = LocalPlayer.fadeProgress() }
        }
    }
    return blend
}

/**
 * The cover of the song going out, laid over the current one and fading as the fade goes: the
 * two dissolve into each other. Over it rather than under, so the current cover still loading
 * (or its placeholder) never shows through early. Give it the same [modifier] and decode
 * ([requestBuilderTransform]) as the cover under it, so it comes from the memory cache at once.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SongBlendCover(
    blend: SongBlend?,
    modifier: Modifier = Modifier,
    requestBuilderTransform: (RequestBuilder<Drawable>) -> RequestBuilder<Drawable> = { it },
) {
    val cover = blend?.song?.coverUri?.takeIf { it.isNotBlank() } ?: return
    GlideImage(
        model = cover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { alpha = 1f - blend.progress() },
        requestBuilderTransform = requestBuilderTransform,
    )
}
