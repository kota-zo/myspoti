package com.music.spotui.ui.look

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.preferences.Look
import com.music.spotui.ui.theme.AppSans
import kotlin.math.abs
import kotlin.math.min

/*
 * The live preview of the onboarding's look step: each look drawn at a real phone's size, then
 * scaled down into a phone frame, playing DemoScript's loop with DemoSong. The pieces are small
 * and stateless on purpose: they never touch the real players, so the step cannot start a song.
 */

private val PhoneW = 360.dp
private val PhoneH = 760.dp

/** The preview's clock: where the loop is, and where the demo song is with it. */
@Stable
class DemoClock(startMs: Long) {
    val t = mutableLongStateOf(startMs)
    /** The song position, read by the lyric sweep in its draw phase. */
    val song = mutableLongStateOf(DemoScript.songMs(startMs))

    fun set(ms: Long) {
        t.longValue = ms
        song.longValue = DemoScript.songMs(ms)
    }
}

/** A clock that runs on the display's frames while [running], and holds still otherwise. */
@Composable
fun rememberDemoClock(running: Boolean): DemoClock {
    val clock = remember { DemoClock(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = -1L
        var nanos = clock.t.longValue * 1_000_000L
        while (true) withFrameNanos { now ->
            if (last >= 0) {
                nanos = (nanos + (now - last)) % (DemoScript.LOOP_MS * 1_000_000L)
                clock.set(nanos / 1_000_000L)
            }
            last = now
        }
    }
    return clock
}

/** A clock stopped at [ms], for the still frames. */
@Composable
fun rememberStillClock(ms: Long): DemoClock = remember(ms) { DemoClock(ms) }

/**
 * A phone, drawn: [content] is laid out at a real phone's size and scaled down to fit, so the
 * preview keeps the real proportions of text, icons and spacing.
 */
@Composable
fun PhoneFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val bezel = 5.dp
        val scale = min((maxWidth - bezel * 2) / PhoneW, (maxHeight - bezel * 2) / PhoneH).coerceAtLeast(0.05f)
        val outer = RoundedCornerShape(44.dp * scale + bezel)
        Box(
            Modifier
                .size(PhoneW * scale + bezel * 2, PhoneH * scale + bezel * 2)
                .background(Color(0xFF050505), outer)
                .border(1.dp, Color.White.copy(alpha = 0.16f), outer),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .requiredSize(PhoneW, PhoneH)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        shape = RoundedCornerShape(44.dp)
                        clip = true
                    },
                content = content,
            )
        }
    }
}

/** One look, playing the demo loop on [clock]. [still] keeps the backdrop from drifting. */
@Composable
fun LookDemo(look: Look, clock: DemoClock, still: Boolean = false) {
    when (look) {
        Look.CLASSIC -> ClassicDemo(clock)
        Look.IMMERSIVE -> ImmersiveDemo(clock, still)
    }
}

/** The loop's moments, each recomposing only when it changes. */
private class DemoPhases(clock: DemoClock) {
    val open = derivedStateOf { DemoScript.open(clock.t.longValue) }
    val lyrics = derivedStateOf { DemoScript.lyrics(clock.t.longValue) }
    val playing = derivedStateOf { DemoScript.playing(clock.t.longValue) }
    val progress = derivedStateOf { clock.song.longValue.toFloat() / DemoSong.DURATION_MS }
    val seconds = derivedStateOf { clock.song.longValue / 1000 }
    val active = derivedStateOf { DemoSong.activeLine(clock.song.longValue) }
}

@Composable
private fun rememberPhases(clock: DemoClock) = remember(clock) { DemoPhases(clock) }

/** Where the cover is drawn, in phone dp. */
private data class CoverRect(val x: Float, val y: Float, val size: Float)

private fun lerp(a: CoverRect, b: CoverRect, f: Float) = CoverRect(
    a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.size + (b.size - a.size) * f,
)

