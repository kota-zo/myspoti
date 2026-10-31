// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.spotify

import app.myspoti.api.Chat
import app.myspoti.api.ChatEvent
import app.myspoti.api.ChatMessage
import app.myspoti.api.ChatReaction
import app.myspoti.api.ChatRequest
import app.myspoti.api.Gif
import app.myspoti.api.Messages
import app.myspoti.api.UserRef
import app.myspoti.spotify.gql.Gql
import app.myspoti.spotify.gql.HttpException
import app.myspoti.spotify.gql.SpotifyHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A conversation as the server sends it, before its people and shares are looked up. */
internal data class RawChat(
    val id: String,
    val name: String?,
    val usernames: List<String>,
    val unread: Boolean,
    val last: RawMessage?,
    val group: Boolean = false,
    val admins: Set<String> = emptySet(),
    val request: ChatRequest = ChatRequest.NONE,
)

internal data class RawMessage(
    val id: String,
    val authorUsername: String,
    val timestampMs: Long,
    val text: String?,
    val sharedUri: String?,
    val reactions: List<RawReaction> = emptyList(),
    /** The kind of chat event, and the username it was done to (added, removed). */
    val event: Pair<ChatEvent.Kind, String?>? = null,
    val gifId: String? = null,
    val media: Boolean = false,
    val deleted: Boolean = false,
)

internal data class RawReaction(val id: String, val emoji: String, val username: String)

/**
 * A `Hierarchy` (a conversation): identifier 1 (id 1), statistic 3 (all events seen 2),
 * metadata 4 (status 2, the account's participation 3, type 5: 1 direct, 2 group; the
 * account's state 9 > phase 1: 2 a request to it, 3 a request from it), group 5 (name 1, members
 * 2 with their username 1 and role 3, 2 = admin). Null for one that was deleted, cancelled,
 * rejected or blocked, or that the account deleted or left.
 */
internal fun rawChat(h: ProtoMessage, last: RawMessage?): RawChat? {
    val id = h.message(1)?.string(1)?.takeIf { it.isNotEmpty() } ?: return null
    val meta = h.message(4)
    if (meta?.long(2)?.let { it in CLOSED_STATUSES } == true || meta?.long(3)?.let { it in GONE_PARTICIPATIONS } == true) return null
    val group = h.message(5)
    val members = group?.messages(2).orEmpty()
    return RawChat(
        id = id,
        name = group?.string(1)?.takeIf { it.isNotBlank() },
        usernames = members.mapNotNull { it.string(1) }.ifEmpty { h.message(2)?.strings(2).orEmpty() },
        unread = h.message(3)?.long(2) != 1L,
        last = last,
        group = meta?.long(5) == 2L,
        admins = members.filter { it.long(3) == 2L }.mapNotNull { it.string(1) }.toSet(),
        request = when (meta?.message(9)?.long(1)) {
            2L -> ChatRequest.INCOMING
            3L -> ChatRequest.OUTGOING
            else -> ChatRequest.NONE
        },
    )
}

/**
 * A `ClientNode`: identifier 1 (node id 1), children 3 (reactions), moderation 5, event capture
 * 6 (author 1, event 2 with its time 1), payload 8. Contributions (8.1) are messages: text 1,
 * a Spotify share 2 or 3 (uri 1), a chat event 5, rich media 6 (a GIF 1 with its id 1, a sticker
 * 2, a photo or video 3). Receipts and acks (8.2), removed, hidden and deleted messages are left
 * out; with [keepDeleted] a deleted one comes back as such (a conversation's last message).
 */
