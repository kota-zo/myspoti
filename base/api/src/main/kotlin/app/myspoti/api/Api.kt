package app.myspoti.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Entry point the UI talks to. One instance per signed-in account. */
interface MyspotiApi {
    val player: Player
    val search: Search
    val catalog: Catalog
    val library: Library
    val playlists: Playlists
    val home: Home
    val podcasts: Podcasts
    val users: Users
    val extras: TrackExtras
    val devices: Devices
    val jam: Jam
    /** Blends ("Mix à plusieurs"): playlists made of several people's taste, joined through an invitation link. */
    val blends: Blends
    /** The account's "Recently played", as Spotify's own list has it. */
    val recents: Recents
    /** Spotify's Messages: the account's conversations, read and written. */
    val messages: Messages

    /** False until the playback engine is ready to take commands. */
    val ready: StateFlow<Boolean>
}

interface Player {
    /** Latest state, null when no playback session exists yet. */
    val state: StateFlow<PlayerState?>
    val queue: StateFlow<Queue?>

    /** Plays [contextUri] (playlist, album, artist, show, collection...), starting at [trackUri] or [index] if given. */
    /**
     * Plays [contextUri] from [trackUri] (or [index]). With [order], the context's songs play
     * in that order instead of the context's own (a sorted playlist).
     */
    suspend fun play(contextUri: Uri, trackUri: Uri? = null, index: Int? = null, positionMs: Long? = null, order: List<Uri>? = null)
    suspend fun pause()
    suspend fun resume()
    suspend fun seek(positionMs: Long)
    suspend fun next()
    suspend fun previous()
    suspend fun setShuffle(on: Boolean)
    suspend fun setRepeat(mode: Repeat)
    suspend fun addToQueue(uris: List<Uri>)
    suspend fun removeFromQueue(items: List<NowPlaying>)
    suspend fun clearQueue()
    /** Jumps to an item of [Queue.queued] or [Queue.nextUp]. */
    suspend fun skipTo(item: NowPlaying)
    /** "Play next": before everything already queued. */
    suspend fun playNext(uris: List<Uri>)
    /** Queues [uris] just before [before], a queued item of [Queue.queued]. */
    suspend fun insertInQueue(uris: List<Uri>, before: NowPlaying)
    /** Moves [item] just before or after [target], in the same part of the queue. */
    suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean)

    /** Volume from 0.0 to 1.0. */
    suspend fun volume(): Double
    suspend fun setVolume(level: Double)
    /** Playback speed (podcasts): 0.5 to 3.5. */
    suspend fun setSpeed(speed: Double)
    /**
     * Unheard on this phone while it keeps playing: an extension plays the song itself and only
     * mirrors it here. The app then ignores this player's state ([localMute]).
     */
    suspend fun setLocalMute(on: Boolean) {}
    val localMute: Boolean get() = false
}

interface Search {
    /** Top results of every music kind at once, for the search screen's first view. */
    suspend fun all(query: String, limit: Int = 10): SearchResults
    suspend fun tracks(query: String, offset: Int = 0, limit: Int = 30): Page<Track>
    suspend fun artists(query: String, offset: Int = 0, limit: Int = 30): Page<Artist>
    suspend fun albums(query: String, offset: Int = 0, limit: Int = 30): Page<Album>
    suspend fun playlists(query: String, offset: Int = 0, limit: Int = 30): Page<Playlist>
    suspend fun shows(query: String, offset: Int = 0, limit: Int = 30): Page<MediaCard>
    suspend fun episodes(query: String, offset: Int = 0, limit: Int = 30): Page<Episode>
    suspend fun audiobooks(query: String, offset: Int = 0, limit: Int = 30): Page<MediaCard>
    suspend fun users(query: String, offset: Int = 0, limit: Int = 30): Page<MediaCard>
    suspend fun genres(query: String, offset: Int = 0, limit: Int = 30): Page<MediaCard>
    /** Completions while typing. */
    suspend fun suggestions(query: String, limit: Int = 10): List<String>
    suspend fun recent(): List<MediaCard>
    suspend fun clearRecent()
}

interface Catalog {
    suspend fun track(uri: Uri): Track
    suspend fun album(uri: Uri): Album
    suspend fun albumTracks(uri: Uri, offset: Int = 0, limit: Int = 50): Page<Track>
    suspend fun artist(uri: Uri): Artist
    suspend fun artistTopTracks(uri: Uri): List<Track>
    suspend fun artistPage(uri: Uri): ArtistPage
    /** The recording's ISRC (to match the same recording on other platforms). */
    suspend fun isrc(trackUri: Uri): String?
    /** Albums, singles and compilations, newest first. */
    suspend fun discography(uri: Uri, offset: Int = 0, limit: Int = 50): Page<Album>
    suspend fun relatedArtists(uri: Uri): List<Artist>
    suspend fun appearsOn(uri: Uri): List<Album>
    /** "More like this" albums for a track. */
    suspend fun similarAlbums(trackUri: Uri, limit: Int = 20): List<Album>
}

