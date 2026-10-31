package app.myspoti.spotify

import app.myspoti.api.Account
import app.myspoti.api.Audiobook
import app.myspoti.api.BrowsePage
import app.myspoti.api.Canvas
import app.myspoti.api.Category
import app.myspoti.api.CreditRole
import app.myspoti.api.Credits
import app.myspoti.api.Device
import app.myspoti.api.Devices
import app.myspoti.api.Episode
import app.myspoti.api.EpisodeAudio
import app.myspoti.api.FriendActivity
import app.myspoti.api.HistoryEntry
import app.myspoti.api.Home
import app.myspoti.api.HomeFeed
import app.myspoti.api.HomeFilter
import app.myspoti.api.Image
import app.myspoti.api.Jam
import app.myspoti.api.JamAvailability
import app.myspoti.api.JamMember
import app.myspoti.api.JamSession
import app.myspoti.api.Lyrics
import app.myspoti.api.LyricsLine
import app.myspoti.api.LyricsWord
import app.myspoti.api.MediaKind
import app.myspoti.api.Page
import app.myspoti.api.Podcasts
import app.myspoti.api.Shelf
import app.myspoti.api.Show
import app.myspoti.api.TimeRange
import app.myspoti.api.TopContent
import app.myspoti.api.Track
import app.myspoti.api.TrackExtras
import app.myspoti.api.UserProfile
import app.myspoti.api.UserRef
import app.myspoti.api.Users
import app.myspoti.api.Uri
import app.myspoti.api.ArtistRef
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.service
import app.myspoti.spotify.gql.Gql
import app.myspoti.spotify.gql.GqlException
import app.myspoti.spotify.gql.SpotifyHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.TimeZone

private fun idOf(uri: Uri) = uri.substringAfterLast(':')

/**
 * A soundfinder response read into [EpisodeAudio]. Only `passthroughUrl` is usable: `url[]`
 * is the Widevine-encrypted copy, so it is never returned. "MANDATORY" wants the publisher
 * file as much as "ALLOWED" (the Web Player's format mapping is inconsistent there), so only
 * "NONE" means there is no clear file.
 */
internal fun episodeAudio(json: JsonElement): EpisodeAudio {
    val pass = json.str("passthrough")?.uppercase().orEmpty()
    val passUrl = json.str("passthroughUrl")?.takeIf { it.isNotBlank() }
    return EpisodeAudio(
        url = passUrl?.takeIf { pass != "NONE" },
        passthrough = pass.ifBlank { "NONE" },
        format = json.str("format").orEmpty(),
    )
}

/** Promotions and ads (HomePromotionShelf, sponsored cards…): never shown as content. */
internal fun isPromotion(typename: String?): Boolean =
    typename != null && Regex("Promotion|Sponsor|Advert|(^|[a-z])Ad([A-Z]|$)").containsMatchIn(typename)

private fun shelf(section: JsonElement): Shelf? {
    if (isPromotion(section.at("data").typename()) || isPromotion(section.typename())) return null
    val items = section.list("sectionItems.items")
        .filterNot { isPromotion(it.at("content").typename()) || isPromotion(it.at("content.data").typename()) }
        .mapNotNull { card(it.at("content")) }
    if (items.isEmpty()) return null
    return Shelf(
        uri = section.str("uri"),
        title = section.str("data.title.transformedLabel") ?: section.str("data.title.text"),
        items = items,
        total = section.int("sectionItems.totalCount") ?: items.size,
    )
}

class SpotifyHome(private val gql: Gql) : Home {
    override suspend fun feed(filter: HomeFilter, itemsPerShelf: Int): HomeFeed {
        val home = gql.query("home", vars(
            "timeZone" to TimeZone.getDefault().id, "sp_t" to "", "facet" to filter.facet,
            "sectionItemsLimit" to itemsPerShelf, "homeEndUserIntegration" to "INTEGRATION_WEB_PLAYER",
            "includeEpisodeContentRatingsV2" to true, "includeHomeChapterVideoCards" to false,
        )).entity("data.home", "home")
        val filters = home.list("homeChips").mapNotNull { chip -> HomeFilter.entries.firstOrNull { it.facet == chip.str("id") } }
        return HomeFeed(
            greeting = home.str("greeting.transformedLabel") ?: "",
            filters = listOf(HomeFilter.ALL) + filters,
            shelves = home.list("sectionContainer.sections.items").mapNotNull(::shelf),
        )
    }

