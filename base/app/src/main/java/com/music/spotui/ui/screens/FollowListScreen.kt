package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.api.UserRef
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.navigation.profileRoute
import com.music.spotui.ui.theme.AppBackground

const val FOLLOW_LIST_ROUTE = "followlist"

/** Route to the followers (or following) list of a user id. */
fun followListRoute(userId: String, followers: Boolean): String =
    "$FOLLOW_LIST_ROUTE/${android.net.Uri.encode(userId)}/${if (followers) "followers" else "following"}"

/** The people who follow a user, or the people that user follows; tap opens the profile. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun FollowListScreen(navController: NavController, userId: String, followers: Boolean) {
    var people by remember(userId, followers) { mutableStateOf<List<UserRef>?>(null) }
    LaunchedEffect(userId, followers) {
        val uri = "spotify:user:$userId"
        people = runCatching {
            if (followers) MyspotiRuntime.api.users.followers(uri) else MyspotiRuntime.api.users.following(uri)
        }.getOrDefault(emptyList())
    }
    val list = people
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
        modifier = Modifier.fillMaxSize().background(AppBackground).statusBarsPadding(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accessibility_navigate_back), tint = AppText,
                    modifier = Modifier.pressable(0.96f) { navController.navigateUp() }.padding(16.dp).size(26.dp),
                )
                Text(
                    stringResource(if (followers) R.string.profile_followers_title else R.string.profile_following_title),
                    color = AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        when {
            list == null -> item { com.music.spotui.ui.components.SkeletonList(count = 8, round = true) }
            list.isEmpty() -> item {
                Text(stringResource(R.string.profile_list_empty), color = AppMuted, fontSize = 15.sp,
                    modifier = Modifier.padding(16.dp))
            }
            else -> items(list, key = { it.uri }) { u ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.fillMaxWidth()
                        .pressable(0.98f) { navController.navigate(profileRoute(u.uri.substringAfterLast(':'), u.name)) }
                        .padding(16.dp, 8.dp),
                ) {
                    GlideImage(
                        model = com.music.spotui.ui.components.spotifyImageUrl(u.imageUrl), contentDescription = null, contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder),
                        modifier = Modifier.size(52.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(u.name, color = AppText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
