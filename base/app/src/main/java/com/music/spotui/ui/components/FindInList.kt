package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import java.text.Normalizer
import java.util.Locale

private val MARKS = Regex("""\p{M}+""")
private val SPACES = Regex("""\s+""")

/**
 * Titles and names folded once (lower case, no accents) and kept: a playlist search folds each
 * row's fields on every key typed. The patterns were compiled again for every field of every row
 * and held the main thread over a second a key on a big playlist (reported: typing in the search
 * froze the player until the app crashed).
 */
private const val FOLDED_KEEP = 20_000
private val folded = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > FOLDED_KEEP
}
private fun fold(s: String): String = if (s.isEmpty()) s else synchronized(folded) {
    folded.getOrPut(s) { Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT) }
}

/**
 * "Find in…" match: every word of [query] appears in one of [fields], ignoring case and
 * accents ("beyonce hal" finds "Halo" by Beyoncé). A blank query matches everything.
 */
fun matchesFind(query: String, vararg fields: String): Boolean {
    val words = fold(query).split(SPACES).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    return words.all { w -> fields.any { fold(it).contains(w) } }
}

/**
 * [rows] with one key each, unique and stable for that list: a playlist can hold the same song
 * twice (the id alone is not unique) and the index changes at every sort (which would lose every
 * item). Returned together with the rows: a list that re-reads its rows outside composition (a
 * search letter, the "×") must never pair them with keys kept from the list before.
 */
fun <T> withRowKeys(rows: List<T>, id: (T) -> String): Pair<List<T>, List<String>> {
    val seen = HashMap<String, Int>()
    return rows to rows.map { s ->
        val key = id(s)
        val n = (seen[key] ?: 0) + 1
        seen[key] = n
        if (n == 1) key else "$key#$n"
    }
}

/**
 * The "×" at the end of a search field, shown by the caller only while there is text. A tap clears
 * the field through [onClear] (which resets the results), then puts the cursor back in the field
 * with the keyboard up, so the next search can be typed straight away. [focusRequester] must be
 * attached to that field.
 */
@Composable
fun SearchClearButton(
    focusRequester: FocusRequester,
    tint: Color,
    modifier: Modifier = Modifier,
    onClear: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) {
                onClear()
                focusRequester.requestFocus()
                keyboard?.show()
            },
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.immersive_shell_search_clear),
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The "Find in…" field at the top of a list of songs. */
@Composable
fun FindInListField(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = AppText, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = AppText, fontSize = 15.sp),
            cursorBrush = SolidColor(AppText),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).padding(8.dp, 10.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(hint, color = AppMuted, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) SearchClearButton(focus, AppMuted) { onQuery("") }
    }
}

/** "Couldn't find …" under a "Find in…" field with no match. */
@Composable
fun FindInListEmpty(text: String) {
    Text(text, color = AppMuted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(16.dp, 24.dp))
}
