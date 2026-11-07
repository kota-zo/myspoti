/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: the sections (now playing, next in queue, next from the context), placement-only
 * item motion and the fading edges, over Myspoti's SongPlayer.engineQueue. A row is removed
 * by swiping it away, and shuffle and repeat sit in the header as in the queue sheet.
 * A handle on each upcoming row drags it within its section or between them (sh.calvin.reorderable,
 * then SongPlayer.reorder / moveIntoQueue); history and
 * the autoplay heading are left out for now.
 */
package com.music.spotui.ui.immersive.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import android.os.SystemClock
import app.myspoti.host.android.Logbook
import kotlinx.coroutines.delay
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.di.Graph
import com.music.spotui.di.Haptics
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.rememberScroller
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.RepeatIcons
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.screens.ExplicitBadge

/**
 * How a queue row moves when the running order changes under it.
 *
 * Placement only — the fades are off deliberately. A row Compose treats as removed or
 * inserted goes on being drawn at the slot it used to hold for as long as it takes to fade,
 * and a queue row's background is transparent: the fading copy and whichever row is sliding
 * through that slot both draw their title and artist into the same few pixels. Without the
 * fades a row that leaves is gone the moment it leaves, so movement is the only thing left
 * to see. Bounded rather than the default spring so a track change settles at once.
 */
private val QUEUE_ROW_MOTION = tween<IntOffset>(durationMillis = 200, easing = FastOutSlowInEasing)

