package com.music.spotui.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import app.myspoti.extensions.PlayAttempt
import com.music.spotui.extensions.Extensions
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone

/**
 * "Why this source?": the last [PlayAttempt] of the song [trackId] through the extensions: the
 * sources asked and how each ended (and in how long), the ones skipped and why, the one chosen,
 * how its address opened, the quality it announced against the one the player [detected], the
 * fallback and the last error. Addresses are shown without their query.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhySourceSheet(trackId: String, detected: String?, onDismiss: () -> Unit) {
    val a = remember(trackId) {
        if (detected != null) Extensions.noteAttempt(trackId) { it.copy(detected = detected) }
        Extensions.attempts.latest(trackId)
    }
    LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.why_source_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (a == null) {
                Line(stringResource(R.string.why_source_none), muted = true)
                return@Column
            }
            Head(stringResource(R.string.why_source_asked))
            a.asked.forEach { s ->
                val name = nameOf(s.extension)
                val what = stringResource(R.string.why_source_ms, outcomeLabel(s.outcome), s.ms)
                Line("$name — " + (s.skippedReason?.let { stringResource(R.string.why_source_skipped, PlayAttempt.maskText(it)) } ?: what) +
                    (s.detail?.let { " (${PlayAttempt.maskText(it)})" } ?: ""))
            }
            Line(a.chosen?.let { stringResource(R.string.why_source_chosen, nameOf(it)) } ?: stringResource(R.string.why_source_none_chosen))
            a.open?.let { o ->
                val http = o.httpStatus?.let { " · " + stringResource(R.string.why_source_http, it) } ?: ""
                Line(if (o.ok) stringResource(R.string.why_source_open_ok, o.ms) + http
                    else stringResource(R.string.why_source_open_failed, o.ms, PlayAttempt.maskText(o.error ?: "?")) + http)
            }
            a.announced?.let { Line(stringResource(R.string.why_source_announced, it)) }
            (detected ?: a.detected)?.let { Line(stringResource(R.string.why_source_detected, it)) }
            a.fallback?.let { Line(stringResource(R.string.why_source_fallback, PlayAttempt.maskText(it)), muted = true) }
            a.error?.let { Line(stringResource(R.string.why_source_error, PlayAttempt.maskText(it)), muted = true) }
        }
    }
}

private fun nameOf(id: String): String = Extensions.registry.value.find(id)?.name ?: id

@Composable
private fun outcomeLabel(key: String): String = stringResource(when (key) {
    "found" -> R.string.outcome_found
    "not-found" -> R.string.outcome_not_found
    "sign-in" -> R.string.outcome_sign_in
    "rate-limited" -> R.string.outcome_rate_limited
    "quality-unavailable" -> R.string.outcome_quality_unavailable
    "server-down" -> R.string.outcome_server_down
    "network" -> R.string.outcome_network
    "skipped" -> R.string.outcome_skipped
    else -> R.string.outcome_bug
})

@Composable
private fun Head(text: String) = Text(text, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp))

@Composable
private fun Line(text: String, muted: Boolean = false) = Text(text, color = if (muted) AppMuted else AppText, fontSize = 14.sp,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp))

/** What the player's decoder reported ("audio/flac · 44.1 kHz · 2 ch"), or null: its input, not proof of a bit-perfect output. */
fun detectedLabel(f: androidx.media3.common.Format?): String? {
    f ?: return null
    val parts = listOfNotNull(
        f.sampleMimeType ?: f.codecs,
        f.sampleRate.takeIf { it > 0 }?.let { r -> if (r % 1000 == 0) "${r / 1000} kHz" else "%.1f kHz".format(java.util.Locale.US, r / 1000.0) },
        f.channelCount.takeIf { it > 0 }?.let { "$it ch" },
        f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbit/s" },
    )
    return parts.joinToString(" · ").ifBlank { null }
}