/**
 * The cover, flying between where it sits on each screen. Drawn at [base] dp at the phone's
 * top left and moved with a graphics layer, so a flight only relayers; [pause] is the Immersive
 * step back, about the cover's centre.
 */
@Composable
private fun FlyingCover(rect: () -> CoverRect, base: Float, radius: Dp, shadow: Boolean, pause: () -> Float = { 1f }) {
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .size(base.dp)
            .graphicsLayer {
                val r = rect()
                val p = pause()
                val s = r.size / base * p
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = (r.x + r.size * (1f - p) / 2f).dp.toPx()
                translationY = (r.y + r.size * (1f - p) / 2f).dp.toPx()
                scaleX = s
                scaleY = s
                this.shape = shape
                clip = true
                if (shadow) shadowElevation = 20.dp.toPx()
            },
    ) { DemoCover(Modifier.fillMaxSize()) }
}

@Composable
private fun Glyph(icon: ImageVector, size: Dp, tint: Color = Color.White) =
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))

@Composable
private fun Glyph(res: Int, size: Dp, tint: Color = Color.White) =
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(size))

/** Play or pause, crossfading between the two glyphs. */
@Composable
private fun PlayGlyph(playing: Boolean, size: Dp, tint: Color) {
    Crossfade(playing, animationSpec = tween(150), label = "playGlyph") {
        Glyph(if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, size, tint)
    }
}

@Composable
private fun TimeRow(seconds: Long, color: Color, family: FontFamily, size: TextUnit, weight: FontWeight = FontWeight.Normal) {
    Row(Modifier.fillMaxWidth()) {
        Text(demoTime(seconds * 1000), color = color, fontSize = size, fontFamily = family, fontWeight = weight)
        Spacer(Modifier.weight(1f))
        Text("-" + demoTime(DemoSong.DURATION_MS - seconds * 1000), color = color, fontSize = size, fontFamily = family, fontWeight = weight)
    }
}

// ---------------------------------------------------------------- Classic: today's look

private val ClassicGrey = Color(0xFFB3B3B3)
private val ClassicGreen = Color(0xFF1DB954)
private val ClassicTop = Color(0xFF9A2A5C)
private val ClassicBottom = Color(0xFF2A0A1B)

@Composable
private fun ClassicDemo(clock: DemoClock) {
    val phases = rememberPhases(clock)
    val open by phases.open
    val lyrics by phases.lyrics
    val playing by phases.playing
    val mini = CoverRect(16f, 642f, 40f)
    val big = CoverRect(24f, 100f, 312f)
    Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        ClassicHome()
        ClassicMini(playing, phases.progress, Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 70.dp))
        ClassicTabs(Modifier.align(Alignment.BottomCenter))
        if (open > 0f) ClassicPlayer(playing, phases, Modifier.graphicsLayer { translationY = (1f - open) * size.height })
        FlyingCover({ lerp(mini, big, open) }, base = 312f, radius = 8.dp, shadow = false)
        if (open > 0f) ClassicLyricsCard(clock, phases, { open }, { lyrics })
    }
}