internal fun rawMessage(n: ProtoMessage, keepDeleted: Boolean = false): RawMessage? {
    val id = n.message(1)?.string(1) ?: return null
    // Moderation: removed 2, hidden 3, deleted for the account 4, deleted for all 5.
    val moderation = n.message(5)
    if (moderation?.let { m -> (2..5).any(m::has) } == true) {
        if (!keepDeleted || !(moderation.has(4) || moderation.has(5))) return null
        val capture = n.message(6)
        return RawMessage(id, capture?.string(1) ?: "", capture?.message(2)?.message(1)?.timestampMs() ?: 0, null, null, deleted = true)
    }
    val c = n.message(8)?.message(1) ?: return null
    val text = c.message(1)?.string(1)?.takeIf { it.isNotBlank() }
    val shared = (c.message(2) ?: c.message(3))?.string(1)?.substringBefore('?')?.takeIf { it.startsWith("spotify:") }
    val event = c.message(5)?.message(1)?.let(::chatEvent)
    val rich = c.message(6)
    val gifId = rich?.message(1)?.string(1)?.takeIf { it.isNotEmpty() }
    val media = rich != null && gifId == null
    // An invite to the chat (4) or a taste match card (8): shown as a share without a name.
    val other = c.has(4) || c.has(8)
    if (text == null && shared == null && event == null && gifId == null && !media && !other) return null
    val capture = n.message(6)
    return RawMessage(
        id, capture?.string(1) ?: "", capture?.message(2)?.message(1)?.timestampMs() ?: 0, text, shared,
        reactions = n.messages(3).mapNotNull(::rawReaction), event = event, gifId = gifId, media = media || (other && text == null && shared == null),
    )
}

/** A child node holding a reaction: payload 8 > activity 2 > reaction 1 (emoji 3, who 2). */
private fun rawReaction(n: ProtoMessage): RawReaction? {
    if (n.message(5)?.let { m -> (2..5).any(m::has) } == true) return null
    val id = n.message(1)?.string(1) ?: return null
    val r = n.message(8)?.message(2)?.message(1) ?: return null
    val emoji = r.string(3)?.takeIf { it.isNotEmpty() } ?: REACTION_EMOTI[r.long(1)] ?: return null
    return RawReaction(id, emoji, r.string(2) ?: n.message(6)?.string(1) ?: "")
}

/** `ChatEventType`: which event (field number) and the username it names, if any. */
private fun chatEvent(t: ProtoMessage): Pair<ChatEvent.Kind, String?>? = when {
    t.has(1) -> ChatEvent.Kind.CREATED to null
    t.has(2) -> ChatEvent.Kind.ADDED to t.message(2)?.string(1)
    t.has(3) -> ChatEvent.Kind.JOINED to null
    t.has(4) -> ChatEvent.Kind.LEFT to null
    t.has(5) -> ChatEvent.Kind.RENAMED to null
    t.has(6) -> ChatEvent.Kind.NEW_ADMIN to null
    t.has(7) -> ChatEvent.Kind.PHOTO to null
    t.has(8) -> ChatEvent.Kind.REMOVED to t.message(8)?.string(1)
    t.has(9) -> ChatEvent.Kind.JOINED_BY_LINK to null
    t.has(10) -> ChatEvent.Kind.REPORTED to null
    else -> null
}

/** The fixed reactions of older messages (`ReactionEmoti`), for those without their emoji. */
private val REACTION_EMOTI = mapOf(1L to "👍", 2L to "❤️", 3L to "😂", 7L to "🥺", 8L to "👎", 9L to "🔥", 10L to "🎧")

// Each list once per id: the screens key their rows by it, and a repeated key crashes the list.

/** `RetrieveLatestNodeResponse`: each conversation (1) with its hierarchy (1) and latest node (2). */
internal fun rawChats(body: ByteArray): List<RawChat> =
    parseProto(body).messages(1).mapNotNull { e -> e.message(1)?.let { rawChat(it, e.message(2)?.let { n -> rawMessage(n, keepDeleted = true) }) } }
        .distinctBy { it.id }

/** `RetrieveNodeResponse`: the conversation's nodes (2), oldest first. */
internal fun rawMessages(body: ByteArray): List<RawMessage> =
    parseProto(body).messages(2).mapNotNull(::rawMessage).distinctBy { it.id }.sortedBy { it.timestampMs }

