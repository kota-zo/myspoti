package app.myspoti.api

/** What a uri points to. */
enum class MediaKind {
    TRACK, ALBUM, ARTIST, PLAYLIST, SHOW, EPISODE, AUDIOBOOK, CHAPTER, USER, GENRE,
    /** Liked Songs, Your Episodes and other built-in collections. */
    COLLECTION,
    FOLDER, UNKNOWN;

    companion object {
        fun of(uri: Uri): MediaKind = when {
            uri.startsWith("spotify:track:") -> TRACK
            uri.startsWith("spotify:album:") -> ALBUM
            uri.startsWith("spotify:artist:") -> ARTIST
            uri.startsWith("spotify:playlist:") -> PLAYLIST
            uri.startsWith("spotify:show:") -> SHOW
            uri.startsWith("spotify:episode:") -> EPISODE
            uri.startsWith("spotify:audiobook:") -> AUDIOBOOK
            uri.startsWith("spotify:chapter:") -> CHAPTER
            uri.startsWith("spotify:user:") && ":collection" in uri -> COLLECTION
            uri.startsWith("spotify:user:") -> USER
            uri.startsWith("spotify:genre:") || uri.startsWith("spotify:page:") -> GENRE
            uri.startsWith("spotify:collection:") -> COLLECTION
            uri.startsWith("spotify:folder:") || uri.contains(":folder:") -> FOLDER
            else -> UNKNOWN
        }
    }
}

/** A tile in a shelf: home, browse, search top results, "fans also like"... */
data class MediaCard(
    val uri: Uri,
    val kind: MediaKind,
    val title: String,
    val subtitle: String?,
    val images: List<Image>,
)

/**
 * One row of the account's "Recently played", as the Web Player's own list returns it: a track
 * or a context (playlist, album, artist, show) that was played. Spotify keeps the list, so it
 * covers every device of the account; the app's own play counter does not.
 */
data class RecentItem(
    val uri: Uri,
    val kind: MediaKind,
    val title: String,
    /** The artists (or the playlist's owner), when the server says. */
    val subtitle: String?,
    val images: List<Image>,
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    /** The play's day (epoch ms, local midnight): the list dates its rows, it does not time them. */
    val playedAtMs: Long? = null,
    /**
     * The server's own type word for the entity ("EP", "Playlist", "Artiste"), localised with the
     * account's language: the recents page prints it between the play count and the creators,
     * which a [MediaKind] alone cannot say (an EP is an album).
     */
    val typeLabel: String? = null,
)

data class Shelf(val uri: Uri?, val title: String?, val items: List<MediaCard>, val total: Int)

// --- Home and browse ---------------------------------------------------------

enum class HomeFilter(val facet: String) { ALL(""), MUSIC("music-chip"), PODCASTS("podcasts-chip"), AUDIOBOOKS("audiobooks-chip") }

data class HomeFeed(val greeting: String, val filters: List<HomeFilter>, val shelves: List<Shelf>)

data class Category(val uri: Uri, val title: String, val images: List<Image>, val color: String?)

data class BrowsePage(val uri: Uri, val title: String, val shelves: List<Shelf>)

// --- Library -----------------------------------------------------------------

enum class LibraryFilter(val id: String?) { ALL(null), PLAYLISTS("Playlists"), ARTISTS("Artists"), ALBUMS("Albums"), PODCASTS("Podcasts"), AUDIOBOOKS("Audiobooks") }

enum class LibrarySort(val id: String?) { RECENTS(null), RECENTLY_ADDED("Recently Added"), ALPHABETICAL("Alphabetical"), CREATOR("Creator"), CUSTOM("Custom Order") }

data class LibraryItem(
    val uri: Uri,
    val kind: MediaKind,
    val name: String,
    val subtitle: String?,
    val images: List<Image>,
    val pinned: Boolean,
    val addedAt: String?,
    /** Items in a folder, tracks in Liked Songs. */
    val count: Int? = null,
)

data class SavedTrack(val track: Track, val addedAt: String?)

// --- Users -------------------------------------------------------------------

data class Account(
    val uri: Uri,
    val username: String,
    val name: String,
    val images: List<Image>,
    val premium: Boolean,
    val country: String?,
)

data class UserProfile(
    val uri: Uri,
    val name: String,
    val images: List<Image>,
    val followers: Int?,
    val following: Int?,
    val publicPlaylists: List<Playlist>,
)

/**
 * The windows Spotify ranks the account's top artists and tracks over (the official app's
 * "Listening stats" and the profile's "Top artists this month"): about the past 4 weeks, the
 * past 6 months, and about a year.
 */
enum class TimeRange { SHORT_TERM, MEDIUM_TERM, LONG_TERM }

/**
 * The account's most played artists and tracks over a [TimeRange], best first. The totals are
 * how many Spotify ranks in that window (the lists hold at most the asked limit).
 */
data class TopContent(
    val artists: List<Artist>,
    val tracks: List<Track>,
    val artistTotal: Int? = null,
    val trackTotal: Int? = null,
)

/**
 * A friend's latest listen (the official "Friend Activity" feed): someone the account follows
 * who shares their listening activity. [trackImageUrl] is the cover Spotify sends with it and
 * [contextName] the playlist, album or artist it was played from.
 */
