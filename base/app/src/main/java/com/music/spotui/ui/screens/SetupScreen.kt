package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppAccent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import app.myspoti.power.BatteryExemption
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.navigation.isImmersive

/**
 * Shown once after signing in: what Myspoti needs from the phone, each with its own button.
 * Nothing is required; "Continue" goes on with whatever was allowed.
 */
object Setup {
    private const val KEY = "SetupDone"
    private const val EXTENSIONS_KEY = "SetupExtensionsSeen"
    /** The permissions step, the sound quality one, the look one or the extensions one (each new step is shown once after the update too). */
    fun needed(context: Context) = !permissionsDone(context) || !com.music.spotui.data.preferences.isQualityChosen(context) ||
        !com.music.spotui.data.preferences.LookPref.isChosen(context) || !extensionsSeen(context)
    fun permissionsDone(context: Context) = prefs(context).getBoolean(KEY, false)
    fun done(context: Context) = prefs(context).edit().putBoolean(KEY, true).apply()
    fun extensionsSeen(context: Context) = prefs(context).getBoolean(EXTENSIONS_KEY, false)
    fun setExtensionsSeen(context: Context) = prefs(context).edit().putBoolean(EXTENSIONS_KEY, true).apply()
    private fun prefs(context: Context) = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)

    /** A page to open once the app shows (the extensions step's "See the extensions"); taken by [App]. */
    var openAfter: String? = null
}

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    // How Myspoti works (first setup only), the look, the sound quality, the permissions, then the
    // extensions (last, so the end of the setup leads straight to their page); each skipped when
    // done before an update. Every step after the look is drawn in the look chosen there.
    val needPerms = remember { !Setup.permissionsDone(context) }
    val needQuality = remember { !com.music.spotui.data.preferences.isQualityChosen(context) }
    val needLook = remember { !com.music.spotui.data.preferences.LookPref.isChosen(context) }
    val needExtensions = remember { !Setup.extensionsSeen(context) }
    val order = remember {
        listOfNotNull(
            STEP_INTRO.takeIf { needPerms }, STEP_LOOK.takeIf { needLook }, STEP_QUALITY.takeIf { needQuality },
            STEP_PERMISSIONS.takeIf { needPerms }, STEP_EXTENSIONS.takeIf { needExtensions },
        ).ifEmpty { listOf(STEP_PERMISSIONS) }
    }
    var index by remember { mutableIntStateOf(0) }
    val steps = order.size
    fun next() { if (index + 1 < order.size) index++ else onDone() }
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    // Back goes to the previous step (the look can be changed again from there).
    androidx.activity.compose.BackHandler(enabled = index > 0) { index-- }
    val immersive = isImmersive()
    Box(Modifier.fillMaxSize().background(if (immersive) Immersive.Page else AppBackground)) {
        // The page takes the look picked in the look step, live.
        androidx.compose.animation.Crossfade(immersive, animationSpec = tween(350), label = "setupBackdrop") { imm ->
            if (imm) Box(Modifier.fillMaxSize()) {
                com.music.spotui.ui.look.MeshGradientBackground(com.music.spotui.ui.look.LookMeshPalettes.first(), animated = false)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            } else Glow(Modifier.fillMaxSize(), alpha = 0.16f)
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Several steps to go through: dots say where the user is.
            if (steps > 1) StepDots(count = steps, current = index, Modifier.padding(start = 20.dp, top = 16.dp))
            // One step slides away to the left as the next comes in from the right.
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(420))) togetherWith
                        (slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(250)))
                },
                label = "setupStep",
                modifier = Modifier.weight(1f),
            ) { i ->
                when (order[i]) {
                    STEP_INTRO -> IntroStep(onNext = ::next)
                    STEP_QUALITY -> QualityStep(onNext = {
                        com.music.spotui.data.preferences.setQualityChosen(context)
                        next()
                    })
                    STEP_LOOK -> com.music.spotui.ui.look.LookStep(onNext = {
                        com.music.spotui.data.preferences.LookPref.setChosen(context)
                        next()
                    })
                    STEP_EXTENSIONS -> ExtensionsStep(
                        onBrowse = {
                            Setup.setExtensionsSeen(context)
                            Setup.openAfter = com.music.spotui.ui.navigation.Routes.Extensions.route
                            next()
                        },
                        onLater = { Setup.setExtensionsSeen(context); next() },
                    )
                    else -> PermissionsStep(onNext = { Setup.done(context); next() })
                }
            }
        }
    }
}

private const val STEP_INTRO = 0
private const val STEP_LOOK = 1
private const val STEP_QUALITY = 2
private const val STEP_EXTENSIONS = 3
private const val STEP_PERMISSIONS = 4

