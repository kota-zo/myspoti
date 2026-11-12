package app.myspoti.spotify.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The device registration is the one request where this client tells Spotify what it is. It must
 * stay byte-for-byte what the real Web Player sends — the capabilities in particular: a device that
 * declares fewer formats is the only one of its kind (and the prime suspect for the 402 refusals
 * some accounts get). Only the display name changes, so the phone appears as the phone it is.
 */
class DeviceRegistrationTest {
    // The real payload, as captured from the Web Player: note `manifest_urls_audio_ad` twice.
    private val registration = """
        {"device":{"brand":"spotify","capabilities":{"change_volume":true,"enable_play_token":true,
        "supports_file_media_type":true,"play_token_lost_behavior":"pause","disable_connect":false,
        "audio_podcasts":true,"video_playback":true,"manifest_formats":["file_ids_mp3","file_urls_mp3",
        "manifest_urls_audio_ad","manifest_ids_video","file_urls_external","file_ids_mp4","file_ids_mp4_dual",
        "manifest_urls_audio_ad"],"supports_preferred_media_type":true,"supports_playback_offsets":true,
        "supports_playback_speed":true},"device_id":"x","device_type":"computer","metadata":{},
        "model":"web_player","name":"Web Player (Chrome)",
        "platform_identifier":"web_player linux undefined;chrome 155.0.0.0;desktop","is_group":false,
        "is_public":false},"client_version":"harmony:4.81.1-1f3d48b7f","volume":65535}
    """.trimIndent()

    @Test fun `the device name becomes the phone's own`() {
        val out = DeviceRegistration.withDeviceName(registration, "Test Phone")
        assertTrue(""""name":"Test Phone"""" in out, out)
        assertFalse("Web Player (Chrome)" in out, out)
    }

    @Test fun `nothing else in the registration changes`() {
        val out = DeviceRegistration.withDeviceName(registration, "Test Phone")
        // The capability list is the Web Player's own, duplicate entry included: it is the shape
        // every other client has, and lying about it is what stood out.
        assertTrue("\"manifest_urls_audio_ad\",\"manifest_ids_video\"" in out, out)
        assertEquals(2, Regex("manifest_urls_audio_ad").findAll(out).count(), out)
        assertTrue(""""model":"web_player"""" in out && """"device_type":"computer"""" in out, out)
        assertTrue(""""platform_identifier":"web_player linux undefined;chrome 155.0.0.0;desktop"""" in out, out)
        assertTrue(""""client_version":"harmony:4.81.1-1f3d48b7f"""" in out && """"volume":65535""" in out, out)
        // Same length apart from the name: nothing was re-serialised.
        assertEquals(registration.length - "Web Player (Chrome)".length + "Test Phone".length, out.length, out)
    }

    @Test fun `a name with quotes is escaped, an empty one changes nothing`() {
        val tricky = DeviceRegistration.withDeviceName(registration, "The \"Test\" Phone")
        val expected = "\"name\":\"The \\\"Test\\\" Phone\""
        assertTrue(expected in tricky, tricky)
        assertEquals(registration, DeviceRegistration.withDeviceName(registration, ""))
        assertEquals(registration, DeviceRegistration.withDeviceName(registration, "Web Player (Chrome)"))
    }

    @Test fun `other bodies stay as they are`() {
        assertEquals("not json", DeviceRegistration.withDeviceName("not json", "Test Phone"))
        assertEquals("""{"seq_num":2}""", DeviceRegistration.withDeviceName("""{"seq_num":2}""", "Test Phone"))
    }

    @Test fun `only the registration is rewritten`() {
        assertTrue(DeviceRegistration.isDeviceRegistration("POST", "https://gew4-spclient.spotify.com/track-playback/v1/devices"))
        assertFalse(DeviceRegistration.isDeviceRegistration("PUT", "https://gew4-spclient.spotify.com/track-playback/v1/devices/abc/state"))
        assertFalse(DeviceRegistration.isDeviceRegistration("POST", "https://gew4-spclient.spotify.com/track-playback/v1/devices/abc/state"))
    }
}
