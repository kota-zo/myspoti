package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

/** Background colours for a profile without a picture, picked from the name (stable per user). */
private val AVATAR_COLORS = listOf(
    0xFF8D67AB, 0xFF1E3264, 0xFFE8115B, 0xFF148A08, 0xFFBA5D07, 0xFF477D95, 0xFFAF2896, 0xFF503750,
)

/**
 * A profile picture, round. A profile without one shows its name's first letter on a colour of its
 * own, as Spotify does; a profile without a name either, a person icon. Reported: an empty profile
 * picture showed nothing at all.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ProfileAvatar(url: String?, name: String?, size: Dp, modifier: Modifier = Modifier) {
    if (!url.isNullOrBlank()) {
        GlideImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
        return
    }
    val label = name?.trim().orEmpty()
    val letter = label.firstOrNull()?.uppercaseChar()
    val color = Color(AVATAR_COLORS[(label.hashCode() and Int.MAX_VALUE) % AVATAR_COLORS.size])
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (letter != null) Text(letter.toString(), color = AppText, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
        else Icon(Icons.Filled.Person, contentDescription = null, tint = AppText, modifier = Modifier.size(size * 0.55f))
    }
}
