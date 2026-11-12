package com.music.spotui.ui.look

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect

/*
 * The song the look preview plays: written for Myspoti, nothing real in it. The cover is drawn,
 * the words are original and timed by hand, and nothing goes through the network.
 */

object DemoSong {
    const val TITLE = "Night Drive"
    const val ARTIST = "The Demo Band"
    const val ALBUM = "Neon Miles"
    const val DURATION_MS = 204_000L

    /** The cover's two loud colours and the night behind them, as the Immersive backdrop. */
    val mesh = MeshPalette(listOf(Color(0xFFFF3D7F), Color(0xFF7B2FF7), Color(0xFFFF9A3C), Color(0xFF241A8C)))

    /** The Classic lyrics page: the cover's colour, dark unsung words, white sung ones. */
    val lyricsBackground = Color(0xFFC2386F)
    val lyricsText = Color(0xFF3D0B24)

    private fun line(vararg words: Triple<String, Long, Long>) = SweptLine(
        timeMs = words.first().second,
        text = words.joinToString(" ") { it.first },
        words = words.map { (text, start, end) -> SweptWord(start, end, text) },
    )

    /** Three lines, timed word by word; the last word is held long enough to glow. */
    val lines = listOf(
        line(
            Triple("Headlights", 63_700L, 64_080L), Triple("paint", 64_080L, 64_300L), Triple("the", 64_300L, 64_400L),
            Triple("empty", 64_400L, 64_620L), Triple("road", 64_620L, 64_950L),
        ),
        line(
            Triple("Every", 64_950L, 65_200L), Triple("sign", 65_200L, 65_420L), Triple("is", 65_420L, 65_520L),
            Triple("pointing", 65_520L, 65_850L), Triple("home", 65_850L, 66_150L),
        ),
        line(
            Triple("Until", 66_150L, 66_400L), Triple("the", 66_400L, 66_500L), Triple("morning", 66_500L, 66_700L),
            Triple("glows", 66_700L, 67_700L),
        ),
    )

    /** The line being sung at [songMs]: the last one started, the first before any has. */
    fun activeLine(songMs: Long): Int = lines.indexOfLast { it.timeMs <= songMs }.coerceAtLeast(0)
}

/**
 * The preview's ~9 s loop, in milliseconds from its start: the mini player, the player opening,
 * a second's pause, the lyrics with their three lines, then everything closing again.
 */
object DemoScript {
    const val LOOP_MS = 9_200L
    private const val OPEN = 500L
    private const val MOVE = 500L
    private const val PAUSE_FROM = 2_000L
    private const val PAUSE_TO = 3_000L
    private const val LYRICS_OPEN = 3_300L
    private const val LYRICS_MOVE = 400L
    private const val LYRICS_CLOSE = 7_700L
    private const val CLOSE = 8_100L
    private const val SONG_START_MS = 61_000L

    /** Still frames for "Remove animations": the player, the pause, the lyrics. */
    val stills = listOf(1_500L, 2_700L, 5_300L)

    /** How far the player is open, 0..1. */
    fun open(t: Long): Float = rise(t, OPEN, MOVE) * (1f - rise(t, CLOSE, MOVE))

    /** How far the lyrics are open, 0..1. */
    fun lyrics(t: Long): Float = rise(t, LYRICS_OPEN, LYRICS_MOVE) * (1f - rise(t, LYRICS_CLOSE, LYRICS_MOVE))

    fun playing(t: Long): Boolean = t < PAUSE_FROM || t >= PAUSE_TO

    /** Where the song is: it runs with the loop except during the pause. */
    fun songMs(t: Long): Long = SONG_START_MS + t - (t.coerceIn(PAUSE_FROM, PAUSE_TO) - PAUSE_FROM)

    private fun rise(t: Long, from: Long, length: Long): Float =
        FastOutSlowInEasing.transform(((t - from).toFloat() / length).coerceIn(0f, 1f))
}

