package app.myspoti.spotify

import app.myspoti.api.Page
import app.myspoti.api.PlaylistMerge
import app.myspoti.api.Track
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistMergeTest {
    private fun t(id: String) = Track("spotify:track:$id", id, 1000, emptyList(), null)
    private fun uris(vararg ids: String) = ids.map { "spotify:track:$it" }

    @Test fun `nothing already there adds everything without a question`() {
        val plan = PlaylistMerge.plan(uris("x"), uris("a", "b"))
        assertFalse(plan.hasDuplicates)
        assertEquals(uris("a", "b"), plan.fresh)
    }

    @Test fun `some already there offers add all or only the new ones, in order`() {
        val plan = PlaylistMerge.plan(uris("b", "z"), uris("a", "b", "c"))
        assertTrue(plan.someDuplicate)
        assertFalse(plan.allDuplicate)
        assertEquals(1, plan.duplicateCount)
        assertEquals(uris("a", "b", "c"), plan.all)
        assertEquals(uris("a", "c"), plan.fresh)
    }

    @Test fun `all already there offers add anyway or don't add`() {
        val plan = PlaylistMerge.plan(uris("a", "b", "z"), uris("a", "b"))
        assertTrue(plan.allDuplicate)
        assertFalse(plan.someDuplicate)
        assertTrue(plan.fresh.isEmpty())
    }

    @Test fun `an empty source is no duplicate case`() {
        val plan = PlaylistMerge.plan(uris("a"), emptyList())
        assertFalse(plan.hasDuplicates)
        assertFalse(plan.allDuplicate)
    }

    @Test fun `local files are left out`() {
        assertEquals(uris("a"), PlaylistMerge.addable(listOf("spotify:local:x:y:z:1", "spotify:track:a")))
    }

    @Test fun `every page is read until the last one`() = runBlocking {
        val seen = mutableListOf<Int>()
        val all = PlaylistMerge.readAll { offset ->
            seen += offset
            val items = (offset until minOf(250, offset + 100)).map { t("t$it") }
            Page(items, offset, 250, (offset + 100).takeIf { it < 250 })
        }
        assertEquals(listOf(0, 100, 200), seen)
        assertEquals(250, all.size)
        assertEquals("spotify:track:t249", all.last())
    }

    @Test fun `liking a whole list likes only the songs not liked yet, each once`() = runBlocking {
        var asked: List<String>? = null
        val plan = PlaylistMerge.likePlan(uris("a", "b", "a", "c")) { list -> asked = list; list.map { it.endsWith(":b") } }
        assertEquals(uris("a", "b", "c"), asked)
        assertEquals(uris("a", "c"), plan.fresh)
        assertTrue(PlaylistMerge.likePlan(uris("b")) { list -> list.map { true } }.allDuplicate)
    }
}
