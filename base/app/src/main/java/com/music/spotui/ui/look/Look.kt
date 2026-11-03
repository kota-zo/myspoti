package com.music.spotui.ui.look

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.myspoti.engine.R
import com.music.spotui.data.preferences.Look
import com.music.spotui.data.preferences.LookPref

/** The look the screens below are drawn in; see [Look]. */
val LocalLook = compositionLocalOf { Look.CLASSIC }

/** Hands the chosen look ([LookPref.look]) to everything inside. */
@Composable
fun ProvideLook(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLook provides LookPref.look, content = content)
}

/**
 * The Immersive look's typeface: Inter (SIL Open Font License 1.1, licenses/Inter-OFL.txt), the
 * free face closest to the system one of the player it follows, cut down to Latin. One variable
 * file with a weight axis and an optical-size axis: text sizes use its "Text" cut (opsz 14),
 * titles from 20sp up its "Display" cut (opsz 32), tighter and finer, as that face does.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(weight: Int, opticalSize: Float) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
        androidx.compose.ui.text.font.FontVariation.weight(weight),
        androidx.compose.ui.text.font.FontVariation.Setting("opsz", opticalSize),
    ),
)

private val weights = listOf(400, 500, 600, 700, 800)

private val interText = FontFamily(weights.map { inter(it, 14f) })
private val interDisplay = FontFamily(weights.map { inter(it, 32f) })

/** Body text, captions, buttons (the phone's system font when chosen in the settings: AppFontPref). */
val ImmersiveSans: FontFamily get() = if (com.music.spotui.data.preferences.AppFontPref.system) FontFamily.Default else interText

/** Titles, the player's title and the lyrics (20sp and up); the system font as [ImmersiveSans]. */
val ImmersiveDisplay: FontFamily get() = if (com.music.spotui.data.preferences.AppFontPref.system) FontFamily.Default else interDisplay

/**
 * The phone's "Remove animations" setting, read once at the root (see App) for both looks.
 * Null where nothing provides it: [rememberReduceMotion] then reads the setting itself.
 */
val LocalReduceMotion = compositionLocalOf<Boolean?> { null }

/**
 * True when the phone asks for no animation ("Remove animations": the animator duration
 * scale is 0). Read once per composition; the setting rarely changes while a screen is up.
 */
@Composable
fun rememberReduceMotion(): Boolean = LocalReduceMotion.current ?: rememberReduceMotionSetting()

@Composable
private fun rememberReduceMotionSetting(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
