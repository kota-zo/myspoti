package com.music.spotui.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.api.GeniusMeaning


/** The reader's choice between Genius's own words and their translation, for the whole session. */
internal val showOriginal = mutableStateOf(false)

/** Genius's words as shown: each text in the reader's language as soon as its translation arrives. */
internal class ShownMeaning(val meaning: GeniusMeaning.Meaning, val translating: Boolean, val canTranslate: Boolean)

@Composable
internal fun rememberShown(original: GeniusMeaning.Meaning, texts: List<String>): ShownMeaning {
    val lang = readerLanguage()
    // The texts on screen are asked for first (the rest may already be on their way in the background).
    LaunchedEffect(original, texts) { texts.forEach { GeniusMeaning.requestTranslation(original, it, lang) } }
    val same = GeniusMeaning.isSameLanguage(original, lang)
    val show = !showOriginal.value && same != true
    fun t(text: String) = if (show) GeniusMeaning.translation(text, lang) ?: text else text
    val meaning = original.copy(about = original.about?.let(::t), notes = original.notes.map { it.copy(explanation = t(it.explanation)) })
    val translating = show && texts.any { GeniusMeaning.isTranslating(it, lang) }
    return ShownMeaning(meaning, translating, same == false)
}

@Composable
fun readerLanguage(): String {
    val context = LocalContext.current
    return remember { com.music.spotui.data.preferences.getLyricTranslateLang(context).ifBlank { java.util.Locale.getDefault().language.ifBlank { "en" } } }
}

@Composable
private fun TranslateChip(shown: ShownMeaning) {
    when {
        shown.translating -> Text(stringResource(R.string.lyrics_meaning_translating), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        // Spotify's small outlined pill.
        shown.canTranslate -> Text(
            stringResource(if (showOriginal.value) R.string.lyrics_meaning_translate else R.string.lyrics_meaning_original),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
                .clickable { showOriginal.value = !showOriginal.value }.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun GeniusSource(url: String) {
    val context = LocalContext.current
    Text(stringResource(R.string.lyrics_meaning_source_short), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = url.isNotBlank()) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }.padding(vertical = 6.dp))
}

/** The light bulb of the lyrics: what Genius says about the song as a whole. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(original: GeniusMeaning.Meaning, background: Color, onDismiss: () -> Unit) {
    val shown = rememberShown(original, listOfNotNull(original.about))
    val m = shown.meaning
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cardColor(background),
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.4f)) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp, 4.dp, 24.dp, 24.dp)) {
            Text(stringResource(R.string.lyrics_meaning_about), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${m.title} · ${m.artist}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
            Text(m.about.orEmpty(), color = Color.White.copy(alpha = 0.92f), fontSize = 16.sp, lineHeight = 23.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
            if (m.notes.isNotEmpty()) Text(stringResource(R.string.lyrics_meaning_hint), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                modifier = Modifier.padding(top = 18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
                TranslateChip(shown)
                Spacer(Modifier.weight(1f))
                GeniusSource(m.url)
            }
        }
    }
}

/**
 * A line held in the lyrics: Genius's explanation of it, on the lyrics' own colour and in their
 * type (as Spotify's own cards on that screen), the lyrics still in view above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSheet(original: GeniusMeaning.Meaning, index: Int, background: Color, onDismiss: () -> Unit) {
    val shown = rememberShown(original, listOfNotNull(original.notes.getOrNull(index)?.explanation))
    val m = shown.meaning
    val note = m.notes.getOrNull(index) ?: return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = cardColor(background),
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.4f)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp, 4.dp, 24.dp, 24.dp)) {
            Text(note.fragment, color = Color.White, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            Text(note.explanation, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, lineHeight = 23.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
                TranslateChip(shown)
                Spacer(Modifier.weight(1f))
                GeniusSource(m.url)
            }
        }
    }
}

/** The sheet's colour: the lyrics' own, darker, so the white text reads on any of them. */
private fun cardColor(background: Color) = androidx.compose.ui.graphics.lerp(background, Color.Black, 0.35f)

/** For each lyric line, the explanation that covers it (a Genius fragment can span several lines). */
fun notesByLine(meaning: GeniusMeaning.Meaning, lines: List<String>): Map<Int, Int> {
    val frags = meaning.notes.map { squash(it.fragment) }
    val out = HashMap<Int, Int>()
    lines.forEachIndexed { i, line ->
        val l = squash(line)
        if (l.length < 8) return@forEachIndexed
        // Several explanations on one line: the most liked.
        val n = frags.indices.filter { j -> frags[j].let { f -> f.contains(l) || (f.length >= 8 && l.contains(f)) } }
            .maxByOrNull { meaning.notes[it].votes } ?: return@forEachIndexed
        out[i] = n
    }
    return out
}

private fun squash(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
