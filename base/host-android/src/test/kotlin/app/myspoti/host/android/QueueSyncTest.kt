package app.myspoti.host.android

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSyncTest {
    private val queue = listOf(
        QueueEntry(0, "Song A", "Artist A", null),
        QueueEntry(1, "Song B", "Artist B", null),
    )

    @Test fun rowOfTheSongShownIsMarked() {
        assertEquals(0L, QueueSync.activeRow(queue, 0, "Song A", "Artist A"))
    }

    @Test fun queueBehindTheMetadataMarksNoRow() {
        // The song changed, the queue has not followed yet.
        assertEquals(QueueSync.NONE, QueueSync.activeRow(queue, 0, "Song B", "Artist B"))
        // An ad plays over the song's row.
        assertEquals(QueueSync.NONE, QueueSync.activeRow(queue, 0, "Ad", ""))
    }

    @Test fun missingRowOrNoneMarksNoRow() {
        assertEquals(QueueSync.NONE, QueueSync.activeRow(queue, 5, "Song A", "Artist A"))
        assertEquals(QueueSync.NONE, QueueSync.activeRow(queue, QueueSync.NONE, "Song A", "Artist A"))
        assertEquals(QueueSync.NONE, QueueSync.activeRow(emptyList(), 0, "Song A", "Artist A"))
    }

    @Test fun nothingShownYetKeepsTheRow() {
        assertEquals(1L, QueueSync.activeRow(queue, 1, null, null))
    }

    @Test fun missingArtistMatchesAnEmptyOne() {
        val q = listOf(QueueEntry(0, "Episode", "", null))
        assertEquals(0L, QueueSync.activeRow(q, 0, "Episode", null))
    }
}