/** A home page like today's: filter chips, eight shortcuts, a shelf of covers. */
@Composable
private fun ClassicHome() {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).background(Color(0xFFFF8A65), CircleShape))
            listOf(R.string.look_demo_all, R.string.look_demo_music, R.string.look_demo_podcasts).forEachIndexed { i, label ->
                Text(
                    stringResource(label), color = if (i == 0) Color.Black else Color.White, fontSize = 13.sp, fontFamily = AppSans,
                    modifier = Modifier.background(if (i == 0) ClassicGreen else Color(0xFF2A2A2A), CircleShape).padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        for (row in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 2) {
                    val i = row * 2 + col
                    Row(
                        Modifier.weight(1f).height(56.dp).background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DemoTile(i, Modifier.size(56.dp))
                        Text(DemoShelf[i], color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = AppSans,
                            maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.look_demo_section), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFamily = AppSans)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until 3) Column(Modifier.width(140.dp)) {
                DemoTile(i + 3, Modifier.size(140.dp).clip(RoundedCornerShape(4.dp)))
                Text(DemoShelf[(i + 5) % DemoShelf.size], color = ClassicGrey, fontSize = 12.sp, fontFamily = AppSans, maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ClassicMini(playing: Boolean, progress: State<Float>, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(lerp(ClassicTop, Color.Black, 0.3f), RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(DemoSong.TITLE, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AppSans, maxLines = 1)
                Text(DemoSong.ARTIST, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = AppSans, maxLines = 1)
            }
            Glyph(R.drawable.ic_devices, 22.dp)
            Spacer(Modifier.width(18.dp))
            PlayGlyph(playing, 30.dp, Color.White)
        }
        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp).height(2.dp)) {
            drawRect(Color.White.copy(alpha = 0.25f))
            drawRect(Color.White, size = Size(size.width * progress.value, size.height))
        }
    }
}

@Composable
private fun ClassicTabs(modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black))),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            R.drawable.ic_home_filled to R.string.navigationbar_musicappitems_home_title,
            R.drawable.ic_search_big to R.string.navigationbar_musicappitems_search_title,
            R.drawable.ic_library_big to R.string.navigationbar_musicappitems_yourlibrary_title,
        ).forEachIndexed { i, (icon, label) ->
            val tint = if (i == 0) Color.White else ClassicGrey
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Glyph(icon, 26.dp, tint)
                Text(stringResource(label), color = tint, fontSize = 10.sp, fontFamily = AppSans, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Today's player: the cover's colour over the whole screen, a white round play button. */
@Composable
private fun ClassicPlayer(playing: Boolean, phases: DemoPhases, modifier: Modifier) {
    val seconds by phases.seconds
    Column(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ClassicTop, lerp(ClassicTop, ClassicBottom, 0.55f), ClassicBottom)))
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Rounded.KeyboardArrowDown, 30.dp)
            Text(DemoSong.ALBUM, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AppSans,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Glyph(Icons.Rounded.MoreVert, 24.dp)
        }
        Spacer(Modifier.height(24.dp + 312.dp + 40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(DemoSong.TITLE, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = AppSans, maxLines = 1)
                Text(DemoSong.ARTIST, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontFamily = AppSans, maxLines = 1)
            }
            Glyph(R.drawable.ic_add, 26.dp)
        }
        Spacer(Modifier.height(16.dp))
        ClassicSeekBar(phases.progress)
        TimeRow(seconds, Color.White.copy(alpha = 0.7f), AppSans, 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_shuffle, 24.dp)
            Glyph(R.drawable.ic_skip_prev, 34.dp)
            Box(Modifier.size(64.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                PlayGlyph(playing, 36.dp, Color.Black)
            }
            Glyph(R.drawable.ic_skip_next, 34.dp)
            Glyph(R.drawable.ic_repeat, 24.dp)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_devices, 20.dp, Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.weight(1f))
            Glyph(R.drawable.ic_share, 20.dp, Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.width(24.dp))
            Glyph(Icons.Rounded.QueueMusic, 22.dp, Color.White.copy(alpha = 0.8f))
        }
    }
}

/** Today's seek bar: a 3dp track and a small white thumb. */
@Composable
private fun ClassicSeekBar(progress: State<Float>) {
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val y = size.height / 2f
        val track = 3.dp.toPx()
        val x = size.width * progress.value
        drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track))
        drawRoundRect(Color.White, Offset(0f, y - track / 2), Size(x, track), CornerRadius(track))
        drawCircle(Color.White, 6.dp.toPx(), Offset(x, y))
    }
}

/**
 * The lyrics card under the controls: it peeks at the bottom of the player and, when the lyrics
 * open, grows into the full-screen page (sung words white, the rest dark, a whole word at a time).
 */
