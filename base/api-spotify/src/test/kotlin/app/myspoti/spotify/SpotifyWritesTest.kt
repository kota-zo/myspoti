package app.myspoti.spotify

import app.myspoti.api.PlaylistSort
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import app.myspoti.spotify.gql.Gql
import app.myspoti.spotify.gql.SpotifyHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Answers each `Myspoti.call(id, method, args)` with [reply] and keeps every request it was sent. */
private class RecordingHost(val reply: (JsonObject) -> JsonElement) : BridgeHost {
    val calls = mutableListOf<JsonObject>()
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override suspend fun evaluate(script: String) {
        val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""").find(script) ?: return
        val args = Bridge.json.parseToJsonElement(m.groupValues[3]).jsonObject
        calls += args
        messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":${reply(args)}}""")
    }
}

/** Records each GraphQL request's variables and answers with [respond]. */
private class FakeGql(val respond: (String, JsonObject) -> JsonElement) : Gql {
    val variables = mutableListOf<JsonObject>()
    override suspend fun query(name: String, variables: JsonObject): JsonElement {
        this.variables += variables
        return respond(name, variables)
    }
}

private fun JsonObject.method() = this["method"]!!.jsonPrimitive.content
private fun JsonObject.args() = this["args"]!!.jsonArray.toString()

/** A `fetchPlaylistContents` answer holding rows `offset until min(total, offset + limit)`. */
private fun playlistPage(total: Int, offset: Int, limit: Int): JsonElement {
    val rows = (offset until minOf(total, offset + limit)).joinToString(",") { i ->
        """{"uid":"u$i","addedAt":{"isoString":"2026-01-01T00:00:00Z"},"itemV2":{"__typename":"TrackResponseWrapper","data":
          {"__typename":"Track","uri":"spotify:track:t$i","name":"Track ${"%03d".format(i)}","duration":{"totalMilliseconds":1000},
          "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
          "albumOfTrack":{"uri":"spotify:album:b","name":"Album"}}}}"""
    }
    return Bridge.json.parseToJsonElement("""{"data":{"playlistV2":{"__typename":"Playlist","content":{"totalCount":$total,"items":[$rows]}}}}""")
}

class SpotifyWritesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach fun stop() = scope.cancel()

    private fun runBridge(reply: (JsonObject) -> JsonElement, block: suspend (RecordingHost, Bridge) -> Unit) = runBlocking {
        val host = RecordingHost(reply)
        val bridge = Bridge(host, scope)
        block(host, bridge)
    }

    @Test fun `don't play this artist writes the artistban set and nothing else`() = runBridge({ JsonNull }) { host, bridge ->
        val extras = SpotifyTrackExtras(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        extras.setArtistBanned("spotify:artist:a", true)
        extras.setArtistBanned("spotify:artist:a", false)
        assertEquals(listOf("add", "remove"), host.calls.map { it.method() })
        assertEquals("""["artistban",["spotify:artist:a"]]""", host.calls[0].args())
        assertEquals("CollectionPlatformAPI", host.calls[0]["service"]!!.jsonPrimitive.content)
    }

    @Test fun `saving an episode uses the listenlater set that savedEpisodes reads`() = runBridge({ JsonNull }) { host, bridge ->
        val podcasts = SpotifyPodcasts(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        podcasts.setEpisodeSaved("spotify:episode:e", true)
        podcasts.setEpisodeSaved("spotify:episode:e", false)
        assertEquals(listOf("add", "remove"), host.calls.map { it.method() })
        assertEquals("""["listenlater",["spotify:episode:e"]]""", host.calls[0].args())
    }

    @Test fun `public, private and collaborative set the base permission`() = runBridge({ call ->
        if (call.method() == "getBasePermission") kotlinx.serialization.json.JsonPrimitive("BLOCKED") else JsonNull
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.setPublic("spotify:playlist:p", false)
        playlists.setCollaborative("spotify:playlist:p", true)
        playlists.setPublic("spotify:playlist:p", true)
        val sets = host.calls.filter { it.method() == "setBasePermission" }.map { it.args() }
        assertEquals(
            listOf("""["spotify:playlist:p","BLOCKED"]""", """["spotify:playlist:p","CONTRIBUTOR"]""", """["spotify:playlist:p","VIEWER"]"""),
            sets,
        )
        assertEquals(app.myspoti.api.PlaylistAccess.PRIVATE, playlists.access("spotify:playlist:p"))
    }

    @Test fun `rename still sends the name as a string`() = runBridge({ call ->
        if (call.method() == "getMetadata") Bridge.json.parseToJsonElement("""{"name":"New"}""") else JsonNull
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.rename("spotify:playlist:p", "New")
        assertEquals("""["spotify:playlist:p",{"name":"New"}]""", host.calls.first { it.method() == "updateDetails" }.args())
    }

    @Test fun `sorted tracks read every page of 100 and sort on the client`() = runBridge({ JsonNull }) { _, bridge ->
        val gql = FakeGql { _, v ->
            playlistPage(250, v["offset"]!!.jsonPrimitive.content.toInt(), v["limit"]!!.jsonPrimitive.content.toInt())
        }
        val playlists = SpotifyPlaylists(gql, bridge, SpotifyHttp(OkHttpClient(), bridge))
        val sorted = playlists.sortedTracks("spotify:playlist:p", PlaylistSort.TITLE, descending = true)
        assertEquals(listOf("0", "100", "200"), gql.variables.map { it["offset"]!!.jsonPrimitive.content })
        assertEquals(250, sorted.size)
        assertEquals("Track 249", sorted.first().name)
        assertEquals("Track 000", sorted.last().name)
    }

    @Test fun `custom sort keeps the playlist's own order`() = runBridge({ JsonNull }) { _, bridge ->
        val gql = FakeGql { _, v -> playlistPage(3, 0, v["limit"]!!.jsonPrimitive.content.toInt()) }
        val playlists = SpotifyPlaylists(gql, bridge, SpotifyHttp(OkHttpClient(), bridge))
        assertEquals(listOf("u0", "u1", "u2"), playlists.sortedTracks("spotify:playlist:p").map { it.uid })
        assertEquals(listOf("u2", "u1", "u0"), playlists.sortedTracks("spotify:playlist:p", PlaylistSort.CUSTOM, true).map { it.uid })
    }

    @Test fun `the same song twice in a playlist keeps two row ids, and removing one sends only its id`() = runBridge({ JsonNull }) { _, bridge ->
        fun row(uid: String) = """{"uid":"$uid","addedAt":{"isoString":"2026-01-01T00:00:00Z"},"itemV2":{"__typename":"TrackResponseWrapper","data":
          {"__typename":"Track","uri":"spotify:track:same","name":"Same","duration":{"totalMilliseconds":1000},
          "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
          "albumOfTrack":{"uri":"spotify:album:b","name":"Album"}}}}"""
        val gql = FakeGql { name, _ ->
            if (name == "fetchPlaylistContents")
                Bridge.json.parseToJsonElement("""{"data":{"playlistV2":{"__typename":"Playlist","content":{"totalCount":2,"items":[${row("u1")},${row("u2")}]}}}}""")
            else JsonNull
        }
        val playlists = SpotifyPlaylists(gql, bridge, SpotifyHttp(OkHttpClient(), bridge))
        val rows = playlists.tracks("spotify:playlist:p", 0, 100).items
        assertEquals(listOf("spotify:track:same", "spotify:track:same"), rows.map { it.uri })
        assertEquals(listOf("u1", "u2"), rows.map { it.uid })
        playlists.removeTracks("spotify:playlist:p", listOf(rows[1].uid!!))
        val removal = gql.variables.last()
        assertEquals("""["u2"]""", removal["uids"]!!.jsonArray.toString())
        assertEquals("spotify:playlist:p", removal["playlistUri"]!!.jsonPrimitive.content)
    }

    @Test fun `making a listed playlist private takes it off the profile before the base permission`() = runBridge({ call ->
        if (call.method() == "getPublishedState") kotlinx.serialization.json.JsonPrimitive("true") else JsonNull
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.setAccess("spotify:playlist:p", app.myspoti.api.PlaylistAccess.PRIVATE)
        val writes = host.calls.filter { it.method() == "setPublishedState" || it.method() == "setBasePermission" }
        assertEquals(listOf("setPublishedState", "setBasePermission"), writes.map { it.method() })
        assertEquals("""["spotify:playlist:p",false]""", writes[0].args())
        assertEquals("""["spotify:playlist:p","BLOCKED"]""", writes[1].args())
    }

    @Test fun `public and collaborative leave the profile listing alone`() = runBridge({ JsonNull }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.setAccess("spotify:playlist:p", app.myspoti.api.PlaylistAccess.COLLABORATIVE)
        playlists.setAccess("spotify:playlist:p", app.myspoti.api.PlaylistAccess.PUBLIC)
        assertEquals(listOf("setBasePermission", "setBasePermission"), host.calls.map { it.method() })
    }

    @Test fun `a playlist created in a folder is placed after that folder and its uri is returned`() = runBridge({ call ->
        if (call.method() == "createPlaylist") kotlinx.serialization.json.JsonPrimitive("spotify:playlist:new") else JsonNull
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        assertEquals("spotify:playlist:new", playlists.create("Mine", "spotify:user:u:folder:abc"))
        assertEquals("""["Mine",{"after":{"uri":"spotify:user:u:folder:abc"}}]""", host.calls.single().args())
    }

    @Test fun `joining with an invitation token claims it on the page, then follows the playlist when it is not in the library`() = runBridge({ call ->
        when (call.method()) {
            "claimPermissions" -> Bridge.json.parseToJsonElement("""{"userPermission":{"revision":"b3du","permissionLevel":"CONTRIBUTOR"},"capabilities":{"canEditItems":true}}""")
            "contains" -> Bridge.json.parseToJsonElement("[false]")
            else -> JsonNull
        }
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        assertEquals(app.myspoti.api.PlaylistAccess.COLLABORATIVE, playlists.join("spotify:playlist:p", "tok123"))
        assertEquals(listOf("claimPermissions", "contains", "add"), host.calls.map { it.method() })
        assertEquals("""["spotify:playlist:p","tok123"]""", host.calls[0].args())
    }

    @Test fun `leaving a collaborative playlist removes this account from its members, by username`() = runBridge({ call ->
        when (call.method()) {
            "getUser" -> Bridge.json.parseToJsonElement("""{"username":"user0002"}""")
            "getBasePermission" -> kotlinx.serialization.json.JsonPrimitive("CONTRIBUTOR")
            else -> JsonNull
        }
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.leave("spotify:playlist:p")
        assertEquals(listOf("getUser", "getBasePermission", "removeMember"), host.calls.map { it.method() })
        assertEquals("""["spotify:playlist:p","user0002"]""", host.calls.last().args())
    }

    @Test fun `leaving a private playlist takes it out of the library first, as the page's dialog does`() = runBridge({ call ->
        when (call.method()) {
            "getUser" -> Bridge.json.parseToJsonElement("""{"username":"user0002"}""")
            "getBasePermission" -> kotlinx.serialization.json.JsonPrimitive("BLOCKED")
            else -> JsonNull
        }
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.leave("spotify:playlist:p")
        assertEquals(listOf("getUser", "getBasePermission", "remove", "removeMember"), host.calls.map { it.method() })
    }

    private val membersReply = """{"members":[
        {"permissionLevel":"OWNER","isOwner":true,"user":{"username":"owner","displayName":"Owner","images":[]}},
        {"permissionLevel":"CONTRIBUTOR","isOwner":false,"user":{"username":"guest1","displayName":"Guest 1","images":[]}},
        {"permissionLevel":"VIEWER","isOwner":false,"user":{"username":"guest2","displayName":"Guest 2","images":[]}}]}"""

    @Test fun `members carry their level, and the owner's menu writes setMemberPermission and removeMember by username`() = runBridge({ call ->
        if (call.method() == "getMembers") Bridge.json.parseToJsonElement(membersReply) else JsonNull
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        assertEquals(listOf(false, true, false), playlists.members("spotify:playlist:p").map { it.isCollaborator })
        host.calls.clear()
        playlists.setMemberCollaborator("spotify:playlist:p", "guest1", false)
        playlists.setMemberCollaborator("spotify:playlist:p", "guest2", true)
        playlists.removeMember("spotify:playlist:p", "guest2")
        assertEquals(listOf("setMemberPermission", "setMemberPermission", "removeMember"), host.calls.map { it.method() })
        assertEquals("""["spotify:playlist:p","guest1","VIEWER"]""", host.calls[0].args())
        assertEquals("""["spotify:playlist:p","guest2","CONTRIBUTOR"]""", host.calls[1].args())
        assertEquals("""["spotify:playlist:p","guest2"]""", host.calls[2].args())
    }

    @Test fun `stopping the collaboration of a private playlist makes the collaborators listeners, then makes it private`() = runBridge({ call ->
        when (call.method()) {
            "getMembers" -> Bridge.json.parseToJsonElement(membersReply)
            "getPublishedState" -> kotlinx.serialization.json.JsonPrimitive("false")
            else -> JsonNull
        }
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.stopCollaboration("spotify:playlist:p")
        val writes = host.calls.filter { it.method().startsWith("set") || it.method() == "removeMember" }
        assertEquals(listOf("setMemberPermission", "setBasePermission"), writes.map { it.method() })
        assertEquals("""["spotify:playlist:p","guest1","VIEWER"]""", writes[0].args())
        assertEquals("""["spotify:playlist:p","BLOCKED"]""", writes[1].args())
    }

    @Test fun `stopping the collaboration of a public playlist removes the collaborators, not the listeners`() = runBridge({ call ->
        when (call.method()) {
            "getMembers" -> Bridge.json.parseToJsonElement(membersReply)
            "getPublishedState" -> kotlinx.serialization.json.JsonPrimitive("true")
            else -> JsonNull
        }
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.stopCollaboration("spotify:playlist:p")
        val writes = host.calls.filter { it.method().startsWith("set") || it.method() == "removeMember" }
        assertEquals(listOf("removeMember", "setBasePermission"), writes.map { it.method() })
        assertEquals("""["spotify:playlist:p","guest1"]""", writes[0].args())
        assertEquals("""["spotify:playlist:p","VIEWER"]""", writes[1].args())
    }

    @Test fun `a playlist can be left only when its capabilities say canCancelMembership`() {
        fun parse(flag: String) = playlist(Bridge.json.parseToJsonElement("""{"uri":"spotify:playlist:p","name":"n","currentUserCapabilities":{"canEditItems":true,"canCancelMembership":$flag}}"""))!!
        assertEquals(true, parse("true").canLeave)
        assertEquals(false, parse("false").canLeave)
        assertEquals(false, playlist(Bridge.json.parseToJsonElement("""{"uri":"spotify:playlist:p","name":"n"}"""))!!.canLeave)
    }

    @Test fun `a Blend invitation id is read from the link the page generates, or given bare`() {
        val blends = SpotifyBlends(SpotifyHttp(OkHttpClient(), Bridge(RecordingHost { JsonNull }, scope)))
        assertEquals("f6c3d87d8beb9a3f", blends.idOf("https://open.spotify.com/blend/taste-match/f6c3d87d8beb9a3f?si=DJpS4omqRFOjF4es_Fnm8A&fallback=getapp&blendDecoration=5f9c38d2"))
        assertEquals("f6c3d87d8beb9a3f", blends.idOf("  f6c3d87d8beb9a3f "))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { blends.idOf("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") }
    }

    @Test fun `a pending invitation of this account reads as waiting, a joined one carries its playlist`() {
        val blends = SpotifyBlends(SpotifyHttp(OkHttpClient(), Bridge(RecordingHost { JsonNull }, scope)))
        // Both bodies are what the live service answered.
        val waiting = blends.parseInvitation(Bridge.json.parseToJsonElement("""{"page_type":"PENDING_INVITATION","members":null,
            "recipient":{"name":"Sam","username":"user0002","image_url":null,"hash":"7370"},"playlist_uri":null,
            "title":"Invite is pending","subtitle":"Invite someone else while you wait!","members_title":null,"button_text":"Invite","footnote":null}"""))
        assertEquals(app.myspoti.api.BlendInvitationState.WAITING, waiting.state)
        assertEquals("Sam", waiting.inviter?.name)
        assertEquals(emptyList<app.myspoti.api.BlendMember>(), waiting.members)
        val joined = blends.parseInvitation(Bridge.json.parseToJsonElement("""{"page_type":"ALREADY_JOINED","members":null,"recipient":null,
            "playlist_uri":"spotify:playlist:37i9dQZF1EJMv6aMStShzx","title":null,"subtitle":null,"members_title":null,"button_text":null,"footnote":null}"""))
        assertEquals(app.myspoti.api.BlendInvitationState.JOINED, joined.state)
        assertEquals("spotify:playlist:37i9dQZF1EJMv6aMStShzx", joined.playlist)
    }

    @Test fun `a whole album is added in requests of 100 songs, each at the bottom and in order`() = runBridge({ JsonNull }) { _, bridge ->
        val gql = FakeGql { _, _ -> JsonNull }
        val playlists = SpotifyPlaylists(gql, bridge, SpotifyHttp(OkHttpClient(), bridge))
        val uris = (0 until 230).map { "spotify:track:t$it" }
        playlists.addTracks("spotify:playlist:p", uris)
        assertEquals(listOf(100, 100, 30), gql.variables.map { it["playlistItemUris"]!!.jsonArray.size })
        assertEquals("spotify:track:t100", gql.variables[1]["playlistItemUris"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("BOTTOM_OF_PLAYLIST", gql.variables[2]["newPosition"]!!.jsonObject["moveType"]!!.jsonPrimitive.content)
        assertEquals("spotify:playlist:p", gql.variables[2]["playlistUri"]!!.jsonPrimitive.content)
    }

    @Test fun `a Blend's members and its taste-match card read as the live service gives them`() {
        val blends = SpotifyBlends(SpotifyHttp(OkHttpClient(), Bridge(RecordingHost { JsonNull }, scope)))
        // Shortened from what the live service answered for a two-person Blend.
        val group = blends.parseGroup(Bridge.json.parseToJsonElement("""{"title":"Other Blend members (2)","button_text":"Invite more people",
            "invitation_link":"https://open.spotify.com/blend/taste-match/98ad2d0287420131?si=x","members":[
            {"name":"Alex","image_url":"https://i.scdn.co/image/a","hash":"9","username":"user0001"},
            {"name":"Sam","image_url":"","hash":"7","username":"user0002"}]}"""))
        assertEquals(listOf("Alex", "Sam"), group.members.map { it.name })
        assertEquals(null, group.members[1].imageUrl)
        assertEquals("https://open.spotify.com/blend/taste-match/98ad2d0287420131?si=x", group.link)
        val story = blends.parseStory(Bridge.json.parseToJsonElement("""{"wrapped_mode_enabled":false,"playlist_uri":"spotify:playlist:37i9dQZF1EJEqgDMQbDle1",
            "intro_story":{"title1":{"text":"You're a 98% match","text_color":"#191414"},"subtitle1":{"text":"A friendship the world envies.","text_color":"#2F6F56"},
            "background_color":"#F5F3E4"},"stories":[{"story_type":"standard_two_user:blend","subtitle":{"text":"98% taste match","text_color":"#2F6F56"},
            "body":{"text":"The song that brings you together is X","text_color":"#2F6F56"},"image":"https://i.scdn.co/image/b","background_color":"#F5F3E4"}]}"""))
        assertEquals("You're a 98% match", story?.title)
        assertEquals("The song that brings you together is X", story?.detail)
        assertEquals("#F5F3E4", story?.background)
        assertEquals(null, blends.parseStory(Bridge.json.parseToJsonElement("""{"intro_story":null,"stories":[]}""")))
    }

    @Test fun `blocking a profile is the social graph's is-blocking path with both usernames, and its protobuf answer is read`() {
        assertEquals("https://spclient.wg.spotify.com/socialgraph/v4/me.name/is-blocking/other",
            SpotifyUsers.blockUrl("me.name", "spotify:user:other"))
        assertEquals("https://spclient.wg.spotify.com/socialgraph/v4/me/is-blocking/a%20b", SpotifyUsers.blockUrl("me", "spotify:user:a b"))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { SpotifyUsers.blockUrl("", "spotify:user:x") }
        assertEquals(true, SpotifyUsers.blockedAnswer(String(byteArrayOf(8, 1))))
        assertEquals(false, SpotifyUsers.blockedAnswer(String(byteArrayOf(8, 0))))
        assertEquals(false, SpotifyUsers.blockedAnswer(""))
    }
}