    override suspend fun categories(): List<Category> =
        gql.query("browseAll", vars(
            "pagePagination" to mapOf("offset" to 0, "limit" to 10),
            "sectionPagination" to mapOf("offset" to 0, "limit" to 99),
            "browseEndUserIntegration" to "INTEGRATION_WEB_PLAYER",
        )).list("data.browseStart.sections.items").flatMap { it.list("sectionItems.items") }.mapNotNull { item ->
            val c = item.obj("content.data.data.cardRepresentation") ?: item.obj("content.data.cardRepresentation") ?: return@mapNotNull null
            val uri = item.str("uri") ?: return@mapNotNull null
            Category(uri, c.str("title.transformedLabel") ?: "", images(c.list("artwork.sources")), c.str("backgroundColor.hex"))
        }

    override suspend fun whatsNew(offset: Int, limit: Int): Page<app.myspoti.api.Album> {
        val feed = gql.query("queryWhatsNewFeed", vars("offset" to offset, "limit" to limit, "onlyUnPlayedItems" to false))
            .at("data.whatsNewFeedItems")
        return page(feed, offset) { item ->
            val d = item.at("content.data")
            album(d)?.let { a -> if (a.type == "ALBUM" && d.str("albumType") != null) a.copy(type = d.str("albumType")!!) else a }
        }
    }

    override suspend fun category(uri: Uri): BrowsePage {
        val page = gql.query("browsePage", vars(
            "uri" to uri, "pagePagination" to mapOf("offset" to 0, "limit" to 10),
            "sectionPagination" to mapOf("offset" to 0, "limit" to 20),
            "browseEndUserIntegration" to "INTEGRATION_WEB_PLAYER", "includeEpisodeContentRatingsV2" to true,
        )).entity("data.browse", "browsePage")
        return BrowsePage(uri, page.str("header.title.transformedLabel") ?: "", page.list("sections.items").mapNotNull(::shelf))
    }
}

class SpotifyPodcasts(private val gql: Gql, private val bridge: Bridge, private val http: SpotifyHttp) : Podcasts {
    private suspend fun showUnion(uri: Uri) = gql.query("queryShowMetadataV2", vars(
        "uri" to uri, "includeContentCapabilityTrait" to true, "includeEpisodeContentRatingsV2" to true,
    )).entity("data.podcastUnionV2", "queryShowMetadataV2")

    override suspend fun show(uri: Uri): Show = show(showUnion(uri)) ?: throw GqlException("queryShowMetadataV2", "bad show")

    /**
     * The audio of one episode, from the Web Player's soundfinder endpoint. The key system is
     * part of the path and is mandatory (anything else answers 400). The endpoint is `unauth`
     * (probed without a token), but going through [SpotifyHttp] keeps the cookie and token
     * story the same as every other call, and the host allow-list applies.
     */
    override suspend fun audioUrl(uri: Uri): EpisodeAudio {
        val id = idOf(uri)
        val json = http.getJson("https://spclient.wg.spotify.com/soundfinder/v1/unauth/episode/$id/com.widevine.alpha")
            ?: return EpisodeAudio(null, "NONE", "") // 404: no audio for this episode
        return episodeAudio(json)
    }

    override suspend fun episodes(uri: Uri, offset: Int, limit: Int): Page<Episode> {
        val e = gql.query("queryPodcastEpisodes", vars("uri" to uri, "offset" to offset, "limit" to limit, "includeEpisodeContentRatingsV2" to true))
            .entity("data.podcastUnionV2", "queryPodcastEpisodes").at("episodesV2")
        return page(e, offset) { episode(it.at("entity.data")) }
    }

