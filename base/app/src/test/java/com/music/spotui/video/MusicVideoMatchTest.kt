package com.music.spotui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVideoMatchTest {
    private val demon = "デーモンダンストーキョー"
    private val demonMs = 220_000L

    private fun e(name: String, uploader: String, sec: Long) = MusicVideoMatch.Entry(name, uploader, sec)

    /** The accepted entries, best first. */
    private fun accepted(title: String, artist: String, durationMs: Long, vararg entries: MusicVideoMatch.Entry): List<MusicVideoMatch.Entry> =
        MusicVideoMatch.rank(title, artist, durationMs, entries.toList()).map { entries[it] }

    @Test fun otherSongOfTheSameArtistIsRefused() {
        // Discord report: "Ambivalent - Eve MV" (3:42) played for デーモンダンストーキョー (3:40).
        assertTrue(accepted(demon, "Eve", demonMs, e("Ambivalent - Eve MV", "Eve", 222)).isEmpty())
    }

    @Test fun theTracksOwnMvIsKeptAndTheOtherDropped() {
        val own = e("デーモンダンストーキョー - Eve MV", "Eve", 241)
        assertEquals(listOf(own), accepted(demon, "Eve", demonMs, e("Ambivalent - Eve MV", "Eve", 222), own))
    }

    @Test fun artistMustMatchTheChannelOrTheName() {
        assertTrue(accepted("Song", "Eve", 200_000, e("Song (Official Video)", "Someone Else", 200)).isEmpty())
    }

    @Test fun artistIsWholeWordsNotASubstring() {
        assertTrue(accepted("Demon", "Eve", 200_000, e("Demon - Evening Show", "Evening Music", 200)).isEmpty())
    }

    @Test fun vevoAndTopicChannelsAreTheArtist() {
        assertEquals(1, accepted("Song", "Eve", 200_000, e("Song", "EveVEVO", 200)).size)
        assertEquals(1, accepted("Song", "Eve", 200_000, e("Song", "Eve - Topic", 200)).size)
    }

    @Test fun accentsCaseAndDollarSignAreNormalized() {
        assertEquals(1, accepted("Halo", "Beyoncé", 261_000, e("HALO (Official Music Video)", "BEYONCE", 261)).size)
        assertEquals(1, accepted("Praise The Lord", "A\$AP Rocky", 200_000, e("Praise The Lord - A\$AP Rocky (Official Video)", "ASAP Rocky", 200)).size)
    }

    @Test fun featuredPartAndDecorationsAreNotInTheTitle() {
        assertEquals(1, accepted("Song (feat. Someone)", "Artist", 200_000, e("Song ft. Someone [Official MV]", "Artist", 200)).size)
        assertEquals(1, accepted("Song feat. Someone", "Artist", 200_000, e("Song (Official Video)", "Artist", 200)).size)
    }

    @Test fun aPartInBracesIsNotInTheTitle() {
        assertEquals(1, accepted("Song {Remastered}", "Artist", 200_000, e("Song (Official Video)", "Artist", 200)).size)
    }

    @Test fun titleMustBeInTheName() {
        assertTrue(accepted("Song", "Eve", 200_000, e("Songbird - Eve", "Eve", 200)).isEmpty())
    }

    @Test fun liveCoverLyricReactionAndFanVersionsAreRefused() {
        assertTrue(accepted(demon, "Eve", demonMs, e("デーモンダンストーキョー (Live) - Eve", "Eve", 220)).isEmpty())
        assertTrue(accepted("Demon", "Eve", 200_000, e("Demon cover - Eve", "Someone", 200)).isEmpty())
        assertTrue(accepted(demon, "Eve", demonMs, e("デーモンダンストーキョー (Lyrics) - Eve", "Eve", 220)).isEmpty())
        assertTrue(accepted("Demon", "Eve", 200_000, e("Demon - Eve reaction", "Reactor", 200)).isEmpty())
        assertTrue(accepted("Demon", "Eve", 200_000, e("Demon - Eve fan made MV", "Fan", 200)).isEmpty())
    }

    @Test fun aVersionTheTitleNamesIsAllowed() {
        assertEquals(1, accepted("Live Forever", "Oasis", 270_000, e("Live Forever (Official Video)", "Oasis", 270)).size)
        assertEquals(1, accepted("Song (Remix)", "Eve", 200_000, e("Song (Remix) - Eve", "Eve", 200)).size)
    }

    @Test fun remixAndAudioOnlyAreRefusedUnlessTheTitleSaysSo() {
        assertTrue(accepted("Song", "Eve", 200_000, e("Song (Remix) - Eve", "Eve", 200)).isEmpty())
        assertTrue(accepted("Song", "Eve", 200_000, e("Song - Eve (Official Audio)", "Eve", 200)).isEmpty())
    }

    @Test fun lengthToleranceIsMinus10SecondsToPlus90() {
        val track = 200_000L
        assertEquals(1, accepted("Song", "Eve", track, e("Song - Eve", "Eve", 190)).size)
        assertTrue(accepted("Song", "Eve", track, e("Song - Eve", "Eve", 189)).isEmpty())
        assertEquals(1, accepted("Song", "Eve", track, e("Song - Eve", "Eve", 290)).size)
        assertTrue(accepted("Song", "Eve", track, e("Song - Eve", "Eve", 291)).isEmpty())
    }

    @Test fun unknownDurationIsHeldAgainstACandidateOnlyWhenTheTrackHasOne() {
        assertTrue(accepted("Song", "Eve", 200_000, e("Song - Eve", "Eve", 0)).isEmpty())
        assertEquals(1, accepted("Song", "Eve", 0, e("Song - Eve", "Eve", 0)).size)
    }

    @Test fun artistChannelAndOfficialNameComeFirst() {
        val byLabel = e("Song - Eve (Official Video)", "Warner Music", 200)
        val byArtist = e("Song - Eve", "Eve", 200)
        assertEquals(listOf(byArtist, byLabel), accepted("Song", "Eve", 200_000, byLabel, byArtist))
    }

    @Test fun primaryArtistOfAListIsMatched() {
        assertEquals(1, accepted("Song", "Eve, Someone", 200_000, e("Song - Eve", "Eve", 200)).size)
    }
}