interface Library {
    /** Whether each uri (track, album, artist, show, playlist) is saved, in the same order. */
    suspend fun contains(uris: List<Uri>): List<Boolean>
    suspend fun add(uris: List<Uri>)
    suspend fun remove(uris: List<Uri>)

    /** The "Your Library" list, as the Web Player shows it. [folderUri] opens a folder. */
    suspend fun items(
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: LibrarySort = LibrarySort.RECENTS,
        folderUri: Uri? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): Page<LibraryItem>

    suspend fun likedTracks(offset: Int = 0, limit: Int = 50): Page<SavedTrack>
    suspend fun pin(uri: Uri)
    suspend fun unpin(uri: Uri)

    suspend fun createFolder(name: String, parentFolderUri: Uri? = null): Uri
    suspend fun renameFolder(folderUri: Uri, name: String)
    suspend fun deleteFolder(folderUri: Uri)
    /** Moves a playlist or folder into [folderUri] (null = top level). */
    suspend fun moveToFolder(uri: Uri, folderUri: Uri?)

    /** Pushed when the library changes, from this device or another one. */
    val changes: Flow<Unit>
}

interface Playlists {
    suspend fun playlist(uri: Uri): Playlist
    suspend fun tracks(uri: Uri, offset: Int = 0, limit: Int = 100): Page<Track>
    /** Every row, sorted on the client (the server has no sort). Use [sortedFor] to sort rows already loaded. */
    suspend fun sortedTracks(uri: Uri, sort: PlaylistSort = PlaylistSort.CUSTOM, descending: Boolean = false): List<Track>
    /** The user's own playlists and the ones they follow, in library order, folders flattened. */
    suspend fun mine(): List<Playlist>
    /** Playlists the user may add tracks to. */
    suspend fun editable(): List<Playlist>

    /** A new playlist at the top of the library, or at the top of [folderUri]. */
    suspend fun create(name: String, folderUri: Uri? = null): Uri
    suspend fun rename(uri: Uri, name: String)
    suspend fun setDescription(uri: Uri, description: String)
    /** Who may see or change the playlist (owner only). */
    suspend fun access(uri: Uri): PlaylistAccess
    suspend fun setAccess(uri: Uri, access: PlaylistAccess)
    /** Shown on the user's profile (the official "Add to profile"); a private playlist never is. */
    suspend fun published(uri: Uri): Boolean
    suspend fun setPublished(uri: Uri, published: Boolean)
    /** Public playlists show on the profile and in search; private ones only to the owner. */
    suspend fun setPublic(uri: Uri, public: Boolean) = setAccess(uri, if (public) PlaylistAccess.PUBLIC else PlaylistAccess.PRIVATE)
    /** Collaborative playlists let other users add and remove tracks (owner only). */
    suspend fun setCollaborative(uri: Uri, collaborative: Boolean) = setAccess(uri, if (collaborative) PlaylistAccess.COLLABORATIVE else PlaylistAccess.PUBLIC)
    /** A new cover (JPEG bytes), or null to go back to the generated one. */
    suspend fun setCover(uri: Uri, jpeg: ByteArray?)
    /** Removes the playlist from the library (owners: deletes it; it can be restored for 90 days). */
    suspend fun delete(uri: Uri)
    suspend fun follow(uri: Uri)
    suspend fun unfollow(uri: Uri)
    /**
     * Joins a collaborative playlist through the `pt` token of its invitation link
     * (`open.spotify.com/playlist/{id}?si=…&pt={token}`): the account becomes a member with the
     * level the token grants and the playlist is put in the library when it is not there yet.
     * Fails (HTTP 400) when the token is wrong or has expired. Returns the level granted.
     */
    suspend fun join(uri: Uri, invitationToken: String): PlaylistAccess

    /**
     * The invitation link of a collaborative playlist (owner): whoever opens it becomes a
     * collaborator ([join]). A plain link only lets people see the playlist. Valid 7 days.
     */
    suspend fun collaboratorInvite(uri: Uri): String

    /** The owner and the collaborators of a playlist (just the owner when it is not shared). */
    suspend fun members(uri: Uri): List<PlaylistMember>

    /**
     * Leaves a playlist this account joined as a collaborator ([Playlist.canLeave]): the page's
     * `PlaylistPermissionsAPI.removeMember(uri, ownUsername)`. A private one (owner-only base
     * permission) also leaves the library, as in the Web Player's dialog.
     */
    suspend fun leave(uri: Uri)