@Composable
private fun ClassicLyricsCard(clock: DemoClock, phases: DemoPhases, open: () -> Float, lyrics: () -> Float) {
    val song = clock.song
    val playing by phases.playing
    val active by phases.active
    val sungWords by remember { derivedStateOf { DemoSong.lines.sumOf { l -> l.words.count { it.startMs <= song.longValue } } } }
    val heights = remember { mutableStateListOf(0, 0, 0) }
    val scroll by animateFloatAsState(heights.take(active).sum().toFloat(), tween(450), label = "classicScroll")
    val peekTop = 688f
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val f = lyrics()
                val inset = 16.dp.toPx() * (1f - f)
                translationY = (1f - open()) * size.height + (peekTop * (1f - f)).dp.toPx()
                scaleX = (size.width - inset * 2) / size.width
                transformOrigin = TransformOrigin(0.5f, 0f)
                shape = RoundedCornerShape((12f * (1f - f)).dp)
                clip = true
            }
            .background(DemoSong.lyricsBackground),
    ) {
        // The card's own title while it peeks.
        Text(
            stringResource(R.string.lyrics_card_title), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = AppSans,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp).graphicsLayer { alpha = 1f - lyrics() * 2f },
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).graphicsLayer { alpha = (lyrics() * 2f - 0.6f).coerceIn(0f, 1f) }) {
            Spacer(Modifier.height(40.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.Rounded.KeyboardArrowDown, 30.dp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(DemoSong.TITLE, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AppSans)
                    Text(DemoSong.ARTIST, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontFamily = AppSans)
                }
                Glyph(Icons.Rounded.MoreVert, 24.dp)
            }
            Spacer(Modifier.height(36.dp))
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                Column(Modifier.graphicsLayer { translationY = -scroll }) {
                    var before = 0
                    DemoSong.lines.forEachIndexed { index, line ->
                        val first = before
                        before += line.words.size
                        Text(
                            buildAnnotatedString {
                                line.words.forEachIndexed { i, w ->
                                    if (i > 0) append(" ")
                                    withStyle(SpanStyle(color = if (first + i < sungWords) Color.White else DemoSong.lyricsText)) { append(w.text) }
                                }
                            },
                            fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, fontFamily = AppSans,
                            modifier = Modifier.padding(bottom = 18.dp).onSizeChanged { heights[index] = it.height },
                        )
                    }
                }
            }
            // The page keeps a seek bar and the play button.
            ClassicSeekBar(phases.progress)
            TimeRow(phases.seconds.value, Color.White.copy(alpha = 0.8f), AppSans, 11.sp)
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 36.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(56.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    PlayGlyph(playing, 32.dp, Color.Black)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Immersive

/** The pale tint lines take once they are not the one being sung (the cover's lightest colour). */
private val ImmersiveTint = Color(0xFFF2D3E4)
private val Glass = Color(0xFF2C2C34)

@Composable
private fun ImmersiveDemo(clock: DemoClock, still: Boolean) {
    val phases = rememberPhases(clock)
    val open by phases.open
    val lyrics by phases.lyrics
    val playing by phases.playing
    val pause by rememberPauseScale(playing)
    val mini = CoverRect(26f, 624f, 36f)
    val big = CoverRect(28f, 66f, 304f)
    val header = CoverRect(24f, 52f, 48f)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ImmersiveHome()
        ImmersiveMini(playing, Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 88.dp))
        ImmersiveTabs(Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 18.dp))
        if (open > 0f) ImmersivePlayer(clock, phases, still, { lyrics }, Modifier.graphicsLayer {
            translationY = (1f - open) * size.height
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
            clip = true
        })
        FlyingCover(
            rect = { lerp(lerp(mini, big, open), header, lyrics) },
            base = 304f, radius = 12.dp, shadow = open > 0.5f,
            // Only the big cover steps back; the mini one and the lyrics header stay put.
            pause = { 1f + (pause - 1f) * open * (1f - lyrics) },
        )
    }
}

