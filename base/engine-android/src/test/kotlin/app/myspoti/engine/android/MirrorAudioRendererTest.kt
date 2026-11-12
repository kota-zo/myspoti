package app.myspoti.engine.android

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The muted mirror plays its song through without a decoder: the format is read (the sample queue
 * asks the licence then), encrypted samples wait for their keys, each sample is taken at its time,
 * the position runs at the playback speed, and a switch to the decoder and back keeps the position.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MirrorAudioRendererTest {
    /** 10 s of AAC frames, one every ~23 ms (1024 samples at 44.1 kHz). */
    private val frames = LongArray(431) { it * 23_220L }
    private val time = FakeClock()
    private val stream = FakeStream(frames)
    private val decoder = FakeDecoder()
    private val silent = SilentAudioRenderer()
    private val renderer = MirrorAudioRenderer(decoder, silent).apply { init(0, PlayerId.UNSET, time) }

    private fun enable(at: Long = 0L) {
        renderer.enable(RendererConfiguration.DEFAULT, arrayOf(AAC), stream, at, false, true, at, 0L, MediaSource.MediaPeriodId(Any()))
        // One song per item: the player marks its stream as the last one, so its end is read.
        renderer.setCurrentStreamFinal()
        renderer.start()
    }

    private fun position() = renderer.mediaClock.positionUs

    /** Plays [ms] in 10-ms steps (as the player's loop does), checking the position never goes back. */
    private fun play(ms: Long) {
        var last = position()
        repeat((ms / 10).toInt()) {
            time.nowMs += 10
            renderer.render(position(), time.nowMs * 1000)
            val now = position()
            assertTrue("position went back: $last -> $now", now >= last)
            last = now
        }
    }

    @Test fun mutedTheSongIsPlayedThroughWithoutADecoder() {
        renderer.handleMessage(MirrorAudioRenderer.MSG_SET_SILENT, true)
        enable()
        play(2_000)
        assertEquals(0, decoder.enables)
        assertEquals(0, decoder.renders)
        // The licence: the format is asked first, as a decoder asks it.
        assertTrue(stream.reads.first() and SampleStream.FLAG_REQUIRE_FORMAT != 0)
        // No sample's data read (nothing copied, nothing decrypted)...
        assertEquals(0, stream.dataReads)
        // ...but every sample taken off the stream at its time, none ahead of the position.
        assertEquals(frames.count { it <= position() }, silent.samplesTaken)
        assertEquals(2_000_000.0, position().toDouble(), 20_000.0)
        assertTrue(renderer.isReady)
    }

    @Test fun theSilentPositionFollowsThePlaybackSpeedAndPauses() {
        renderer.handleMessage(MirrorAudioRenderer.MSG_SET_SILENT, true)
        enable()
        renderer.mediaClock.playbackParameters = PlaybackParameters(1.5f)
        play(2_000)
        assertEquals(3_000_000.0, position().toDouble(), 30_000.0)
        renderer.stop()
        val held = position()
        time.nowMs += 5_000
        assertEquals(held, position())
    }

    @Test fun encryptedSamplesWaitForTheirKeysAsForADecoder() {
        stream.keys = false
        renderer.handleMessage(MirrorAudioRenderer.MSG_SET_SILENT, true)
        enable()
        play(500)
        assertEquals(1, stream.formatReads)
        assertEquals(0, silent.samplesTaken)
        assertFalse(renderer.isReady)
        stream.keys = true
        renderer.render(position(), 0)
        assertTrue(renderer.isReady)
        assertTrue(silent.samplesTaken > 0)
    }

    @Test fun theStreamEndIsTheSongEnd() {
        renderer.handleMessage(MirrorAudioRenderer.MSG_SET_SILENT, true)
        enable()
        play(9_000)
        assertFalse(renderer.isEnded)
        play(1_200)
        assertTrue(renderer.isEnded)
        assertEquals(frames.size, silent.samplesTaken)
    }

    @Test fun unmutedMidSongTheDecoderTakesOverWhereTheSilentRendererWas() {
        renderer.handleMessage(MirrorAudioRenderer.MSG_SET_SILENT, true)
        enable()
        play(3_000)
        val at = position()
        renderer.handleMessage(MirrorAudioRenderer.MSG_SET_SILENT, false)
        play(10)
        assertFalse(renderer.isSilent)
        assertEquals(1, decoder.enables)
        // The decoder starts from the sample at the position, not later: nothing skipped.
        assertTrue(decoder.firstSampleUs in (at - 30_000)..(at + 30_000))
        // Its audio is not out yet: the position holds still until the decoder reaches it...
        play(100)
        val held = position()
        play(100)
        assertEquals(held, position())
        // ...then follows the decoder's.
        decoder.heardUs = held + 50_000
        play(10)
        assertEquals(held + 50_000, position())
    }

    @Test fun mutedMidSongTheDecoderStopsAndThePositionGoesOn() {
        enable()
        play(10)
        decoder.heardUs = 1_000_000
        play(10)
        assertEquals(1_000_000, position())
        val readAhead = decoder.lastSampleUs
        renderer.handleMessage(MirrorAudioRenderer.MSG_SET_SILENT, true)
        play(10)
        assertTrue(renderer.isSilent)
        assertEquals(1, decoder.disables)
        play(990)
        // From the decoder's place, at the playback pace.
        assertEquals(2_000_000.0, position().toDouble(), 20_000.0)
        // The samples the decoder had read ahead are not taken twice; the next ones are taken at their time.
        assertTrue(silent.samplesTaken > 0)
        assertEquals(frames.count { it in (readAhead + 1)..position() }, silent.samplesTaken)
    }

    @Test fun aSeekWhileMutedMovesTheSilentClock() {
        renderer.handleMessage(MirrorAudioRenderer.MSG_SET_SILENT, true)
        enable()
        play(1_000)
        stream.seekTo(6_000_000)
        renderer.resetPosition(6_000_000)
        play(500)
        assertEquals(6_500_000.0, position().toDouble(), 20_000.0)
    }

    private companion object {
        val AAC: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(44_100).build()
    }

    private class FakeClock : Clock {
        var nowMs = 1_000L
        override fun currentTimeMillis() = nowMs
        override fun elapsedRealtime() = nowMs
        override fun uptimeMillis() = nowMs
        override fun nanoTime() = nowMs * 1_000_000
        override fun createHandler(looper: Looper, callback: Handler.Callback?): HandlerWrapper = throw UnsupportedOperationException()
        override fun onThreadBlocked() {}
    }

    /** A sample queue's behaviour: format first (or when required), encrypted samples only with keys, peek and data-less reads. */
    private class FakeStream(private val times: LongArray) : SampleStream {
        var keys = true
        var index = 0
        private var formatSent = false
        val reads = ArrayList<Int>()
        var formatReads = 0
        var dataReads = 0

        fun seekTo(us: Long) { index = times.indexOfFirst { it >= us }.let { if (it < 0) times.size else it } }

        override fun isReady() = keys && index <= times.size
        override fun maybeThrowError() {}
        override fun skipData(positionUs: Long) = 0

        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            reads += readFlags
            if (!formatSent || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                formatSent = true
                formatReads++
                formatHolder.format = AAC
                return C.RESULT_FORMAT_READ
            }
            if (!keys) return C.RESULT_NOTHING_READ
            if (index >= times.size) { buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM); return C.RESULT_BUFFER_READ }
            buffer.timeUs = times[index]
            if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) dataReads++
            if (readFlags and SampleStream.FLAG_PEEK == 0) index++
            return C.RESULT_BUFFER_READ
        }
    }

    /** Stands for MediaCodecAudioRenderer: reads samples half a second ahead (its buffers), its clock is what was heard. */
    private class FakeDecoder : BaseRenderer(C.TRACK_TYPE_AUDIO), MediaClock {
        var enables = 0; var disables = 0; var renders = 0
        var heardUs = 0L
        var firstSampleUs = C.TIME_UNSET
        var lastSampleUs = C.TIME_UNSET
        private var formatRead = false
        private val holder = FormatHolder()
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var params = PlaybackParameters.DEFAULT

        override fun getName() = "FakeDecoder"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_HANDLED)
        override fun getMediaClock(): MediaClock = this
        override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) { enables++; formatRead = false; firstSampleUs = C.TIME_UNSET }
        override fun onDisabled() { disables++ }
        override fun onPositionReset(positionUs: Long, joining: Boolean) { heardUs = positionUs }

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            renders++
            if (!formatRead) {
                holder.clear(); buffer.clear()
                if (readSource(holder, buffer, SampleStream.FLAG_REQUIRE_FORMAT) != C.RESULT_FORMAT_READ) return
                formatRead = true
            }
            while (lastSampleUs == C.TIME_UNSET || lastSampleUs < positionUs + 500_000) {
                holder.clear(); buffer.clear()
                if (readSource(holder, buffer, 0) != C.RESULT_BUFFER_READ || buffer.isEndOfStream) return
                if (firstSampleUs == C.TIME_UNSET) firstSampleUs = buffer.timeUs
                lastSampleUs = buffer.timeUs
            }
        }

        override fun isReady() = formatRead
        override fun isEnded() = false
        override fun getPositionUs() = heardUs
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { params = playbackParameters }
        override fun getPlaybackParameters() = params
    }
}
