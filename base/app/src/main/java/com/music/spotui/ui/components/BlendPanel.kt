package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.api.BlendGroup
import app.myspoti.api.BlendStory
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The Blend's own part of its page: one line (faces, match, invite), which opens on the
 * taste-match card and the members. Both come from Spotify's Blend service; the last few Blends opened are
 * kept so going back to one shows them at once (they are small, and few).
 */
@Composable
fun BlendPanel(uri: String) {
    var info by remember(uri) { mutableStateOf(BlendInfo.recent[uri]) }
    LaunchedEffect(uri) {
        val fresh = withContext(Dispatchers.IO) { BlendInfo.load(uri) } ?: return@LaunchedEffect
        info = fresh
    }
    var open by remember(uri) { mutableStateOf(false) }
    AnimatedVisibility(visible = info != null, enter = fadeIn() + expandVertically()) {
        val i = info ?: return@AnimatedVisibility
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            CompactLine(i) { open = !open }
            // The full card and the members, on a tap of the line.
            AnimatedVisibility(visible = open, enter = fadeIn() + expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
                Column(Modifier.padding(top = 8.dp)) {
                    i.story?.let { StoryCard(it) }
                    i.group?.takeIf { it.members.isNotEmpty() }?.let { Members(it) }
                }
            }
        }
    }
}

/** One line: the members' faces, the match in Spotify's words, and "+" to invite more people. */
@Composable
private fun CompactLine(info: BlendInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val members = info.group?.members.orEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        // Overlapping faces, as Spotify shows a Blend's members.
        Box(Modifier.width((28 + 18 * (members.size.coerceAtMost(4) - 1).coerceAtLeast(0)).dp).height(28.dp)) {
            members.take(4).forEachIndexed { n, m ->
                Box(Modifier.padding(start = (18 * n).dp).size(28.dp).clip(CircleShape).background(tone(0xFF121212)).padding(1.5.dp)) {
                    ProfileAvatar(m.imageUrl, m.name, 25.dp)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
            Text(info.story?.title ?: info.group?.title ?: stringResource(R.string.blend_title), color = AppText, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (members.isNotEmpty()) Text(members.joinToString(", ") { it.name }, color = AppMuted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        info.group?.link?.let { link ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp).clip(CircleShape).background(tone(0xFF2A2A2A)).clickable { BlendInfo.share(context, link) },
            ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.blend_invite_more), tint = AppText, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** What a Blend's page shows besides its songs; the recent ones kept, at most [KEEP]. */
data class BlendInfo(val story: BlendStory?, val group: BlendGroup?) {
    companion object {
        private const val KEEP = 8
        val recent: MutableMap<String, BlendInfo> = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, BlendInfo>(KEEP, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BlendInfo>?) = size > KEEP
            })

        suspend fun load(uri: String): BlendInfo? = coroutineScope {
            val blends = MyspotiRuntime.api.blends
            val story = async { runCatching { blends.story(uri) }.getOrNull() }
            val group = async { runCatching { blends.group(uri) }.getOrNull() }
            BlendInfo(story.await(), group.await())
                .takeIf { it.story != null || it.group != null }
                ?.also { recent[uri] = it }
        }

        /** Shares [link], the Blend's invitation, through the system's share sheet. */
        fun share(context: android.content.Context, link: String) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.blend_invite_message, link))
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun color(hex: String?, fallback: Color): Color =
    hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: fallback

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun StoryCard(story: BlendStory) {
    val background = color(story.background, tone(0xFF2A2A2A))
    val title = color(story.titleColor, AppText)
    val text = color(story.textColor, AppMuted)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(story.title, color = title, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            story.subtitle?.let { Text(it, color = text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
            story.detail?.let { Text(it, color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp)) }
        }
        story.image?.let {
            Spacer(Modifier.width(12.dp))
            GlideImage(
                model = it, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(CircleShape),
            )
        }
    }
}

@Composable
private fun Members(group: BlendGroup) {
    val context = LocalContext.current
    Text(
        group.title ?: stringResource(R.string.blend_members),
        color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
    )
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(group.members, key = { it.username }) { m ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                ProfileAvatar(m.imageUrl, m.name, 64.dp)
                Text(m.name, color = AppText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
        group.link?.let { link ->
            item(key = "invite") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(72.dp).clip(RoundedCornerShape(8.dp)).clickable { BlendInfo.share(context, link) },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp).clip(CircleShape).background(tone(0xFF2A2A2A))) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = AppText, modifier = Modifier.size(28.dp))
                    }
                    Text(group.inviteLabel ?: stringResource(R.string.blend_invite_more), color = AppMuted, fontSize = 12.sp,
                        maxLines = 2, textAlign = TextAlign.Center, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