/** Placement-only item motion; see [QUEUE_ROW_MOTION]. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyItemScope.queueMotion(): Modifier =
    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = QUEUE_ROW_MOTION)

/**
 * The queue of the player, inside the player: what is playing, then "Next in queue" and
 * "Next from <context>". Tap a row to play it, swipe it away to remove it; shuffle and repeat
 * are in the header.
 *
 * The list brings its own side gutter; [contentPadding] is the extra room it keeps clear of
 * whatever the player draws over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveQueuePanel(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val queue by SongPlayer.engineQueue
    val song = Graph.currentSongState
    val shuffle = song.shuffle.value
    val repeatMode = SongPlayer.repeatMode.value
    val contextName = SongPlayer.nowPlaying.value.contextName

    // Rows swiped away, hidden at once: the queue takes a moment to report the removal.
    val removed = remember { mutableStateListOf<String>() }
    LaunchedEffect(queue) { removed.clear() }
    // Rows just dragged, shown in their new order until the queue reports exactly that order (or
    // PENDING_ORDER_MS passes): the reports in between are of the queue before the move.
    var pending by remember { mutableStateOf<QueueDragMath.Pending<QRow>?>(null) }
    var settling by remember { mutableStateOf(false) }
    fun endPending(p: QueueDragMath.Pending<QRow>, why: String) {
        if (pending !== p) return
        pending = null; settling = false
        Logbook.log("queue order: dropped ($why)")
    }
    LaunchedEffect(queue, pending) {
        val p = pending ?: return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        when (val v = p.report(queue.queued.map { it.uri }, queue.nextUp.map { it.uri }, queue.current?.url, now)) {
            QueueDragMath.Verdict.Keep -> {}
            QueueDragMath.Verdict.Matched -> { settling = true; Logbook.log("queue order: matched by the engine's report") }
            is QueueDragMath.Verdict.Drop -> endPending(p, v.why)
        }
    }
    // Limits by time alone: the engine may stay silent past either.
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(SLOW_REPORT_MS)
        if (pending === p && !settling) Logbook.log("queue order: no report yet after ${SLOW_REPORT_MS}ms, kept")
        while (pending === p) {
            delay(250)
            p.expired(SystemClock.elapsedRealtime())?.let { endPending(p, it) }
        }
    }
    // Rebuilt only when the queue, a pending drop or a swiped-away row changes: a drag step
    // (dragOrder) recomposes the panel without touching any of them.
    val rows = remember(queue, pending, removed.toList()) { shownRows(queue, pending, removed) }

    // While a row is dragged, the order on screen: each row or heading it passes swaps with it
    // (sh.calvin.reorderable), and the engine's reports wait for the drop. The drag used to draw
    // its row elsewhere and work out the place from its position: where the row was drawn, where
    // its slot was and where it was dropped could disagree, and the row was lost or put back.
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    var dragStart by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragRows by remember { mutableStateOf<Map<String, QRow>>(emptyMap()) }
    val order = remember(rows, dragOrder) { dragOrder ?: flatOrder(rows) }
    val byKey = remember(rows, dragOrder, dragRows) { if (dragOrder != null) dragRows else rows.associateBy { it.key } }
    val queuedKeys = remember(order) { QueueDragMath.queuedPart(order, HEADING_NEXT) }

    val listState = rememberLazyListState()
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    val direction = LocalLayoutDirection.current
    val current = queue.current
    val hasCurrent = song.title.value.isNotBlank() || current != null
    val topPad = contentPadding.calculateTopPadding() + 8.dp
    val bottomPad = contentPadding.calculateBottomPadding() + 24.dp
    // A speed, not a jump: the list's height every 100 ms was dozens of songs flying by (seen in a recording).
    // It grows with how far into the edge zone the finger is (QueueDragMath.edgePace): slow at the
    // zone's border, to stop on a row, about eight rows a second at the very edge.
    val density = LocalDensity.current
    val maxSpeed = with(density) { 480.dp.toPx() }
    val edgeZone = with(density) { 72.dp.toPx() }
    val topPadPx = with(density) { (contentPadding.calculateTopPadding() + 8.dp).toPx() }
    val bottomPadPx = with(density) { (contentPadding.calculateBottomPadding() + 24.dp).toPx() }
    // The list scrolls toward an edge only once the finger has gone that way: a row taken near the
    // bottom of the screen made the list run at once, the finger not having moved (reported).
    val gate = remember { EdgeScrollGate() }
    gate.slop = with(LocalDensity.current) { 20.dp.toPx() }
    // A speed of 0 holds the scroll (the scroller then moves by nothing until the gate opens).
    val scroller = rememberScroller(listState, {
        if (gate.allows()) QueueDragMath.edgePace(gate.y, topPadPx, gate.height - bottomPadPx, edgeZone, maxSpeed) else 0f
    })
    val reorder = rememberReorderableLazyListState(
        listState,
        scrollThresholdPadding = PaddingValues(top = topPad, bottom = bottomPad),
        scrollThreshold = 72.dp,
        scroller = scroller,
    ) { from, to ->
        val o = dragOrder ?: return@rememberReorderableLazyListState
        val f = from.key as? String ?: return@rememberReorderableLazyListState
        val t = to.key as? String ?: return@rememberReorderableLazyListState
        val row = dragRows[f] ?: return@rememberReorderableLazyListState
        QueueDragMath.swap(o, f, t, HEADING_NEXT, canLeaveQueue = row.item.provider == SongPlayer.LOCAL, block = blockOf(dragRows))?.let { dragOrder = it }
    }
    val onDragStarted: (String) -> Unit = { k ->
        dragStart = order; dragRows = byKey; dragOrder = order
        gate.begin()
        Haptics.click()
    }
    val onDragStopped: (String) -> Unit = stop@{ k ->
        val after = dragOrder ?: return@stop
        val start = dragStart
        val known = dragRows
        dragOrder = null
        val row = known[k] ?: return@stop
        val move = QueueDragMath.moveFor(start, after, k, HEADING_NEXT, blockOf(known))
        val call: () -> Unit = when (move) {
            QueueDragMath.Move.None -> return@stop
            is QueueDragMath.Move.IntoQueue -> {
                val target = move.target?.let { known[it]?.item }
                if (target == null) ({ SongPlayer.moveToQueueFront(row.item) }) else ({ SongPlayer.moveIntoQueue(row.item, target, move.after) })
            }
            is QueueDragMath.Move.Beside -> {
                val target = known[move.target]?.item ?: return@stop
                ({ SongPlayer.reorder(row.item, target, move.after) })
            }
        }
        val queuedNew = QueueDragMath.queuedPart(after, HEADING_NEXT).mapNotNull { known[it]?.copy(queued = true) }
        val nextNew = QueueDragMath.nextPart(after, HEADING_NEXT).mapNotNull { known[it]?.copy(queued = false) }
        pending = QueueDragMath.Pending(
            queuedNew, nextNew,
            QueueDragMath.queuedPart(start, HEADING_NEXT).mapNotNull { known[it]?.item?.uri },
            QueueDragMath.nextPart(start, HEADING_NEXT).mapNotNull { known[it]?.item?.uri },
            queuedNew.map { it.item.uri }, nextNew.map { it.item.uri },
            row.item.uri, queue.current?.url, SystemClock.elapsedRealtime(), pending?.detach(),
        )
        settling = false
        Logbook.log("queue order: set (${row.item.uri}: $move)")
        // The engine shows the dropped order at once and holds Spotify's reports until the move is done.
        SongPlayer.showQueueOrder(queuedNew.map { it.item }, nextNew.map { it.item })
        call()
    }

    val startPad = Immersive.Gutter + contentPadding.calculateStartPadding(direction)
    LazyColumn(
        state = listState,
        modifier = modifier
            .onSizeChanged { gate.height = it.height.toFloat() }
            .pointerInput(gate) {
                // Where the finger is, seen before the rows take it (nothing is consumed).
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()?.let { gate.y = it.position.y }
                }
            }
            .nestedScroll(keepScroll)
            .fadingEdges(),
        contentPadding = PaddingValues(
            start = startPad,
            top = topPad,
            end = Immersive.Gutter + contentPadding.calculateEndPadding(direction),
            bottom = bottomPad,
        ),
    ) {
        item(key = "header") {
            QueueHeader(
                shuffle = shuffle,
                repeatMode = repeatMode,
                onShuffle = {
                    SongPlayer.setShuffle(!shuffle)
                    song.shuffle.value = !shuffle
                },
                onRepeat = { SongPlayer.cycleRepeat() },
            )
        }
        if (hasCurrent) {
            item(key = "heading-now") {
                QueueHeading(
                    title = stringResource(R.string.immersive_queue_now_playing),
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp).then(queueMotion()),
                )
            }
            item(key = "now-playing") {
                QueueRow(
                    title = song.title.value.ifBlank { current?.title.orEmpty() },
                    artist = song.singer.value.ifBlank { current?.singer.orEmpty() },
                    cover = song.coverUri.value.ifBlank { current?.coverUri.orEmpty() },
                    isCurrent = true,
                    explicit = current?.explicit == true,
                    onClick = {},
                    modifier = queueMotion(),
                )
            }
        }
        // Not a place a row can go: the queued rows are under it.
        if (queuedKeys.isNotEmpty()) {
            item(key = "heading-queued") {
                QueueHeading(
                    title = stringResource(R.string.next_in_queue),
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).then(queueMotion()),
                ) {
                    Text(
                        text = stringResource(R.string.clear_queue),
                        style = Immersive.Subhead,
                        color = Immersive.TextSecondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { SongPlayer.clearQueue() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        // The queued rows, the context's heading and its rows: one list, so a row passing the
        // heading only changes places with it (and so changes part). Autoplay's songs come under a
        // heading of their own, a plain item: no Spotify song crosses it (QueueDragMath.swap).
        val autoAt = autoplayStart(order, byKey)
        val rowItem: @Composable LazyItemScope.(String) -> Unit = rowItem@{ k ->
            if (k == HEADING_NEXT) {
                // A place the dragged row swaps with (it then changes part), never dragged itself.
                ReorderableItem(reorder, key = k, animateItemModifier = queueMotion()) {
                    QueueHeading(
                        title = if (contextName.isNotBlank()) stringResource(R.string.next_from, contextName) else stringResource(R.string.next_up),
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }
                return@rowItem
            }
            val row = byKey[k] ?: return@rowItem
            ReorderableItem(reorder, key = k, animateItemModifier = queueMotion()) { dragging ->
                QueueEntry(row, removed, dragging, dimmed = reorder.isAnyItemDragging && !dragging,
                    handle = Modifier.draggableHandle(onDragStarted = { onDragStarted(k) }, onDragStopped = { onDragStopped(k) }))
            }
        }
        items(if (autoAt < 0) order else order.subList(0, autoAt), key = { it }) { k -> rowItem(k) }
        if (autoAt >= 0) {
            item(key = "heading-autoplay") {
                QueueHeading(
                    title = stringResource(R.string.queue_autoplay_heading),
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).then(queueMotion()),
                )
            }
            items(order.subList(autoAt, order.size), key = { it }) { k -> rowItem(k) }
        }
        if (!hasCurrent && order.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.immersive_queue_empty),
                    style = Immersive.Body,
                    color = Immersive.TextSecondary,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }
    }
}

/** One queue row: [index] in the flat list (queued rows, then the context's), [key] stable across moves. */
private data class QRow(val key: String, val item: SongPlayer.QueueItem, val queued: Boolean, val index: Int)

