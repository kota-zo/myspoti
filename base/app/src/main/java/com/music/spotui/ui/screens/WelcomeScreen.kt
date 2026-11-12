package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppSans
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * First screen of an app with no account in place: what Myspoti is, then the music account to use
 * ([AccountChoiceScreen]; on a TV, Spotify's own login page, shown by the engine), once [onLogin] is tapped. The logo, the name, what the app adds and
 * the button rise into place one after the other, over a soft green light that drifts.
 */
@Composable
fun WelcomeScreen(onLogin: () -> Unit) {
    // One entrance per part, a beat apart: logo, name, features, button.
    val parts = remember { List(4) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        parts.forEachIndexed { i, a -> launch { delay(150L + i * 180L); a.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) } }
    }
    fun Modifier.rise(i: Int) = graphicsLayer {
        val v = parts[i].value
        alpha = v
        translationY = (1f - v) * 24.dp.toPx()
    }
    Box(Modifier.fillMaxSize().background(AppBackground)) {
        Glow(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(bottom = 120.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer {
                            val v = parts[0].value
                            alpha = v
                            val s = 0.85f + 0.15f * v
                            scaleX = s; scaleY = s
                        }
                        .clip(RoundedCornerShape(32.dp))
                        .background(tone(0xFF181818)),
                )
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.brand_name), color = AppText, fontFamily = AppSans, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp,
                    modifier = Modifier.rise(1))
                Spacer(Modifier.height(12.dp))
                Box(Modifier.rise(2)) { Features() }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).rise(3),
            ) {
                // A TV remote: the button takes the focus at once and shows it (a white ring); a
                // focus with no look left nothing on screen to say what OK would do.
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val tv = remember { com.music.spotui.ui.tv.Tv.isTv(ctx) }
                // The account is chosen before any sign-in: Spotify's is no longer the first thing asked.
                val choosesAccount = Onboarding.welcomeChoosesAccount(tv)
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                var focused by remember { mutableStateOf(false) }
                if (tv) LaunchedEffect(Unit) { delay(300); runCatching { focus.requestFocus() } }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .then(if (tv) Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused } else Modifier)
                        .pressable(0.96f, onClick = onLogin)
                        .clip(RoundedCornerShape(50))
                        .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(50)) else Modifier)
                        .background(AppAccent),
                ) {
                    Text(stringResource(if (choosesAccount) R.string.welcome_start else R.string.welcome_login), color = Color.Black, fontFamily = AppSans,
                        fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                // Or the whole setup of another device of the user's, by its QR code (same Wi-Fi).
                var passing by remember { mutableStateOf(false) }
                var passFocused by remember { mutableStateOf(false) }
                Text(
                    stringResource(R.string.pass_sign_in_with_device),
                    color = AppText, fontFamily = AppSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 10.dp)
                        .onFocusChanged { passFocused = it.isFocused }
                        .pressable(0.96f, onClick = { passing = true })
                        .clip(RoundedCornerShape(50))
                        // The remote's focus shows on a TV, as on the button above.
                        .then(if (passFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                if (passing) com.music.spotui.pass.PassReceiveDialog(onDismiss = { passing = false })
                // Spotify's sign-in is the account choice's to explain when it is not the next page.
                if (!choosesAccount) Text(
                    stringResource(R.string.welcome_privacy),
                    color = AppText.copy(alpha = 0.5f), fontFamily = AppSans, fontSize = 13.sp, lineHeight = 18.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

/** A soft green light behind the logo, drifting slowly. */
@Composable
internal fun Glow(modifier: Modifier = Modifier, alpha: Float = 0.30f) {
    // "Remove animations": the light stays where it starts.
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    val phase = if (reduce) 0f else {
        val t = rememberInfiniteTransition(label = "glow")
        val p by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "phase")
        p
    }
    Canvas(modifier) {
        val c = Offset(size.width * (0.5f + 0.12f * sin(phase)), size.height * (0.30f + 0.05f * cos(phase)))
        val r = size.width * 1.05f
        drawCircle(Brush.radialGradient(listOf(AppAccent.copy(alpha = alpha), Color.Transparent), c, r), r, c)
    }
}

/** "Your music," then what Myspoti adds to it, one feature after the other. */
@Composable
private fun Features() {
    val features = listOf(
        // What the app is now: a player for the sources the user picks, not an ad blocker; music videos are gone.
        stringResource(R.string.welcome_rotate_sources),
        stringResource(R.string.welcome_rotate_lyrics),
        stringResource(R.string.welcome_rotate_mix),
        stringResource(R.string.welcome_rotate_offline),
    )
    var index by remember { mutableIntStateOf(0) }
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    LaunchedEffect(Unit) {
        delay(1_200)
        while (true) { delay(2_400); index = (index + 1) % features.size }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.welcome_lead), color = AppText.copy(alpha = 0.7f), fontFamily = AppSans,
            fontSize = 17.sp, textAlign = TextAlign.Center)
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else (slideInVertically(tween(400, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(400))) togetherWith
                    (slideOutVertically(tween(400, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(250)))
            },
            label = "feature",
        ) { i ->
            Text(features[i], color = AppText, fontFamily = AppSans, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }
}
