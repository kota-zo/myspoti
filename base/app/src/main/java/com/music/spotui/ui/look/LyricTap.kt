package com.music.spotui.ui.look

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize

/**
 * Where the finger last went down on this element, in its own coordinates, and how big the
 * element was then. Seen before the click handlers below it and never consumed, so a tap on a
 * lyric line can be turned into the word it landed on.
 */
fun Modifier.lastPress(onDown: (Offset, IntSize) -> Unit): Modifier = pointerInput(onDown) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onDown(down.position, size)
    }
}

/** When to play from for a tap at [at] (relative to the text's own top left) on [line] laid out as [layout]. */
fun tapTime(line: SweptLine, layout: TextLayoutResult, at: Offset): Long {
    if (!line.isWordSynced) return line.timeMs
    val drawn = layout.layoutInput.text.text
    val laid = if (drawn == line.text) line else line.copy(text = drawn)
    return laid.startAtChar(layout.getOffsetForPosition(at))
}
