package app.myspoti

import app.myspoti.spotify.bridge.BridgeException
import org.junit.Assert.*
import org.junit.Test

class SessionCheckTest {
    @Test fun aSignedInTokenConfirmsTheSession() {
        assertEquals(SessionCheck.SIGNED_IN, sessionCheck(Result.success(true)))
    }
    @Test fun anAnonymousTokenOrASignInPageSignsOut() {
        // MyspotiRuntime.isSignedIn answers false for both.
        assertEquals(SessionCheck.SIGNED_OUT, sessionCheck(Result.success(false)))
    }
    @Test fun aFailedCheckKeepsTheSession() {
        // Offline, token.get fails or times out: that is no answer, not an anonymous token (issue #137).
        assertEquals(SessionCheck.UNKNOWN, sessionCheck(Result.failure(BridgeException("timeout after 20000 ms: token.get"))))
        assertEquals(SessionCheck.UNKNOWN, sessionCheck(Result.failure(BridgeException("token.get: no accessToken"))))
        assertEquals(SessionCheck.UNKNOWN, sessionCheck(Result.failure(IllegalStateException("page gone"))))
    }
    @Test fun cancellationIsNotACheck() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            sessionCheck(Result.failure(kotlinx.coroutines.CancellationException("activity destroyed")))
        }
    }
}
