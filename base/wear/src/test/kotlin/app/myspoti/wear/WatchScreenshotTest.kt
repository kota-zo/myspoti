/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.wear

import android.app.Application
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.android.horologist.audio.ui.VolumeUiState
import com.google.android.horologist.images.base.paintable.BitmapPaintable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The watch's two pages drawn on the computer at round and square sizes, from made-up state: the
 * player with a song with a long title playing from an extension, and the library with a few lists.
 * Run with `./gradlew :wear:testDebugUnitTest --tests '*WatchScreenshotTest*'`; the images land in
 * wear/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class WatchScreenshotTest(private val size: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun player() = capture("player", page = 0)

    @Test fun library() = capture("library", page = 1)

    /** Nothing playing, the engine's last failure, no phone to list the library. */
    @Test fun idlePlayer() = capture("idle-player", page = 0, idle = true)

    @Test fun idleLibrary() = capture("idle-library", page = 1, idle = true)

    private fun capture(name: String, page: Int, idle: Boolean = false) {
        val playing = WatchPlaying(
            "Breathe Deeper (Extended Version With A Long Title)", "Tame Impala, Lil Yachty", true, "Extension: Example source", null,
            positionMs = 95_000, durationMs = 250_000, atElapsedMs = SystemClock.elapsedRealtime(),
        )
        val lists = listOf(
            WatchList("spotify:collection:tracks", "Liked Songs", "1,204 songs"),
            WatchList("spotify:playlist:1", "Discover Weekly", "Made for you"),
            WatchList("spotify:playlist:2", "Running mix with a long name that does not fit", "48 songs"),
            WatchList("spotify:playlist:3", "Chill evenings", ""),
        )
        rule.mainClock.autoAdvance = false
        // The watch's window is black (Theme.DeviceDefault on Wear); the test activity's is light.
        rule.setContent {
            WatchTheme {
                Box(Modifier.background(Color.Black)) {
                    WatchPager(
                        playing = playing.takeUnless { idle }, error = if (idle) "player: no audio for this song" else null,
                        onPrevious = {}, onToggle = {}, onNext = {},
                        volume = WatchVolumeUi({ VolumeUiState(6, 10) }, kotlinx.coroutines.flow.emptyFlow(), {}, {}),
                        lists = if (idle) Result.failure(IllegalStateException("no phone")) else Result.success(lists), onPlay = {}, onRetry = {},
                        extensionsNote = if (idle) "0 on the watch" else "2 on the watch", onSyncExtensions = {},
                        initialPage = page, artwork = if (idle) null else BitmapPaintable(cover()),
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/watch-$name-$size.png")
    }

    /** A made-up sleeve: a warm diagonal gradient. */
    private fun cover(): ImageBitmap = ImageBitmap(300, 300).also { image ->
        Canvas(image).drawRect(0f, 0f, 300f, 300f, Paint().apply {
            Brush.linearGradient(listOf(Color(0xFFFF7A45), Color(0xFFB0306A), Color(0xFF3A2A8C)), Offset.Zero, Offset(300f, 300f))
                .applyTo(androidx.compose.ui.geometry.Size(300f, 300f), this, 1f)
        })
    }
}

@Config(sdk = [35], qualifiers = "w192dp-h192dp-round-watch-xhdpi", application = Application::class)
class WatchScreenshotTestRound192 : WatchScreenshotTest("round192")

@Config(sdk = [35], qualifiers = "w227dp-h227dp-round-watch-xhdpi", application = Application::class)
class WatchScreenshotTestRound227 : WatchScreenshotTest("round227")

@Config(sdk = [35], qualifiers = "w200dp-h200dp-notround-watch-xhdpi", application = Application::class)
class WatchScreenshotTestSquare200 : WatchScreenshotTest("square200")