/** Where the user is in the steps: the current one a longer green dash. */
@Composable
private fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        for (i in 0 until count) {
            val reduce = com.music.spotui.ui.look.rememberReduceMotion()
            val w by animateDpAsState(if (i == current) 22.dp else 8.dp, if (reduce) snap() else tween(350), label = "dot")
            val on = if (isImmersive()) Color.White else AppAccent
            val c by animateColorAsState(if (i == current) on else AppText.copy(alpha = 0.25f), if (reduce) snap() else tween(350), label = "dotColor")
            Box(Modifier.height(6.dp).width(w).clip(RoundedCornerShape(50)).background(c))
        }
    }
}

/** Each part rising into place a beat after the one above it. */
@Composable
private fun Modifier.riseIn(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    LaunchedEffect(Unit) { if (reduce) a.snapTo(1f) else { delay(80L + index * 70L); a.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) } }
    return graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 18.dp.toPx() }
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    // Re-read when coming back from a system dialog or the settings.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(800); tick++ } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var row = 0
        SetupTitle(stringResource(R.string.setup_title), Modifier.riseIn(row++))
        SetupSubtitle(stringResource(R.string.setup_subtitle), Modifier.riseIn(row++))
        Spacer(Modifier.height(8.dp))
        @Suppress("UNUSED_EXPRESSION") tick
        if (Build.VERSION.SDK_INT >= 33) SetupRow(
            stringResource(R.string.setup_notifications_title), stringResource(R.string.setup_notifications_detail),
            granted(context, Manifest.permission.POST_NOTIFICATIONS), Modifier.riseIn(row++),
        ) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        SetupRow(
            stringResource(R.string.setup_local_title), stringResource(R.string.setup_local_detail),
            LocalMusic.hasPermission(context), Modifier.riseIn(row++),
        ) { launcher.launch(LocalMusic.permission) }
        if (Build.VERSION.SDK_INT >= 31) SetupRow(
            stringResource(R.string.setup_bluetooth_title), stringResource(R.string.setup_bluetooth_detail),
            granted(context, Manifest.permission.BLUETOOTH_CONNECT), Modifier.riseIn(row++),
        ) { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        SetupRow(
            stringResource(R.string.setup_battery_title), stringResource(R.string.setup_battery_detail),
            BatteryExemption.isExempt(context), Modifier.riseIn(row++),
        ) { BatteryExemption.request(context) }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.riseIn(row)) {
            SetupButton(stringResource(R.string.setup_continue), onNext)
        }
    }
}

/** Onboarding, step one: how Myspoti streams and saves music (changed later in Settings). */
@Composable
private fun QualityStep(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SetupTitle(stringResource(R.string.setup_quality_title), Modifier.padding(horizontal = 20.dp).riseIn(0))
        SetupSubtitle(stringResource(R.string.setup_quality_subtitle), Modifier.padding(horizontal = 20.dp).riseIn(1))
        Box(Modifier.riseIn(2)) { com.music.spotui.ui.components.StreamingQualityPicker(Modifier.padding(top = 8.dp)) }
        Box(Modifier.riseIn(3)) { com.music.spotui.ui.components.DownloadQualityPicker() }
        SetupButton(stringResource(R.string.setup_continue), onNext, Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(50.dp).riseIn(4))
    }
}

/** First setup, step one: what Myspoti is made of, in three cards. */
@Composable
private fun IntroStep(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var row = 0
        SetupTitle(stringResource(R.string.setup_intro_title), Modifier.riseIn(row++))
        SetupSubtitle(stringResource(R.string.setup_intro_subtitle), Modifier.riseIn(row++))
        Spacer(Modifier.height(8.dp))
        InfoRow(Icons.Rounded.GraphicEq, stringResource(R.string.setup_intro_spotify_title), stringResource(R.string.setup_intro_spotify_detail), Modifier.riseIn(row++))
        InfoRow(Icons.Rounded.AutoAwesome, stringResource(R.string.setup_intro_more_title), stringResource(R.string.setup_intro_more_detail), Modifier.riseIn(row++))
        InfoRow(Icons.Rounded.Extension, stringResource(R.string.setup_intro_ext_title), stringResource(R.string.setup_intro_ext_detail), Modifier.riseIn(row++))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.riseIn(row)) {
            SetupButton(stringResource(R.string.setup_continue), onNext, Modifier.fillMaxWidth().height(50.dp))
        }
    }
}

/**
 * What extensions are, shown once to everyone (new installs and updates) as the last step: optional,
 * written by the community, permission-checked, and where downloads come from (Mornye is the one
 * most people install). "See the extensions" ends the setup on their page (the Store, in the chosen
 * look); "Skip to Home" ends it on Home.
 */