private const val SLOW_REPORT_MS = 3_000L
/** The carried row's veil: light, so it reads as lifted without a dark box over the cover's colours. */
private val CARRIED_ROW = Color.White.copy(alpha = 0.14f)
/** While a row is carried the others fade to this: what it passes over stays faint under it. */
private const val DIMMED_ALPHA = 0.4f

/**
 * Auto-scroll at the list's edges, held back until the finger goes toward one: from where the drag
 * started, at least [slop] down in the lower half of the list (it then scrolls down) or up in the
 * upper half. [y] is the finger in the list, [height] the list's.
 */
private class EdgeScrollGate {
    var y = 0f
    var height = 0f
    var slop = 0f
    private var startY = 0f
    fun begin() { startY = y }
    fun allows(): Boolean {
        val moved = y - startY
        return if (y > height / 2) moved > slop else moved < -slop
    }
}
private const val HEADING_NEXT = "heading-next"

/** Autoplay's songs (Spotify adds them after the context's) and the context's: blocks no Spotify song leaves. */
private fun blockOf(rows: Map<String, QRow>): (String) -> Int = { k -> if (rows[k]?.item?.provider == AUTOPLAY) 1 else 0 }

/** Where autoplay's songs start in [order]'s "next from" part, or -1. */
private fun autoplayStart(order: List<String>, rows: Map<String, QRow>): Int {
    val h = order.indexOf(HEADING_NEXT)
    if (h < 0) return -1
    return (h + 1 until order.size).firstOrNull { rows[order[it]]?.item?.provider == AUTOPLAY } ?: -1
}

