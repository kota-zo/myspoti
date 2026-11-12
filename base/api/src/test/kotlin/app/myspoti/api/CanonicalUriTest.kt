package app.myspoti.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalUriTest {
    @Test fun `an older playlist uri is the playlist's own`() {
        assertEquals("spotify:playlist:37i9dQZF1DX0", canonicalUri("spotify:user:spotify:playlist:37i9dQZF1DX0"))
        assertEquals(MediaKind.PLAYLIST, MediaKind.of(canonicalUri("spotify:user:someone:playlist:abc123")))
    }

    @Test fun `other uris stay as they are`() {
        for (uri in listOf("spotify:playlist:abc", "spotify:user:someone", "spotify:user:someone:collection", "spotify:user:me:folder:f1", "spotify:track:x")) {
            assertEquals(uri, canonicalUri(uri))
        }
    }
}
