package com.music.spotui.ui.components

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import app.myspoti.engine.R
import com.music.spotui.cast.CastPlayback
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.data.preferences.CrossfadePref
import com.music.spotui.data.preferences.StreamingQuality
import com.music.spotui.di.ConnectMirror
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.extensions.ExtensionAudio
import com.music.spotui.extensions.Extensions
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * "Stats for nerds": what the app knows of the sound that plays, read again every second while the
 * sheet is open (and not at all otherwise: the reading lives in the sheet's composition). For
 * Myspoti's own player: the source, the format the decoder reports, the bit rate, the buffer, the
 * output and the processing. For Spotify: what the page announces and the mirror; the stream itself
 * is encrypted in the page, so nothing more is claimed. The sources asked and the quality announced
 * are told in full by "Why this source?" ([WhySourceSheet]), opened from the player's source line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsForNerdsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sections by produceState(emptyList<NerdSection>()) {
        while (true) {
            value = NerdStats.read(context)
            delay(1_000L)
        }
    }
    LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.nerd_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            sections.forEach { s ->
                Text(s.title, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp))
                s.rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp)) {
                        Text(label, color = AppMuted, fontSize = 13.sp, modifier = Modifier.weight(0.4f).padding(end = 12.dp))
                        Text(value, color = AppText, fontSize = 14.sp, modifier = Modifier.weight(0.6f))
                    }
                }
                s.note?.let { Text(it, color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
            }
        }
    }
}

/** A block of the sheet: its [title], its (label, value) [rows] and a closing [note]. */
internal class NerdSection(val title: String, val rows: List<Pair<String, String>>, val note: String? = null)

internal object NerdStats {
    /** What is read, for both players; on the main thread (ExoPlayer's), with one call into the page. */
    suspend fun read(context: Context): List<NerdSection> = buildList {
        val local = LocalPlayer.active.value
        if (local) add(exoSection(context))
        add(spotifySection(context, local))
    }

    private fun exoSection(c: Context): NerdSection {
        val rows = ArrayList<Pair<String, String>>()
        fun row(label: Int, value: String?) { if (!value.isNullOrBlank()) rows += c.getString(label) to value }
        val t = LocalPlayer.current()
        val id = t?.uri?.takeIf { it.scheme == ExtensionAudio.SCHEME }?.lastPathSegment
        val download = t?.let { OfflineStore.byLocalId(it.id) }
        // The source, as the player's source line and the "why" page know it.
        row(R.string.nerd_source, when {
            id != null -> ExtensionAudio.streamedBy(t)?.let { c.getString(R.string.nerd_source_extension, it) } ?: c.getString(R.string.nerd_source_extension_waiting)
            download != null -> c.getString(R.string.nerd_source_download, listOfNotNull(download.file.substringAfterLast('.', "").uppercase().ifBlank { null },
                download.source.ifBlank { null }, download.providerId?.let { Extensions.registry.value.find(it)?.name }).joinToString(" · "))
            t != null -> c.getString(R.string.nerd_source_file)
            else -> null
        })
        val stream = id?.let(ExtensionAudio::streamOf)
        row(R.string.nerd_delivery, id?.let(ExtensionAudio::deliveryOf))
        row(R.string.nerd_announced, id?.let(ExtensionAudio::qualityOf))
        // The extension's own quality setting (manifest role quality) when it played or fetched the song.
        row(R.string.nerd_ext_quality, id?.let { Extensions.attempts.latest(it)?.sourceQuality } ?: download?.extensionQuality)
        val f = LocalPlayer.audioFormat()
        if (f == null) {
            if (!CastPlayback.active) row(R.string.nerd_codec, c.getString(R.string.nerd_format_unknown))
        } else {
            val mime = f.sampleMimeType ?: f.codecs
            val codecs = f.codecs?.takeIf { it != mime }?.let { " ($it)" }.orEmpty()
            row(R.string.nerd_codec, mime?.let { codecName(it) + codecs + (if (it.contains('/')) " · $it" else "") })
            row(R.string.nerd_container, f.containerMimeType)
            row(R.string.nerd_sample_rate, f.sampleRate.takeIf { it > 0 }?.let(::khz))
            row(R.string.nerd_bit_depth, bits(c, f.pcmEncoding) ?: stream?.bitDepth?.let { c.getString(R.string.nerd_bit_depth_announced, it) })
            row(R.string.nerd_channels, f.channelCount.takeIf { it > 0 }?.toString())
        }
        val kbps = (f?.bitrate?.takeIf { it > 0 } ?: f?.averageBitrate?.takeIf { it > 0 })?.let { c.getString(R.string.nerd_bitrate_value, it / 1000) }
            ?: download?.takeIf { it.bytes > 0 }?.let { d ->
                val ms = LocalPlayer.knownLength() ?: t.durationMs
                if (ms > 0) c.getString(R.string.nerd_bitrate_average, (d.bytes * 8 / ms).toInt()) else null
            }
        row(R.string.nerd_bitrate, kbps)
        row(R.string.nerd_file_size, download?.bytes?.takeIf { it > 0 }?.let { android.text.format.Formatter.formatShortFileSize(c, it) })
        row(R.string.nerd_buffer, LocalPlayer.bufferedAheadMs()?.let { c.getString(R.string.nerd_buffer_value, it / 1000.0) })
        row(R.string.nerd_state, LocalPlayer.playbackState())
        rows += outputRows(c)
        row(R.string.nerd_processing, listOfNotNull(
            CrossfadePref.seconds.takeIf { it > 0 }?.let { c.getString(R.string.nerd_crossfade, it) },
            if (LocalPlayer.fadeProgress() < 1f) c.getString(R.string.nerd_fading) else null,
            com.music.spotui.data.preferences.EqualizerPref.takeIf { it.enabled }?.let {
                c.getString(R.string.nerd_equalizer, c.getString(com.music.spotui.ui.screens.eqPresetLabel(it.preset)))
            },
            com.music.spotui.data.preferences.EqualizerPref.speed.takeIf { it != 1f }?.let { c.getString(R.string.nerd_speed, speedLabel(it.toDouble())) },
            if (com.music.spotui.data.preferences.EqualizerPref.skipSilence) c.getString(R.string.nerd_skip_silence) else null,
        ).joinToString(" · ").ifBlank { c.getString(R.string.nerd_processing_none) })
        return NerdSection(c.getString(R.string.nerd_section_exo), rows)
    }

