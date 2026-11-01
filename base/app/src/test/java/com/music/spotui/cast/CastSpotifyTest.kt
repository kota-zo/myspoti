package com.music.spotui.cast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSpotifyTest {
    @Test fun deviceIdIsTheMd5OfTheName() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", CastProtocol.spotifyDeviceId(""))
        assertEquals("5d41402abc4b2a76b9719d911017c592", CastProtocol.spotifyDeviceId("hello"))
    }

    @Test fun getInfoCarriesTheNameTheIdAndTheGroupFlag() {
        val m = CastProtocol.spotifyGetInfo(7, "Living room", "abc", group = false)
        assertEquals("getInfo", m.getString("type"))
        assertEquals(7, m.getInt("requestId"))
        val p = m.getJSONObject("payload")
        assertEquals("Living room", p.getString("remoteName"))
        assertEquals("abc", p.getString("deviceID"))
        assertEquals(false, p.getBoolean("deviceAPI_isGroup"))
    }

    @Test fun addUserCarriesTheTokenAsAnAccessToken() {
        val p = CastProtocol.spotifyAddUser(8, "tok").getJSONObject("payload")
        assertEquals("tok", p.getString("blob"))
        assertEquals("accesstoken", p.getString("tokenType"))
    }

    @Test fun repliesAreTold() {
        assertEquals(CastProtocol.SpotifyReply.Info("cid"),
            CastProtocol.spotifyReply(JSONObject("""{"type":"getInfoResponse","payload":{"clientID":"cid"}}""")))
        assertEquals(CastProtocol.SpotifyReply.Info("cid2"),
            CastProtocol.spotifyReply(JSONObject("""{"type":"getInfoResponse","payload":{"clientId":"cid2"}}""")))
        assertEquals(CastProtocol.SpotifyReply.Failed("getInfoResponse"),
            CastProtocol.spotifyReply(JSONObject("""{"type":"getInfoResponse","payload":{}}""")))
        assertEquals(CastProtocol.SpotifyReply.UserAdded, CastProtocol.spotifyReply(JSONObject("""{"type":"addUserResponse"}""")))
        assertEquals(CastProtocol.SpotifyReply.Failed("addUserError"), CastProtocol.spotifyReply(JSONObject("""{"type":"addUserError","payload":{}}""")))
        assertNull(CastProtocol.spotifyReply(JSONObject("""{"type":"somethingElse"}""")))
    }

    @Test fun deviceAuthBodyAndToken() {
        val b = JSONObject(CastProtocol.deviceAuthBody("cid", "dev"))
        assertEquals("cid", b.getString("clientId"))
        assertEquals("dev", b.getString("deviceId"))
        assertEquals("secret", CastProtocol.deviceAuthToken("""{"accessToken":"secret","expiresIn":3600}"""))
        assertNull(CastProtocol.deviceAuthToken("""{"error":"x"}"""))
        assertNull(CastProtocol.deviceAuthToken("not json"))
    }

    @Test fun receiverStatusIsReadForTheAppAsked() {
        val status = JSONObject("""{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"CC32E753","sessionId":"s1","transportId":"t1"}]}}""")
        assertEquals(CastProtocol.App("s1", "t1"), CastProtocol.receiver(status, CastProtocol.SPOTIFY_RECEIVER).app)
        assertNull(CastProtocol.receiver(status).app)
        assertTrue(CastProtocol.launch(1, CastProtocol.SPOTIFY_RECEIVER).toString().contains("CC32E753"))
        assertEquals("CC1AD845", CastProtocol.launch(1).getString("appId"))
    }
}
