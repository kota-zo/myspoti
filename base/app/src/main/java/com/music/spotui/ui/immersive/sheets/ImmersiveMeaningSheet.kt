package com.music.spotui.ui.immersive.sheets

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.api.GeniusMeaning
import com.music.spotui.ui.components.rememberLinkedText
import com.music.spotui.ui.components.rememberShown
import com.music.spotui.ui.components.showOriginal
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.immersiveClickable

/** What the sheet has to show: still being read, nothing on Genius, Genius out of reach, or the meaning. */
private sealed interface MeaningLoad {
    data object Loading : MeaningLoad
    data object None : MeaningLoad
    data object Failed : MeaningLoad
    class Ready(val meaning: GeniusMeaning.Meaning) : MeaningLoad
}

/**
 * Genius's words on an Immersive sheet in the song's colours (the song sheet's ground), from the
 * same [GeniusMeaning] the Classic look reads (nothing new on the network: the look-up begun when
 * the song started, or the cached one). [noteIndex] set: the explanation of one lyric line, under
 * that line quoted; null: the song as a whole ("About"). The text is read off the main thread and
 * keyed on [trackId].
 *
 * A note stops short of the top of a portrait screen, so the line being sung (which sits high on
 * the lyrics page) stays in sight above it; landscape has too little height to spare and takes more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveMeaningSheet(
    trackId: String,
    title: String,
    artist: String,
    noteIndex: Int?,
    coverUrl: String? = null,
    onDismiss: () -> Unit,
) {
    val load by produceState<MeaningLoad>(MeaningLoad.Loading, trackId, title, artist) {
        val ready = GeniusMeaning.current.value?.takeIf { it.first == trackId }?.second
            ?: GeniusMeaning.cached(title, artist)
        value = if (ready != null) MeaningLoad.Ready(ready) else try {
            // find() runs on IO and keeps its own small cache; it throws when Genius can't be reached.
            GeniusMeaning.find(title, artist)?.let { MeaningLoad.Ready(it) } ?: MeaningLoad.None
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MeaningLoad.Failed
        }
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fraction = when {
        landscape -> 0.9f
        noteIndex == null -> 0.8f
        configuration.smallestScreenWidthDp >= 600 -> 0.5f
        else -> 0.62f
    }
    ImmersiveSheet(onDismiss = onDismiss, imageUrl = coverUrl, maxHeightFraction = fraction) {
        val songLine = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · ")
        when (val l = load) {
            MeaningLoad.Loading -> {
                MeaningHeader(stringResource(R.string.lyrics_meaning_title), songLine, quoted = false)
                Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Immersive.TextSecondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
            MeaningLoad.None -> {
                MeaningHeader(stringResource(R.string.lyrics_meaning_title), songLine, quoted = false)
                ImmersiveSheetNote(stringResource(R.string.immersive_genius_none), Modifier.padding(bottom = 12.dp))
            }
            MeaningLoad.Failed -> {
                MeaningHeader(stringResource(R.string.lyrics_meaning_title), songLine, quoted = false)
                ImmersiveSheetNote(stringResource(R.string.immersive_genius_error), Modifier.padding(bottom = 12.dp))
            }
            is MeaningLoad.Ready -> MeaningBody(l.meaning, noteIndex)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MeaningBody(original: GeniusMeaning.Meaning, noteIndex: Int?) {
    val note = noteIndex?.let { original.notes.getOrNull(it) }
    val shown = rememberShown(original, listOfNotNull(if (note != null) note.explanation else original.about))
    val m = shown.meaning
    val shownNote = noteIndex?.let { m.notes.getOrNull(it) }
    val songLine = listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" · ")
    if (shownNote != null) {
        MeaningHeader(shownNote.fragment, songLine, quoted = true)
        MeaningText(shownNote.explanation)
    } else {
        MeaningHeader(stringResource(R.string.lyrics_meaning_about), songLine, quoted = false)
        if (!m.about.isNullOrBlank()) MeaningText(m.about!!)
        else ImmersiveSheetNote(stringResource(R.string.immersive_genius_none))
    }
    MeaningFooter(
        shown = shown,
        url = shownNote?.url?.takeIf { it.isNotBlank() } ?: m.url,
        votes = shownNote?.votes,
    )
}

/**
 * The head of the sheet. A note quotes its line in the title face behind a bar in the sheet's
 * accent, as a pull quote, whole rather than cut to one line; the song it is from sits above, small.
 */
