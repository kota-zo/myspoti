// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package com.music.spotui.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import app.myspoti.api.Chat
import app.myspoti.api.ChatEvent
import app.myspoti.api.ChatMessage
import app.myspoti.api.ChatReaction
import app.myspoti.api.ChatRequest
import app.myspoti.api.Gif
import app.myspoti.api.MediaCard
import app.myspoti.api.MediaKind
import app.myspoti.api.UserRef
import app.myspoti.api.best
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.spotifyImageUrl
import com.music.spotui.ui.components.wideClickable
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveSearchField
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveSongListSkeleton
import com.music.spotui.ui.immersive.glass
import com.music.spotui.ui.immersive.pages.ImmersiveGroupRow
import com.music.spotui.ui.immersive.pages.ImmersiveNameDialog
import com.music.spotui.ui.immersive.pages.ImmersivePage
import com.music.spotui.ui.immersive.pages.ImmersiveSheetRow
import com.music.spotui.ui.immersive.pages.ImmersiveSwitchRow
import com.music.spotui.ui.immersive.pages.immersiveGroup
import com.music.spotui.ui.immersive.shell.ImmersiveBarAction
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.chatRoute
import com.music.spotui.ui.navigation.chatSettingsRoute
import com.music.spotui.ui.navigation.episodeRoute
import com.music.spotui.ui.navigation.isImmersive
import com.music.spotui.ui.navigation.newChatRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppDestructive
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The conversations last read, shown again at once when the list comes back (from a chat). */
private var lastChats: List<Chat>? = null

/** On sign-out: the next account never sees this one's conversations. */
fun clearMessagesCache() { lastChats = null }

/** A conversation's title: the group's name, else the other people's names. */
private fun Chat.title(): String = name ?: members.joinToString(", ") { it.name }

/** The reactions Spotify offers on a message, in its order. */
private val REACTIONS = listOf("👍", "❤️", "😂", "🥺", "👎", "🔥", "🎧")

/**
 * The Messages pages in the look the person chose (Settings > Display): the same pages and the
 * same actions, drawn with the Classic palette or with the Immersive one (black page, large
 * titles under a frosted bar, grouped cards, its sheets and type).
 */
private class Look(val immersive: Boolean) {
    val page: Color get() = if (immersive) Immersive.Page else AppBackground
    val text: Color get() = if (immersive) Immersive.Text else AppText
    val muted: Color get() = if (immersive) Immersive.TextSecondary else AppMuted
    val accent: Color get() = if (immersive) Immersive.Accent else AppAccent
    /** What reads on the accent: black on Spotify's green, white on the Immersive accents. */
    val onAccent: Color get() = if (immersive) Color.White else Color.Black
    /** Bubbles from others, fields, chips. */
    val raised: Color get() = if (immersive) Immersive.Raised else tone(0xFF2A2A2A)
    val sheet: Color get() = if (immersive) Immersive.Raised else tone(0xFF282828)
    val destructive: Color get() = if (immersive) Immersive.Destructive else AppDestructive
    val gutter: Dp get() = if (immersive) Immersive.Gutter else 16.dp
    val bubble: Shape = RoundedCornerShape(if (immersive) 20.dp else 18.dp)
    val cover: Shape get() = if (immersive) Immersive.CoverSmall else RoundedCornerShape(6.dp)

    val title: TextStyle get() = if (immersive) Immersive.BodyStrong else TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val body: TextStyle get() = if (immersive) Immersive.Body else TextStyle(fontSize = 15.sp)
    val caption: TextStyle get() = if (immersive) Immersive.Caption else TextStyle(fontSize = 12.sp, color = AppMuted)
    val subhead: TextStyle get() = if (immersive) Immersive.Subhead else TextStyle(fontSize = 13.sp, color = AppMuted)

    /** The look's press: the Immersive one shrinks a little, the Classic one ripples. */
    fun Modifier.press(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
        if (immersive) immersiveClickable(onClick = onClick, onLongClick = onLongClick, pressedScale = 0.99f)
        else this.then(@OptIn(ExperimentalFoundationApi::class) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))
}

@Composable
private fun look(): Look = Look(isImmersive())

/** What Spotify's refusal means for the person: a request not yet accepted, or a plain refusal. */
private fun refusal(context: android.content.Context, e: Throwable): String =
    context.getString(if (e.message?.contains("draft state") == true) R.string.messages_draft_blocked else R.string.messages_action_failed)

private fun toast(context: android.content.Context, text: String) =
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()

// ---------------------------------------------------------------- Page frames

/**
 * A list page of Messages: the Immersive page (large title, frosted bar, bar actions) or the
 * Classic one (a header row over the list). [actions] are [BarAction]s.
 */
@Composable
private fun ChatPage(
    title: String,
    onBack: () -> Unit,
    bottomPadding: Dp = com.music.spotui.ui.components.bottomBarsSpace(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val l = look()
    if (l.immersive) ImmersivePage(title = title, onBack = onBack, actions = actions, bottomPadding = bottomPadding, content = content)
    else Column(Modifier.fillMaxSize().background(l.page).statusBarsPadding()) {
        Header(title, onBack = onBack, actions = { Row(verticalAlignment = Alignment.CenterVertically, content = actions) })
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding), content = content)
    }
}

/** A control of a page's bar: a glass circle in the Immersive look, a plain icon in the Classic one. */
@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    if (look().immersive) ImmersiveBarAction(icon, label, onClick = onClick)
    else Icon(icon, label, tint = AppText, modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick).padding(8.dp))
}

