package app.myspoti.engine.android

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * A real ExoPlayer with the engine's audio renderer, muted: the song is loaded as it plays (the
 * load control paces it on the position, as with a decoder), the position runs with the time, the
 * song ends, and the decoding renderer is never used.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MirrorPlayerLoadTest {
    private val seconds = 30
    private val rate = 8_000
    private val wav = wav(seconds, rate)
    private var loaded = 0L
    private val decoder = FakeDecoderForPlayer()
    private val mirror = MirrorAudioRenderer(decoder)
    private lateinit var player: ExoPlayer

    @After fun release() { if (::player.isInitialized) player.release() }

    private fun advance(ms: Long) {
        repeat((ms / 10).toInt()) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(10))
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(player.playbackLooper).idle()
            Thread.sleep(0, 200_000)
        }
    }

    @Test fun mutedTheSongIsLoadedAsItPlaysAndEnds() {
        val source = DataSource.Factory { Counting(ByteArrayDataSource(wav)) { loaded += it } }
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(), RenderersFactory { _, _, _, _, _ -> arrayOf(mirror) })
            .setLooper(Looper.getMainLooper())
            // The engine's pacing, scaled to this small file: a few seconds ahead, checked often.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 3_000, 500, 500).build())
            .build()
        player.createMessage(mirror).setType(MirrorAudioRenderer.MSG_SET_SILENT).setPayload(true).send()
        player.setMediaSource(ProgressiveMediaSource.Factory(source).setContinueLoadingCheckIntervalBytes(4 * 1024)
            .createMediaSource(MediaItem.fromUri(Uri.parse("myspoti-test://song.wav"))))
        player.prepare()
        player.play()
        advance(2_000)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.isPlaying)
        val bytesPerSecond = rate * 2L
        // Loaded ahead of the position, but not the whole song: the load control paces it.
        val early = loaded
        assertTrue("loaded $early", early < wav.size / 2)
        assertTrue("loaded $early", early >= player.currentPosition / 1000 * bytesPerSecond)
        advance(10_000)
        // The position ran with the time, and the loading went on with it.
        assertEquals(12_000.0, player.currentPosition.toDouble(), 300.0)
        assertTrue("loaded $loaded", loaded > early + 8 * bytesPerSecond)
        assertTrue("loaded $loaded", loaded >= player.currentPosition / 1000 * bytesPerSecond)
        advance(19_000)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertEquals(wav.size.toLong(), loaded)
        // Played through without the decoding renderer.
        assertEquals(0, decoder.enables)
        assertTrue(mirror.isSilent)
    }

    @Test fun unmutedMidSongTheDecoderTakesTheSongOnWithoutAJump() {
        val source = DataSource.Factory { Counting(ByteArrayDataSource(wav)) { loaded += it } }
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(), RenderersFactory { _, _, _, _, _ -> arrayOf(mirror) })
            .setLooper(Looper.getMainLooper()).build()
        player.createMessage(mirror).setType(MirrorAudioRenderer.MSG_SET_SILENT).setPayload(true).send()
        player.setMediaSource(ProgressiveMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(Uri.parse("myspoti-test://song.wav"))))
        player.prepare()
        player.play()
        advance(5_000)
        val before = player.currentPosition
        // (The start waits for the loader's threads, which run in real time: the position is read, not assumed.)
        assertTrue("at $before", before > 2_000)
        assertTrue(player.isPlaying)
        player.createMessage(mirror).setType(MirrorAudioRenderer.MSG_SET_SILENT).setPayload(false).send()
        advance(300)
        assertTrue(!mirror.isSilent)
        assertEquals(1, decoder.enables)
        // The decoder starts at the sample of the position: nothing skipped, nothing played twice.
        val first = decoder.firstSampleUs / 1000
        assertTrue("first sample at $first ms, position $before ms", first in (before - 150)..(before + 400))
        // Its clock (here, what it has read) takes over from there: the position does not go back.
        val after = player.currentPosition
        assertTrue("$before -> $after", after >= before)
        assertTrue(player.isPlaying)
    }

    /** A mono 16-bit PCM WAV of silence. */
    private fun wav(seconds: Int, rate: Int): ByteArray {
        val data = seconds * rate * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        return b.array()
    }

    private class Counting(private val inner: DataSource, private val count: (Int) -> Unit) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec) = inner.open(dataSpec)
        override fun read(buffer: ByteArray, offset: Int, length: Int) = inner.read(buffer, offset, length).also { if (it > 0) count(it) }
        override fun getUri(): Uri? = inner.uri
        override fun close() = inner.close()
    }
}

/**
 * Stands for the decoding renderer in a real player: reads half a second ahead (its buffers), and
 * its clock is what it has played: from its reset position, at the pace of the time once its
 * first sample is in.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class FakeDecoderForPlayer : androidx.media3.exoplayer.BaseRenderer(androidx.media3.common.C.TRACK_TYPE_AUDIO), androidx.media3.exoplayer.MediaClock {
    var enables = 0
    var firstSampleUs = androidx.media3.common.C.TIME_UNSET
    private var resetUs = 0L
    private var startedAtUs = androidx.media3.common.C.TIME_UNSET
    private var nowUs = 0L
    private var formatRead = false
    private var ended = false
    private val holder = androidx.media3.exoplayer.FormatHolder()
    private val buffer = androidx.media3.decoder.DecoderInputBuffer(androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private var params = androidx.media3.common.PlaybackParameters.DEFAULT

    override fun getName() = "FakeDecoderForPlayer"
    override fun supportsFormat(format: androidx.media3.common.Format) = androidx.media3.exoplayer.RendererCapabilities.create(androidx.media3.common.C.FORMAT_HANDLED)
    override fun getMediaClock(): androidx.media3.exoplayer.MediaClock = this
    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) { enables++; formatRead = false; firstSampleUs = androidx.media3.common.C.TIME_UNSET }
    override fun onPositionReset(positionUs: Long, joining: Boolean) { resetUs = positionUs; startedAtUs = androidx.media3.common.C.TIME_UNSET; ended = false }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        nowUs = elapsedRealtimeUs
        if (!formatRead) {
            holder.clear(); buffer.clear()
            if (readSource(holder, buffer, androidx.media3.exoplayer.source.SampleStream.FLAG_REQUIRE_FORMAT) != androidx.media3.common.C.RESULT_FORMAT_READ) return
            formatRead = true
        }
        // Its buffers: up to half a second past the player's position.
        while (true) {
            holder.clear(); buffer.clear()
            if (readSource(holder, buffer, androidx.media3.exoplayer.source.SampleStream.FLAG_PEEK) != androidx.media3.common.C.RESULT_BUFFER_READ) return
            if (buffer.isEndOfStream) { ended = true; return }
            if (buffer.timeUs > positionUs + 500_000) return
            holder.clear(); buffer.clear()
            readSource(holder, buffer, 0)
            // In the song's time (the player's renderer time has an offset).
            if (firstSampleUs == androidx.media3.common.C.TIME_UNSET) firstSampleUs = buffer.timeUs - streamOffsetUs
            if (startedAtUs == androidx.media3.common.C.TIME_UNSET) startedAtUs = elapsedRealtimeUs
        }
    }

    override fun isReady() = formatRead && isSourceReady
    override fun isEnded() = ended
    override fun getPositionUs() = if (startedAtUs == androidx.media3.common.C.TIME_UNSET) resetUs else resetUs + (nowUs - startedAtUs)
    override fun setPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) { params = playbackParameters }
    override fun getPlaybackParameters() = params
}