private const val AUTOPLAY = "autoplay"

/** The panel's flat order of keys: the queued rows, the context's heading (when it has rows), its rows. */
private fun flatOrder(rows: List<QRow>): List<String> {
    val next = rows.filter { !it.queued }.map { it.key }
    return rows.filter { it.queued }.map { it.key } + (if (next.isEmpty()) emptyList() else listOf(HEADING_NEXT)) + next
}

/**
 * The rows to draw: the order just dropped while the engine has not reported it (its reports in
 * between show the queue as it was, or a song out of one part and not yet in the other), else the
 * queue as reported. Keyed by song and occurrence, not by the row id Spotify gives: that changes
 * with a move, and a changed key makes the row vanish and reappear.
 */
private fun shownRows(queue: SongPlayer.EngineQueue, pending: QueueDragMath.Pending<QRow>?, removed: List<String>): List<QRow> {
    // The same keys whichever list is drawn, so swapping the pending order for the engine's moves nothing.
    val all = if (pending != null) {
        (pending.queued + pending.next).map { it.item to it.queued }
    } else {
        queue.queued.map { it to true } + queue.nextUp.map { it to false }
    }
    val seen = HashMap<String, Int>()
    return all.map { (item, queued) ->
        val count = seen.merge(item.uri, 1, Int::plus)!!
        Triple(if (count == 1) item.uri else "${item.uri}~$count", item, queued)
    }.filter { it.first !in removed }.mapIndexed { i, (k, item, queued) -> QRow(k, item, queued, i) }
}