data class FriendActivity(
    val user: UserRef,
    val track: Track?,
    val contextUri: Uri?,
    val timestampMs: Long,
    val contextName: String? = null,
    val trackImageUrl: String? = null,
    /** What the mobile feed says (playing right now or not); null when the feed does not say. */
    val playing: Boolean? = null,
) {
    /** The feed's own answer when it gives one, else "listening now" for 15 minutes after the last play started. */
    fun isNowPlaying(nowMs: Long = System.currentTimeMillis()): Boolean = playing ?: (nowMs - timestampMs in 0 until NOW_PLAYING_MS)

    companion object { const val NOW_PLAYING_MS = 15 * 60_000L }
}

data class UserRef(val uri: Uri, val name: String, val imageUrl: String?)

/**
 * A conversation of Spotify's Messages: a direct one ([name] null) or a group. [members] are the
 * other people in it, never the account itself; [lastMessage] the latest one, for the list.
 */
data class Chat(
    val id: String,
    val name: String?,
    val members: List<UserRef>,
    val unread: Boolean,
    val lastMessage: ChatMessage?,
    val group: Boolean = false,
    /** The account administers this group (rename, remove members). */
    val admin: Boolean = false,
    val request: ChatRequest = ChatRequest.NONE,
)

/** A conversation still waiting for someone's yes: sent to the account, or sent by it. */
enum class ChatRequest { NONE, INCOMING, OUTGOING }

/**
 * One message: some text, something shared from Spotify ([shared]), a GIF ([gifUrl]), a photo or
 * video the app cannot show ([media]), or a line about the conversation itself ([event]).
 * [fromMe] is true for the account's own messages.
 */
data class ChatMessage(
    val id: String,
    val author: UserRef,
    val fromMe: Boolean,
    val timestampMs: Long,
    val text: String?,
    val shared: MediaCard?,
    val reactions: List<ChatReaction> = emptyList(),
    val event: ChatEvent? = null,
    val gifUrl: String? = null,
    val media: Boolean = false,
    /** A deleted message: only ever a conversation's [Chat.lastMessage], conversations leave them out. */
    val deleted: Boolean = false,
)

/** A GIF from Spotify's GIF search, to send in a conversation. */
data class Gif(val id: String, val url: String, val width: Int, val height: Int)

/** An emoji someone put on a message; its [id] is what takes it back off. */
data class ChatReaction(val id: String, val emoji: String, val user: UserRef, val fromMe: Boolean)

/** What happened to the conversation ([ChatMessage.author] did it; [subject] is the person it was done to). */
data class ChatEvent(val kind: Kind, val subject: UserRef? = null) {
    enum class Kind { CREATED, ADDED, JOINED, LEFT, RENAMED, NEW_ADMIN, PHOTO, REMOVED, JOINED_BY_LINK, REPORTED }
}

// --- Podcasts and audiobooks -------------------------------------------------

data class Show(
    val uri: Uri,
    val name: String,
    val publisher: String,
    val description: String?,
    val images: List<Image>,
    val saved: Boolean,
    val rating: Double?,
    val topics: List<String>,
)

data class Episode(
    val uri: Uri,
    val name: String,
    val description: String?,
    val durationMs: Long,
    val releaseDate: String?,
    val images: List<Image>,
    val show: AlbumRef?,
    /** Where the user stopped, and whether they finished it. */
    val playedPositionMs: Long,
    val played: Boolean,
    val explicit: Boolean,
    val playable: Boolean,
    val previewUrl: String?,
)

/**
 * A podcast episode's audio, as the Web Player resolves it (soundfinder REST endpoint).
 *
 * [url] is the publisher's own file — clear MP3/M4A, no DRM — when Spotify exposes one.
 * It is null for Spotify-exclusive shows, whose only copy is Widevine-encrypted.
 */
data class EpisodeAudio(
    val url: String?,
    /** "ALLOWED", "MANDATORY" or "NONE": whether the publisher's file exists. */
    val passthrough: String,
    /** Spotify's copy format ("MP4_128", "DO_NOT_USE"…); diagnostic only. */
    val format: String,
) {
    /** A clear file we can store and play ourselves. */
    val downloadable: Boolean get() = !url.isNullOrBlank() && !passthrough.equals("NONE", ignoreCase = true)
}

data class Audiobook(
    val uri: Uri,
    val name: String,
    val authors: List<String>,
    val narrators: List<String>,
    val description: String?,
    val images: List<Image>,
    val chapterCount: Int,
)

// --- Track extras ------------------------------------------------------------

data class LyricsLine(val startMs: Long, val endMs: Long?, val text: String, val words: List<LyricsWord>? = null)

data class LyricsWord(val startMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricsLine>,
    val synced: Boolean,
    val language: String?,
    val provider: String?,
    /** ARGB colours from Spotify's lyrics card. */
    val background: Int?,
    val text: Int?,
    val highlight: Int?,
)

data class Canvas(val uri: Uri, val videoUrl: String, val type: String)

data class Credits(val roles: List<CreditRole>)

data class CreditRole(val role: String, val artists: List<ArtistRef>)

// --- Devices and Jam ---------------------------------------------------------

data class Device(
    val id: String,
    val name: String,
    val type: String,
    val active: Boolean,
    val volume: Double?,
    /** This app's own player. */
    val local: Boolean,
)

enum class JamAvailability { AVAILABLE, RESTRICTED, UNKNOWN }

data class JamMember(val userUri: Uri, val name: String, val imageUrl: String?, val host: Boolean)

data class JamSession(
    val id: String,
    val joinToken: String?,
    val inviteLink: String?,
    val members: List<JamMember>,
    val isHost: Boolean,
    val queueOnly: Boolean,
)

data class HistoryEntry(val uri: Uri, val kind: MediaKind, val name: String, val images: List<Image>, val playedAtMs: Long?)