/** The page's loading rows, failure (with a retry) or empty line, in the look. */
private fun LazyListScope.pageState(l: Look, loading: Boolean, failed: String?, empty: String?, onRetry: () -> Unit) {
    when {
        loading -> if (l.immersive) immersiveSongListSkeleton(count = 6, circular = true)
            else item { com.music.spotui.ui.components.SkeletonList(count = 6, round = true) }
        failed != null -> item {
            if (l.immersive) ImmersiveMessageState(failed, actionLabel = stringResource(R.string.retry_button), onAction = onRetry)
            else com.music.spotui.ui.components.ErrorRetry(failed, onRetry = onRetry)
        }
        empty != null -> item {
            if (l.immersive) ImmersiveMessageState(empty)
            else Text(empty, color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

/**
 * A bottom sheet in the look. A [full] one opens at full height: the search sheets (music,
 * GIFs), whose results half a sheet hid, most of all on a tablet or a phone held sideways.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSheet(onDismiss: () -> Unit, full: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val l = look()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = full), containerColor = l.sheet) {
        Column(Modifier.navigationBarsPadding().padding(bottom = if (l.immersive) 12.dp else 8.dp), content = content)
    }
}

/** One entry of a sheet or a page: the Immersive sheet row (with its glyph) or a Classic text row. */
@Composable
private fun ActionRow(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val l = look()
    if (l.immersive) ImmersiveSheetRow(icon, label, onClick = onClick, destructive = destructive)
    else Text(label, color = if (destructive) l.destructive else l.text, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp, 14.dp))
}

/** A search box in the look. */
@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val l = look()
    if (l.immersive) ImmersiveSearchField(query = query, onQueryChange = onQuery, onSubmit = {}, placeholder = placeholder,
        modifier = modifier.padding(horizontal = l.gutter, vertical = 6.dp))
    else TextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text(placeholder, color = AppMuted) },
        shape = RoundedCornerShape(24.dp), colors = fieldColors(l),
        modifier = modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 8.dp),
    )
}

// ---------------------------------------------------------------- Messages

/**
 * The Messages button of the Friends tab's header: opens the conversations, with a dot while
 * one of them has something unread.
 */
@Composable
fun MessagesButton(navController: NavController, tint: Color) {
    var unread by remember { mutableStateOf(lastChats?.any { it.unread } == true) }
    LaunchedEffect(Unit) {
        runCatching { MyspotiRuntime.api.messages.chats() }.onSuccess { lastChats = it; unread = it.any { c -> c.unread } }
    }
    Box(Modifier.size(40.dp).clip(CircleShape).clickable { navController.navigate(Routes.Messages.route) }, contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.messages_title), tint = tint, modifier = Modifier.size(24.dp))
        if (unread) Box(Modifier.offset(9.dp, (-9).dp).size(9.dp).clip(CircleShape).background(look().accent))
    }
}

/** Spotify's Messages: the account's conversations, latest first; a tap opens one. */
@Composable
fun MessagesScreen(navController: NavController) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(lastChats?.let { Result.success(it) }) }
    LaunchedEffect(attempt) {
        val result = runCatching { MyspotiRuntime.api.messages.chats() }
        result.onSuccess { lastChats = it }
        // A failed refresh keeps the list in hand.
        if (result.isSuccess || state?.isSuccess != true) state = result
    }
    val now = remember(state) { System.currentTimeMillis() }
    val l = look()
    val s = state
    val chats = s?.getOrNull()
    val failedText = stringResource(R.string.messages_failed)
    val emptyText = stringResource(R.string.messages_empty)
    ChatPage(
        stringResource(R.string.messages_title),
        onBack = { navController.popBackStack() },
        actions = {
            BarAction(Icons.Outlined.Edit, stringResource(R.string.messages_new)) { navController.navigate(newChatRoute()) }
            BarAction(Icons.Filled.Refresh, stringResource(R.string.friends_refresh)) { attempt++ }
        },
    ) {
        when {
            s == null -> pageState(l, loading = true, failed = null, empty = null) {}
            chats == null -> pageState(l, loading = false, failed = failedText, empty = null) { state = null; attempt++ }
            chats.isEmpty() -> pageState(l, loading = false, failed = null, empty = emptyText) {}
            else -> items(chats, key = { it.id }) { c ->
                ChatRow(c, now) { navController.navigate(chatRoute(c.id, c.title())) }
            }
        }
    }
}