    override suspend fun episode(uri: Uri): Episode =
        episode(gql.query("getEpisodeOrChapter", vars("uri" to uri, "includeEpisodeContentRatingsV2" to true))
            .entity("data.episodeUnionV2", "getEpisodeOrChapter")) ?: throw GqlException("getEpisodeOrChapter", "bad episode")

    override suspend fun audiobook(uri: Uri): Audiobook {
        val d = showUnion(uri)
        return Audiobook(
            uri = d.str("uri") ?: uri,
            name = d.str("name") ?: "",
            authors = d.list("authorsV2").mapNotNull { it.str("name") }.ifEmpty { d.list("authors").mapNotNull { it.str("name") } },
            narrators = d.list("narrators.items").mapNotNull { it.str("name") },
            description = d.str("description"),
            images = images(d.list("coverArt.sources")),
            chapterCount = d.int("chaptersV2.totalCount") ?: d.int("episodesV2.totalCount") ?: 0,
        )
    }

    override suspend fun chapters(uri: Uri, offset: Int, limit: Int): Page<Episode> {
        val d = gql.query("queryBookChapters", vars("uri" to uri, "offset" to offset, "limit" to limit))
            .entity("data.podcastUnionV2", "queryBookChapters")
        val list = d.at("chaptersV2") ?: d.at("episodesV2")
        return page(list, offset) { episode(it.at("entity.data") ?: it.at("data")) }
    }

    /** "Your Episodes" is the `listenlater` collection set; details are loaded in parallel. */
    override suspend fun savedEpisodes(offset: Int, limit: Int): Page<Episode> {
        // The page's own get() throws on an empty set: that means no saved episodes.
        val raw = runCatching { bridge.service("CollectionPlatformAPI", "get", JsonPrimitive("listenlater")) }.getOrNull()
        val saved = (raw as? JsonArray).orEmpty()
            .sortedByDescending { it.long("addTime") ?: 0 }
            .mapNotNull { it.str("uri") }
        val slice = saved.drop(offset).take(limit)
        val items = kotlinx.coroutines.coroutineScope {
            slice.map { uri -> async { runCatching { episode(uri) }.getOrNull() } }.map { it.await() }
        }.filterNotNull()
        return Page(items, offset, saved.size, (offset + slice.size).takeIf { it < saved.size })
    }

    /** Saves or unsaves an episode in the `listenlater` set that [savedEpisodes] reads. */
    override suspend fun setEpisodeSaved(uri: Uri, saved: Boolean) {
        bridge.service("CollectionPlatformAPI", if (saved) "add" else "remove", JsonPrimitive("listenlater"), json(listOf(uri)))
    }
}

class SpotifyUsers(private val gql: Gql, private val bridge: Bridge, private val http: SpotifyHttp) : Users {
    internal companion object {
        val BUDDYLIST_HOSTS = listOf("guc-spclient.spotify.com", "spclient.wg.spotify.com")
        const val LISTENING_FEED_URL = "https://spclient.wg.spotify.com/listening-activity/v1/feed"

        /** `/{me}/is-blocking/{user}` on the social graph; both ids are the usernames, never the uris. */
        fun blockUrl(username: String, userUri: Uri): String {
            require(username.isNotEmpty()) { "no username" }
            val me = URLEncoder.encode(username, "UTF-8").replace("+", "%20")
            val target = URLEncoder.encode(idOf(userUri), "UTF-8").replace("+", "%20")
            return "https://spclient.wg.spotify.com/socialgraph/v4/$me/is-blocking/$target"
        }

        /** The protobuf `containsResult` (field 1, varint) of the GET, read from the body text: `08 01` is true, an empty body false. */
        fun blockedAnswer(body: String): Boolean = body.length >= 2 && body[0] == '\u0008' && body[1] == '\u0001'
    }

