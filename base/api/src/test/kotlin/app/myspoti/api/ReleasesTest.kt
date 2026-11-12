package app.myspoti.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleasesTest {
    private fun a(id: String, date: String?, type: String = "SINGLE") = Album("spotify:album:$id", id, emptyList(), emptyList(), type, date, 1)

    @Test fun `a year-only popular release takes the discography's date and place`() {
        val popular = listOf(a("ep", "2024", "EP"), a("old", "2022-03-01", "ALBUM"))
        val discography = listOf(a("late", "2024-11-02"), a("ep", "2024-06-01T00:00:00Z", "EP"), a("early", "2024-01-05"))
        val shown = Releases.newestFirst(popular, discography)
        assertEquals(listOf("late", "ep", "early", "old"), shown.map { it.name })
        assertEquals("2024-06-01T00:00:00Z", shown[1].releaseDate)
    }

    @Test fun `without the discography the years still go newest first`() {
        val shown = Releases.newestFirst(listOf(a("x", "2021"), a("y", "2025"), a("z", null)), emptyList())
        assertEquals(listOf("y", "x", "z"), shown.map { it.name })
    }
}
