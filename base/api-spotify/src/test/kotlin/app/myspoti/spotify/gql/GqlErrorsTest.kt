package app.myspoti.spotify.gql

import app.myspoti.spotify.bridge.Bridge
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class GqlErrorsTest {
    private fun json(s: String) = Bridge.json.parseToJsonElement(s)

    @Test fun `writes are told from reads`() {
        assertTrue(isMutation("addToPlaylist"))
        assertTrue(isMutation("unfollowUsers"))
        assertTrue(isMutation("removeRecentSearches"))
        assertTrue(isMutation("SetItemsStateInWhatsNewFeed"))
        assertFalse(isMutation("fetchPlaylist"))
        assertFalse(isMutation("searchTracks"))
        assertFalse(isMutation("address"))
    }

    @Test fun `a refused write with partial data fails`() {
        assertThrows<GqlException> {
            checkErrors("addToPlaylist", json("""{"data":{"addToPlaylist":null},"errors":[{"message":"Forbidden"}]}"""))
        }
        assertThrows<GqlException> { checkErrors("addToPlaylist", json("""{"data":null}""")) }
    }

    @Test fun `a done write passes`() = assertDoesNotThrow {
        checkErrors("addToPlaylist", json("""{"data":{"addToPlaylist":{"__typename":"AddToPlaylistResponse"}}}"""))
    }

    @Test fun `a read keeps partial data`() {
        assertDoesNotThrow { checkErrors("home", json("""{"data":{"home":{}},"errors":[{"message":"one shelf failed"}]}""")) }
        assertThrows<GqlException> { checkErrors("home", json("""{"data":null,"errors":[{"message":"boom"}]}""")) }
    }
}
