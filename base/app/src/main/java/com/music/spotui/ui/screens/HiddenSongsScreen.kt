package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.preferences.HiddenSongs
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText

/**
 * Settings > Hidden songs (Classic): the songs marked "Not interested" (see [HiddenSongs]), newest
 * first, each with a "Show again" that takes it off the list.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HiddenSongsScreen(navController: NavController) {
    val context = LocalContext.current
    val songs = HiddenSongs.songs
    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable { navController.popBackStack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
            }
            Text(stringResource(R.string.hidden_songs_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (songs.isEmpty()) Text(
            stringResource(R.string.hidden_songs_empty), color = AppMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        ) else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
            items(songs, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlideImage(
                        transition = CrossFade, model = s.coverUri, contentDescription = "", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = AppText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.singer, color = AppMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.show_song_again), color = AppAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { HiddenSongs.show(context, s.id) }.padding(8.dp),
                    )
                }
            }
        }
    }
}