    override suspend fun me(): Account {
        val p = gql.query("profileAttributes", vars()).at("data.me.profile")
        val a = gql.query("accountAttributes", vars()).at("data.me.account")
        return Account(
            uri = p.str("uri") ?: "",
            username = p.str("username") ?: "",
            name = p.str("name") ?: p.str("username") ?: "",
            images = images(p.list("avatar.sources")),
            premium = a.str("product")?.equals("FREE", ignoreCase = true) == false,
            country = a.str("country"),
        )
    }

    override suspend fun profile(userUri: Uri): UserProfile {
        val id = URLEncoder.encode(idOf(userUri), "UTF-8")
        val p = http.getJson("https://spclient.wg.spotify.com/user-profile-view/v3/profile/$id?playlist_limit=50&artist_limit=10&episode_limit=10&market=from_token")
            ?: throw GqlException("user-profile-view", "not found")
        return UserProfile(
            uri = p.str("uri") ?: userUri,
            name = p.str("name") ?: idOf(userUri),
            images = listOfNotNull(p.str("image_url")?.let { Image(it) }),
            followers = p.int("followers_count"),
            following = p.int("following_count"),
            publicPlaylists = p.list("public_playlists").mapNotNull { pl ->
                val uri = pl.str("uri") ?: return@mapNotNull null
                app.myspoti.api.Playlist(uri, pl.str("name") ?: "", null, pl.str("owner_name"),
                    listOfNotNull(pl.str("image_url")?.let { Image(it) }), 0)
            },
        )
    }

    override suspend fun followers(userUri: Uri): List<UserRef> = profileList(userUri, "followers")
    override suspend fun following(userUri: Uri): List<UserRef> = profileList(userUri, "following")

    private suspend fun profileList(userUri: Uri, kind: String): List<UserRef> {
        val id = URLEncoder.encode(idOf(userUri), "UTF-8")
        val r = http.getJson("https://spclient.wg.spotify.com/user-profile-view/v3/profile/$id/$kind?market=from_token") ?: return emptyList()
        return r.list("profiles").mapNotNull { f ->
            val uri = f.str("uri") ?: return@mapNotNull null
            UserRef(uri, f.str("name") ?: idOf(uri), f.str("image_url"))
        }.distinctBy { it.uri }
    }

    override suspend fun top(range: TimeRange, limit: Int): TopContent {
        val input = mapOf("offset" to 0, "limit" to limit, "sortBy" to "AFFINITY", "timeRange" to range.gqlName)
        return topContent(gql.query("userTopContent", vars(
            "includeTopArtists" to true, "topArtistsInput" to input, "includeTopTracks" to true, "topTracksInput" to input,
        )).at("data.me.profile"))
    }

    override suspend fun follow(userUris: List<Uri>) { gql.query("followUsers", vars("usernames" to userUris.map(::idOf))) }
    override suspend fun unfollow(userUris: List<Uri>) { gql.query("unfollowUsers", vars("usernames" to userUris.map(::idOf))) }

    override suspend fun isFollowing(userUris: List<Uri>): List<Boolean> {
        val r = gql.query("isFollowingUsers", vars("uris" to userUris))
        val list = r.list("data.users")
        return userUris.map { u -> list.firstOrNull { it.str("uri") == u }?.bool("following") ?: false }
    }

    // The Web Player's "Block" in a profile's menu is a plain spclient call on the social graph, with
    // the account's own username in the path; its answer to the GET is a protobuf `{ bool contains = 1 }`.
    override suspend fun isBlocked(userUri: Uri): Boolean {
        val (code, text) = http.send("GET", blockUrl(ownUsername(), userUri), accept = "application/x-protobuf")
        return when (code) {
            200 -> blockedAnswer(text)
            204, 404 -> false
            else -> throw app.myspoti.spotify.gql.HttpException(code, text.take(200))
        }
    }

    @Volatile private var username: String? = null
    internal suspend fun ownUsername(): String = username ?: me().username.also { username = it }

    /** Another page load may be another account: the next [ownUsername] asks again. */
    internal fun forgetUsername() { username = null }

