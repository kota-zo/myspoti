package com.music.spotui.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.music.spotui.data.api.InviteLinks

/**
 * Descriptions (show, episode, audiobook, artist bio, playlist) with their links made tappable.
 * Same text, same layout: the [AnnotatedString] goes to the page's own `Text`, so lines, "more"
 * toggles and truncation stay as they were. A Spotify link (open.spotify.com/…, spotify:…) to a
 * playlist, album, artist or show opens its page in the app; any other link opens in the browser.
 */
private val LINK = Regex(
    """(?:https?://|www\.)[^\s<>"']+|spotify:(?:playlist|album|artist|show|track|episode):[A-Za-z0-9]{22}""",
    RegexOption.IGNORE_CASE,
)
private val PAGE_LINK = Regex(
    """(?:open|www|play)\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album|artist|show)/([A-Za-z0-9]{22})""",
    RegexOption.IGNORE_CASE,
)
private val URI = Regex("""spotify:(playlist|album|artist|show|track|episode):([A-Za-z0-9]{22})""", RegexOption.IGNORE_CASE)

/** [text] with every link in it tappable, in [linkColor] and underlined. */
@Composable
fun rememberLinkedText(text: String, linkColor: Color): AnnotatedString {
    val context = LocalContext.current
    return remember(text, linkColor) {
        if (!LINK.containsMatchIn(text)) return@remember AnnotatedString(text)
        val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            var last = 0
            for (hit in LINK.findAll(text)) {
                // Sentence punctuation after a link is not part of it.
                val link = hit.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
                append(text.substring(last, hit.range.first))
                withLink(LinkAnnotation.Clickable("link", styles) { openDescriptionLink(context, link) }) { append(link) }
                last = hit.range.first + link.length
            }
            append(text.substring(last))
        }
    }
}

/** A tapped link: a Spotify list / artist / show page or invitation in the app, anything else in the browser. */
fun openDescriptionLink(context: android.content.Context, link: String) {
    val spotify = link.contains("spotify.com", ignoreCase = true) || link.contains("spotify.link", ignoreCase = true) || link.startsWith("spotify:", ignoreCase = true)
    if (spotify) {
        // A collaborative-playlist invitation, a Blend invitation or a short link.
        if (link.startsWith("http", ignoreCase = true) && InviteLinks.handle(link)) return
        val uri = URI.find(link)?.let { "spotify:${it.groupValues[1].lowercase()}:${it.groupValues[2]}" }
            ?: PAGE_LINK.find(link)?.let { "spotify:${it.groupValues[1].lowercase()}:${it.groupValues[2]}" }
        if (uri != null && uri.substringAfter(':').substringBefore(':') in setOf("playlist", "album", "artist", "show")) {
            InviteLinks.page.value = uri
            return
        }
    }
    // A song or an episode (spotify: uri) has no page here: its web address opens instead.
    val web = when {
        link.startsWith("spotify:", ignoreCase = true) -> URI.find(link)?.let { "https://open.spotify.com/${it.groupValues[1].lowercase()}/${it.groupValues[2]}" }
        link.startsWith("www.", ignoreCase = true) -> "https://$link"
        else -> link
    } ?: return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