    /** The bit depth a PCM encoding says, or null (a compressed format, or none reported). */
    private fun bits(c: Context, encoding: Int): String? = when (encoding) {
        C.ENCODING_PCM_8BIT -> c.getString(R.string.nerd_bit_depth_value, 8)
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> c.getString(R.string.nerd_bit_depth_value, 16)
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> c.getString(R.string.nerd_bit_depth_value, 24)
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> c.getString(R.string.nerd_bit_depth_value, 32)
        C.ENCODING_PCM_FLOAT -> c.getString(R.string.nerd_bit_depth_float)
        else -> null
    }

    private fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(Locale.US, hz / 1000.0)

    /**
     * Where the sound goes: the speaker, Bluetooth, USB or wired headphones (with the device's name
     * when Android gives it), the highest rate a USB device says it takes, and the rate Android's
     * mixer runs at. Android does not tell an app the Bluetooth codec: it is not shown.
     */
    private fun outputRows(c: Context): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        val cast = CastPlayback.device.value?.takeIf { CastPlayback.active }
        if (cast != null) return listOf(c.getString(R.string.nerd_output) to c.getString(R.string.nerd_output_cast, cast.name))
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val usb = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
        val route = AudioRoute.current(c)
        val kindLabel = when {
            route.kind == AudioRoute.Kind.BLUETOOTH -> R.string.nerd_output_bluetooth
            usb != null -> R.string.nerd_output_usb
            route.kind == AudioRoute.Kind.WIRED -> R.string.nerd_output_wired
            else -> R.string.nerd_output_speaker
        }
        val device = usb.takeIf { kindLabel == R.string.nerd_output_usb }
        val name = if (device != null) runCatching { device.productName?.toString().orEmpty() }.getOrDefault("") else route.name
        val kind = c.getString(kindLabel)
        rows += c.getString(R.string.nerd_output) to (if (name.isBlank()) kind else c.getString(R.string.nerd_output_named, kind, name))
        device?.sampleRates?.maxOrNull()?.takeIf { it > 0 }?.let { rows += c.getString(R.string.nerd_output_max_rate) to khz(it) }
        am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.let { rows += c.getString(R.string.nerd_mixer_rate) to khz(it) }
        return rows
    }

    private suspend fun spotifySection(c: Context, local: Boolean): NerdSection {
        val rows = ArrayList<Pair<String, String>>()
        val title = c.getString(R.string.nerd_section_spotify)
        val remote = SongPlayer.activeDevice.value
        if (remote != null) {
            rows += c.getString(R.string.nerd_device) to remote.name
            return NerdSection(title, rows, c.getString(R.string.nerd_spotify_remote))
        }
        val page = app.myspoti.host.android.MyspotiRuntime.spotify.player
        rows += c.getString(R.string.nerd_device) to c.getString(R.string.nerd_device_this_phone)
        val extension = Extensions.mirrorBy.value
        rows += c.getString(R.string.nerd_mirror) to when {
            extension != null -> c.getString(R.string.nerd_mirror_extension, extension)
            ConnectMirror.running -> c.getString(R.string.nerd_mirror_on)
            else -> c.getString(R.string.nerd_mirror_off)
        }
        // The level the page is given (the lowest one while it only mirrors), and what it says it plays aloud.
        rows += c.getString(R.string.nerd_requested) to c.getString(streamingLabel(StreamingQuality.of(page.streamingLevel)))
        if (!local) {
            val (bitrate, mime) = page.audioInfo()
            rows += c.getString(R.string.nerd_page_reports) to
                if (bitrate > 0) "${codecName(mime)} · ${bitrate / 1000} kbit/s" else c.getString(R.string.nerd_page_reports_none)
        }
        return NerdSection(title, rows, c.getString(R.string.nerd_spotify_note))
    }
}