    override suspend fun blockUser(userUri: Uri) = writeBlock("POST", userUri)
    override suspend fun unblockUser(userUri: Uri) = writeBlock("DELETE", userUri)

    private suspend fun writeBlock(method: String, userUri: Uri) {
        val (code, text) = http.send(method, blockUrl(ownUsername(), userUri))
        if (code !in 200..299) throw app.myspoti.spotify.gql.HttpException(code, text.take(200))
    }

    /**
     * The mobile client's feed (`listening-activity/v1/feed`, protobuf), which the page's token
     * reads too: the desktop's buddylist below came back empty for accounts whose friends show in
     * the phone app. It sends uris only, so names, covers and avatars are looked up in two calls.
     * Any other answer than 200 falls back to the buddylist.
     */
    override suspend fun friendActivity(): List<FriendActivity> {
        val (code, body) = http.sendProto("POST", LISTENING_FEED_URL, proto { varint(1, 1) })
        if (code != 200) return buddyListActivity()
        val feed = listeningFeed(body)
        if (feed.isEmpty()) return emptyList()
        val (entities, avatars) = kotlinx.coroutines.coroutineScope {
            val e = async { lookupEntities(gql, feed.flatMap { listOfNotNull(it.userUri, it.itemUri, it.contextUri) }) }
            val a = async { runCatching { lookupAvatars(gql, feed.map { it.userUri }) }.getOrDefault(emptyMap()) }
            e.await() to a.await()
        }
        return feed.map { f ->
            val item = entities[f.itemUri]
            val cardOfItem = card(item)
            FriendActivity(
                user = UserRef(f.userUri, userName(entities, f.userUri), avatars[f.userUri]),
                track = track(item.obj("data")) ?: cardOfItem?.let { c ->
                    // An episode (or anything not a track): its name, and its show as the "artist".
                    Track(c.uri, c.title, 0, listOfNotNull(c.subtitle?.let { ArtistRef("", it) }), null)
                },
                contextUri = f.contextUri ?: f.itemUri.takeIf { !it.startsWith("spotify:track:") },
                timestampMs = f.timestampMs,
                contextName = f.contextUri?.let { card(entities[it])?.title },
                trackImageUrl = cardOfItem?.images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url,
                playing = f.playing,
            )
        }
    }

