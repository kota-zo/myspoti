package app.myspoti.host.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidevineTest {
    @Test fun theVersionsSeenRefusedAreTooOld() {
        assertTrue(Widevine.tooOld("15.0.0"))
        assertTrue(Widevine.tooOld("16.0.0"))
    }

    @Test fun newerOrUnreadableVersionsAreNot() {
        assertFalse(Widevine.tooOld("16.1.1@014"))
        assertFalse(Widevine.tooOld("18.0.0@343213000"))
        assertFalse(Widevine.tooOld("***@***"))
        assertFalse(Widevine.tooOld(null))
    }
}