@Composable
private fun ChatRow(c: Chat, now: Long, onClick: () -> Unit) {
    val l = look()
    val last = c.lastMessage
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = with(l) { Modifier.fillMaxWidth().press(onClick) }.padding(l.gutter, 10.dp),
    ) {
        val face = c.members.firstOrNull()
        com.music.spotui.ui.components.ProfileAvatar(url = if (c.group) null else face?.imageUrl, name = c.name ?: face?.name, size = 52.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title(), style = if (c.unread) l.title else l.body, color = l.text,
                    // The name takes the room, so the time sits at the row's end.
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (last != null && last.timestampMs > 0) Text(
                    android.text.format.DateUtils.getRelativeTimeSpanString(last.timestampMs, now,
                        android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                    style = l.caption, maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val line = when {
                    c.request == ChatRequest.INCOMING -> stringResource(R.string.messages_request_label_in)
                    last == null -> if (c.request == ChatRequest.OUTGOING) stringResource(R.string.messages_request_label_out) else ""
                    last.event != null -> eventText(last)
                    // "You: …", or the author in a group; the language places the colon.
                    last.fromMe -> stringResource(R.string.messages_from, stringResource(R.string.messages_you), preview(last))
                    c.group -> stringResource(R.string.messages_from, last.author.name, preview(last))
                    else -> preview(last)
                }
                Text(line, style = l.subhead, color = if (c.unread || c.request == ChatRequest.INCOMING) l.text else l.muted,
                    fontWeight = if (c.unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.unread) Box(Modifier.padding(start = 8.dp).size(9.dp).clip(CircleShape).background(l.accent))
            }
        }
    }
}

/** A message in one line, for the list and the action sheet. */
@Composable
private fun preview(m: ChatMessage): String = m.text ?: m.shared?.title ?: when {
    m.deleted -> stringResource(R.string.messages_deleted)
    m.gifUrl != null -> stringResource(R.string.messages_gif)
    m.media ->stringResource(R.string.messages_media)
    else -> stringResource(R.string.messages_shared)
}

/** A chat event as a sentence: who did it (the author) and to whom. */
@Composable
private fun eventText(m: ChatMessage): String {
    val who = if (m.fromMe) stringResource(R.string.messages_you) else m.author.name
    val whom = m.event?.subject?.name.orEmpty()
    return when (m.event?.kind) {
        ChatEvent.Kind.CREATED -> stringResource(R.string.messages_event_created, who)
        ChatEvent.Kind.ADDED -> stringResource(R.string.messages_event_added, who, whom)
        ChatEvent.Kind.JOINED -> stringResource(R.string.messages_event_joined, who)
        ChatEvent.Kind.LEFT -> stringResource(R.string.messages_event_left, who)
        ChatEvent.Kind.RENAMED -> stringResource(R.string.messages_event_renamed, who)
        ChatEvent.Kind.NEW_ADMIN -> stringResource(R.string.messages_event_admin, who)
        ChatEvent.Kind.PHOTO -> stringResource(R.string.messages_event_photo, who)
        ChatEvent.Kind.REMOVED -> stringResource(R.string.messages_event_removed, who, whom)
        ChatEvent.Kind.JOINED_BY_LINK -> stringResource(R.string.messages_event_link, who)
        ChatEvent.Kind.REPORTED -> stringResource(R.string.messages_event_reported)
        null -> ""
    }
}

// ---------------------------------------------------------------- New message

/**
 * "New message": the people to write to, from the friend feed and the people the account
 * follows, narrowed by what is typed (and Spotify's user search for anyone else). One person
 * opens the direct conversation (the existing one when there is one); several make a group.
 * With [addTo], the people picked join that group instead.
 */
@Composable
fun NewChatScreen(navController: NavController, addTo: String?) {
    val api = MyspotiRuntime.api
    var known by remember { mutableStateOf<List<UserRef>?>(null) }
    LaunchedEffect(Unit) {
        known = kotlinx.coroutines.coroutineScope {
            val friends = async { runCatching { api.users.friendActivity().map { it.user } }.getOrDefault(emptyList()) }
            val following = async { runCatching { api.users.following(api.users.me().uri) }.getOrDefault(emptyList()) }
            // People only: the account's "following" list holds the artists it follows too.
            (friends.await() + following.await()).filter { it.uri.startsWith("spotify:user:") }.distinctBy { it.uri }
        }
    }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<UserRef>>(emptyList()) }
    LaunchedEffect(query) {
        found = emptyList()
        if (query.isBlank()) return@LaunchedEffect
        delay(350)
        found = runCatching { api.search.users(query.trim(), limit = 20).items.map { UserRef(it.uri, it.title, it.images.best(100)?.url) } }
            .getOrDefault(emptyList()).distinctBy { it.uri }
    }
    val picked = remember { mutableStateListOf<UserRef>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val l = look()
    val startFailed = stringResource(R.string.messages_start_failed, picked.joinToString(", ") { it.name })

    fun go(groupName: String?) {
        busy = true
        scope.launch {
            runCatching {
                when {
                    addTo != null -> { api.messages.addMembers(addTo, picked.map { it.uri }); null }
                    picked.size == 1 -> api.messages.startChat(picked[0].uri) to picked[0].name
                    else -> api.messages.createGroup(picked.map { it.uri }, groupName) to (groupName ?: picked.joinToString(", ") { it.name })
                }
            }.onSuccess { opened ->
                if (opened == null) navController.popBackStack()
                else navController.navigate(chatRoute(opened.first, opened.second)) { popUpTo(Routes.Messages.route) }
            }.onFailure { toast(context, startFailed) }
            busy = false
        }
    }

    if (naming) NameDialog(stringResource(R.string.messages_group_name), "", onDismiss = { naming = false }) { name -> naming = false; go(name.ifBlank { null }) }

    Box(Modifier.fillMaxSize().background(l.page)) {
        ChatPage(stringResource(if (addTo != null) R.string.messages_add_people else R.string.messages_new),
            onBack = { navController.popBackStack() }, bottomPadding = 120.dp) {
            item(key = "search") { SearchBox(query, { query = it }, stringResource(R.string.messages_find_people)) }
            val q = query.trim()
            val shown = known.orEmpty().filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                .let { mine -> mine + found.filter { f -> mine.none { it.uri == f.uri } } }
            pageState(l, loading = known == null, failed = null, empty = null) {}
            items(shown, key = { it.uri }) { u ->
                val on = picked.any { it.uri == u.uri }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = with(l) {
                        Modifier.fillMaxWidth().press({ if (!busy) { if (on) picked.removeAll { it.uri == u.uri } else picked += u } })
                    }.semantics { selected = on }.padding(l.gutter, 8.dp),
                ) {
                    com.music.spotui.ui.components.ProfileAvatar(url = u.imageUrl, name = u.name, size = 48.dp)
                    Text(u.name, style = l.body, color = l.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp).weight(1f))
                    Icon(if (on) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                        tint = if (on) l.accent else l.muted, modifier = Modifier.size(24.dp))
                }
            }
        }
        if (picked.isNotEmpty()) {
            val label = when {
                addTo != null -> stringResource(R.string.messages_add_people)
                picked.size == 1 -> stringResource(R.string.messages_write_to, picked[0].name)
                else -> stringResource(R.string.messages_create_group, picked.size)
            }
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(l.gutter, 16.dp).fillMaxWidth().height(52.dp)
                    .clip(if (l.immersive) Immersive.Button else RoundedCornerShape(26.dp)).background(l.accent)
                    .clickable(enabled = !busy) { if (addTo == null && picked.size > 1) naming = true else go(null) },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = l.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else Text(label, style = l.title.copy(fontSize = 16.sp), color = l.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- A conversation

/**
 * One conversation: its messages, newest at the bottom, and a field to write. Spotify pushes new
 * messages to its own app; here the conversation is read again every few seconds while it is open,
 * and marked as seen when someone else wrote. A long press on a message reacts, copies or
 * deletes it; the + shares music; the title opens the conversation's details.
 */
@Composable
fun ChatScreen(navController: NavController, chatId: String, name: String) {
    val api = MyspotiRuntime.api.messages
    var chat by remember { mutableStateOf(lastChats?.find { it.id == chatId }) }
    LaunchedEffect(chatId) {
        runCatching { api.chats() }.onSuccess { lastChats = it; chat = it.find { c -> c.id == chatId } ?: chat }
    }
    var messages by remember { mutableStateOf<List<ChatMessage>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var seenUpTo by remember { mutableStateOf<String?>(null) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Only while the app is on screen: in the background it neither reads the conversation every
    // few seconds nor tells Spotify that messages nobody looked at were seen.
    LaunchedEffect(chatId, refresh, owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { while (true) {
            runCatching { api.messages(chatId) }
                .onSuccess { list ->
                    messages = list
                    failed = false
                    // Seen: once per newest message from someone else, as the phone app acks on open.
                    val newest = list.lastOrNull()
                    if (newest != null && !newest.fromMe && newest.id != seenUpTo) {
                        seenUpTo = newest.id
                        runCatching { api.markSeen(chatId) }
                        lastChats = lastChats?.map { if (it.id == chatId) it.copy(unread = false) else it }
                    }
                }
                .onFailure { if (messages == null) failed = true }
            delay(POLL_MS)
        } }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Kept through a change of theme, language or font size, which recreates the page.
    var draft by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<ChatMessage?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    var gifs by remember { mutableStateOf(false) }
    val group = chat?.group ?: (messages.orEmpty().map { it.author.uri }.distinct().size > 2)
    val l = look()
    val title = chat?.title() ?: name
    val openDetails = { navController.navigate(chatSettingsRoute(chatId)) }

    /** Runs a change, then reads the conversation again; Spotify's refusal is said in a toast. */
    fun act(change: suspend () -> Unit) {
        scope.launch {
            runCatching { change() }
                .onSuccess { runCatching { api.messages(chatId) }.onSuccess { messages = it } }
                .onFailure { toast(context, refusal(context, it)) }
        }
    }

    acting?.let { m ->
        MessageSheet(m, onDismiss = { acting = null }, onAct = { change -> acting = null; act(change) })
    }
    if (sharing) ShareSheet(onDismiss = { sharing = false }) { card -> sharing = false; act { api.share(chatId, card.uri) } }
    if (gifs) GifSheet(onDismiss = { gifs = false }) { gif -> gifs = false; act { api.sendGif(chatId, gif) } }
    // The phone app's +: music, or a GIF (photos are not offered to the accounts tried).
    if (attaching) ChatSheet(onDismiss = { attaching = false }) {
        ActionRow(Icons.Outlined.MusicNote, stringResource(R.string.messages_attach_music)) { attaching = false; sharing = true }
        ActionRow(Icons.Outlined.Gif, stringResource(R.string.messages_gif)) { attaching = false; gifs = true }
    }

    Box(Modifier.fillMaxSize().background(l.page)) {
        Column(
            Modifier.fillMaxSize()
                .then(if (l.immersive) Modifier.padding(top = immersiveTopContentPadding()) else Modifier.statusBarsPadding())
                .navigationBarsPadding().imePadding(),
        ) {
            if (!l.immersive) Header(title, onBack = { navController.popBackStack() }, onTitle = openDetails) {
                BarAction(Icons.Outlined.Info, stringResource(R.string.messages_settings), onClick = openDetails)
            }
            when (chat?.request) {
                ChatRequest.INCOMING -> RequestBanner(chat!!.title(),
                    onAccept = { act { api.answerRequest(chatId, true); chat = api.chats().also { lastChats = it }.find { it.id == chatId } } },
                    onDecline = { scope.launch { runCatching { api.answerRequest(chatId, false) }.onSuccess { navController.popBackStack() }.onFailure { toast(context, refusal(context, it)) } } })
                ChatRequest.OUTGOING -> Text(stringResource(R.string.messages_request_outgoing, chat!!.title()), style = l.subhead,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (l.immersive) Modifier.padding(horizontal = l.gutter).clip(Immersive.Card) else Modifier)
                        .background(l.raised).padding(16.dp, 10.dp))
                else -> {}
            }
            val list = messages
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    list == null && failed -> if (l.immersive) ImmersiveMessageState(stringResource(R.string.messages_failed),
                        actionLabel = stringResource(R.string.retry_button), onAction = { failed = false; refresh++ })
                        else com.music.spotui.ui.components.ErrorRetry(stringResource(R.string.messages_failed)) { failed = false; refresh++ }
                    list == null -> CircularProgressIndicator(color = l.accent, modifier = Modifier.align(Alignment.Center))
                    list.isEmpty() -> Text(stringResource(R.string.messages_chat_empty), style = l.subhead, modifier = Modifier.align(Alignment.Center))
                    else -> LazyColumn(reverseLayout = true, contentPadding = PaddingValues(l.gutter - 8.dp, 8.dp), modifier = Modifier.fillMaxSize()) {
                        val rows = list.asReversed()
                        items(rows.size, key = { rows[it].id }) { i ->
                            val m = rows[i]
                            val before = rows.getOrNull(i + 1)
                            // One column: a reversed list also reverses the children of an item, which put the time under its message.
                            Column {
                                // A time line above a message that comes after a pause.
                                if (before == null || m.timestampMs - before.timestampMs > GAP_MS) TimeLine(m.timestampMs)
                                if (m.event != null) EventLine(m)
                                else {
                                    val firstOfRun = before == null || before.author.uri != m.author.uri || before.event != null
                                    Bubble(m, showAuthor = group && !m.fromMe && firstOfRun, navController,
                                        onLongClick = { acting = m },
                                        onReaction = { emoji ->
                                            val mine = m.reactions.firstOrNull { it.fromMe && it.emoji == emoji }
                                            act { if (mine != null) api.unreact(mine) else api.react(m.id, emoji) }
                                        })
                                }
                            }
                        }
                    }
                }
            }
            if (chat?.request != ChatRequest.INCOMING) Composer(
                draft = draft, onDraft = { draft = it }, sending = sending,
                onAttach = { attaching = true },
                onSend = {
                    val text = draft.trim()
                    sending = true
                    scope.launch {
                        runCatching { api.send(chatId, text) }
                            .onSuccess {
                                draft = ""
                                runCatching { api.messages(chatId) }.onSuccess { messages = it }
                            }
                            .onFailure { toast(context, refusal(context, it)) }
                        sending = false
                    }
                },
            )
        }
        // The Immersive bar: the frosted strip with the back chevron, the title and the details, over the messages.
        if (l.immersive) ImmersiveTopBar(title = title, collapse = { 1f }, onBack = { navController.popBackStack() }, actions = {
            ImmersiveBarAction(Icons.Outlined.Info, stringResource(R.string.messages_settings), onClick = openDetails)
        })
    }
}

/** The field to write, with the + that sends music, a GIF or a photo, and the send button. */
@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, sending: Boolean, onAttach: () -> Unit, onSend: () -> Unit) {
    val l = look()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp, 8.dp, 12.dp, 8.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).then(if (l.immersive) Modifier.glass() else Modifier).clickable(onClick = onAttach),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, stringResource(R.string.messages_attach), tint = if (l.immersive) l.accent else l.text, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(6.dp))
        TextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = { Text(stringResource(R.string.messages_hint), color = l.muted, style = l.body) },
            textStyle = l.body,
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            colors = fieldColors(l),
            modifier = Modifier.weight(1f).then(if (l.immersive) Modifier.glass(RoundedCornerShape(24.dp)) else Modifier),
        )
        val canSend = draft.isNotBlank() && !sending
        Box(
            Modifier.padding(start = 8.dp).size(48.dp).clip(CircleShape).background(if (canSend) l.accent else l.raised)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            if (sending) CircularProgressIndicator(color = l.text, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.messages_send), tint = if (canSend) l.onAccent else l.muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun RequestBanner(who: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    val l = look()
    Column(
        Modifier.fillMaxWidth().then(if (l.immersive) Modifier.padding(horizontal = l.gutter).clip(Immersive.Card) else Modifier)
            .background(l.raised).padding(16.dp, 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.messages_request_incoming, who), style = l.body, color = l.text, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(stringResource(R.string.messages_decline), l.text, Color.Transparent, onDecline)
            Pill(stringResource(R.string.messages_accept), l.onAccent, l.accent, onAccept)
        }
    }
}