/** gif-proxy's `SearchResponse` / `PopularResponse`: GIFs (1) with id 1, url 2, width 5, height 6. */
internal fun rawGifs(body: ByteArray): List<Gif> = parseProto(body).messages(1).mapNotNull { g ->
    val id = g.string(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val url = g.string(2)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    Gif(id, url, g.long(5)?.toInt() ?: 0, g.long(6)?.toInt() ?: 0)
}.distinctBy { it.id }

/** `ClientNodeResponse` or `CreateGroupResponse`: the conversation a request opened (hierarchy 1 > identifier 1 > id 1). */
internal fun createdChatId(body: ByteArray): String? =
    parseProto(body).message(1)?.message(1)?.string(1)?.takeIf { it.isNotEmpty() }

/**
 * `CanMessageUsersResponse`: the shared conversations per username (map 2: key 1, value 2 =
 * `SharedHierarchies` > entries 1 with identifier 1 (id 1) and type 2). The direct one (type 1)
 * with [username], if any.
 */
internal fun directChatId(body: ByteArray, username: String): String? =
    parseProto(body).messages(2).filter { it.string(1) == username }
        .flatMap { it.message(2)?.messages(1).orEmpty() }
        .firstOrNull { it.long(2) == 1L }
        ?.message(1)?.string(1)?.takeIf { it.isNotEmpty() }

private val CLOSED_STATUSES = setOf(3L, 4L, 5L, 6L)
/** The account's participation: deleted 6, left 7. */
private val GONE_PARTICIPATIONS = setOf(6L, 7L)

/**
 * Spotify's Messages, on the mobile client's `speechless` service (protobuf over spclient),
 * which the page's token reads and writes as the phone app does. Conversations are
 * "hierarchies", messages "nodes"; the server names people by username only, so names, avatars
 * and shared items are looked up afterwards. Every request here is the one the phone app sends
 * (read from its code), field numbers in the comments.
 */
class SpotifyMessages(private val gql: Gql, private val http: SpotifyHttp, private val users: SpotifyUsers) : Messages {

    override suspend fun chats(): List<Chat> {
        // RetrieveLatestNodeRequest: newest first (1 = 2), 50 conversations.
        val raw = rawChats(call("retrieve/chats-share-set/latest", proto { varint(1, 2); varint(2, 50) }))
        val me = users.ownUsername()
        val looked = lookUp(raw.flatMap { it.usernames + listOfNotNull(it.last?.authorUsername) }, raw.mapNotNull { it.last?.sharedUri })
        return raw.map { c ->
            Chat(c.id, c.name, c.usernames.filter { it != me }.map { looked.user(it) }, c.unread, c.last?.let { looked.message(it, me) },
                group = c.group, admin = me in c.admins, request = c.request)
        }
    }

    override suspend fun messages(chatId: String, limit: Int): List<ChatMessage> {
        // RetrieveNodeRequest: the conversation (1), newest first (5 = 2), [limit] before now (3).
        val raw = rawMessages(call("retrieve/hierarchy", proto { string(1, chatId); varint(3, limit.toLong()); varint(5, 2) }))
        val me = users.ownUsername()
        val people = raw.flatMap { m -> listOf(m.authorUsername) + m.reactions.map { it.username } + listOfNotNull(m.event?.second) }
        val looked = lookUp(people, raw.mapNotNull { it.sharedUri })
        return raw.map { looked.message(it, me) }
    }

    override suspend fun send(chatId: String, text: String) = contribute(chatId) { message(1) { string(1, text) } }

    // TrackedShare (2): the uri (1), as the phone app's share sheet sends it.
    override suspend fun share(chatId: String, uri: String) = contribute(chatId) { message(2) { string(1, uri) } }

    /**
     * ClientNodeRequest > NodeRequest > MessageRequest > AddContribution (1: the conversation,
     * 2: ContributionPayload written by [payload]).
     */
    private suspend fun contribute(chatId: String, payload: ProtoWriter.() -> Unit) {
        submit { message(2) { message(1) { string(1, chatId); message(2, payload) } } }
    }

    // gif-proxy (Spotify's own GIPHY proxy): SearchRequest (query 1, limit 2) or PopularRequest
    // (limit 1), both rated up to PG-13 (filter > rating 1 = 4) as the phone app asks.
    override suspend fun gifs(query: String): List<Gif> {
        val body = if (query.isBlank()) proto { varint(1, 30); message(2) { varint(1, 4) } }
        else proto { string(1, query.trim()); varint(2, 30); message(4) { varint(1, 4) } }
        val (code, answer) = http.sendProto("POST", "https://spclient.wg.spotify.com/gif-proxy/v1/${if (query.isBlank()) "popular" else "search"}", body)
        if (code != 200) throw HttpException(code, answer.toString(Charsets.UTF_8).take(200))
        return rawGifs(answer)
    }

    // RichMedia (6) > GiphyGif (1: its id 1).
    override suspend fun sendGif(chatId: String, gif: Gif) = contribute(chatId) { message(6) { message(1) { string(1, gif.id) } } }

    override suspend fun startChat(userUri: String): String {
        val username = userUri.substringAfterLast(':')
        // The direct conversation Spotify already keeps with that person, even an empty one (a
        // song shared years ago opens one): the phone app asks `can-message-users` (targets 1,
        // direct ones only 2 = 1) and writes there. Creating another made a two-person group.
        directChatId(call("can-message-users", proto { string(1, username); varint(2, 1) }), username)?.let { return it }
        // HierarchyRequest (1) > CreateNew (8): a direct conversation (type 1) with one person,
        // as the phone app's "new message". The answer's hierarchy is it.
        val answer = submit { message(1) { message(8) { varint(1, 1); string(2, username) } } }
        return createdChatId(answer) ?: throw IllegalStateException("Spotify did not open the conversation")
    }

    // BulkMessageRequest (3) > AddHierarchyActivity (2: the conversation 1, ActivityRequest 2 > ack 3 = user seen 1).
    override suspend fun markSeen(chatId: String) {
        submit { message(3) { message(2) { string(1, chatId); message(2) { varint(3, 1) } } } }
    }

    // MessageRequest > AddActivity (4: the message 1, ActivityRequest 2 > EmojiReaction 4 > emoji 1).
    override suspend fun react(messageId: String, emoji: String) {
        submit { message(2) { message(4) { string(1, messageId); message(2) { message(4) { string(1, emoji) } } } } }
    }

    // MessageRequest > DeleteActivity (5): the reaction's own node (not the message's), and its emoji.
    override suspend fun unreact(reaction: ChatReaction) {
        submit { message(2) { message(5) { string(1, reaction.id); message(2) { message(4) { string(1, reaction.emoji) } } } } }
    }

    // MessageRequest > DeleteContribution (3: the message 1, for everyone 2).
    override suspend fun delete(messageId: String, forEveryone: Boolean) {
        submit { message(2) { message(3) { string(1, messageId); if (forEveryone) varint(2, 1) } } }
    }

    // HierarchyRequest > SetParticipationStatus (4: the conversation 1, status 2: accepted 2, rejected 4).
    override suspend fun answerRequest(chatId: String, accept: Boolean) {
        submit { message(1) { message(4) { string(1, chatId); varint(2, if (accept) 2 else 4) } } }
    }

    // GetHierarchySettings: muted for the account (1).
    override suspend fun isMuted(chatId: String): Boolean =
        parseProto(call("hierarchy/settings", proto { string(1, chatId) })).long(1) == 1L

    // MuteHierarchyRequest (the conversation 1; no duration: until unmuted) / UnmuteHierarchyRequest.
    override suspend fun setMuted(chatId: String, muted: Boolean) {
        call(if (muted) "hierarchy/settings/mute" else "hierarchy/settings/unmute", proto { string(1, chatId) })
    }

    // DeleteChatForMeRequest: the conversation (1).
    override suspend fun deleteChat(chatId: String) {
        call("hierarchy/delete", proto { string(1, chatId) })
    }

    // CreateGroupRequest: the members' usernames (2), a name (3).
    override suspend fun createGroup(userUris: List<String>, name: String?): String {
        val answer = call("group/create", proto {
            userUris.forEach { string(2, it.substringAfterLast(':')) }
            name?.takeIf { it.isNotBlank() }?.let { string(3, it) }
        })
        return createdChatId(answer) ?: throw IllegalStateException("Spotify did not create the group")
    }

    // RenameGroupRequest > RenameGroup (1: the conversation 1, the name 2).
    override suspend fun rename(chatId: String, name: String) {
        call("group/rename", proto { message(1) { string(1, chatId); string(2, name) } })
    }

    // AddMembersRequest > AddMembers (1: the conversation 1, usernames 2).
    override suspend fun addMembers(chatId: String, userUris: List<String>) {
        call("group/add-members", proto { message(1) { string(1, chatId); userUris.forEach { string(2, it.substringAfterLast(':')) } } })
    }

    // RemoveMemberRequest > RemoveMember (1: the conversation 1, the username 2).
    override suspend fun removeMember(chatId: String, userUri: String) {
        call("group/remove-member", proto { message(1) { string(1, chatId); string(2, userUri.substringAfterLast(':')) } })
    }

    // LeaveGroupRequest: the conversation (1), leave mode (2: 2 = delete it from the account's list too).
    override suspend fun leave(chatId: String) {
        call("group/leave", proto { string(1, chatId); varint(2, 2) })
    }

    /** One ClientNodeRequest (`submit/request`) with one NodeRequest (1) written by [request]. */
    private suspend fun submit(request: ProtoWriter.() -> Unit): ByteArray = call("submit/request", proto { message(1, request) })

    private suspend fun call(path: String, body: ByteArray): ByteArray {
        val (code, answer) = http.sendProto("POST", "https://spclient.wg.spotify.com/speechless/v1/$path", body)
        if (code != 200) throw HttpException(code, answer.toString(Charsets.UTF_8).take(200))
        return answer
    }

    private class Looked(val entities: Map<String, kotlinx.serialization.json.JsonElement>, val avatars: Map<String, String>) {
        fun user(username: String): UserRef {
            val uri = "spotify:user:$username"
            return UserRef(uri, userName(entities, uri), avatars[uri])
        }

        fun message(m: RawMessage, me: String) = ChatMessage(
            m.id, user(m.authorUsername), m.authorUsername == me, m.timestampMs, m.text, m.sharedUri?.let { card(entities[it]) },
            reactions = m.reactions.map { ChatReaction(it.id, it.emoji, user(it.username), it.username == me) },
            event = m.event?.let { (kind, subject) -> ChatEvent(kind, subject?.let(::user)) },
            // GIPHY's 200 px wide copy: the original can weigh megabytes, a lot for a bubble on mobile data.
            gifUrl = m.gifId?.let { "https://media.giphy.com/media/$it/200w.gif" },
            media = m.media,
            deleted = m.deleted,
        )
    }

    /** The people's names and avatars and the shared items, best effort: a failed lookup leaves ids. */
    private suspend fun lookUp(usernames: List<String>, sharedUris: List<String>): Looked = coroutineScope {
        val userUris = usernames.filter { it.isNotEmpty() }.distinct().map { "spotify:user:$it" }
        val entities = async { runCatching { lookupEntities(gql, userUris + sharedUris) }.getOrDefault(emptyMap()) }
        val avatars = async { runCatching { lookupAvatars(gql, userUris) }.getOrDefault(emptyMap()) }
        Looked(entities.await(), avatars.await())
    }
}
