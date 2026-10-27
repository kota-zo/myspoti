package com.music.spotui.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Side of the cover decode for colours and the mesh gradient. Glide keys its decodes on the size,
 * so one size means the cover is decoded once for both.
 */
const val COVER_DECODE_PX = 128

class Palette {
    companion object {
        /** The tint when a cover gives none (none loaded). */
        val NEUTRAL = Color(0xFF2A2A2A)
        // Cover colours already computed (process-wide): a screen opening on a known
        // cover gets its colour in the first frame instead of popping in afterwards.
        private val colors = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<String, Color>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 200
        })
        fun cachedSecond(imageUrl: String): Color? = colors["2|$imageUrl"]
        fun cachedFirst(imageUrl: String): Color? = colors["1|$imageUrl"]

        /**
         * The first colour carries white text and icons (the mini player, the player's gradient). A
         * cover with no dark swatch gave its dominant colour, a pale yellow or pink the white text
         * vanished on: darkened, same hue, until white reads on it.
         */
        internal fun readableUnderWhite(c: Color): Color {
            var out = c
            var t = 0f
            while (out.luminance() > MAX_TINT_LUMINANCE && t < 0.8f) {
                t += 0.05f
                out = androidx.compose.ui.graphics.lerp(c, Color.Black, t)
            }
            return out
        }

        /** White on a colour of this luminance has a contrast ratio of about 5.5:1. */
        private const val MAX_TINT_LUMINANCE = 0.14f
    }

//    fun extractColorFromImageUrl(context: Context, imageUrl: String, onPaletteGenerated: (Palette) -> Unit) {
//        Glide.with(context)
//            .asBitmap()
//            .load(imageUrl)
//            .into(object : CustomTarget<Bitmap>() {
//                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
//                    Palette.from(resource).generate { palette ->
//                        palette?.let { onPaletteGenerated(it) }
//                    }
//                }
//
//                override fun onLoadCleared(placeholder: Drawable?) {
//                    // Handle cleanup here if necessary
//                }
//            })
//    }

    fun extractFirstColorFromImageUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        val key = "1|$imageUrl"
        colors[key]?.let { onColorExtracted(it); return }
        Glide.with(context)
            .asBitmap()
            .load(imageUrl)
            .override(COVER_DECODE_PX, COVER_DECODE_PX)
            .disallowHardwareConfig()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    Palette.from(resource).generate { palette ->
                        // Not every cover has a dark vibrant swatch: without one the colour of the song
                        // before stayed (reported: the tint "doesn't change" between some songs).
                        val dominantColor = palette?.let { it.darkVibrantSwatch ?: it.darkMutedSwatch ?: it.dominantSwatch ?: it.vibrantSwatch }?.rgb
                        dominantColor?.let {
                            // Convert RGB color integer to ARGB color integer with full opacity
                            val argbColor = readableUnderWhite(Color(it or (0xFF shl 24)))
                            colors[key] = argbColor; onColorExtracted(argbColor)
                        }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Handle cleanup here if necessary
                }

                // No cover (failed load): a neutral tint, never the song before's.
                override fun onLoadFailed(errorDrawable: Drawable?) { onColorExtracted(NEUTRAL) }
            })
    }
    fun extractSecondColorFromCoverUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        val key = "2|$imageUrl"
        colors[key]?.let { onColorExtracted(it); return }
        Glide.with(context)
            .asBitmap()
            .load(imageUrl)
            .override(COVER_DECODE_PX, COVER_DECODE_PX)
            .disallowHardwareConfig()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    // Create a scaled down version of the bitmap
                    val scaledBitmap = Bitmap.createScaledBitmap(resource, 50, 50, true)

                    Palette
                        .from(scaledBitmap)
                        .generate { palette ->
                        val lightVibrantColor = palette?.let { it.mutedSwatch ?: it.darkMutedSwatch ?: it.dominantSwatch ?: it.vibrantSwatch }?.rgb
                        lightVibrantColor?.let {
                            // Convert RGB color integer to ARGB color integer with full opacity
                            val argbColor = Color(it or (0xFF shl 24))
                            colors[key] = argbColor; onColorExtracted(argbColor)
                        }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Handle cleanup here if necessary
                }

                // No cover (failed load): a neutral tint, never the song before's.
                override fun onLoadFailed(errorDrawable: Drawable?) { onColorExtracted(NEUTRAL) }
            })
    }
}