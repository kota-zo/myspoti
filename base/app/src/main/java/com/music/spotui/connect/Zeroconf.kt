package com.music.spotui.connect

import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder

/** What a Spotify Connect device says about itself on its ZeroConf port (`getInfo`). */
data class ZeroconfInfo(
    val deviceId: String,
    val name: String,
    val clientId: String,
    /** How it wants to be logged in: "accesstoken" (a token Spotify issues), "default" (stored credentials), "authorization_code" (Sonos). */
    val tokenType: String,
    val version: String,
    /** The account logged in on it now ("" when none). Never logged. */
    val activeUser: String,
    val brand: String,
    val model: String,
) {
    /**
     * Worth a login attempt: it announced the ids Spotify's device-auth needs. A device that says
     * "accesstoken" takes the token as it is (Bose, Onkyo, Yamaha, Audio Pro reported); one that
     * says "authorization_code" (Sonos, reported once, no source says how its blob is made) is tried
     * the same way, and the answer is logged, since nothing here can tell beforehand.
     */
    val loginable: Boolean get() = deviceId.isNotBlank() && clientId.isNotBlank()
}

/**
 * Spotify Connect's ZeroConf API, the part that logs a speaker in: a speaker advertises
 * `_spotify-connect._tcp` (mDNS) with its port and a `CPath`, answers `getInfo` and takes
 * `addUser`. Devices whose getInfo says `tokenType=accesstoken` (Bose, Onkyo, Yamaha, Audio Pro
 * firmware reported) take a token in the blob as it is; the token is the one Spotify's
 * device-auth issues for the device's client id (the same one the Cast receiver gets), so
 * the account's web token never leaves the phone. Sonos announces `authorization_code`. Pure functions: the sockets are in [ZeroconfClient].
 */
object Zeroconf {
    const val SERVICE_TYPE = "_spotify-connect._tcp"
    const val ACCESS_TOKEN = "accesstoken"
    /** The ZeroConf version announced to the device (the one reported working with accesstoken devices). */
    const val VERSION = "2.7.1"

    /** The `CPath` of the service's TXT record as a path ("" and "/" both mean the root). */
    fun path(cpath: String?): String = "/" + (cpath ?: "").trim().trimStart('/')

    fun infoRequest(host: String, port: Int, cpath: String?): ByteArray =
        "GET ${path(cpath)}?action=getInfo&version=$VERSION HTTP/1.1\r\nHost: $host:$port\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.UTF_8)

    /** The `addUser` POST: the action in the query, the fields in the body (form encoded), as Spotify's partners' firmware reads it. */
    fun addUserRequest(host: String, port: Int, cpath: String?, userName: String, token: String, info: ZeroconfInfo, controllerName: String, controllerId: String): ByteArray {
        val body = form(
            "userName" to userName, "blob" to token, "clientKey" to info.clientId, "tokenType" to ACCESS_TOKEN,
            "loginId" to userName, "deviceName" to controllerName, "deviceId" to controllerId, "version" to VERSION,
        ).toByteArray(Charsets.UTF_8)
        val head = "POST ${path(cpath)}?action=addUser HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.size}\r\nAccept: */*\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + body
    }

    fun form(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    /** The device's getInfo answer, or null when it is not one (an error status, not JSON, no device id). */
    fun parseInfo(body: String): ZeroconfInfo? {
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (j.has("status") && j.optInt("status") != 101) return null
        val id = j.optString("deviceID").ifBlank { return null }
        return ZeroconfInfo(
            deviceId = id,
            name = j.optString("remoteName"),
            clientId = j.optString("clientID"),
            tokenType = j.optString("tokenType"),
            version = j.optString("version"),
            activeUser = j.optString("activeUser"),
            brand = j.optString("brandDisplayName"),
            model = j.optString("modelDisplayName"),
        )
    }

    /** The status and its name from an addUser answer, for the log (no token is ever in one). */
    fun answerSummary(body: String): String =
        runCatching { JSONObject(body) }.getOrNull()?.let { "status ${it.optInt("status", -1)} ${it.optString("statusString").take(40)}" } ?: "not JSON"

    /** The answer to addUser: accepted (101), already in that state (404 with no body, as Bose does), or refused. */
    fun addUserAccepted(status: Int, body: String): Boolean {
        if (status == 404 && body.isBlank()) return true
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return false
        return j.optInt("status", -1) == 101
    }

    /** The status and the body of an HTTP/1.1 answer read to its end (chunked included), or null when it is not one. */
    fun parseResponse(raw: ByteArray): Pair<Int, String>? {
        val end = indexOf(raw, "\r\n\r\n".toByteArray(), 0)
        if (end < 0) return null
        val head = String(raw, 0, end, Charsets.ISO_8859_1).split("\r\n")
        val status = head.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
        val chunked = head.any { it.startsWith("Transfer-Encoding:", true) && it.contains("chunked", true) }
        val bytes = if (chunked) dechunk(raw, end + 4) else raw.copyOfRange(end + 4, raw.size)
        return status to String(bytes, Charsets.UTF_8)
    }

    /** [raw] holds a whole answer already (the device may keep the connection open after it, whatever `Connection: close` says). */
    fun complete(raw: ByteArray): Boolean {
        val end = indexOf(raw, "\r\n\r\n".toByteArray(), 0)
        if (end < 0) return false
        val head = String(raw, 0, end, Charsets.ISO_8859_1).split("\r\n")
        if (head.any { it.startsWith("Transfer-Encoding:", true) && it.contains("chunked", true) })
            return indexOf(raw, "\r\n0\r\n".toByteArray(), end) >= 0 || (raw.size >= end + 7 && raw[end + 4] == '0'.code.toByte())
        val length = head.firstNotNullOfOrNull { l -> l.takeIf { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() } ?: return false
        return raw.size - (end + 4) >= length
    }

    private fun dechunk(raw: ByteArray, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var at = from
        while (at < raw.size) {
            val eol = indexOf(raw, "\r\n".toByteArray(), at)
            if (eol < 0) break
            val size = String(raw, at, eol - at, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = eol + 2
            out.write(raw, start, minOf(size, raw.size - start))
            at = start + size + 2
        }
        return out.toByteArray()
    }

    private fun indexOf(a: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..a.size - pattern.size) {
            for (k in pattern.indices) if (a[i + k] != pattern[k]) continue@outer
            return i
        }
        return -1
    }

    /**
     * An address on the local network (private IPv4, link-local, IPv6 unique local): the only ones
     * a token is ever sent to, so a service advertising a public address is not offered.
     */
    fun isLan(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return false
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        val b = address.address
        return b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
    }

    /** [info] is one of the account's Connect devices already: by the id Spotify gives it, else by its name. */
    fun inConnectList(info: ZeroconfInfo, ids: Set<String>, names: Set<String>): Boolean =
        info.deviceId in ids || (info.name.isNotBlank() && info.name in names)
}