/** "m:ss", as players print a position. */
fun demoTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** The demo cover, drawn: a sun going down over a road, in the two colours the backdrop picks up. */
@Composable
fun DemoCover(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2A0B5E), Color(0xFFC2185B), Color(0xFFFF8A3D)), endY = h * 0.7f))
        // The sun, striped across its lower half.
        val sun = Offset(w * 0.5f, h * 0.56f)
        val r = w * 0.24f
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFE07A), Color(0xFFFF4F8B), ), startY = sun.y - r, endY = sun.y + r), r, sun)
        for (i in 0 until 4) {
            val y = sun.y + r * (0.15f + i * 0.2f)
            drawRect(Color(0xFFC2185B).copy(alpha = 0.85f), Offset(sun.x - r, y), Size(r * 2, h * (0.012f + i * 0.006f)))
        }
        // The ground, and the road running into it.
        val horizon = h * 0.68f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF3A1170), Color(0xFF12052B)), startY = horizon), Offset(0f, horizon), Size(w, h - horizon))
        val road = Path().apply {
            moveTo(w * 0.47f, horizon); lineTo(w * 0.53f, horizon); lineTo(w * 0.82f, h); lineTo(w * 0.18f, h); close()
        }
        drawPath(road, Color(0xFF1E0A40))
        clipRect(top = horizon) {
            for (i in 0 until 4) {
                val f = 0.12f + i * 0.24f
                val y = horizon + (h - horizon) * f
                val dash = (h - horizon) * (0.05f + f * 0.1f)
                val half = w * (0.004f + f * 0.012f)
                drawRect(Color(0xFFFFD54F), Offset(w * 0.5f - half, y), Size(half * 2, dash))
            }
        }
        // The horizon's glow.
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFF8A3D).copy(alpha = 0.55f), Color.Transparent), startY = horizon - h * 0.04f, endY = horizon + h * 0.04f),
            Offset(0f, horizon - h * 0.04f), Size(w, h * 0.08f),
        )
    }
}

/** The look screen's own backdrop colours (Immersive), cycled one into the next. */
val LookMeshPalettes: List<MeshPalette> = listOf(
    DemoSong.mesh,
    MeshPalette(listOf(Color(0xFF00A6C8), Color(0xFF3D5AFE), Color(0xFF26C281), Color(0xFF0B2350))),
    MeshPalette(listOf(Color(0xFFFF6E40), Color(0xFFFFC233), Color(0xFFE0306A), Color(0xFF4A1430))),
    MeshPalette(listOf(Color(0xFF9C5CFF), Color(0xFFFF5CC8), Color(0xFF4E7BFF), Color(0xFF1C1250))),
)

/** Invented names for the home page's tiles. */
val DemoShelf = listOf("Night Drive", "Slow Hours", "Sunday Club", "Low Tide", "City Lights", "Late Bloom", "Paper Moons", "Blue Static")

/** A tile of the demo home page: a drawn cover in one of a few made-up palettes. */
@Composable
fun DemoTile(seed: Int, modifier: Modifier = Modifier) {
    val palettes = listOf(
        Color(0xFF1E88E5) to Color(0xFF00E5A8), Color(0xFFFF7043) to Color(0xFFFFD54F),
        Color(0xFF7E57C2) to Color(0xFFFF4F8B), Color(0xFF00897B) to Color(0xFFB2FF59),
        Color(0xFFD81B60) to Color(0xFFFF9E80), Color(0xFF3949AB) to Color(0xFF80DEEA),
        Color(0xFF6D4C41) to Color(0xFFFFB74D), Color(0xFF263238) to Color(0xFF4DD0E1),
    )
    val (a, b) = palettes[seed % palettes.size]
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset(size.width, size.height)))
        val r = size.minDimension
        when (seed % 3) {
            0 -> drawCircle(Color.White.copy(alpha = 0.22f), r * 0.32f, Offset(size.width * 0.68f, size.height * 0.36f))
            1 -> for (i in 0 until 4) drawRect(Color.Black.copy(alpha = 0.12f), Offset(0f, size.height * (0.2f + i * 0.2f)), Size(size.width, size.height * 0.07f))
            else -> drawCircle(Color.Black.copy(alpha = 0.18f), r * 0.55f, Offset(size.width * 0.2f, size.height * 0.9f))
        }
    }
}
