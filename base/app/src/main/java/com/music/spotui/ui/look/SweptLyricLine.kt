/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt at edc1e6f
 * (SweptLyricLine, its drawing helpers and the constants they use) and
 * sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/player/PlayerPlatformUi.android.kt
 * (clipShiftedDown). Modified for Myspoti, 2026-10.
 *
 * Changes: public so the Immersive screens can draw it; drawn from SweptLine (see
 * SweptLyricModel.kt); the translation particles are left out.
 */
package com.music.spotui.ui.look

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Returns true if most of the strongly directional characters in [text] are right-to-left. */
private fun isRtl(text: String): Boolean {
    var rtlCount = 0
    var ltrCount = 0
    for (ch in text) {
        val code = ch.code
        // Hebrew presentation forms
        if (code in 0xFB1D..0xFB4F) return true
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtlCount++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltrCount++
        }
    }
    return rtlCount > ltrCount
}

/** Whether this text should align to the right end of its box. */
private fun alignsRight(text: String, alignEnd: Boolean, laneLocked: Boolean): Boolean =
    if (laneLocked) alignEnd else isRtl(text)

/** How far back the part of the playing line that hasn't been sung yet is held. */
const val UNSUNG_ALPHA = 0.45f

/**
 * The bloom behind the line being sung, at its very strongest.
 *
 * Kept well under half strength: the halo is drawn from the same white as the
 * text, so at full alpha it stops reading as light and starts reading as a
 * second, badly printed copy of the words. What is actually drawn is this
 * scaled by each letter's own bloom, so only a properly carried note ever sees
 * the whole of it — the bloom is attached to the letters of the held words
 * (see [SweptLine.growingWords]), not dragged along under the sweep.
 */
const val GLOW_ALPHA = 0.62f

/**
 * How far the bloom spreads off a letter. Tight, because it is a letter's worth
 * of light: a wide radius on something this small is a smudge behind the text
 * instead of a glow coming off it.
 */
private val GLOW_RADIUS = 6.dp

/**
 * Room reserved inside each copy of a line for the halo to spread into.
 *
 * A blur is computed on its layer's own bitmap, so a halo with nowhere to go
 * inside those bounds is a halo with a hard edge. Every copy carries the same
 * inset so they still lay out identically, and the list gives the width back
 * by taking it off its own padding and row spacing.
 */
val GLOW_ROOM = 10.dp

/** Where a lyric line is in its own animation; see [SweptLyricLine]. */
private const val PHASE_BEFORE = 0
private const val PHASE_MOVING = 1
private const val PHASE_AFTER = 2

/**
 * How far the sweep's leading edge fades out instead of ending on a cut.
 *
 * A hard boundary is legible as a boundary: the eye reads a bar travelling
 * across the words rather than the words themselves lighting up as they are
 * sung. Feathering it over roughly a character and a half is what turns the
 * cut back into a wavefront.
 */
private val WIPE_FEATHER = 30.dp

/**
 * How far the word being sung lifts off the line. Two pixels, and it has to be
 * about two: enough that the eye catches the words moving under the sweep,
 * little enough that nothing appears to come loose from the line it belongs to.
 */
private val WORD_RISE = 2.dp

/**
 * How much further up a row is opened when it holds a word being animated
 * letter by letter, in multiples of [WORD_RISE]: a letter that swells has to be
 * given the room above the line it grew out of.
 */
private const val GROW_HEADROOM = 3f

/**
 * How the stack falls away either side of the line being sung, indexed by
 * distance from it. The two lines around the playing one stay legible so you
 * can read ahead and behind; the last entry stands for everything further out.
 */
val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)

/** The playing line sits at 1; the rest sit fractionally back from it. */
const val INACTIVE_SCALE = 0.98f

/**
 * The curve every handover runs on: away quickly, in slowly and softly. One
 * curve for the lot — dimming, blurring, scaling and the scroll — so a line
 * handing over reads as a single movement rather than four.
 */
val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
const val LYRIC_SETTLE_MS = 400

