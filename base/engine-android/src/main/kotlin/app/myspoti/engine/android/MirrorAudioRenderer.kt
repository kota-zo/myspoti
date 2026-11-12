// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine.android

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.StandaloneMediaClock
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream

/**
 * The engine's one audio renderer: the decoding one ([audible], Media3's MediaCodecAudioRenderer)
 * while the engine is heard, a [SilentAudioRenderer] while it is a muted listen mirror
 * ([MSG_SET_SILENT]). A mirror's song is never heard, yet it was decrypted, decoded and played at
 * volume 0 (about a sixth of a core for the whole listen, measured on a phone); silent, the song is
 * still loaded, licensed and played through at its pace, but nothing is decrypted or decoded and no
 * AudioTrack is open.
 *
 * What the account sees does not change: the CDN ranges come from the player's load control, which
 * follows the playback position (the same in both modes), the licence is asked when the stream's
 * format is read (the sample queue acquires the DRM session then, and holds encrypted samples back
 * until its keys are there, whoever reads them), and the reports come from the player's states and
 * positions, which the silent renderer keeps as the decoding one does: ready once samples can be
 * read, ended at the stream's end, its position on a clock that runs at the playback speed.
 *
 * A switch happens at the next [render], on the player's thread, between two renderers given the
 * same stream at the same position: the decoding one takes the samples from where the silent one
 * stopped (the one at the position), the silent one from where the decoder stopped reading (its
 * buffers ahead are dropped, the song goes on). The position never jumps: going silent, the clock
 * starts from the decoder's position; going heard, it holds still until the decoder's audio has
 * caught up with it (as Media3 does when a renderer's clock takes over).
 */
@OptIn(UnstableApi::class)
class MirrorAudioRenderer(
    private val audible: Renderer,
    private val silent: SilentAudioRenderer = SilentAudioRenderer(),
) : Renderer {
    private val audibleClock: MediaClock = requireNotNull(audible.mediaClock) { "the audible renderer must give the clock" }
    private val clock = SwitchClock()

    /** Silent asked ([MSG_SET_SILENT]); applied at the next render. Player's thread. */
    private var silentWanted = false
    private var active: Renderer = audible
    private var state = Renderer.STATE_DISABLED

    // What the player gave at enable and after, to enable the other renderer the same way.
    private var configuration: RendererConfiguration? = null
    private var formats: Array<Format> = emptyArray()
    private var stream: SampleStream? = null
    private var startPositionUs = 0L
    private var offsetUs = 0L
    private var periodId: MediaSource.MediaPeriodId? = null
    private var mayRenderStartOfStream = false
    private var streamFinal = false
    private var speed = 1f
    private var targetSpeed = 1f
    private lateinit var standalone: StandaloneMediaClock

    /** True while the silent renderer plays (for tests and logs). */
    val isSilent: Boolean get() = active === silent

    override fun getName() = "MirrorAudioRenderer"
    override fun getTrackType() = C.TRACK_TYPE_AUDIO
    override fun getCapabilities(): RendererCapabilities = audible.capabilities

    override fun init(index: Int, playerId: PlayerId, clock: Clock) {
        audible.init(index, playerId, clock)
        silent.init(index, playerId, clock)
        standalone = StandaloneMediaClock(clock)
    }

    override fun getMediaClock(): MediaClock = clock
    override fun getState() = state

    override fun enable(
        configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream, positionUs: Long,
        joining: Boolean, mayRenderStartOfStream: Boolean, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        this.configuration = configuration
        this.formats = formats
        this.stream = stream
        this.startPositionUs = startPositionUs
        this.offsetUs = offsetUs
        this.periodId = mediaPeriodId
        this.mayRenderStartOfStream = mayRenderStartOfStream
        streamFinal = false
        active = if (silentWanted) silent else audible
        active.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
        clock.reset(positionUs)
        state = Renderer.STATE_ENABLED
    }

    override fun start() {
        active.start()
        clock.start()
        state = Renderer.STATE_STARTED
    }

    override fun replaceStream(formats: Array<Format>, stream: SampleStream, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        this.formats = formats
        this.stream = stream
        this.startPositionUs = startPositionUs
        this.offsetUs = offsetUs
        this.periodId = mediaPeriodId
        streamFinal = false
        active.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun getStream(): SampleStream? = active.stream
    override fun hasReadStreamToEnd() = active.hasReadStreamToEnd()
    override fun getReadingPositionUs() = active.readingPositionUs
    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long) = active.getDurationToProgressUs(positionUs, elapsedRealtimeUs)
    override fun setCurrentStreamFinal() { streamFinal = true; active.setCurrentStreamFinal() }
    override fun isCurrentStreamFinal() = active.isCurrentStreamFinal
    override fun maybeThrowStreamError() = active.maybeThrowStreamError()

    override fun resetPosition(positionUs: Long) {
        active.resetPosition(positionUs)
        clock.reset(positionUs)
    }

    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        speed = currentPlaybackSpeed
        targetSpeed = targetPlaybackSpeed
        active.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    }

    override fun enableMayRenderStartOfStream() {
        mayRenderStartOfStream = true
        active.enableMayRenderStartOfStream()
    }

    override fun setTimeline(timeline: Timeline) {
        audible.setTimeline(timeline)
        silent.setTimeline(timeline)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (state != Renderer.STATE_DISABLED && silentWanted != isSilent) switchTo(if (silentWanted) silent else audible, positionUs)
        active.render(positionUs, elapsedRealtimeUs)
    }

    /** The other renderer takes the stream at [positionUs], started if the player plays. */
    private fun switchTo(next: Renderer, positionUs: Long) {
        val previous = active
        val wasStarted = state == Renderer.STATE_STARTED
        val clockAt = clock.positionUs
        if (previous.state == Renderer.STATE_STARTED) previous.stop()
        if (previous.state != Renderer.STATE_DISABLED) previous.disable()
        active = next
        next.enable(configuration!!, formats, stream!!, positionUs, false, mayRenderStartOfStream, startPositionUs, offsetUs, periodId!!)
        if (streamFinal) next.setCurrentStreamFinal()
        next.setPlaybackSpeed(speed, targetSpeed)
        clock.switched(maxOf(clockAt, positionUs))
        if (wasStarted) { next.start(); clock.start() }
    }

    override fun isReady() = active.isReady
    override fun isEnded() = active.isEnded

    override fun stop() {
        if (active.state == Renderer.STATE_STARTED) active.stop()
        clock.stop()
        state = Renderer.STATE_ENABLED
    }

    override fun disable() {
        if (active.state != Renderer.STATE_DISABLED) active.disable()
        clock.stop()
        stream = null
        state = Renderer.STATE_DISABLED
    }

    override fun reset() {
        audible.reset()
        silent.reset()
    }

    override fun release() {
        audible.release()
        silent.release()
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == MSG_SET_SILENT) { silentWanted = message == true; return }
        // Volume, attributes, session, effects: the decoder's sink keeps them while it is idle.
        audible.handleMessage(messageType, message)
    }

    /**
     * The position: the decoder's while it is heard, a standalone clock (elapsed time × speed)
     * while silent. After a switch to the decoder the standalone clock holds still until the
     * decoder's position reaches it, so the position never goes back.
     */
    private inner class SwitchClock : MediaClock {
        private var handingOver = false
        private var started = false

        fun reset(positionUs: Long) {
            standalone.resetPosition(positionUs)
            handingOver = false
        }

        fun start() {
            started = true
            if (isSilent || handingOver) standalone.start()
        }

        fun stop() {
            started = false
            standalone.stop()
        }

        /** The renderer just changed, the position being [positionUs]. */
        fun switched(positionUs: Long) {
            standalone.resetPosition(positionUs)
            handingOver = !isSilent
            if (!started) standalone.stop()
        }

        override fun getPositionUs(): Long {
            if (isSilent) return standalone.positionUs
            if (handingOver) {
                val heard = audibleClock.positionUs
                if (heard < standalone.positionUs) {
                    standalone.stop()
                    return standalone.positionUs
                }
                handingOver = false
                return heard
            }
            return audibleClock.positionUs
        }

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            standalone.playbackParameters = playbackParameters
            audibleClock.playbackParameters = playbackParameters
        }

        override fun getPlaybackParameters(): PlaybackParameters =
            if (isSilent) standalone.playbackParameters else audibleClock.playbackParameters

        override fun hasSkippedSilenceSinceLastCall(): Boolean = !isSilent && audibleClock.hasSkippedSilenceSinceLastCall()
    }

    companion object {
        /** Payload true: silent (a muted mirror), false: decoded and heard. */
        const val MSG_SET_SILENT = Renderer.MSG_CUSTOM_BASE + 0x6d
    }
}

