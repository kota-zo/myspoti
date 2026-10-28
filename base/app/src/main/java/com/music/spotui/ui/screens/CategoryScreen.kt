package com.music.spotui.ui.screens

import com.music.spotui.ui.components.wideClickable
import com.music.spotui.ui.theme.AppText

import androidx.compose.ui.res.stringResource

import com.music.spotui.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.viewmodel.CategoryViewModel

@Composable
fun CategoryScreen(navController: NavController, genre: String, title: String) {
    val viewModel: CategoryViewModel = viewModel()
    LaunchedEffect(genre) { viewModel.load(genre) }
    val playlists by viewModel.playlists.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.music.spotui.ui.theme.AppBackground)
            .statusBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp, 12.dp, 16.dp, 8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.accessibility_navigate_back),
                tint = AppText,
                modifier = Modifier
                    .wideClickable(horizontal = 8.dp, vertical = 10.dp) { navController.popBackStack() }
                    .size(40.dp)
                    .padding(8.dp)
            )
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = AppText,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        when (playlists) {
            is Response.Loading -> Loader()
            is Response.Success -> CategoryGrid((playlists as Response.Success).data, navController)
            else -> Box(modifier = Modifier.padding(20.dp, 60.dp)) { Snackbar(showMessage = stringResource(R.string.couldnt_load, title)) }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun CategoryGrid(entries: List<LibraryEntry>, navController: NavController) {
    if (entries.isEmpty()) {
        Box(modifier = Modifier.padding(20.dp, 40.dp)) { Snackbar(showMessage = stringResource(R.string.nothing_here)) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(com.music.spotui.ui.components.windowSize().columns(2, 200.dp)),
        contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, com.music.spotui.ui.components.bottomBarsSpace()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries) { entry ->
            Column(
                modifier = Modifier.pressable(0.96f, onPressHint = { if (entry.isPlaylist) ContextViewModel.prefetch("playlist", entry.spotifyId) }) {
                    navController.navigate(if (entry.isPlaylist) playlistRoute(entry.spotifyId, entry.name) else com.music.spotui.ui.navigation.albumRoute(entry.name, entry.subtitle, entry.spotifyId))
                }
                    
            ) {
                GlideImage(
                    transition = CrossFade,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp)),
                    model = entry.coverUri,
                    contentScale = ContentScale.Crop,
                    failure = placeholder(R.drawable.placeholder),
                    loading = placeholder(R.drawable.placeholder),
                    contentDescription = "",
                )
                Text(
                    text = entry.name,
                    color = AppText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 16.sp,
                    // Two lines tall either way: every card of a grid row ends at the same height.
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = entry.subtitle,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
