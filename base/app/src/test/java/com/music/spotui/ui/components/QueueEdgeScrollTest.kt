package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The queue drag auto-scroll: direction and ramp of the per-frame scroll step. */
/** Where the queue block sits in the sheet's list, which the drag's auto-scroll reads. */
class QueueEdgeScrollTest {
    @Test fun queueBlockOffsetFollowsTheListScroll() {
        val row = 64f
        // At rest: the queue block starts one row below the current track.
        assertEquals(row, queueTopInViewport(2, 0, 0, row), 0f)
        // Current track half scrolled off.
        assertEquals(32f, queueTopInViewport(2, 0, 32, row), 0f)
        // Current track gone: the queue block starts at the viewport top.
        assertEquals(0f, queueTopInViewport(2, 1, 0, row), 0f)
        // Queue block scrolled up by 500 px.
        assertEquals(-500f, queueTopInViewport(2, 1, 500, row), 0f)
        // No current track: the queue block is the list's only item.
        assertEquals(-120f, queueTopInViewport(1, 0, 120, 0f), 0f)
        assertEquals(0f, queueTopInViewport(0, 0, 0, 0f), 0f)
    }

    @Test fun theDraggedRowKeepsItsPlaceOnScreenWhileTheListScrolls() {
        // One auto-scroll step: the list content moves by `scrolled` and the drag offset
        // is compensated by the same amount (QueueList.startAutoScroll), so the row stays
        // under the finger and the drop target is the row the user actually sees.
        val row = 64f
        val itemTopInQueue = 3 * row
        var dragOffset = 0f
        var firstOffset = 300
        val before = queueTopInViewport(2, 1, firstOffset, row) + itemTopInQueue + dragOffset + row / 2
        val scrolled = 14
        firstOffset += scrolled
        dragOffset += scrolled
        val after = queueTopInViewport(2, 1, firstOffset, row) + itemTopInQueue + dragOffset + row / 2
        assertEquals(before, after, 0.001f)
    }
}