/** One upcoming row: swiped away to remove it, dragged by its handle ([handle]) to move it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueEntry(row: QRow, removed: SnapshotStateList<String>, dragging: Boolean, dimmed: Boolean, handle: Modifier) {
    val item = row.item
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) {
            removed += row.key
            SongPlayer.removeFromQueue(item)
            true
        } else {
            false
        }
    })
    // Carried: a light veil, and the other rows faded, so the titles it passes over do not read
    // through it (seen in a recording, 04/11 19:10). An opaque dark card instead looked like a box
    // dragged along, and lingered after the drop (reported): both go in 160 ms.
    val veil by animateColorAsState(if (dragging) CARRIED_ROW else Color.Transparent, tween(if (dragging) 90 else 160), label = "veil")
    val alpha by animateFloatAsState(if (dimmed) DIMMED_ALPHA else 1f, tween(160), label = "dim")
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        // No swipe while the row is carried.
        gesturesEnabled = !dragging,
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(14.dp))
            .background(veil),
        backgroundContent = {
            // Drawn only while swiping: the row is transparent, so at rest this × showed
            // through behind the drag handle.
            if (dismiss.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(
                    Modifier.fillMaxSize().padding(end = 4.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.immersive_queue_remove),
                        tint = Immersive.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
    ) {
        QueueRow(
            title = item.song.title,
            artist = item.song.singer,
            cover = item.song.coverUri,
            isCurrent = false,
            explicit = item.song.explicit,
            onClick = { SongPlayer.skipTo(item) },
            handle = {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.immersive_queue_reorder),
                    tint = Immersive.TextSecondary,
                    modifier = handle.size(40.dp).padding(8.dp),
                )
            },
        )
    }
}

@Composable
private fun QueueHeader(
    shuffle: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.immersive_queue_title),
            style = Immersive.Title,
            modifier = Modifier.weight(1f),
        )
        QueueToggle(Icons.Rounded.Shuffle, stringResource(R.string.immersive_queue_shuffle), shuffle, onShuffle)
        Spacer(Modifier.width(8.dp))
        QueueToggle(
            when (repeatMode) { 2 -> Icons.Rounded.RepeatOne; 3 -> RepeatIcons.once; else -> Icons.Rounded.Repeat },
            stringResource(R.string.immersive_queue_repeat),
            repeatMode != 0,
            onRepeat,
        )
    }
}

/** A round toggle: lit white when on, a quiet disc when off. */
@Composable
private fun QueueToggle(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun QueueHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = Immersive.BodyStrong,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun QueueRow(
    title: String,
    artist: String,
    cover: String,
    isCurrent: Boolean,
    explicit: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    handle: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Immersive.RowHeight)
            .clip(Immersive.CoverMedium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isCurrent,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlideImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(Immersive.CoverSmall)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = title,
                style = Immersive.Body,
                color = if (isCurrent) Immersive.Accent else Immersive.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (explicit) {
                    ExplicitBadge()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = artist,
                    style = Immersive.Subhead,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (handle != null) handle()
        if (isCurrent) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = stringResource(R.string.immersive_queue_now_playing),
                tint = Immersive.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
