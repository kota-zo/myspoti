package com.music.spotui.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Repeat once: the loop with "1×" inside, next to the loop with a bare "1" that repeats the song
 * for good (Material's RepeatOne). Drawn with the Classic player's strokes (2 wide, round), which
 * match Material's repeat closely enough to stand beside it in the Immersive player and the queue.
 */
object RepeatIcons {
    val once: ImageVector = ImageVector.Builder("repeatOnce", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Rounded frame open at the lower left, where the arrow head sits.
            moveTo(3f, 12f); verticalLineTo(9f); arcTo(4f, 4f, 0f, false, true, 7f, 5f)
            horizontalLineTo(17f); arcTo(4f, 4f, 0f, false, true, 21f, 9f)
            verticalLineTo(12f); arcTo(4f, 4f, 0f, false, true, 17f, 16f)
            horizontalLineTo(8f)
            moveTo(11f, 12.5f); lineTo(7.5f, 16f); lineTo(11f, 19.5f)
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // The "1", then the "×".
            moveTo(7.6f, 8.6f); lineTo(9.4f, 7.7f); verticalLineTo(12.6f)
            moveTo(12.6f, 8.6f); lineTo(16.2f, 12.2f)
            moveTo(16.2f, 8.6f); lineTo(12.6f, 12.2f)
        }
    }.build()
}
