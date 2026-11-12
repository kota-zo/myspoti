package app.myspoti.api.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TrackIdResolverTest {
    private val id = "4uLU6hMCjMI75M1A2tKUQC"

    @Test fun `the known id passes through for its provider only`() {
        val t = SyncTrack(ids = mapOf("spotify" to id))
        assertEquals(id, TrackIdResolver.KnownIds.idFor(t, "spotify"))
        assertNull(TrackIdResolver.KnownIds.idFor(t, "deezer"))
    }

    @Test fun `a blank id or none is no id`() {
        assertNull(TrackIdResolver.KnownIds.idFor(SyncTrack(ids = mapOf("spotify" to "")), "spotify"))
        assertNull(TrackIdResolver.KnownIds.idFor(SyncTrack(), "spotify"))
    }

    @Test fun `a chained resolver is asked only when the ones before it know nothing`() {
        val asked = ArrayList<String>()
        val byIsrc = TrackIdResolver { t, _ -> asked += t.title; if (t.isrc == "XX0000000001") "matched" else null }
        val r = TrackIdResolver.chain(TrackIdResolver.KnownIds, byIsrc)
        assertEquals(id, r.idFor(SyncTrack(ids = mapOf("spotify" to id), title = "known"), "spotify"))
        assertEquals("matched", r.idFor(SyncTrack(isrc = "XX0000000001", title = "by isrc"), "spotify"))
        assertNull(r.idFor(SyncTrack(title = "unknown"), "spotify"))
        assertEquals(listOf("by isrc", "unknown"), asked)
    }
}
