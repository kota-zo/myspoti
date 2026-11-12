package com.music.spotui.podcast

import com.music.spotui.podcast.SponsorSegments.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SponsorSegmentsTest {
    private val all = SponsorSegments.ALL_CATEGORIES.toSet()

    @Test fun hashPrefixIsTheFirstFourHexOfSha256() {
        // The server answered prefix 800 with this episode (service=Spotify), 2026-11-03.
        assertEquals("8004", SponsorSegments.hashPrefix("1asgl8mRtVJhZFF1zYZQvZ"))
    }

    @Test fun parseKeepsOnlyTheEpisodesSkipSegments() {
        // Shape of a real answer of /api/skipSegments/800?service=Spotify, trimmed.
        val body = """[{"videoID":"1asgl8mRtVJhZFF1zYZQvZ","segments":[
            {"category":"sponsor","actionType":"skip","segment":[0,76.974],"UUID":"a","videoDuration":4458.26,"locked":0,"votes":0,"description":""},
            {"category":"sponsor","actionType":"full","segment":[0,0],"UUID":"b","videoDuration":4458.26}]},
            {"videoID":"2KLAgmjeelP9WPTbC2wpW3","segments":[{"category":"sponsor","actionType":"skip","segment":[860.238,1019.027],"UUID":"c","videoDuration":5242.551}]}]"""
        assertEquals(listOf(Segment(0, 76_974, "sponsor", "a", 4_458_260)), SponsorSegments.parse(body, "1asgl8mRtVJhZFF1zYZQvZ"))
        assertEquals(emptyList<Segment>(), SponsorSegments.parse(body, "other"))
        assertNull(SponsorSegments.parse("Hash prefix does not match format requirements.", "x"))
    }

    @Test fun segmentsOfACopyOfAnotherLengthAreLeftOut() {
        val s = listOf(
            Segment(10_000, 40_000, "sponsor", "a", 600_000),
            Segment(100_000, 130_000, "sponsor", "b", 660_000), // marked on a copy one minute longer
            Segment(200_000, 230_000, "sponsor", "c", 0),       // length not given: kept
        )
        assertEquals(listOf("a", "c"), SponsorSegments.usable(s, 601_500, all).map { it.uuid })
    }

    @Test fun onlyTheChosenCategoriesAreKept() {
        val s = listOf(Segment(0, 20_000, "intro", "i"), Segment(50_000, 80_000, "sponsor", "s"), Segment(90_000, 95_000, "selfpromo", "p"))
        assertEquals(listOf("s"), SponsorSegments.usable(s, 0, setOf("sponsor")).map { it.uuid })
    }

    @Test fun overlappingSegmentsAreMergedAndTinyOnesDropped() {
        val s = listOf(
            Segment(50_000, 80_000, "sponsor", "a"),
            Segment(80_500, 90_000, "selfpromo", "b"),
            Segment(120_000, 120_400, "sponsor", "tiny"),
        )
        val u = SponsorSegments.usable(s, 0, all)
        assertEquals(1, u.size)
        assertEquals(50_000L, u[0].startMs)
        assertEquals(90_000L, u[0].endMs)
    }

    @Test fun aSegmentEndsWithTheEpisode() {
        val u = SponsorSegments.usable(listOf(Segment(500_000, 700_000, "outro", "o", 0)), 600_000, all)
        assertEquals(600_000L, u.single().endMs)
    }

    @Test fun eachSegmentIsSkippedOnce() {
        val t = SkipTracker(listOf(Segment(10_000, 40_000, "sponsor", "a"), Segment(100_000, 130_000, "sponsor", "b")))
        assertNull(t.due(5_000))
        assertEquals("a", t.due(10_200)?.uuid)
        // The Undo goes back into it: it plays.
        assertNull(t.due(10_200))
        assertNull(t.due(20_000))
        assertEquals(60_000L, t.untilNext(40_000))
        assertEquals("b", t.due(100_300)?.uuid)
        assertNull(t.untilNext(131_000))
    }

    @Test fun aSegmentAboutToEndIsLetEnd() {
        val t = SkipTracker(listOf(Segment(10_000, 40_000, "sponsor", "a")))
        assertNull(t.due(39_000))
    }
}