@Composable
private fun ExtensionsStep(onBrowse: () -> Unit, onLater: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var row = 0
        SetupTitle(stringResource(R.string.setup_ext_title), Modifier.riseIn(row++))
        SetupSubtitle(stringResource(R.string.setup_ext_subtitle), Modifier.riseIn(row++))
        Spacer(Modifier.height(8.dp))
        InfoRow(Icons.Rounded.Download, stringResource(R.string.setup_ext_download_title), stringResource(R.string.setup_ext_download_mornye_detail), Modifier.riseIn(row++))
        InfoRow(Icons.Rounded.Headphones, stringResource(R.string.setup_ext_audio_title), stringResource(R.string.setup_ext_audio_detail), Modifier.riseIn(row++))
        InfoRow(Icons.Rounded.Widgets, stringResource(R.string.setup_ext_more_title), stringResource(R.string.setup_ext_more_detail), Modifier.riseIn(row++))
        InfoRow(Icons.Rounded.VerifiedUser, stringResource(R.string.setup_ext_safe_title), stringResource(R.string.setup_ext_safe_detail), Modifier.riseIn(row++))
        SetupSubtitle(stringResource(R.string.setup_ext_where), Modifier.padding(horizontal = 4.dp).riseIn(row++), size = 13.sp)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.riseIn(row), horizontalAlignment = Alignment.CenterHorizontally) {
            SetupButton(stringResource(R.string.setup_ext_browse), onBrowse, Modifier.fillMaxWidth().height(50.dp))
            SetupLinkButton(stringResource(R.string.setup_ext_home), onLater, Modifier.fillMaxWidth().height(48.dp))
        }
    }
}

/** The setup's title, drawn in the chosen look. */
@Composable
private fun SetupTitle(text: String, modifier: Modifier = Modifier) {
    if (isImmersive()) Text(text, style = Immersive.Title.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), modifier = modifier)
    else Text(text, color = AppText, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
private fun SetupSubtitle(text: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 14.sp) {
    if (isImmersive()) Text(text, style = Immersive.Subhead.copy(fontSize = size, color = Color.White.copy(alpha = 0.72f)), modifier = modifier)
    else Text(text, color = AppMuted, fontSize = size, modifier = modifier)
}

/** The step's main button: green and square-ish in Classic, white and rounded in Immersive (as the look step's). */
@Composable
private fun SetupButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth().height(50.dp)) {
    val immersive = isImmersive()
    Button(
        onClick = onClick,
        shape = if (immersive) Immersive.Button else ButtonDefaults.shape,
        colors = ButtonDefaults.buttonColors(containerColor = if (immersive) Color.White else AppAccent, contentColor = Color.Black),
        modifier = modifier,
    ) {
        Text(text, fontFamily = if (immersive) com.music.spotui.ui.look.ImmersiveSans else null, fontSize = 16.sp,
            fontWeight = if (immersive) FontWeight.SemiBold else FontWeight.Bold)
    }
}

@Composable
private fun SetupLinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = if (isImmersive()) Color.White else AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = if (isImmersive()) com.music.spotui.ui.look.ImmersiveSans else null)
    }
}

/** A card of the setup: Classic's dark grey, Immersive's raised grouped-list fill. */
@Composable
private fun Modifier.setupCard(): Modifier =
    if (isImmersive()) this.fillMaxWidth().clip(Immersive.Card).background(Immersive.Raised.copy(alpha = 0.92f))
    else this.fillMaxWidth().background(tone(0xFF1E1E1E), RoundedCornerShape(12.dp))

/** A card of the intro and extensions steps: an accent icon, a title and a line of detail. */
@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    val immersive = isImmersive()
    val accent = if (immersive) Immersive.Accent else AppAccent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.setupCard().padding(16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
        ) { Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            CardTitle(title)
            CardDetail(detail)
        }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, on: Boolean, modifier: Modifier = Modifier, onEnable: () -> Unit) {
    val accent = if (isImmersive()) Immersive.Accent else AppAccent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.setupCard().padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            CardTitle(title)
            CardDetail(detail, lineHeight = 17.sp)
        }
        // Allowed: the button gives way to a green check that pops in.
        AnimatedContent(
            targetState = on,
            transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
            label = "allowed",
        ) { allowed ->
            if (allowed) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.padding(start = 12.dp).size(28.dp))
            else TextButton(onClick = onEnable) { Text(stringResource(R.string.setup_enable), color = accent, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    if (isImmersive()) Text(text, style = Immersive.BodyStrong)
    else Text(text, color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CardDetail(text: String, lineHeight: androidx.compose.ui.unit.TextUnit = 18.sp) {
    if (isImmersive()) Text(text, style = Immersive.Caption.copy(fontSize = 13.sp, lineHeight = lineHeight))
    else Text(text, color = AppMuted, fontSize = 13.sp, lineHeight = lineHeight)
}
