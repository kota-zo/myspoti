/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AnalysisIdentityTest {
    private fun stream(provider: String = "a", duration: Long = 200_000, url: String = "https://audio.test/recording?token=old") =
        AnalysisIdentity.stream("track", provider, duration, "flac", null, url, null)

    @Test fun replacingSourceOrEditionCannotUseOldBeatGrid() {
        assertNotEquals(stream(), stream(provider = "b"))
        assertNotEquals(stream(), stream(duration = 210_000))
        assertNotEquals(stream(), stream(url = "https://audio.test/other-recording?token=old"))
    }

    @Test fun renewingAnAccessTokenDoesNotReanalyseSameRecording() {
        assertEquals(stream(), stream(url = "https://audio.test/recording?token=new"))
    }

    @Test fun queryParametersSelectingAnotherRecordingRemainPartOfTheIdentity() {
        assertNotEquals(stream(url = "https://audio.test/recording?edition=studio&token=old"),
            stream(url = "https://audio.test/recording?edition=live&token=old"))
        assertEquals(stream(url = "https://audio.test/recording?edition=studio&token=old"),
            stream(url = "https://audio.test/recording?edition=studio&token=new"))
    }

    @Test fun replacingAFileAtSamePathInvalidatesItsAnalysis() {
        assertNotEquals(AnalysisIdentity.file("file:///song.flac", 100, 20), AnalysisIdentity.file("file:///song.flac", 101, 20))
        assertNotEquals(AnalysisIdentity.file("file:///song.flac", 100, 20), AnalysisIdentity.file("file:///song.flac", 100, 21))
    }

    @Test fun refreshingDashAccessTokensKeepsAnalysisButAnotherMediaPathDoesNot() {
        fun dash(path: String, token: String): String {
            val xml = "<MPD><BaseURL>https://audio.test/$path?token=$token&amp;Expires=$token</BaseURL></MPD>"
            return AnalysisIdentity.stream("track", "a", 200_000, "flac", null, "", java.util.Base64.getEncoder().encodeToString(xml.toByteArray()))
        }
        assertEquals(dash("same", "old"), dash("same", "new"))
        assertNotEquals(dash("same", "old"), dash("different", "old"))
    }
}