@Composable
private fun MeaningHeader(title: String, songLine: String, quoted: Boolean) {
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 14.dp)) {
        if (songLine.isNotBlank()) {
            Text(
                songLine.uppercase(),
                style = Immersive.Caption.copy(letterSpacing = 0.6.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (quoted) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(LocalImmersiveSheetTint.current.accent),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = Immersive.Title.copy(fontSize = 21.sp, lineHeight = 27.sp, color = Immersive.Text),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        } else {
            Text(title, style = Immersive.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A paragraph that is only a picture's address: Genius's plain text keeps some images that way. */
private val IMAGE_URL = Regex("""https?://\S+\.(?:jpe?g|png|gif|webp)(?:\?\S*)?""", RegexOption.IGNORE_CASE)

/**
 * The annotation, set for reading: one block per paragraph with air between them, a looser
 * leading than the sheets' rows, and the web addresses tappable (Spotify ones open in the app).
 * A paragraph that is a picture's address shows the picture, rounded, at the column's width.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun MeaningText(text: String) {
    val paragraphs = remember(text) {
        text.replace("\r\n", "\n")
            .split(Regex("""\n\s*\n"""))
            .map { p -> p.lines().joinToString("\n") { it.trim() }.trim() }
            .filter { it.isNotEmpty() }
    }
    val accent = LocalImmersiveSheetTint.current.accent
    val style = Immersive.Body.copy(fontSize = 16.5.sp, lineHeight = 25.sp, color = Immersive.Text.copy(alpha = 0.92f))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        paragraphs.forEach { p ->
            if (IMAGE_URL.matches(p)) {
                GlideImage(
                    model = p,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(Immersive.CoverMedium),
                )
            } else {
                Text(rememberLinkedText(p, accent), style = style)
            }
        }
    }
}

/**
 * Where the text came from, small, and what can be done with it: translate it (or go back to the
 * original), and read it on Genius, where the note's own page opens when Genius gave one.
 */
@Composable
private fun MeaningFooter(shown: com.music.spotui.ui.components.ShownMeaning, url: String, votes: Int?) {
    val context = LocalContext.current
    val accent = LocalImmersiveSheetTint.current.accent
    Spacer(Modifier.height(18.dp))
    ImmersiveSheetDivider(Modifier.padding(horizontal = 22.dp))
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.immersive_genius_source), style = Immersive.Caption.copy(color = Immersive.TextSecondary))
            if (votes != null && votes > 0) {
                Text(pluralStringResource(R.plurals.immersive_genius_upvotes, votes, votes), style = Immersive.Caption.copy(color = Immersive.TextTertiary))
            }
        }
        when {
            shown.translating -> Text(
                stringResource(R.string.lyrics_meaning_translating),
                style = Immersive.Subhead,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            shown.canTranslate -> FooterPill(
                label = stringResource(if (showOriginal.value) R.string.lyrics_meaning_translate else R.string.lyrics_meaning_original),
                icon = { Icon(Icons.Rounded.Translate, null, tint = Immersive.Text, modifier = Modifier.size(15.dp)) },
                color = Immersive.Text,
                onClick = { showOriginal.value = !showOriginal.value },
            )
        }
        if (url.isNotBlank()) FooterPill(
            label = stringResource(R.string.immersive_genius_read),
            icon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = accent, modifier = Modifier.size(15.dp)) },
            color = accent,
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
    }
}

@Composable
private fun FooterPill(label: String, icon: @Composable () -> Unit, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Immersive.Text.copy(alpha = 0.08f))
            .immersiveClickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(label, style = Immersive.Subhead.copy(color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
    }
}
