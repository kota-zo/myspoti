package app.myspoti.spotify.bridge

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PageAuthTest {
    @Test fun `one 401 alone is a token the page refreshes`() {
        val a = AuthLoss()
        assertNull(a.record(0, licence = false))
        assertNull(a.record(5_000, licence = false))
    }

    @Test fun `three 401s within half a minute are a stale token`() {
        val a = AuthLoss()
        assertNull(a.record(0, false)); assertNull(a.record(200, false))
        assertNotNull(a.record(800, false))
    }

    @Test fun `401s too far apart are not`() {
        val a = AuthLoss()
        assertNull(a.record(0, false)); assertNull(a.record(20_000, false))
        assertNull(a.record(40_000, false))
    }

    @Test fun `a licence answered 401 is at once`() = assertNotNull(AuthLoss().record(0, licence = true))

    @Test fun `said once a minute while the 401s go on`() {
        val a = AuthLoss()
        assertNotNull(a.record(0, true))
        assertNull(a.record(1_000, true))
        assertNull(a.record(30_000, true))
        assertNotNull(a.record(61_000, true))
    }

    @Test fun `a new page counts from zero`() {
        val a = AuthLoss()
        a.record(0, false); a.record(100, false)
        a.clear()
        assertNull(a.record(200, false))
    }
}