    /**
     * Owner: turns a member into a collaborator or back into a listener (the "Shared with" list's
     * menu): `PlaylistPermissionsAPI.setMemberPermission(uri, username, "CONTRIBUTOR" | "VIEWER")`.
     */
    suspend fun setMemberCollaborator(uri: Uri, username: String, collaborator: Boolean)

    /** Owner: removes a member from the playlist (`PlaylistPermissionsAPI.removeMember(uri, username)`). */
    suspend fun removeMember(uri: Uri, username: String)

    /**
     * Owner: stops collaborating. Every collaborator becomes a listener when the playlist is private
     * (not listed on the profile), or is removed from it when it is public; then the base permission
     * goes back to private / public. The invitation links already shared are NOT revoked: the Web
     * Player has no call for it (they expire by themselves, after 7 days).
     */
    suspend fun stopCollaboration(uri: Uri)

    /** Who added each row of a playlist (shown on collaborative ones). */
    suspend fun addedBy(uri: Uri): List<RowAdder>

    suspend fun addTracks(uri: Uri, trackUris: List<Uri>)
    /** Rows are addressed by [Track.uid]. */
    suspend fun removeTracks(uri: Uri, uids: List<String>)
    suspend fun moveTracks(uri: Uri, uids: List<String>, beforeUid: String?)
    /** Tracks Spotify suggests adding at the bottom of the playlist. */
    suspend fun recommended(uri: Uri, limit: Int = 10, skip: List<Uri> = emptyList()): List<Track>
}

interface Home {
    suspend fun feed(filter: HomeFilter = HomeFilter.ALL, itemsPerShelf: Int = 10): HomeFeed
    suspend fun categories(): List<Category>
    suspend fun category(uri: Uri): BrowsePage
    /** New releases from the artists the user follows, newest first ("What's new"). */
    suspend fun whatsNew(offset: Int = 0, limit: Int = 50): Page<Album>
}

interface Podcasts {
    suspend fun show(uri: Uri): Show
    /**
     * The episode's audio, resolved right before a download (the links are signed and expire).
     *
     * Spotify serves its own copy only as a Widevine-encrypted MP4; the clear file we can play
     * offline is the publisher's own upload ("passthrough"). It does not exist for
     * Spotify-exclusive shows, which report [EpisodeAudio.passthrough] = "NONE".
     */
    suspend fun audioUrl(uri: Uri): EpisodeAudio
    suspend fun episodes(uri: Uri, offset: Int = 0, limit: Int = 50): Page<Episode>
    suspend fun episode(uri: Uri): Episode
    suspend fun audiobook(uri: Uri): Audiobook
    suspend fun chapters(uri: Uri, offset: Int = 0, limit: Int = 50): Page<Episode>
    /** Episodes the user saved ("Your Episodes"). */
    suspend fun savedEpisodes(offset: Int = 0, limit: Int = 50): Page<Episode>
    suspend fun setEpisodeSaved(uri: Uri, saved: Boolean)
}

interface Users {
    suspend fun me(): Account
    suspend fun profile(userUri: Uri): UserProfile
    /** People following this user. */
    suspend fun followers(userUri: Uri): List<UserRef>
    /** People this user follows. */
    suspend fun following(userUri: Uri): List<UserRef>
    suspend fun top(range: TimeRange = TimeRange.SHORT_TERM, limit: Int = 10): TopContent
    suspend fun follow(userUris: List<Uri>)
    suspend fun unfollow(userUris: List<Uri>)
    suspend fun isFollowing(userUris: List<Uri>): List<Boolean>
    /** Whether this account blocked [userUri] (the Web Player's profile menu: `GET /socialgraph/v4/{me}/is-blocking/{user}`). */
    suspend fun isBlocked(userUri: Uri): Boolean
    /** Blocks [userUri] (`POST /socialgraph/v4/{me}/is-blocking/{user}`); Spotify also drops the follow between the two. */
    suspend fun blockUser(userUri: Uri)
    /** Lifts the block ([blockUser]'s `DELETE`). */
    suspend fun unblockUser(userUri: Uri)
    /**
     * What the people the account follows are listening to, latest first. Throws when Spotify
     * refuses the feed (an empty list means nobody shares their activity).
     */
    suspend fun friendActivity(): List<FriendActivity>
    /** Contexts played lately (Recents). */
    suspend fun history(): List<HistoryEntry>
    suspend fun setPrivateSession(on: Boolean)
}

