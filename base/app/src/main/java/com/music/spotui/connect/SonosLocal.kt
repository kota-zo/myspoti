package com.music.spotui.connect

import java.net.InetAddress

/**
 * A Sonos's own local control (UPnP AVTransport on port 1400, the interface the SoCo library and
 * Home Assistant drive): after a Spotify Connect transfer a Sonos may take the session and stay
 * paused, with no sound until its play is pressed (reported to SpotifyPlus, fixed there the same
 * way: read the transport, press play when it does not play). Only the transport is touched:
 * nothing is queued or loaded here, the song stays Spotify's.
 */
object SonosLocal {
    const val PORT = 1400
    private const val CONTROL = "/MediaRenderer/AVTransport/Control"
    private const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

    /** A Sonos, by what it says on its ZeroConf port (brand) or where it says it (port 1400, /spotifyzc). */
    fun isSonos(d: LanDevice): Boolean =
        d.info.brand.equals("Sonos", ignoreCase = true) || (d.port == PORT && d.cpath.trimEnd('/') == "/spotifyzc")

    fun request(host: String, action: String, args: String = ""): ByteArray {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="$SERVICE"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "POST $CONTROL HTTP/1.1\r\nHost: $host:$PORT\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n" +
            "SOAPACTION: \"$SERVICE#$action\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + bytes
    }

    /** `CurrentTransportState` of a GetTransportInfo answer (PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING…), or null. */
    fun transportState(body: String): String? =
        Regex("<CurrentTransportState>([A-Z_]+)</CurrentTransportState>").find(body)?.groupValues?.get(1)

    /** The UPnP error code of a SOAP fault (701: not the group's coordinator, for one), or null. */
    fun faultCode(body: String): Int? = Regex("<errorCode>(\\d+)</errorCode>").find(body)?.groupValues?.get(1)?.toIntOrNull()

    /** What to do once Spotify moved the song: press play only when the speaker stands still and the song played here. */
    fun needsPlay(state: String?, wanted: Boolean): Boolean =
        wanted && state in setOf("PAUSED_PLAYBACK", "STOPPED")

    /**
     * Once the song was moved to [d]: reads its transport for a few seconds (it says TRANSITIONING
     * while it fetches) and presses play when it stands still while the song was playing.
     */
    suspend fun startIfSilent(d: LanDevice, wanted: Boolean) {
        if (!isSonos(d) || !wanted) return
        val host = d.host
        var state: String? = null
        for (k in 1..6) {
            kotlinx.coroutines.delay(1000)
            state = call(host, "GetTransportInfo")?.let { transportState(it.second) }
            if (state == "PLAYING") { log("plays after the transfer (${k}s)"); return }
            if (state != null && state != "TRANSITIONING" && k >= 3) break
        }
        if (!needsPlay(state, wanted)) { log("after the transfer: ${state ?: "no answer"}"); return }
        val answer = call(host, "Play", "<Speed>1</Speed>")
        val fault = answer?.let { faultCode(it.second) }
        log("after the transfer: $state, play pressed: ${answer?.first ?: "no answer"}${fault?.let { " (UPnP error $it)" }.orEmpty()}")
    }

    /** After a transfer to [id] / [name]: [startIfSilent] when it is a Sonos found on the network. */
    suspend fun afterTransfer(id: String, name: String, wanted: Boolean) {
        val d = LanDiscovery.devices.toList().firstOrNull { it.info.deviceId == id }
            ?: LanDiscovery.devices.toList().firstOrNull { name.isNotBlank() && it.info.name == name } ?: return
        startIfSilent(d, wanted)
    }

    private fun call(host: InetAddress, action: String, args: String = ""): Pair<Int, String>? =
        LanDiscovery.call(host, PORT, request(host.hostAddress.orEmpty(), action, args), 4_000)

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("lan: sonos local: $line")
}