/** A home page in the Immersive look: a big title, a row of large cards, a shelf. */
@Composable
private fun ImmersiveHome() {
    val text = TextStyle(fontFamily = ImmersiveSans, color = Color.White)
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 54.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.navigationbar_musicappitems_home_title), style = text, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Box(Modifier.size(34.dp).background(Color(0xFF7E57C2), CircleShape))
        }
        Text(stringResource(R.string.look_demo_section), style = text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until 2) Box(Modifier.size(width = 220.dp, height = 280.dp).clip(RoundedCornerShape(14.dp))) {
                DemoTile(i + 2, Modifier.fillMaxSize())
                Text(DemoShelf[i + 1], style = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until 3) Column(Modifier.width(150.dp)) {
                DemoTile(i + 5, Modifier.size(150.dp).clip(RoundedCornerShape(10.dp)))
                Text(DemoShelf[(i + 3) % DemoShelf.size], style = text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ImmersiveMini(playing: Boolean, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Glass.copy(alpha = 0.92f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(start = 60.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(DemoSong.TITLE, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = ImmersiveSans, maxLines = 1)
            Text(DemoSong.ARTIST, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = ImmersiveSans, maxLines = 1)
        }
        PlayGlyph(playing, 30.dp, Color.White)
        Spacer(Modifier.width(12.dp))
        Glyph(Icons.Rounded.FastForward, 28.dp)
    }
}

/** Floating, translucent tabs, with search in a bubble of its own. */
@Composable
private fun ImmersiveTabs(modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(62.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Glass.copy(alpha = 0.9f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                Icons.Rounded.Home to R.string.navigationbar_musicappitems_home_title,
                Icons.Rounded.LibraryMusic to R.string.navigationbar_musicappitems_yourlibrary_title,
            ).forEachIndexed { i, (icon, label) ->
                val tint = if (i == 0) Color.White else Color.White.copy(alpha = 0.7f)
                Column(
                    Modifier.weight(1f).fillMaxHeight().background(if (i == 0) Color.White.copy(alpha = 0.14f) else Color.Transparent, CircleShape),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Glyph(icon, 24.dp, tint)
                    Text(stringResource(label), color = tint, fontSize = 10.sp, fontFamily = ImmersiveSans, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Box(
            Modifier.size(62.dp).background(Glass.copy(alpha = 0.9f), CircleShape).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(Icons.Rounded.Search, 26.dp) }
    }
}

/** A disc of frosted white behind a small glyph (the star and the menu). */
@Composable
private fun GlassDisc(icon: ImageVector) {
    Box(Modifier.size(30.dp).background(Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
        Glyph(icon, 20.dp)
    }
}

/**
 * The Immersive player over the cover's mesh. When the lyrics open, the cover goes up to the
 * corner, the title moves beside it and the lyrics take the middle; the bar, the transport, the
 * volume and the bottom row stay where they are.
 */
@Composable
private fun ImmersivePlayer(clock: DemoClock, phases: DemoPhases, still: Boolean, lyrics: () -> Float, modifier: Modifier) {
    val seconds by phases.seconds
    val progress by phases.progress
    val playing by phases.playing
    val text = TextStyle(fontFamily = ImmersiveSans, color = Color.White)
    val faint = Color.White.copy(alpha = 0.55f)
    Box(modifier.fillMaxSize()) {
        MeshGradientBackground(DemoSong.mesh, animated = !still)
        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(width = 38.dp, height = 5.dp).background(Color.White.copy(alpha = 0.45f), CircleShape))
        // The title under the big cover, which gives way to the lyrics.
        Row(
            Modifier.padding(start = 28.dp, end = 28.dp).offset(y = 394.dp).graphicsLayer { alpha = 1f - lyrics() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(DemoSong.TITLE, style = text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(DemoSong.ARTIST, style = text, color = Color.White.copy(alpha = 0.62f), fontSize = 20.sp, maxLines = 1)
            }
            GlassDisc(Icons.Rounded.StarOutline)
            Spacer(Modifier.width(10.dp))
            GlassDisc(Icons.Rounded.MoreHoriz)
        }
        // The lyrics, under the cover gone up to the corner.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = lyrics() }) {
            Row(Modifier.padding(start = 24.dp + 48.dp + 12.dp, top = 54.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(DemoSong.TITLE, style = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(DemoSong.ARTIST, style = text, color = Color.White.copy(alpha = 0.62f), fontSize = 15.sp, maxLines = 1)
                }
                GlassDisc(Icons.Rounded.StarOutline)
                Spacer(Modifier.width(10.dp))
                GlassDisc(Icons.Rounded.MoreHoriz)
            }
            ImmersiveLyrics(clock, phases, Modifier.padding(top = 112.dp, start = 24.dp - GLOW_ROOM, end = 24.dp - GLOW_ROOM).height(336.dp))
        }
        // Bar, transport, volume and the bottom row: the same in both.
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).offset(y = 452.dp)) {
            ThinSlider(value = progress, onValueChange = {}, idleHeight = 6.dp)
            TimeRow(seconds, faint, ImmersiveSans, 12.sp, FontWeight.Medium)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.Rounded.FastRewind, 44.dp)
                PlayGlyph(playing, 58.dp, Color.White)
                Glyph(Icons.Rounded.FastForward, 44.dp)
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.Rounded.VolumeDown, 18.dp, faint)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    ThinSlider(value = 0.62f, onValueChange = {}, idleHeight = 6.dp)
                }
                Glyph(Icons.Rounded.VolumeUp, 18.dp, faint)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).background(Color.White.copy(alpha = 0.24f * lyrics()), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Glyph(Icons.Rounded.Lyrics, 22.dp, Color.White.copy(alpha = 0.85f)) }
                Spacer(Modifier.weight(1f))
                Glyph(Icons.Rounded.Speaker, 22.dp, Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.weight(1f))
                Glyph(Icons.Rounded.FormatListBulleted, 22.dp, Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

/** Big bold lines swept word by word, the others tinted, blurred and set back, the edges faded. */
@Composable
private fun ImmersiveLyrics(clock: DemoClock, phases: DemoPhases, modifier: Modifier) {
    val active by phases.active
    val heights = remember { mutableStateListOf(0, 0, 0) }
    val spec = tween<Float>(LYRIC_SETTLE_MS, easing = LYRIC_EASING)
    val scroll by animateFloatAsState(heights.take(active).sum().toFloat(), spec, label = "immersiveScroll")
    val style = TextStyle(fontFamily = ImmersiveSans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp)
    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            // Fades the lines out towards the top and the bottom of their area.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        // The line being sung sits a little down, so the one before it stays in view above.
        Column(Modifier.graphicsLayer { translationY = 56.dp.toPx() - scroll }) {
            DemoSong.lines.forEachIndexed { index, line ->
                val distance = min(abs(index - active), LINE_FALLOFF_ALPHA.size - 1)
                val alpha by animateFloatAsState(LINE_FALLOFF_ALPHA[distance], spec, label = "lineAlpha")
                val blur by animateFloatAsState(LINE_FALLOFF_BLUR[distance].value, spec, label = "lineBlur")
                val scale by animateFloatAsState(if (distance == 0) 1f else INACTIVE_SCALE, spec, label = "lineScale")
                val tail by animateFloatAsState(if (index < active) 1f else UNSUNG_ALPHA, label = "lineTail")
                SweptLyricLine(
                    line = line,
                    clock = clock.song,
                    style = style,
                    dimAlpha = tail,
                    color = if (distance == 0) Color.White else ImmersiveTint,
                    glowAlpha = if (distance == 0) GLOW_ALPHA else 0f,
                    glowRoom = GLOW_ROOM,
                    feather = distance == 0,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .onSizeChanged { heights[index] = it.height }
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .then(if (blur > 0.05f) Modifier.blur(blur.dp) else Modifier),
                )
            }
        }
    }
}
