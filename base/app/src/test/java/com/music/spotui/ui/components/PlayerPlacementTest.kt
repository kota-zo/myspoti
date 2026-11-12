/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.components

import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the players' controls go: the layout a window gets, and the pull that closes the sheet. */
class PlayerPlacementTest {
    @Test fun `a phone held upright keeps one column whatever its box measures`() {
        val phone = WindowSize(411, 891)
        assertTrue(phone.portraitPhone)
        // A passing measure shorter than wide, or a box wider than 600 dp: still one column.
        assertFalse(phone.playerSideBySide(411.dp, 300.dp))
        assertFalse(phone.playerSideBySide(700.dp, 300.dp, minWidth = 600.dp))
        assertFalse(WindowSize(320, 568).playerSideBySide(320.dp, 200.dp))
    }

    @Test fun `windows on their side and large windows still put the cover beside the controls`() {
        assertTrue(WindowSize(891, 411).playerSideBySide(891.dp, 411.dp))
        assertTrue(WindowSize(891, 411).playerSideBySide(891.dp, 411.dp, minWidth = 600.dp))
        assertTrue(WindowSize(1280, 800).playerSideBySide(1280.dp, 800.dp))
        // A large portrait tablet: two panes from its size class.
        assertTrue(WindowSize(900, 1280).playerSideBySide(900.dp, 1280.dp))
        // A foldable upright: one column, as before.
        assertFalse(WindowSize(673, 841).playerSideBySide(673.dp, 841.dp, minWidth = 600.dp))
    }

    @Test fun `a pull that closed the sheet is back at rest when the sheet opens again`() = runTest {
        var closed = 0
        val pull = PullToDismissState(this, thresholdPx = 100f, flingPx = 1_000f) { closed++ }
        pull.drag(300f); advanceUntilIdle()
        assertTrue(pull.offset > 0f)
        pull.release(0f); advanceUntilIdle()
        assertEquals(1, closed)
        // Reopened before the close was over: drawn low, and no longer pullable, until reset.
        assertEquals(0f, pull.drag(50f))
        pull.reset()
        assertEquals(0f, pull.offset)
        assertEquals(50f, pull.drag(50f)); advanceUntilIdle()
        assertTrue(pull.offset > 0f)
    }
}