    /**
     * The Web Player's own friend feed is a stub (its BuddyFeedAPI says `isSupported: false`), so
     * this reads the endpoint the desktop client's feed uses, with the page's token: guc-spclient
     * first (the desktop's host), then the generic spclient host.
     */
    private suspend fun buddyListActivity(): List<FriendActivity> {
        var last: Exception? = null
        for (host in BUDDYLIST_HOSTS) {
            try {
                return buddyList(http.getJson("https://$host/presence-view/v1/buddylist"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("friend activity unavailable")
    }

    override suspend fun history(): List<HistoryEntry> =
        (bridge.service("RecentlyPlayedAPI", "getContexts") as? JsonArray).orEmpty().mapNotNull { c ->
            val uri = c.str("uri") ?: return@mapNotNull null
            HistoryEntry(uri, MediaKind.of(uri), c.str("name") ?: "",
                c.list("images").mapNotNull { i -> i.str("url")?.let { Image(it, i.int("width"), i.int("height")) } },
                c.long("timestampMs"))
        }

    override suspend fun setPrivateSession(on: Boolean) {
        val supported = bridge.service("PrivateSessionAPI", "getCapabilities").bool("isSupported") ?: false
        if (!supported) throw UnsupportedOperationException("private session is not available in the Web Player")
        bridge.service("PrivateSessionAPI", "setPrivateSession", JsonPrimitive(on))
    }
}

class SpotifyTrackExtras(private val gql: Gql, private val bridge: Bridge, private val http: SpotifyHttp) : TrackExtras {
    override suspend fun lyrics(trackUri: Uri): Lyrics? {
        val r = http.getJson("https://spclient.wg.spotify.com/color-lyrics/v2/track/${idOf(trackUri)}?format=json&vocalRemoval=false&market=from_token")
            ?: return null
        val l = r.obj("lyrics") ?: return null
        val synced = l.str("syncType") != "UNSYNCED"
        val lines = l.list("lines").map { line ->
            val start = line.long("startTimeMs") ?: 0
            LyricsLine(
                startMs = start,
                endMs = line.long("endTimeMs")?.takeIf { it > 0 },
                text = line.str("words") ?: "",
                words = line.list("syllables").mapNotNull { s -> s.str("words")?.let { LyricsWord(s.long("startTimeMs") ?: start, it) } }.ifEmpty { null },
            )
        }.filter { it.text.isNotBlank() || synced }
        return Lyrics(lines, synced, l.str("language"), l.str("providerDisplayName") ?: l.str("provider"),
            r.int("colors.background"), r.int("colors.text"), r.int("colors.highlightText"))
    }

    override suspend fun canvas(trackUri: Uri): Canvas? {
        val c = gql.query("canvas", vars("trackUri" to trackUri)).obj("data.trackUnion.canvas") ?: return null
        val url = c.str("url") ?: return null
        return Canvas(c.str("uri") ?: "", url, c.str("type") ?: "")
    }

    override suspend fun credits(trackUri: Uri): Credits {
        val contributors = gql.query("queryTrackCreditsGroupedModal", vars("trackUri" to trackUri))
            .list("data.trackUnion.creditsTrait.contributors.items")
        val roles = contributors.groupBy { it.str("role") ?: it.str("roleGroup.name") ?: "" }.map { (role, people) ->
            CreditRole(role, people.map { ArtistRef(it.str("uri") ?: "", it.str("name") ?: "") })
        }
        return Credits(roles)
    }

    override suspend fun radio(seedUri: Uri): Uri? =
        http.getJson("https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/$seedUri?response-format=json")
            ?.list("mediaItems")?.firstOrNull()?.str("uri")

    override suspend fun setExcludedFromTaste(trackUri: Uri, excluded: Boolean) {
        bridge.service("CollectionPlatformAPI", if (excluded) "add" else "remove", JsonPrimitive("ignoreinrecs"), json(listOf(trackUri)))
    }

    override suspend fun isExcludedFromTaste(trackUris: List<Uri>): List<Boolean> = collectionContains("ignoreinrecs", trackUris)

    /** "Don't play this artist" is the `artistban` collection set, written like the taste exclusion. */
    override suspend fun setArtistBanned(artistUri: Uri, banned: Boolean) {
        bridge.service("CollectionPlatformAPI", if (banned) "add" else "remove", JsonPrimitive("artistban"), json(listOf(artistUri)))
    }

    override suspend fun isArtistBanned(artistUris: List<Uri>): List<Boolean> = collectionContains("artistban", artistUris)

    /** Asks the server: the page's own contains() answers from a cache that removals do not clear. */
    private suspend fun collectionContains(set: String, uris: List<Uri>): List<Boolean> {
        if (uris.isEmpty()) return emptyList()
        val username = bridge.service("UserAPI", "getUser").str("username") ?: throw GqlException("collection", "no user")
        val body = json(mapOf("username" to username, "set" to set, "items" to uris.map { mapOf("uri" to it) })).toString()
        val (code, text) = http.send("POST", "https://spclient.wg.spotify.com/collection/v2/contains?market=from_token", body, contentType = "application/json")
        if (code != 200) throw app.myspoti.spotify.gql.HttpException(code, text.take(200))
        return Bridge.json.parseToJsonElement(text).list("found").map { (it as? JsonPrimitive)?.content == "true" }
    }
}

class SpotifyDevices(private val bridge: Bridge) : Devices {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    override val devices: StateFlow<List<Device>> = _devices

    override suspend fun refresh() {
        _devices.value = (bridge.service("ConnectDevicesAPI", "getDevices") as? JsonArray).orEmpty().mapNotNull { d ->
            Device(
                id = d.str("id") ?: return@mapNotNull null,
                name = d.str("name") ?: "",
                type = d.str("type") ?: "unknown",
                active = d.bool("isActive") ?: false,
                volume = d.double("volume"),
                local = d.bool("isLocal") ?: false,
            )
        }
    }

    override suspend fun transfer(deviceId: String, play: Boolean) {
        bridge.service("ConnectTransferAPI", "transferPlayback", JsonPrimitive(deviceId), json(mapOf("playOnTransfer" to play)))
        refresh()
    }

    override suspend fun pullHere() {
        bridge.service("ConnectTransferAPI", "pullToLocalDevice", json(mapOf<String, Any>()))
        refresh()
    }
}

/** Jam goes through the page's SocialConnectAPI. Free accounts get RESTRICTED from Spotify. */
class SpotifyJam(private val bridge: Bridge) : Jam {
    private val _session = MutableStateFlow<JamSession?>(null)
    override val session: StateFlow<JamSession?> = _session

    override suspend fun availability(): JamAvailability =
        when ((bridge.service("SocialConnectAPI", "getEnabled") as? JsonPrimitive)?.content) {
            "ENABLED", "AVAILABLE" -> JamAvailability.AVAILABLE
            "RESTRICTED", "DISABLED" -> JamAvailability.RESTRICTED
            else -> JamAvailability.UNKNOWN
        }

    private fun parse(s: JsonElement?): JamSession? {
        val id = s.str("session_id") ?: s.str("sessionId") ?: return null
        val members = (s.list("session_members").ifEmpty { s.list("sessionMembers") }).map { m ->
            JamMember(
                userUri = "spotify:user:" + (m.str("username") ?: m.str("id") ?: ""),
                name = m.str("display_name") ?: m.str("displayName") ?: m.str("username") ?: "",
                imageUrl = m.str("image_url") ?: m.str("imageUrl") ?: m.str("large_image_url"),
                host = m.bool("is_host") ?: m.bool("isHost") ?: (m.str("username") == s.str("session_owner_id")),
            )
        }
        return JamSession(
            id = id,
            joinToken = s.str("join_session_token") ?: s.str("joinSessionToken"),
            inviteLink = null,
            members = members,
            isHost = s.bool("is_session_owner") ?: s.bool("isSessionOwner") ?: false,
            queueOnly = s.bool("queue_only_mode") ?: s.bool("queueOnlyMode") ?: false,
        )
    }

    override suspend fun refresh() { _session.value = parse(bridge.service("SocialConnectAPI", "fetchCurrentSession")) }

    override suspend fun start(): JamSession {
        if (availability() == JamAvailability.RESTRICTED) throw UnsupportedOperationException("Jam is restricted on this account")
        val s = parse(bridge.service("SocialConnectAPI", "createSession")) ?: throw GqlException("jam", "no session")
        _session.value = s
        return s
    }

    override suspend fun join(joinToken: String): JamSession {
        bridge.service("SocialConnectAPI", "joinSession", JsonPrimitive(joinToken))
        refresh()
        return _session.value ?: throw GqlException("jam", "join failed")
    }

    override suspend fun leave() { bridge.service("SocialConnectAPI", "leaveSession"); _session.value = null }
    override suspend fun end() { bridge.service("SocialConnectAPI", "deleteSession"); _session.value = null }

    override suspend fun inviteLink(): String? {
        val id = _session.value?.id ?: return null
        val r = bridge.service("SocialConnectAPI", "getShortInviteLink", JsonPrimitive(id))
        return (r as? JsonPrimitive)?.content ?: r.str("shareUrl") ?: r.str("url")
    }

    override suspend fun removeMember(userUri: Uri) { bridge.service("SocialConnectAPI", "removeSessionMember", JsonPrimitive(idOf(userUri))); refresh() }
    override suspend fun setQueueOnly(on: Boolean) { bridge.service("SocialConnectAPI", "setQueueOnlyMode", JsonPrimitive(on)); refresh() }
    override suspend fun setParticipantVolumeControl(on: Boolean) { bridge.service("SocialConnectAPI", "setParticipantVolumeControl", JsonPrimitive(on)) }
}
