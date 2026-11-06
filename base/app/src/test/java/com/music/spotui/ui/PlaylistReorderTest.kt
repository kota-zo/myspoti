package com.music.spotui.ui

import com.music.spotui.ui.screens.PlaylistReorder
import com.music.spotui.ui.screens.PlaylistReorder.RowMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistReorderTest {
    private val rows = listOf("a", "b", "c", "d")

    @Test fun movedDownIsPutBeforeTheRowNowAfterIt() =
        assertEquals(RowMove("a", "d"), PlaylistReorder.rowMove(rows, listOf("b", "c", "a", "d"), "a"))

    @Test fun movedUpIsPutBeforeTheRowNowAfterIt() =
        assertEquals(RowMove("c", "a"), PlaylistReorder.rowMove(rows, listOf("c", "a", "b", "d"), "c"))

    @Test fun movedLastGoesToTheEnd() =
        assertEquals(RowMove("b", null), PlaylistReorder.rowMove(rows, listOf("a", "c", "d", "b"), "b"))

    @Test fun backWhereItWasIsNoMove() {
        assertNull(PlaylistReorder.rowMove(rows, rows, "b"))
        assertNull(PlaylistReorder.rowMove(rows, rows, "d"))
    }

    @Test fun unknownRowIsNoMove() = assertNull(PlaylistReorder.rowMove(rows, rows, "z"))

    @Test fun appliedAsSpotifyDoes() {
        assertEquals(listOf("b", "c", "a", "d"), PlaylistReorder.applied(rows, RowMove("a", "d")) { it })
        assertEquals(listOf("c", "a", "b", "d"), PlaylistReorder.applied(rows, RowMove("c", "a")) { it })
        assertEquals(listOf("a", "c", "d", "b"), PlaylistReorder.applied(rows, RowMove("b", null)) { it })
    }

    @Test fun everyDropAppliedGivesTheDroppedOrder() {
        for (from in rows.indices) for (to in rows.indices) {
            val after = rows.toMutableList().apply { add(to, removeAt(from)) }
            val move = PlaylistReorder.rowMove(rows, after, rows[from])
            if (from == to) assertNull(move)
            else assertEquals(after, PlaylistReorder.applied(rows, move!!) { it })
        }
    }

    @Test fun appliedNeedsBothRows() {
        assertNull(PlaylistReorder.applied(rows, RowMove("z", "a")) { it })
        assertNull(PlaylistReorder.applied(rows, RowMove("a", "z")) { it })
    }

    private fun allowed(
        type: String = "playlist", editable: Boolean = true, isBlend: Boolean = false, custom: Boolean = true,
        complete: Boolean = true, find: String = "", uids: List<String> = rows,
    ) = PlaylistReorder.allowed(type, editable, isBlend, custom, complete, find, uids)

    @Test fun ownPlaylistInItsOrderCanMove() = assertTrue(allowed())

    @Test fun onlyWhereEveryConditionHolds() {
        assertFalse(allowed(type = "album"))
        assertFalse(allowed(type = "liked"))
        assertFalse(allowed(editable = false))
        assertFalse(allowed(isBlend = true))
        assertFalse(allowed(custom = false))
        assertFalse(allowed(complete = false))
        assertFalse(allowed(find = "halo"))
        assertTrue(allowed(find = "  "))
    }

    @Test fun everyRowNeedsItsOwnId() {
        assertFalse(allowed(uids = listOf("a", "", "c")))
        assertFalse(allowed(uids = listOf("a", "b", "a")))
        assertFalse(allowed(uids = listOf("a")))
    }
}