@Composable
private fun Pill(label: String, color: Color, fill: Color, onClick: () -> Unit) {
    val l = look()
    val shape = if (l.immersive) Immersive.Button else RoundedCornerShape(20.dp)
    Box(Modifier.clip(shape).background(fill).border(1.dp, if (fill == Color.Transparent) l.muted else fill, shape)
        .clickable(onClick = onClick).padding(20.dp, 8.dp)) {
        Text(label, style = l.title.copy(fontSize = 14.sp), color = color)
    }
}

@Composable
private fun TimeLine(ms: Long) {
    if (ms <= 0) return
    val context = LocalContext.current
    val flags = android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_DATE or
        android.text.format.DateUtils.FORMAT_ABBREV_MONTH
    Text(android.text.format.DateUtils.formatDateTime(context, ms, flags), style = look().caption.copy(fontSize = 11.sp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).wrapContentWidth(Alignment.CenterHorizontally))
}

@Composable
private fun EventLine(m: ChatMessage) {
    Text(eventText(m), style = look().caption, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp, 6.dp))
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Bubble(m: ChatMessage, showAuthor: Boolean, navController: NavController, onLongClick: () -> Unit, onReaction: (String) -> Unit) {
    val l = look()
    Column(
        horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        if (showAuthor) Text(m.author.name, style = l.caption, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
        val gif = m.gifUrl
        if (gif != null) {
            GlideImage(model = gif, contentDescription = preview(m), contentScale = ContentScale.Crop,
                modifier = with(l) { Modifier.size(220.dp).clip(l.bubble).background(l.raised).press({}, onLongClick) })
        } else Column(
            with(l) { Modifier.widthIn(max = 290.dp).clip(l.bubble).background(if (m.fromMe) l.accent else l.raised).press({}, onLongClick) },
        ) {
            m.shared?.let { card -> SharedCard(card, fromMe = m.fromMe, onLongClick = onLongClick) { open(card, navController) } }
            // A share Spotify could not name (a pre-release page...) still shows as a share.
            val text = m.text ?: if (m.shared == null) preview(m) else null
            text?.let { Text(it, style = l.body, color = if (m.fromMe) l.onAccent else l.text, modifier = Modifier.padding(12.dp, 8.dp)) }
        }
        if (m.reactions.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            m.reactions.groupBy { it.emoji }.forEach { (emoji, list) ->
                val mine = list.any(ChatReaction::fromMe)
                Text(
                    if (list.size > 1) "$emoji ${list.size}" else emoji, fontSize = 13.sp, color = l.text,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(l.raised)
                        .border(1.dp, if (mine) l.accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { onReaction(emoji) }.padding(8.dp, 3.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun SharedCard(card: MediaCard, fromMe: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val l = look()
    val ink = if (fromMe) l.onAccent else l.text
    Row(verticalAlignment = Alignment.CenterVertically, modifier = with(l) { Modifier.press(onClick, onLongClick) }.padding(8.dp)) {
        card.images.best(160)?.url?.let { url ->
            GlideImage(transition = CrossFade, model = spotifyImageUrl(url), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(if (card.kind == MediaKind.ARTIST) CircleShape else l.cover))
        }
        Column(Modifier.padding(horizontal = 10.dp).widthIn(max = 200.dp)) {
            Text(card.title, style = l.title.copy(fontSize = 14.sp), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            card.subtitle?.let {
                Text(it, style = l.caption, color = ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A long-pressed message: a reaction, copy, delete (for everyone when it is the account's own). */
@Composable
private fun MessageSheet(m: ChatMessage, onDismiss: () -> Unit, onAct: (suspend () -> Unit) -> Unit) {
    val api = MyspotiRuntime.api.messages
    val clipboard = LocalClipboardManager.current
    val l = look()
    ChatSheet(onDismiss) {
        Text(preview(m), style = l.subhead, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(l.gutter, 0.dp, l.gutter, 12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            REACTIONS.forEach { emoji ->
                val mine = m.reactions.firstOrNull { it.fromMe && it.emoji == emoji }
                Text(emoji, fontSize = 28.sp, modifier = Modifier.clip(CircleShape)
                    .background(if (mine != null) l.accent.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable { onAct { if (mine != null) api.unreact(mine) else api.react(m.id, emoji) } }.padding(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        m.text?.let { text -> ActionRow(Icons.Outlined.ContentCopy, stringResource(R.string.messages_copy)) { clipboard.setText(AnnotatedString(text)); onDismiss() } }
        if (m.fromMe) ActionRow(Icons.Outlined.DeleteForever, stringResource(R.string.messages_delete_all), destructive = true) { onAct { api.delete(m.id, forEveryone = true) } }
        ActionRow(Icons.Outlined.Delete, stringResource(R.string.messages_delete_me), destructive = true) { onAct { api.delete(m.id, forEveryone = false) } }
    }
}

/** The + of a conversation: Spotify's search, and a tap shares the song, album, artist or playlist. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ShareSheet(onDismiss: () -> Unit, onPick: (MediaCard) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaCard>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        results = runCatching {
            val r = MyspotiRuntime.api.search.all(query.trim(), limit = 6)
            r.tracks.items.map { MediaCard(it.uri, MediaKind.TRACK, it.name, it.artists.joinToString(", ") { a -> a.name }, it.album?.images.orEmpty()) } +
                r.albums.items.map { MediaCard(it.uri, MediaKind.ALBUM, it.name, it.artists.joinToString(", ") { a -> a.name }, it.images) } +
                r.artists.items.map { MediaCard(it.uri, MediaKind.ARTIST, it.name, null, it.images) } +
                r.playlists.items.map { MediaCard(it.uri, MediaKind.PLAYLIST, it.name, it.owner, it.images) }
        }.getOrDefault(emptyList()).distinctBy { it.uri }
    }
    val l = look()
    ChatSheet(onDismiss, full = true) {
        Column(Modifier.imePadding()) {
            Text(stringResource(R.string.messages_share_title), style = if (l.immersive) Immersive.Headline else l.title.copy(fontSize = 18.sp),
                color = l.text, modifier = Modifier.padding(l.gutter, 0.dp, l.gutter, 8.dp))
            SearchBox(query, { query = it }, stringResource(R.string.messages_share_hint))
            LazyColumn(Modifier.heightIn(max = SHEET_LIST_MAX).weight(1f, fill = false)) {
                items(results, key = { it.uri }) { card ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = with(l) { Modifier.fillMaxWidth().press({ onPick(card) }) }.padding(l.gutter, 8.dp)) {
                        GlideImage(model = card.images.best(100)?.url?.let(::spotifyImageUrl), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(if (card.kind == MediaKind.ARTIST) CircleShape else l.cover).background(l.raised))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(card.title, style = l.body, color = l.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(kindLabel(card.kind), card.subtitle).joinToString(" • "), style = l.caption,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The GIFs of the +: Spotify's GIF search (popular ones until something is typed); a tap sends one. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun GifSheet(onDismiss: () -> Unit, onPick: (Gif) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Gif>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(350)
        results = runCatching { MyspotiRuntime.api.messages.gifs(query) }.getOrDefault(emptyList())
    }
    val l = look()
    ChatSheet(onDismiss, full = true) {
        Column(Modifier.imePadding()) {
            Text(stringResource(R.string.messages_gif), style = if (l.immersive) Immersive.Headline else l.title.copy(fontSize = 18.sp),
                color = l.text, modifier = Modifier.padding(l.gutter, 0.dp, l.gutter, 8.dp))
            SearchBox(query, { query = it }, stringResource(R.string.messages_gif_hint))
            LazyVerticalGrid(
                // Two across on a phone, more on a tablet's wider sheet.
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(l.gutter, 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = SHEET_LIST_MAX).weight(1f, fill = false),
            ) {
                gridItems(results, key = { it.id }) { gif ->
                    GlideImage(model = gif.url, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = with(l) { Modifier.fillMaxWidth().height(120.dp).clip(l.cover).background(l.raised).press({ onPick(gif) }) })
                }
            }
            // GIPHY asks its users to name it under its results.
            Text(stringResource(R.string.messages_gif_giphy), style = l.caption, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable
private fun kindLabel(kind: MediaKind): String? = when (kind) {
    MediaKind.TRACK -> stringResource(R.string.messages_kind_track)
    MediaKind.ALBUM -> stringResource(R.string.messages_kind_album)
    MediaKind.ARTIST -> stringResource(R.string.messages_kind_artist)
    MediaKind.PLAYLIST -> stringResource(R.string.messages_kind_playlist)
    else -> null
}

// ---------------------------------------------------------------- Details

/**
 * A conversation's details: its people, and what the phone app lets the account change there
 * (name, members, notifications, leaving or deleting it).
 */
@Composable
fun ChatSettingsScreen(navController: NavController, chatId: String) {
    val api = MyspotiRuntime.api.messages
    var chat by remember { mutableStateOf(lastChats?.find { it.id == chatId }) }
    var muted by remember { mutableStateOf<Boolean?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(chatId, reload) {
        runCatching { api.chats() }.onSuccess { lastChats = it; chat = it.find { c -> c.id == chatId } ?: chat }
    }
    LaunchedEffect(chatId) { muted = runCatching { api.isMuted(chatId) }.getOrNull() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val l = look()

    fun act(change: suspend () -> Unit, after: () -> Unit = { reload++ }) {
        scope.launch { runCatching { change() }.onSuccess { after() }.onFailure { toast(context, refusal(context, it)) } }
    }
    val c = chat
    if (renaming && c != null) NameDialog(stringResource(R.string.messages_group_name), c.name.orEmpty(), onDismiss = { renaming = false }) { name ->
        renaming = false
        if (name.isNotBlank()) act({ api.rename(chatId, name.trim()) })
    }
    if (confirming && c != null) AlertDialog(
        onDismissRequest = { confirming = false },
        containerColor = l.sheet,
        title = { Text(stringResource(if (c.group) R.string.messages_leave else R.string.messages_delete_chat), color = l.text,
            style = if (l.immersive) Immersive.Headline else TextStyle(fontSize = 20.sp)) },
        text = { Text(stringResource(if (c.group) R.string.messages_leave_confirm else R.string.messages_delete_chat_confirm), style = l.body, color = l.muted) },
        confirmButton = {
            TextButton(onClick = {
                confirming = false
                act({ if (c.group) api.leave(chatId) else api.deleteChat(chatId) }) {
                    lastChats = lastChats?.filter { it.id != chatId }
                    navController.popBackStack(Routes.Messages.route, inclusive = false)
                }
            }) { Text(stringResource(if (c.group) R.string.messages_leave else R.string.messages_delete_chat), color = l.destructive) }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.messages_cancel), color = l.text) } },
    )

    val openProfile = { u: UserRef -> navController.navigate(com.music.spotui.ui.navigation.profileRoute(u.uri.substringAfterLast(':'), u.name)) }
    val addPeople = { navController.navigate(newChatRoute(addTo = chatId)) }
    val peopleHeader = stringResource(R.string.messages_members)
    ChatPage(c?.title() ?: stringResource(R.string.messages_settings), onBack = { navController.popBackStack() }, bottomPadding = 32.dp) {
        if (c == null) {
            pageState(l, loading = true, failed = null, empty = null) {}
            return@ChatPage
        }
        item(key = "face") {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val face = c.members.firstOrNull()
                com.music.spotui.ui.components.ProfileAvatar(url = if (c.group) null else face?.imageUrl, name = c.name ?: face?.name, size = 88.dp)
                // The Immersive page already titles itself with the conversation's name.
                if (!l.immersive) Text(c.title(), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (l.immersive) {
            immersiveGroup("settings") {
                if (c.group) row { ImmersiveGroupRow(stringResource(R.string.messages_rename), icon = Icons.Outlined.Edit, chevron = true, onClick = { renaming = true }) }
                muted?.let { on -> row { ImmersiveSwitchRow(stringResource(R.string.messages_mute), checked = on, onChecked = { want -> act({ api.setMuted(chatId, want) }) { muted = want } }) } }
            }
            immersiveGroup("people", header = peopleHeader) {
                if (c.group) row { ImmersiveGroupRow(stringResource(R.string.messages_add_people), icon = Icons.Filled.Add, onClick = addPeople) }
                c.members.forEach { u ->
                    row {
                        ImmersiveGroupRow(u.name, onClick = { openProfile(u) }, trailing = if (c.group && c.admin) {
                            { Icon(Icons.Outlined.PersonRemove, stringResource(R.string.messages_remove_member), tint = Immersive.TextSecondary,
                                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { act({ api.removeMember(chatId, u.uri) }) }.padding(6.dp)) }
                        } else null)
                    }
                }
            }
            immersiveGroup("leave") {
                row {
                    ImmersiveGroupRow(stringResource(if (c.group) R.string.messages_leave else R.string.messages_delete_chat),
                        destructive = true, onClick = { confirming = true })
                }
            }
        } else {
            if (c.group) item(key = "rename") { ActionRow(Icons.Outlined.Edit, stringResource(R.string.messages_rename)) { renaming = true } }
            item(key = "mute") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(20.dp, 6.dp)) {
                    Text(stringResource(R.string.messages_mute), color = AppText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    muted?.let { on ->
                        Switch(checked = on, onCheckedChange = { want -> act({ api.setMuted(chatId, want) }) { muted = want } },
                            colors = SwitchDefaults.colors(checkedTrackColor = AppAccent))
                    }
                }
            }
            item(key = "members") {
                Text(stringResource(R.string.messages_members), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp))
            }
            if (c.group) item(key = "add") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = addPeople).padding(16.dp, 8.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(l.raised), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, null, tint = AppText)
                    }
                    Text(stringResource(R.string.messages_add_people), color = AppText, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
            items(c.members, key = { it.uri }) { u ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { openProfile(u) }.padding(16.dp, 8.dp)) {
                    com.music.spotui.ui.components.ProfileAvatar(url = u.imageUrl, name = u.name, size = 48.dp)
                    Text(u.name, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp).weight(1f))
                    if (c.group && c.admin) Icon(Icons.Outlined.PersonRemove, stringResource(R.string.messages_remove_member), tint = AppMuted,
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { act({ api.removeMember(chatId, u.uri) }) }.padding(8.dp))
                }
            }
            item(key = "leave") {
                Spacer(Modifier.height(16.dp))
                ActionRow(Icons.Outlined.Delete, stringResource(if (c.group) R.string.messages_leave else R.string.messages_delete_chat), destructive = true) { confirming = true }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val l = look()
    if (l.immersive) {
        ImmersiveNameDialog(title, initial, confirm = stringResource(R.string.messages_save), onDismiss = onDismiss, onConfirm = onDone)
        return
    }
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = l.sheet,
        title = { Text(title, color = AppText) },
        text = { TextField(value = name, onValueChange = { name = it }, singleLine = true, colors = fieldColors(l)) },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text(stringResource(R.string.messages_save), color = AppAccent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.messages_cancel), color = AppText) } },
    )
}

@Composable
private fun fieldColors(l: Look) = TextFieldDefaults.colors(
    // The Immersive field is drawn by its glass; the Classic one by its own grey.
    focusedContainerColor = if (l.immersive) Color.Transparent else l.raised,
    unfocusedContainerColor = if (l.immersive) Color.Transparent else l.raised,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
    focusedTextColor = l.text, unfocusedTextColor = l.text, cursorColor = l.accent,
)

/** A shared item, opened as everywhere else: a track or an episode plays, the rest opens its page. */
private fun open(card: MediaCard, navController: NavController) {
    val id = card.uri.substringAfterLast(':')
    when (card.kind) {
        MediaKind.TRACK -> SongPlayer.playSong(card.uri)
        MediaKind.PLAYLIST -> navController.navigate(playlistRoute(id, card.title))
        MediaKind.ALBUM -> navController.navigate(albumRoute(card.title, card.subtitle.orEmpty(), id))
        MediaKind.ARTIST -> navController.navigate(artistRoute(card.title, id))
        MediaKind.SHOW -> navController.navigate(showRoute(id, card.title))
        MediaKind.EPISODE -> navController.navigate(episodeRoute(id, card.title))
        else -> {}
    }
}

/** The Classic look's header row. */
@Composable
private fun Header(title: String, onBack: () -> Unit, onTitle: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp, 12.dp, 8.dp, 8.dp),
        horizontalArrangement = Arrangement.Start) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accessibility_navigate_back), tint = AppText,
            modifier = Modifier.wideClickable(horizontal = 8.dp, vertical = 10.dp) { onBack() }.size(40.dp).padding(8.dp))
        Text(title, fontWeight = FontWeight.Bold, color = AppText, fontSize = 22.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp).weight(1f).then(if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier))
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

/** The tallest a sheet's results get; shorter on a phone held sideways, where the title, the field and GIPHY's line stay in view. */
private val SHEET_LIST_MAX = 420.dp
private const val POLL_MS = 5_000L
private const val GAP_MS = 15 * 60_000L
