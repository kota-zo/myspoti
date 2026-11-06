package com.music.spotui.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.music.spotui.data.entity.SongsModel

/**
 * Moving songs of a playlist by hand (asked on Discord, #83). Spotify takes a move as "these rows
 * go just before that row" (the end when there is none), so a drop is said that way.
 */
object PlaylistReorder {
    /** [uid] goes just before the row [beforeUid]; null: to the end of the playlist. */
    data class RowMove(val uid: String, val beforeUid: String?)

    /**
     * The rows can be moved by hand: an own or collaborative playlist (not a Blend) shown whole, in
     * its own order and from its start, with no search narrowing it, and every row known by its
     * own id (the same song twice is two rows).
     */
    fun allowed(type: String, editable: Boolean, isBlend: Boolean, customOrder: Boolean, complete: Boolean, find: String, rowUids: List<String>): Boolean =
        type == "playlist" && editable && !isBlend && customOrder && complete && find.isBlank() &&
            rowUids.size > 1 && rowUids.none { it.isBlank() } && rowUids.toSet().size == rowUids.size

    /**
     * What a drag did to [moved], from the rows' ids before it ([before]) and after it ([after]):
     * the row now right after it names the place; null when it is back where it was.
     */
    fun rowMove(before: List<String>, after: List<String>, moved: String): RowMove? {
        val at = after.indexOf(moved)
        if (at < 0 || moved !in before) return null
        val next = after.getOrNull(at + 1)
        if (next == before.getOrNull(before.indexOf(moved) + 1)) return null
        return RowMove(moved, next)
    }

    /** [rows] with [move] made, as Spotify makes it; null when a row it names is not there. */
    fun <T> applied(rows: List<T>, move: RowMove, uidOf: (T) -> String): List<T>? {
        val from = rows.indexOfFirst { uidOf(it) == move.uid }
        if (from < 0) return null
        val out = rows.toMutableList()
        val row = out.removeAt(from)
        val to = if (move.beforeUid == null) out.size else out.indexOfFirst { uidOf(it) == move.beforeUid }
        if (to < 0) return null
        out.add(to, row)
        return out
    }

    /** A row's key in the list while its rows can move: its own id, so it keeps its key wherever it goes. */
    fun key(song: SongsModel) = "row:" + song.rowUid
}

/**
 * The row being moved on a playlist page, for both looks: the rows as shown while it moves (moved
 * live under the finger), its offset from its place, and the list scrolling by itself near its
 * edges. A drag starts on a row's handle, as in the queue.
 */
class PlaylistDrag(private val listState: LazyListState, private val haptic: HapticFeedback) {
    /** The rows as shown while one moves, and after the drop until the page has the new order. */
    var order by mutableStateOf<List<SongsModel>?>(null)
    /** The row under the finger, by its row id. */
    var dragged by mutableStateOf<String?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var start: List<String> = emptyList()

    /** Set at every composition by the page: the rows it shows now, and what a drop does. */
    var rows: List<SongsModel> = emptyList()
    var onDrop: (PlaylistReorder.RowMove) -> Boolean = { false }

    private fun begin(uid: String) {
        order = rows
        start = rows.map { it.rowUid }
        dragged = uid
        offset = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** Moves the dragged row to the row its centre is over (rows of the playlist only). */
    fun follow() {
        val key = "row:" + (dragged ?: return)
        val info = listState.layoutInfo.visibleItemsInfo
        val item = info.firstOrNull { it.key == key } ?: return
        val centre = item.offset + offset + item.size / 2f
        val target = info.firstOrNull { it.key != key && (it.key as? String)?.startsWith("row:") == true && centre.toInt() in it.offset until it.offset + it.size } ?: return
        val current = order ?: return
        val from = current.indexOfFirst { PlaylistReorder.key(it) == key }
        val to = current.indexOfFirst { PlaylistReorder.key(it) == target.key }
        if (from < 0 || to < 0) return
        // Keeps the list still when the first visible row is involved.
        if (listState.firstVisibleItemIndex == item.index || listState.firstVisibleItemIndex == target.index)
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        order = current.toMutableList().apply { add(to, removeAt(from)) }
        offset += item.offset - target.offset
    }

    private fun end() {
        val uid = dragged ?: return
        dragged = null
        offset = 0f
        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
        val move = order?.let { PlaylistReorder.rowMove(start, it.map(SongsModel::rowUid), uid) }
        // Back where it was, or the page could not take it: the rows as the page has them.
        if (move == null || !onDrop(move)) order = null
    }

    private fun cancel() {
        dragged = null
        offset = 0f
        order = null
    }

    /** The page has new rows (the move taken, put back, or read again): they are shown, unless a row is still held. */
    fun settle() {
        if (dragged == null) order = null
    }

    /** The handle of the row [uid]: a drag on it moves the row. */
    fun handle(uid: String): Modifier = Modifier.pointerInput(this, uid) {
        try {
            detectDragGestures(
                onDragStart = { begin(uid) },
                onDragEnd = { end() },
                onDragCancel = { cancel() },
            ) { change, amount -> change.consume(); offset += amount.y; follow() }
        } finally { if (dragged == uid) cancel() }
    }

    /** Near the top or bottom edge, the list scrolls by itself while a row is held. */
    internal suspend fun autoScroll(edge: Float) {
        while (dragged != null) {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == "row:" + dragged }
            if (item != null) {
                val top = item.offset + offset
                val bottom = top + item.size
                val speed = when {
                    bottom > info.viewportEndOffset - edge -> 18f
                    top < info.viewportStartOffset + edge -> -18f
                    else -> 0f
                }
                if (speed != 0f) {
                    offset += listState.scrollBy(speed)
                    follow()
                }
            }
            withFrameNanos { }
        }
    }
}

@Composable
fun rememberPlaylistDrag(listState: LazyListState): PlaylistDrag {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val drag = remember(listState) { PlaylistDrag(listState, haptic) }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(drag, drag.dragged) { if (drag.dragged != null) drag.autoScroll(edge) }
    return drag
}

/**
 * The item of a row that can move: the other rows slide aside, the held one rides over them on
 * [lifted]. Same modifier chain held or not, so the handle's gesture is never rebuilt mid-drag.
 */
@Composable
fun LazyItemScope.movableRow(drag: PlaylistDrag, song: SongsModel, lifted: Color): Modifier {
    val held = drag.dragged == song.rowUid
    val still = com.music.spotui.ui.look.rememberReduceMotion()
    return Modifier
        .animateItem(fadeInSpec = null, fadeOutSpec = null,
            placementSpec = if (held || still) null else spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold))
        .zIndex(if (held) 1f else 0f)
        .graphicsLayer { translationY = if (held) drag.offset else 0f }
        .background(if (held) lifted else Color.Transparent)
}