/** Clips to the box, then draws [block] moved down by [dy]. */
private fun DrawScope.clipShiftedDown(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    dy: Float,
    block: DrawScope.() -> Unit,
) {
    clipRect(left = left, top = top, right = right, bottom = bottom) {
        translate(top = dy) { block() }
    }
}

/**
 * A lyric line with the sung part of it lit, the rest dimmed, and the boundary
 * travelling across the words in time with the vocal.
 *
 * Two copies of the same text stacked: a dim one and a bright one clipped to
 * whatever has been sung. Same string, same style, same constraints, so the
 * two lay out identically and the bright copy lands exactly on top of the dim
 * one. The alternative — colouring an AnnotatedString word by word — can only
 * change a whole word at a time, which turns the sweep into a flicker.
 *
 * The clip is recomputed in the draw phase, so a frame costs one clip and one
 * redraw of already-measured text.
 *
 * [glowAlpha] adds the bloom: a third copy, blurred, behind the other two
 * and clipped to the letters of whatever word is being held. Blurring *after*
 * the clip rather than before is what makes the halo bleed out past the letter
 * it belongs to, which is the part that reads as light coming off a carried
 * note rather than a drop shadow sitting under the line.
 */
@Composable
fun SweptLyricLine(
    line: SweptLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
    feather: Boolean = false,
    rise: Boolean = true,
    alignEnd: Boolean = false,
    laneLocked: Boolean = false,
    /** What the words are set in. */
    color: Color = Color.White,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    // Filled in and read back a letter at a time inside the draw lambdas, and
    // shared by all three copies of the line — they draw one after another on
    // the same thread, so there is only ever one letter in hand. Held here
    // rather than allocated per frame: a held word is seven letters at the
    // outside, but this runs on every frame of every line that has one.
    val growth = remember { CharGrowth() }

    // Where this line is in its own animation — not started, moving, or done —
    // which changes three times a line rather than sixty times a second. The
    // draw passes below read the clock only while it says moving; on either
    // side they draw a position that looks identical to any other there (see
    // [SweptLine.animatesFromMs]), so a line nobody is singing never redraws.
    //
    // Read in draw, every line on screen used to redraw on every frame of
    // playback, and each line away from the playing one sits under a blur
    // that then had to be worked out again — most of the panel's frame, for a
    // picture that had not changed. Paused, nothing moved, which is why the
    // panel was smooth paused and slow playing.
    val phase = remember(line, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now < line.animatesFromMs -> PHASE_BEFORE
                now > line.animatesUntilMs -> PHASE_AFTER
                else -> PHASE_MOVING
            }
        }
    }
    val drawnAt: () -> Long = {
        when (phase.value) {
            PHASE_MOVING -> clock.longValue
            PHASE_BEFORE -> line.animatesFromMs - 1
            else -> line.animatesUntilMs + 1
        }
    }

    // Carried by every copy: identical insets keep them laying out identically,
    // and the inset is what gives the blurred copy's layer somewhere to put the
    // halo. Sits inside the blur and outside the draw lambdas, so text-layout
    // coordinates and draw coordinates still agree.
    //
    // Off unless asked for. Only the full panel can afford it — it takes the
    // space back off its own row spacing and content padding. Handed to the
    // one-line strip above the scrubber, where there is no glow to make room
    // for and nothing paying the space back, it just left the line sitting in
    // a pocket of air with the chevron pushed off it.
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    // Sits outside [room] and outside the sweep, so what it moves is the
    // finished picture of the word — dim tail, lit head and all — rather than
    // one copy sliding out from under another. Carried by both copies from the
    // same arithmetic, which is what keeps them on top of each other.
    //
    // Off for the one-line strip above the scrubber ([rise] = false). The lift
    // belongs to a page of lyrics, where a word rising out of the line it sits
    // in is the thing being read; on a single line pinned between the credits
    // and the slider it has nothing to rise away from and reads as the strip
    // itself twitching.
    val riseAgainst: (Modifier) -> Modifier = { inner ->
        if (!rise) {
            inner
        } else {
            Modifier
                .drawWithContent {
                    val measured = layout
                    if (measured == null || line.words.isEmpty()) {
                        drawContent()
                    } else {
                        riseWith(
                            layout = measured,
                            line = line,
                            positionMs = drawnAt(),
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    }
                }
                .then(inner)
        }
    }

    val sweep = Modifier.drawWithContent {
        val position = drawnAt()
        when {
            // Sung and done with: all of it is lit. Checked first so the lines
            // above and below the playing one — which are in this same state
            // for minutes at a time — cost a comparison per frame rather than
            // a walk of their words.
            position >= line.endMs -> drawContent()
            // Not started: nothing lit, the dim copy is the whole of it.
            position <= line.timeMs -> Unit
            else -> layout?.let {
                sweepTo(it, line.revealedChars(position), line.sweepSpans, feather)
            }
        }
    }

    val right = alignsRight(line.text, alignEnd, laneLocked)
    Box(
        modifier,
        contentAlignment = if (right) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft,
    ) {
        Text(
            text = line.text,
            style = style,
            color = color.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = riseAgainst(room),
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .graphicsLayer { alpha = glowAlpha }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    // Each letter is masked to its own brightness with DstIn,
                    // which needs a layer of its own to erase into — against the
                    // backdrop it would take the artwork with it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // Deliberately not the shared sweep: that lights
                        // everything sung so far, and this lights only the words
                        // being held. Most lines draw nothing here at all, which
                        // is the whole difference between this and a halo
                        // travelling under the highlight.
                        val measured = layout ?: return@drawWithContent
                        glowGrown(
                            layout = measured,
                            line = line,
                            positionMs = drawnAt(),
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    },
            )
        }
        Text(
            text = line.text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            // The feather erases into this layer, so the layer has to exist —
            // and only while it is being drawn. Every line carrying one would
            // put the whole panel through an offscreen buffer to soften an edge
            // that at most two of them have.
            modifier = riseAgainst(
                Modifier
                    .graphicsLayer {
                        compositingStrategy = if (feather) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .then(room)
                    .then(sweep),
            ),
        )
    }
}