/**
 * Plays an audio stream through without decoding it: the format is read first (which makes the
 * sample queue acquire the DRM session, so the licence is asked as for a decoder), then each
 * sample is taken off the stream once the playback position has reached it, without its data
 * (nothing copied, nothing decrypted). Ready when the next sample can be read (encrypted samples
 * only once their keys are there) or one is already held ahead of the position; ended once the
 * stream's end was read and the position has passed the last sample.
 */
@OptIn(UnstableApi::class)
class SilentAudioRenderer : BaseRenderer(C.TRACK_TYPE_AUDIO) {
    private val formatHolder = FormatHolder()
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
    private var formatRead = false
    private var inputEnded = false
    /** The last sample taken off the stream (renderer time), C.TIME_UNSET before any. */
    private var lastSampleUs = C.TIME_UNSET
    private var positionUs = 0L

    /** Samples taken so far (tests). */
    var samplesTaken = 0
        private set

    override fun getName() = "SilentAudioRenderer"

    override fun supportsFormat(format: Format): Int =
        RendererCapabilities.create(if (androidx.media3.common.MimeTypes.isAudio(format.sampleMimeType)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        this.positionUs = positionUs
        inputEnded = false
        lastSampleUs = C.TIME_UNSET
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        formatRead = false
    }

    override fun onDisabled() {
        formatRead = false
        inputEnded = false
        lastSampleUs = C.TIME_UNSET
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        this.positionUs = positionUs
        if (!formatRead) {
            formatHolder.clear()
            buffer.clear()
            if (readSource(formatHolder, buffer, SampleStream.FLAG_REQUIRE_FORMAT) != C.RESULT_FORMAT_READ) return
            formatRead = true
        }
        while (!inputEnded) {
            formatHolder.clear()
            buffer.clear()
            // A look first: a sample ahead of the position stays on the stream until its time.
            when (readSource(formatHolder, buffer, SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA)) {
                C.RESULT_FORMAT_READ -> continue
                C.RESULT_NOTHING_READ -> return
            }
            if (!buffer.isEndOfStream && buffer.timeUs > positionUs) return
            formatHolder.clear()
            buffer.clear()
            if (readSource(formatHolder, buffer, SampleStream.FLAG_OMIT_SAMPLE_DATA) != C.RESULT_BUFFER_READ) return
            if (buffer.isEndOfStream) inputEnded = true
            else { lastSampleUs = buffer.timeUs; samplesTaken++ }
        }
    }

    override fun isReady(): Boolean = formatRead && (isSourceReady || inputEnded)

    override fun isEnded(): Boolean = inputEnded && (lastSampleUs == C.TIME_UNSET || positionUs >= lastSampleUs)
}