interface Messages {
    /** The account's conversations, latest activity first. */
    suspend fun chats(): List<Chat>
    /** The latest messages of a conversation, oldest first. */
    suspend fun messages(chatId: String, limit: Int = 50): List<ChatMessage>
    /** Sends [text] to the conversation. */
    suspend fun send(chatId: String, text: String)
    /** GIFs matching [query], or the popular ones when it is blank. */
    suspend fun gifs(query: String): List<Gif>
    suspend fun sendGif(chatId: String, gif: Gif)
    /** Opens a direct conversation with [userUri] (the existing one, if any) and returns its id. */
    suspend fun startChat(userUri: Uri): String
    /** Shares a track, album, playlist, artist, show or episode in the conversation. */
    suspend fun share(chatId: String, uri: Uri)
    /** Tells Spotify the account has seen the conversation up to now. */
    suspend fun markSeen(chatId: String)
    suspend fun react(messageId: String, emoji: String)
    /** Takes back one of the account's own reactions. */
    suspend fun unreact(reaction: ChatReaction)
    /** Deletes a message: for everyone (the account's own messages) or only for the account. */
    suspend fun delete(messageId: String, forEveryone: Boolean)
    /** Accepts or declines a conversation someone started with the account. */
    suspend fun answerRequest(chatId: String, accept: Boolean)
    suspend fun isMuted(chatId: String): Boolean
    suspend fun setMuted(chatId: String, muted: Boolean)
    /** Removes the conversation from the account's list (the others keep it). */
    suspend fun deleteChat(chatId: String)
    /** Creates a group with these people and returns its id. */
    suspend fun createGroup(userUris: List<Uri>, name: String?): String
    suspend fun rename(chatId: String, name: String)
    suspend fun addMembers(chatId: String, userUris: List<Uri>)
    suspend fun removeMember(chatId: String, userUri: Uri)
    /** Leaves a group; it leaves the account's list too. */
    suspend fun leave(chatId: String)
}

interface TrackExtras {
    suspend fun lyrics(trackUri: Uri): Lyrics?
    suspend fun canvas(trackUri: Uri): Canvas?
    suspend fun credits(trackUri: Uri): Credits
    /** The track's radio, as a playlist uri to play. Works with artist uris too. */
    suspend fun radio(seedUri: Uri): Uri?
    /** Hide this track from recommendations, or show it again. */
    suspend fun setExcludedFromTaste(trackUri: Uri, excluded: Boolean)
    suspend fun isExcludedFromTaste(trackUris: List<Uri>): List<Boolean>
    /** "Don't play this artist": the artist is kept out of recommendations and radios. */
    suspend fun setArtistBanned(artistUri: Uri, banned: Boolean)
    suspend fun isArtistBanned(artistUris: List<Uri>): List<Boolean>
}

interface Devices {
    /** Spotify Connect devices of the account, this app's player included. */
    val devices: StateFlow<List<Device>>
    suspend fun refresh()
    suspend fun transfer(deviceId: String, play: Boolean = true)
    /** Brings playback back to this app. */
    suspend fun pullHere()
}

interface Jam {
    suspend fun availability(): JamAvailability
    val session: StateFlow<JamSession?>
    suspend fun refresh()
    suspend fun start(): JamSession
    suspend fun join(joinToken: String): JamSession
    suspend fun leave()
    suspend fun end()
    suspend fun inviteLink(): String?
    suspend fun removeMember(userUri: Uri)
    suspend fun setQueueOnly(on: Boolean)
    suspend fun setParticipantVolumeControl(on: Boolean)
}

/**
 * Spotify Blend. A Blend starts as an invitation link; the Blend playlist itself only exists
 * once someone else has joined through it. An existing Blend is an ordinary playlist
 * ([Playlist.isBlend]), opened with [Playlists].
 */
interface Blends {
    /** Makes a new invitation. */
    suspend fun invite(): BlendInvite
    /** What an invitation (its link or its bare id) leads to for this account. */
    suspend fun invitation(linkOrId: String): BlendInvitation
    /** Joins through an invitation; the uri of the Blend playlist. */
    suspend fun join(linkOrId: String): Uri
    /** Who is in the Blend [playlist], and the link to invite more people. */
    suspend fun group(playlist: Uri): BlendGroup
    /** The Blend's taste-match card, or null when Spotify has none for it. */
    suspend fun story(playlist: Uri): BlendStory?
    suspend fun rename(playlist: Uri, name: String)
    /** Leaves the Blend: it goes from the library, the others keep it. */
    suspend fun leave(playlist: Uri)
}

interface Recents {
    /**
     * What the account played lately, newest first, as the Web Player lists it: context rows
     * (playlists, albums, artists, shows) followed by the plays they collected, with the group
     * keys the page regroups them by (see [groupRecents]).
     */
    suspend fun recentEntries(offset: Int = 0, limit: Int = 50): Page<RecentEntry>
}