/**
 * Draws this text clipped to the letters of the words being held, each at its
 * own brightness — the light the singing is actually giving off, rather than a
 * band of it dragged along behind the highlight.
 *
 * Nothing at all on a line of ordinary syllables: the words that light up are
 * the ones held long enough to have earned it, so a verse of patter is simply
 * dark and costs one comparison to establish. That selectiveness is the point.
 * A glow present on every word is a property of the highlight; a glow that
 * arrives only when a note is carried is a property of the voice.
 *
 * Each letter is masked to its own bloom rather than drawn at it, because the
 * caller's layer is what this erases into — see [SweptLyricLine]. The mask
 * lands before the blur, so what spreads is already the right brightness.
 */
private fun ContentDrawScope.glowGrown(
    layout: TextLayoutResult,
    line: SweptLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isGrowing(positionMs)) return
    val em = layout.layoutInput.style.fontSize.toPx()
    val length = layout.layoutInput.text.length
    for (word in line.growingWords) {
        if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
        val span = line.wordSpans[word.index]
        val fall = line.wordFall(word.index, positionMs)
        for (char in span.first..minOf(span.last, length - 1)) {
            word.sampleInto(char - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val visualLine = layout.getLineForOffset(char)
            // Row-aware, for the same reason the sweep is; see [xOn].
            val from = layout.xOn(char, visualLine, inset)
            val to = layout.xOn(char + 1, visualLine, inset)
            if (to <= from) continue
            val dx = growth.shift * em
            val dy = -growth.rise * peak * fall
            val rowTop = layout.getLineTop(visualLine) + inset
            val bottom = layout.getLineBottom(visualLine) + inset
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipRect(
                left = from - overhang + dx,
                top = rowTop - peak * GROW_HEADROOM,
                right = to + overhang + dx,
                bottom = bottom,
            ) {
                translate(left = dx, top = dy) {
                    scale(
                        growth.scale,
                        growth.scale,
                        Offset((from + to) / 2f, (rowTop + bottom) / 2f),
                    ) {
                        this@glowGrown.drawContent()
                    }
                }
                // Scoped to this letter's own clip, so it takes this letter's
                // brightness down and leaves its neighbours — which have their
                // own, a beat behind — where they are.
                drawRect(
                    color = Color.White.copy(alpha = growth.bloom),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

/**
 * Redraws this row with the word being sung lifted off the line, and the ones
 * behind it settling back down.
 *
 * The line is cut at word boundaries and each piece replayed at its own
 * height, which is what CSS gets for free by making every syllable its own
 * box. Cutting between words rather than inside one means no glyph is ever
 * sliced, and the pieces that are on the floor — which is most of them, most
 * of the time — are one replay between them rather than one each.
 *
 * Costs nothing at all until something is off the floor: a line with no lift
 * on it draws exactly once, the same as it did before any of this.
 */
private fun ContentDrawScope.riseWith(
    layout: TextLayoutResult,
    line: SweptLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isLifted(positionMs)) {
        drawContent()
        return
    }
    val em = layout.layoutInput.style.fontSize.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
        // The row's own box. Anything standing still is clipped to exactly
        // this: a band opened upwards would take in the bottom of the row
        // above and draw it a second time, and two passes of a half-transparent
        // line do not add up to the same line. That doubled sliver along every
        // row is what read as the lines overlapping.
        val top = layout.getLineTop(visualLine) + inset
        val bottom = layout.getLineBottom(visualLine) + inset
        var at = lineStart
        var edge = layout.getLineLeft(visualLine) + inset
        for (index in line.words.indices) {
            val span = line.wordSpans[index]
            val start = maxOf(span.first, lineStart)
            val end = minOf(span.last + 1, lineEnd)
            if (start >= end) continue
            // Only while it is actually moving. Once the last letter has come to
            // rest the word is back to being an ordinary sung word settling
            // down, and the two agree exactly at the handover — a letter rests
            // at precisely the lift [SweptLine.wordLift] would give it — so the
            // cheaper single slice takes over without a step.
            val held = line.growingAt(index)?.takeIf { positionMs in it.startMs..it.restsAtMs }
            val lift = line.wordLift(index, positionMs)
            // A word with nothing happening to it is left to the flat run,
            // which is the whole of the line for all but a syllable of it.
            if (held == null && lift <= 0.01f) continue
            val from = layout.xOn(start, visualLine, inset)
            val to = layout.xOn(end, visualLine, inset)
            // Nothing to cut. Left where it is rather than stepped over, so the
            // flat run still has it and the row keeps its words.
            if (to <= from) continue
            // Everything between the last risen word and this one is flat, and
            // goes down in a single piece however many words that spans.
            if (start > at) sliceRisen(edge, top, from, bottom, 0f)
            if (held != null) {
                growEach(
                    layout, held, line, positionMs, visualLine,
                    start, end, top, bottom, inset, peak, em, growth,
                )
            } else {
                // Only what is off the floor gets room above the row to be off
                // it in; see [top].
                sliceRisen(from, top - peak, to, bottom, -lift * peak)
            }
            at = end
            edge = to
        }
        if (at < lineEnd) {
            sliceRisen(edge, top, layout.getLineRight(visualLine) + inset, bottom, 0f)
        }
    }
}

/**
 * Redraws one held word a letter at a time, each at its own swell and height.
 *
 * The word is cut between characters rather than between words, so a letter can
 * be scaled about its own centre without the ones either side of it coming
 * along. Each piece is clipped to where its letter is *going* rather than where
 * it sits: a glyph grown about its middle reaches past the box it was laid out
 * in, and clipping to that box would shave both sides off it as it swells.
 *
 * The overlap that buys — a letter's clip reaching a pixel or so into its
 * neighbour's — is why this is only ever run on a word that has earned it. Two
 * copies of a glyph edge a pixel apart is nothing on a letter mid-swell and
 * would be an obvious double image across a whole line.
 */
@Suppress("LongParameterList")
private fun ContentDrawScope.growEach(
    layout: TextLayoutResult,
    word: GrowingWord,
    line: SweptLine,
    positionMs: Long,
    visualLine: Int,
    start: Int,
    end: Int,
    top: Float,
    bottom: Float,
    inset: Float,
    peak: Float,
    em: Float,
    growth: CharGrowth,
) {
    // The settle is shared with every other word: a letter comes to rest at the
    // same small lift, and then goes down with the rest of the line.
    val fall = line.wordFall(word.index, positionMs)
    val first = line.wordSpans[word.index].first
    // Room to swell into, above the row rather than inside it. The pivot stays
    // on the row's own middle: scaling about the middle of the *band* would
    // walk every letter downwards as it grew.
    val ceiling = top - peak * GROW_HEADROOM
    val middle = (top + bottom) / 2f
    for (char in start until end) {
        word.sampleInto(char - first, positionMs, growth)
        val from = layout.xOn(char, visualLine, inset)
        val to = layout.xOn(char + 1, visualLine, inset)
        if (to <= from) continue
        val dx = growth.shift * em
        val dy = -growth.rise * peak * fall
        val overhang = (to - from) * (growth.scale - 1f) / 2f
        clipShiftedDown(
            left = from - overhang + dx,
            top = ceiling,
            right = to + overhang + dx,
            bottom = bottom,
            dy = dy,
        ) {
            translate(left = dx) {
                scale(growth.scale, growth.scale, Offset((from + to) / 2f, middle)) {
                    this@growEach.drawContent()
                }
            }
        }
    }
}

/**
 * Where an offset sits horizontally *on the row it was cut out of*.
 *
 * [TextLayoutResult.getHorizontalPosition] answers for the row the offset
 * itself belongs to — and the offset one past the last character of a wrapped
 * row belongs to the next row, so asking where a word that runs up to a wrap
 * *ends* gives a position at the far left, one row down. A slice cut between
 * there and the word's start is empty, and the walk then treats the row as
 * finished: everything from that word to the end of the row is never drawn.
 *
 * Whole rows disappeared that way, and Japanese lines disappeared most, because
 * word spans there are often whole phrases and reach a wrap on their own where
 * an English word rarely does.
 */
/**
 * Where a character offset sits across a visual line, in pixels.
 *
 * For RTL paragraphs the first character of a visual line is at the right
 * edge and the last is at the left — this function respects that so callers
 * (horizontalAt, sweepTo) get correct clip boundaries regardless of text
 * direction.  Interior offsets delegate to Compose's own cursor-position
 * calculation which already handles direction; only the two boundary cases
 * need special handling because getLineLeft / getLineRight return physical
 * bounding-box edges (always left ≤ right), not paragraph-direction edges.
 */
private fun TextLayoutResult.xOn(offset: Int, visualLine: Int, inset: Float): Float {
    val lineStart = getLineStart(visualLine)
    val lineEndVisible = getLineEnd(visualLine, visibleEnd = true)
    val lineLeft = getLineLeft(visualLine) + inset
    val lineRight = getLineRight(visualLine) + inset

    val rtl = getParagraphDirection(lineStart.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) ==
              androidx.compose.ui.text.style.ResolvedTextDirection.Rtl

    return when {
        offset <= lineStart -> if (rtl) lineRight else lineLeft
        offset >= lineEndVisible -> if (rtl) lineLeft else lineRight
        // The inset belongs on every answer, not just the two edges: the rows
        // are drawn [inset] in from the layout's origin, and an interior cut
        // without it lands that far left of the glyph boundary — the lifted
        // and swelling slices then sliced letters in half.
        else -> (getHorizontalPosition(offset, usePrimaryDirection = true) + inset)
            .coerceIn(lineLeft, lineRight)
    }
}

/** One piece of a line, clipped to its own width and drawn at its own height. */
private fun ContentDrawScope.sliceRisen(
    from: Float,
    top: Float,
    to: Float,
    bottom: Float,
    dy: Float,
) {
    if (to <= from) return
    clipShiftedDown(left = from, top = top, right = to, bottom = bottom, dy = dy) {
        this@sliceRisen.drawContent()
    }
}

/**
 * Where a fractional character index sits across a visual line, in pixels.
 *
 * Inside a timed piece — a syllable, or a word nobody split — that sits whole
 * on this row, the index is read as a share of the piece and laid across its
 * width. Read letter by letter instead, the edge crossed a "w" in the same time
 * as an "i": it sped up and slowed down inside every word, a wobble that a held
 * note made plain. am-lyrics wipes each syllable across its box at a constant
 * rate, and this is the same thing.
 */
private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    visualLine: Int,
    spans: List<IntRange>,
): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
    // Use the *all* end (including trailing whitespace/punctuation) for the
    // boundary check — visibleEnd can exclude chars that sit at the physical
    // edge of an RTL line and would cause xOn to resolve to the next visual
    // line instead of the current one's far edge.
    val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
    val word = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (word != null && word.first >= lineStart && word.last + 1 <= lineEndVisible) {
        val from = layout.xOn(word.first, visualLine, 0f)
        val to = layout.xOn(word.last + 1, visualLine, 0f)
        return from + (to - from) * (chars - word.first) / (word.last + 1 - word.first)
    }
    // A word broken over a wrap, or the space between two words: letter by
    // letter, which is all a single space or a half-word needs.
    val index = chars.toInt().coerceIn(lineStart, lineEndAll)
    // Row-aware at both ends: on the last character of a wrapped row the next
    // position belongs to the row below, and read straight it puts the edge
    // back at the left margin — the highlight jumped backwards a letter before
    // every wrap.  Guard against that by checking whether index+1 would land
    // on the next visual line; if so, clamp to this line's far edge instead.
    val here = layout.xOn(index, visualLine, 0f)
    val rawNext = index + 1
    val textLen = layout.layoutInput.text.length
    val spills: Boolean = layout.getLineForOffset(rawNext.coerceIn(0, textLen)) != visualLine
    val next: Float = if (spills) {
        // Spilled to the next visual line: use this line's far edge as the
        // interpolation target.  For RTL that is getLineLeft; for LTR it is
        // getLineRight.  The fallback below (xOn with coerceAtMost(lineEnd))
        // would return the wrong side for RTL.
        if (rawNext > lineEndVisible) {
            // At or past the visible end: use the all-end edge.
            val rtl = layout.getParagraphDirection(
                visualLine.coerceAtMost(lineEndAll).coerceIn(0, textLen - 1)
            ) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
            val farLeft = layout.getLineLeft(visualLine)
            val farRight = layout.getLineRight(visualLine)
            // For RTL, the "far" edge in character order is getLineLeft;
            // for LTR it is getLineRight.  We detect by comparing the two:
            if (rtl) farLeft else farRight
        } else {
            layout.xOn(rawNext.coerceAtMost(lineEndVisible), visualLine, 0f)
        }
    } else {
        layout.xOn(rawNext, visualLine, 0f)
    }
    return here + (next - here) * (chars - index)
}

/**
 * Draws this text clipped to its first [revealedChars] characters.
 *
 * Wrapped lines are handled a visual line at a time: the ones already passed
 * are drawn whole, the one holding the boundary is cut at it, and the rest are
 * left to the dim copy. Within a word the cut moves across the word's width
 * at a steady rate — see [horizontalAt] — rather than jumping a letter at a
 * time. [spans] are the line's timed pieces, [SweptLine.sweepSpans]; empty, the
 * cut goes letter by letter.
 *
 * The boundary itself is then feathered over the [WIPE_FEATHER] ahead of it
 * rather than left as the cut, which needs the caller to give this an offscreen layer to erase
 * into — see [SweptLyricLine]. Only the line actually being sung carries one;
 * everywhere else the boundary is at one end of the text or the other and
 * there is nothing to soften.
 */
private fun ContentDrawScope.sweepTo(
    layout: TextLayoutResult,
    revealedChars: Float,
    spans: List<IntRange>,
    feather: Boolean,
) {
    if (revealedChars <= 0f) return
    val textLen = layout.layoutInput.text.length
    if (revealedChars >= textLen) {
        drawContent()
        return
    }
    // Derive RTL from the layout's own resolved paragraph direction, not from
    // text heuristics.  This keeps geometry in lock-step with how Compose
    // actually laid out the glyphs — critical when a translation line (Hebrew)
    // sits under an English lead and both get different text-analysis results.
    val offset = layout.getLineStart(0).coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(offset) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl

    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)

        // Lines whose character-range start is past the sweep boundary have
        // nothing lit; lines after that too.  Use `>` (not `>=`) so a line
        // whose *first* char is exactly at the boundary still gets drawn —
        // it is the boundary line and must show the feather edge.
        if (revealedChars <= lineStart) return

        val cut = revealedChars < lineEndAll
        val charPos = if (cut) {
            horizontalAt(layout, revealedChars, visualLine, spans)
        } else {
            // The sweep has passed this entire visual line.  Its boundary is
            // the *far* edge: right edge for RTL (lineLeft), left edge for LTR.
            if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
        }

        val lineLeft = layout.getLineLeft(visualLine)
        val lineRight = layout.getLineRight(visualLine)

        // Clamp the sweep boundary so the clip rect is always valid and never
        // inverted near the line ends.  For RTL the revealed prefix [0,k) must
        // occupy [x(k), lineRight], with x(k) clamped inside [lineLeft, lineRight].
        val safeCharPos = charPos.coerceIn(lineLeft, lineRight)

        // The feather runs *ahead* of the boundary, into what is still to be
        // sung: lit solid up to now, fading out over the next [WIPE_FEATHER].
        // Trailing behind it instead, the letter being sung right now was the
        // dimmest part of the lit copy, and the line ran out of time with a
        // faded tail still on it and then snapped to full — which read as the
        // highlight being late. Ahead of it, the fade runs off the end of the
        // text as the last letter is reached, and the line finishes already
        // whole.
        //
        // Grown in over the first [WIPE_FEATHER] of each row rather than
        // opened at full width, or the first letter of every row would pop in
        // half-lit the frame the sweep reached it.
        val featherPx = if (feather && cut) {
            minOf(
                WIPE_FEATHER.toPx(),
                if (rtl) lineRight - safeCharPos else safeCharPos - lineLeft,
            )
        } else {
            0f
        }
        val lead = if (rtl) {
            (safeCharPos - featherPx).coerceAtLeast(lineLeft)
        } else {
            (safeCharPos + featherPx).coerceAtMost(lineRight)
        }

        // For RTL lines the sweep fills right-to-left: clip from the line's
        // right edge down to the feather's leading edge.  For LTR it fills
        // left-to-right as before.
        val (clipLeft, clipRight) = if (rtl) {
            lead to lineRight
        } else {
            lineLeft to lead
        }

        // Guard: an empty or inverted clip is a no-op; skip it cleanly.
        if (clipLeft >= clipRight) continue

        val top = layout.getLineTop(visualLine)
        val bottom = layout.getLineBottom(visualLine)
        clipRect(
            left = clipLeft,
            top = top,
            right = clipRight,
            bottom = bottom,
        ) {
            this@sweepTo.drawContent()
        }

        // Only the visual line holding the boundary has an edge to soften; a
        // line revealed to its end runs into the wrap, which is not an edge.
        if (featherPx < 0.5f) continue

        // Scoped to this line's band so the mask cannot reach the lines above
        // and below it: DstIn erases whatever the source does not cover, and
        // outside the clip there is no source at all, so they are left alone.
        // Within it the brush clamps — opaque up to the boundary, gone past
        // the feather. The gradient keeps its full width even where the clip
        // is cut short by the end of the row, so the last letters keep fading
        // up instead of being cut off.
        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White,
                    1f to Color.Transparent,
                    startX = safeCharPos,
                    endX = if (rtl) safeCharPos - featherPx else safeCharPos + featherPx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}
