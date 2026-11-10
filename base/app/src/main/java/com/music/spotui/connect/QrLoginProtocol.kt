// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
package com.music.spotui.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Target device on the local network ready to receive Spotify Web Player cookies from a phone.
 * Either [token] (scanned from QR / deep link) or [pin] (typed manually from the TV screen) must be non-blank.
 */
data class QrPairTarget(
    val host: String,
    val port: Int,
    val token: String = "",
    val pin: String = "",
    val name: String = "",
) {
    val authQuery: String
        get() = when {
            token.isNotBlank() -> "t=${URLEncoder.encode(token, "UTF-8")}"
            pin.isNotBlank() -> "pin=${URLEncoder.encode(pin, "UTF-8")}"
            else -> ""
        }
}

data class LanCandidate(
    val address: InetAddress,
    val interfaceName: String = "",
    val isWifiOrEthernet: Boolean = false,
    val isVpnOrVirtual: Boolean = false,
    val isPointToPoint: Boolean = false,
)

/** Pure protocol helpers for LAN QR / phone pairing and Spotify cookie import (JVM-tested). */
object QrLoginProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{12,128}$")
    private val PIN_RE = Regex("^\\d{6}$")
    private val IPV4_RE = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val IPV6_CHARS_RE = Regex("^[0-9a-fA-F:]+$")
    val ALLOWED_COOKIES = setOf("sp_dc", "sp_key", "sp_t")

    /** Builds the HTTP URL encoded into the QR code shown on TV / waiting device. */
    fun buildPairUri(host: String, port: Int, token: String): String {
        val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "http://$bracketed:$port/pair?t=$token"
    }

    /** Builds the `myspoti://pair?...` deep link offered to phones that have Myspoti installed. */
    fun buildDeepLink(host: String, port: Int, token: String, deviceName: String): String {
        val encHost = URLEncoder.encode(host, "UTF-8")
        val encToken = URLEncoder.encode(token, "UTF-8")
        val encName = URLEncoder.encode(deviceName.take(48), "UTF-8")
        return "myspoti://pair?host=$encHost&port=$port&t=$encToken&name=$encName"
    }

    /** Formats a 6-digit PIN as `123 456` for easy reading across a living room. */
    fun formatPin(pin: String): String =
        if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3, 6)}" else pin

    /**
     * Checks that [rawHost] is a numeric LAN IP literal (private IPv4 RFC-1918, IPv4 link-local, or
     * IPv6 unique-local / link-local) without ever triggering a DNS resolution.
     */
    fun isPrivateLanHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (host.isBlank()) return false
        val ipv4 = IPV4_RE.matchEntire(host)
        if (ipv4 != null) {
            val octets = (1..4).map { ipv4.groupValues[it].toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            val bytes = byteArrayOf(octets[0].toByte(), octets[1].toByte(), octets[2].toByte(), octets[3].toByte())
            val addr = runCatching { InetAddress.getByAddress(bytes) as Inet4Address }.getOrNull() ?: return false
            return Zeroconf.isLan(addr)
        }
        if (':' in host && IPV6_CHARS_RE.matches(host)) {
            val addr = runCatching { InetAddress.getByName(host) as? Inet6Address }.getOrNull() ?: return false
            return Zeroconf.isLan(addr)
        }
        return false
    }

    /** True if [name] belongs to a VPN tunnel, cellular modem, or virtual/loopback interface. */
    fun isVirtualOrVpnInterfaceName(name: String): Boolean {
        val n = name.lowercase().trim()
        if (n.isEmpty()) return false
        return n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp") ||
            n.startsWith("wg") || n.startsWith("wireguard") || n.startsWith("tailscale") ||
            n.startsWith("utun") || n.startsWith("ipsec") || n.startsWith("vti") ||
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("wwan") || n.startsWith("clat") || n.startsWith("v4-") ||
            n.startsWith("dummy") || n.startsWith("sit") || n.startsWith("gre")
    }

    /**
     * Picks the best physical Wi-Fi / Ethernet LAN address, deliberately ignoring VPN tunnels,
     * point-to-point links, and cellular interfaces so QR codes always point to the real LAN IP
     * even when a VPN is active on the TV or phone.
     */
    fun pickBestLanAddress(candidates: List<LanCandidate>): InetAddress? {
        val eligible = candidates.filter { c ->
            Zeroconf.isLan(c.address) &&
                !c.isVpnOrVirtual &&
                !c.isPointToPoint &&
                !isVirtualOrVpnInterfaceName(c.interfaceName)
        }
        if (eligible.isEmpty()) return null
        return eligible.maxByOrNull { c ->
            var score = 0
            val n = c.interfaceName.lowercase()
            if (c.isWifiOrEthernet || n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("en") || n.startsWith("ap") || n.startsWith("swlan")) {
                score += 1000
            }
            val addr = c.address
            if (addr is Inet4Address) {
                score += 200
                val b = addr.address
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                when {
                    b0 == 192 && b1 == 168 -> score += 50
                    b0 == 172 && b1 in 16..31 -> score += 30
                    b0 == 10 -> score += 10
                }
            }
            if (n.startsWith("eth") || n.startsWith("wlan")) score += 5
            score
        }?.address
    }

    /** Parses either an `http://<lan-ip>:<port>/pair?t=<token>` QR URL or a `myspoti://pair?...` deep link. */
    fun parsePairUri(raw: String): QrPairTarget? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val query = parseQuery(uri.rawQuery)
        return when (scheme) {
            "http" -> {
                val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
                val port = uri.port
                if (port !in 1024..65535 || !isPrivateLanHost(host)) return null
                val path = uri.path.orEmpty()
                if (path != "/pair" && path != "/" && path.isNotEmpty()) return null
                val token = query["t"]?.trim().orEmpty()
                if (!TOKEN_RE.matches(token)) return null
                QrPairTarget(host = host, port = port, token = token)
            }
            "myspoti" -> {
                if (!uri.host.equals("pair", ignoreCase = true)) return null
                val host = query["host"]?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return null
                val port = query["port"]?.trim()?.toIntOrNull() ?: return null
                if (port !in 1024..65535 || !isPrivateLanHost(host)) return null
                val token = query["t"]?.trim().orEmpty()
                if (!TOKEN_RE.matches(token)) return null
                val name = query["name"]?.trim().orEmpty().take(48)
                QrPairTarget(host = host, port = port, token = token, name = name)
            }
            else -> null
        }
    }

    /**
     * Parses a manual address (`192.168.1.42:54321` or `http://192.168.1.42:54321`) + 6-digit PIN,
     * or a pasted full `http://.../pair?t=...` / `myspoti://pair?...` URL.
     */
    fun parseManualPairTarget(addressInput: String, pinInput: String): QrPairTarget? {
        parsePairUri(addressInput)?.let { return it }
        val normalizedAddr = addressInput.trim().let {
            if (it.startsWith("http://", ignoreCase = true)) it else "http://$it"
        }
        val uri = runCatching { URI(normalizedAddr) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
        val port = uri.port
        if (port !in 1024..65535 || !isPrivateLanHost(host)) return null
        val pin = pinInput.filter { it.isDigit() }
        if (!PIN_RE.matches(pin)) return null
        return QrPairTarget(host = host, port = port, pin = pin)
    }

    fun isValidTokenCharValue(value: String): Boolean =
        value.length in 16..4096 && value.none { it <= ' ' || it == ';' || it == '"' || it == '\\' || it.code >= 127 }

    /**
     * Extracts Spotify Web Player cookies (`sp_dc` required, plus optional `sp_key` / `sp_t`) from
     * any common clipboard format: bare `sp_dc` token, `sp_dc=...`, HTTP `Cookie:` string, JSON object/array
     * (Cookie-Editor export), or Netscape `cookies.txt` lines.
     */
    fun parseCookieInput(raw: String): Map<String, String>? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 32_768) return null
        val found = linkedMapOf<String, String>()

        fun consider(nameRaw: String, valRaw: String) {
            val name = nameRaw.trim()
            if (name !in ALLOWED_COOKIES) return
            val cleaned = valRaw.trim().removeSurrounding("\"")
            if (isValidTokenCharValue(cleaned)) found[name] = cleaned
        }

        // 1) JSON object or array (Cookie-Editor / extension export / internal payload)
        if (text.startsWith("{") || text.startsWith("[")) {
            runCatching {
                when (val el = json.parseToJsonElement(text)) {
                    is JsonObject -> {
                        val mapObj = (el["cookies"] as? JsonObject) ?: el
                        mapObj.forEach { (k, v) ->
                            (v as? JsonPrimitive)?.contentOrNull?.let { consider(k, it) }
                        }
                    }
                    is JsonArray -> {
                        el.forEach { item ->
                            val o = item as? JsonObject ?: return@forEach
                            val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            val v = o["value"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            consider(n, v)
                        }
                    }
                    else -> Unit
                }
            }
            if (!found["sp_dc"].isNullOrBlank()) return found.toMap()
        }

        // 2) Netscape cookies.txt lines (tab-separated, 7 columns: domain, flag, path, secure, exp, name, value)
        if ('\t' in text) {
            text.lineSequence().forEach { line ->
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) return@forEach
                val cols = l.split('\t')
                if (cols.size >= 7) consider(cols[5], cols[6])
            }
            if (!found["sp_dc"].isNullOrBlank()) return found.toMap()
        }

        // 3) Cookie header ("Cookie: sp_dc=...; sp_key=...") or newline/semicolon separated key=value pairs
        val withoutPrefix = text.replace(Regex("^(?i)cookie\\s*:\\s*"), "")
        if ('=' in withoutPrefix) {
            withoutPrefix.split(';', '\n', '\r').forEach { part ->
                val eq = part.indexOf('=')
                if (eq > 0) consider(part.substring(0, eq), part.substring(eq + 1))
            }
            if (!found["sp_dc"].isNullOrBlank()) return found.toMap()
        }

        // 4) Single `sp_dc: <value>` line copied from DevTools detail view
        val colonMatch = Regex("(?i)^sp_dc\\s*:\\s*(\\S+)$").matchEntire(text)
        if (colonMatch != null) {
            consider("sp_dc", colonMatch.groupValues[1])
            if (!found["sp_dc"].isNullOrBlank()) return found.toMap()
        }

        // 5) Bare `sp_dc` value pasted directly
        val unwrapped = text.removeSurrounding("\"")
        if ('=' !in unwrapped && ';' !in unwrapped && !unwrapped.any { it.isWhitespace() }) {
            consider("sp_dc", unwrapped)
            if (!found["sp_dc"].isNullOrBlank()) return found.toMap()
        }

        return null
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val eq = pair.indexOf('=')
            val key = decodeQueryComponent(if (eq >= 0) pair.substring(0, eq) else pair)
            val value = if (eq >= 0) decodeQueryComponent(pair.substring(eq + 1)) else ""
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun decodeQueryComponent(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun jsonObjectString(vararg pairs: Pair<String, String>): String =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    fun escapeHtml(s: String): String = buildString(s.length) {
        s.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}

/** Minimal strict HTTP/1.1 parser & response formatter for the local LAN pairing socket. */
object QrHttp {
    private val json = Json { ignoreUnknownKeys = true }

    data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private const val MAX_HEAD_BYTES = 8 * 1024
    private const val MAX_BODY_BYTES = 16 * 1024

    fun readRequest(input: InputStream): HttpRequest? {
        val headBuf = ByteArrayOutputStream()
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        while (headBuf.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            headBuf.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        val headText = headBuf.toString("ISO_8859_1")
        if (!headText.endsWith("\r\n\r\n")) return null
        val lines = headText.substring(0, headText.length - 4).split("\r\n")
        if (lines.isEmpty()) return null
        val reqParts = lines[0].split(' ')
        if (reqParts.size < 2) return null
        val method = reqParts[0].uppercase()
        val rawTarget = reqParts[1]
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val query = if (qIdx >= 0) QrLoginProtocol.parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()
        val headers = linkedMapOf<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                headers[lines[i].substring(0, colon).trim().lowercase()] = lines[i].substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return HttpRequest(method, path, query, headers, body)
    }

    fun buildResponse(
        code: Int,
        status: String,
        contentType: String,
        body: ByteArray,
    ): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return head + body
    }

    fun jsonResponse(code: Int, status: String, jsonBody: String): ByteArray =
        buildResponse(code, status, "application/json; charset=utf-8", jsonBody.toByteArray(Charsets.UTF_8))

    fun parseJsonObject(bytes: ByteArray): JsonObject? =
        runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()

    fun buildHttpGet(host: String, port: Int, pathAndQuery: String): ByteArray {
        val hostHeader = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
        return ("GET $pathAndQuery HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
    }

    fun buildHttpJsonPost(host: String, port: Int, path: String, jsonBody: String): ByteArray {
        val body = jsonBody.toByteArray(Charsets.UTF_8)
        val hostHeader = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
        val head = ("POST $path HTTP/1.1\r\nHost: $hostHeader\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.US_ASCII)
        return head + body
    }

    fun readResponse(input: InputStream): Pair<Int, String>? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < 64 * 1024) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        val raw = out.toByteArray().toString(Charsets.UTF_8)
        val sep = raw.indexOf("\r\n\r\n")
        if (sep < 0) return null
        val statusLine = raw.substringBefore("\r\n")
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
        return code to raw.substring(sep + 4)
    }

    /** Mobile-friendly Spotify-themed offline HTML page opened when a phone scans the QR code. */
    fun pairingHtml(deviceName: String, host: String, port: Int, authorizedToken: String?): String {
        val safeName = QrLoginProtocol.escapeHtml(deviceName.ifBlank { "Myspoti" })
        val appCardHtml = if (!authorizedToken.isNullOrBlank()) {
            val deepLink = QrLoginProtocol.escapeHtml(QrLoginProtocol.buildDeepLink(host, port, authorizedToken, deviceName))
            """
  <div class="card" id="appCard">
    <h2>1. Already have Myspoti on this phone?</h2>
    <p>Tap below to transfer your signed-in session in one tap.</p>
    <a class="btn btn-green" href="$deepLink">Open in Myspoti App</a>
  </div>"""
        } else ""
        val stepHeading = if (!authorizedToken.isNullOrBlank()) "2. Or sign in using any desktop browser" else "Sign in using any browser"
        val pinDisplay = if (!authorizedToken.isNullOrBlank()) "none" else "block"
        return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sign in $safeName — Myspoti</title>
<style>
  :root{--bg:#121212;--card:#1e1e1e;--muted:#b3b3b3;--green:#1db954;--err:#ff6b6b;--border:#333}
  *{box-sizing:border-box}
  body{margin:0;padding:20px 16px 40px;background:var(--bg);color:#fff;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;line-height:1.45}
  .wrap{max-width:460px;margin:0 auto}
  h1{font-size:24px;margin:8px 0 6px;font-weight:800}
  p.sub{color:var(--muted);margin:0 0 20px;font-size:15px}
  .card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:18px;margin-bottom:16px}
  .card h2{font-size:17px;margin:0 0 8px}
  .card p,.card li{color:var(--muted);font-size:14px;margin:0 0 10px}
  .card ol{margin:8px 0 14px;padding-left:20px}
  .btn{display:block;width:100%;text-align:center;text-decoration:none;border:0;border-radius:999px;padding:14px 18px;font-size:16px;font-weight:700;cursor:pointer}
  .btn-green{background:var(--green);color:#000}
  .btn-dark{background:#2a2a2a;color:#fff;border:1px solid #444;margin-top:10px}
  label{display:block;font-size:13px;font-weight:600;color:var(--muted);margin:12px 0 6px}
  input,textarea{width:100%;background:#121212;color:#fff;border:1px solid #444;border-radius:10px;padding:12px;font-size:15px;font-family:monospace}
  textarea{min-height:84px;resize:vertical}
  .status{margin-top:14px;padding:12px 14px;border-radius:10px;font-size:14px;display:none}
  .status.show{display:block}
  .status.info{background:#1d2b22;border:1px solid #2b6a41;color:#d6ffe4}
  .status.err{background:#2d1a1a;border:1px solid #7d3030;color:#ffd0d0}
  code{background:#121212;padding:2px 6px;border-radius:6px;font-size:13px;color:#fff}
</style>
</head>
<body>
<div class="wrap">
  <h1>Sign in $safeName</h1>
  <p class="sub">Send your Spotify session directly over your local Wi&#8209;Fi — no TV remote typing or TV captcha.</p>$appCardHtml

  <div class="card">
    <h2>$stepHeading</h2>
    <ol>
      <li>Open <a href="https://open.spotify.com" target="_blank" rel="noopener" style="color:#1db954">open.spotify.com</a> in Chrome, Edge, or Firefox and log in.</li>
      <li>Press <code>F12</code> → <b>Application</b> (or <b>Storage</b>) → <b>Cookies</b> → <code>https://open.spotify.com</code>.</li>
      <li>Copy the value of <code>sp_dc</code> and paste it below.</li>
    </ol>
    <form id="pairForm">
      <div id="pinRow" style="display:$pinDisplay">
        <label for="pin">6-digit code shown on screen</label>
        <input id="pin" inputmode="numeric" maxlength="6" placeholder="123456" autocomplete="one-time-code">
      </div>
      <label for="cookieText">Paste <code>sp_dc</code> cookie value (or Cookie header / JSON)</label>
      <textarea id="cookieText" placeholder="AQ... or sp_dc=AQ..."></textarea>
      <button class="btn btn-green" type="submit" id="submitBtn" style="margin-top:12px">Sign In Device</button>
    </form>
    <div id="status" class="status"></div>
  </div>
</div>
<script>
(function(){
  var q=new URLSearchParams(location.search);
  var t=q.get('t')||'';
  var hasToken=${if (!authorizedToken.isNullOrBlank()) "true" else "false"};
  var st=document.getElementById('status');
  var btn=document.getElementById('submitBtn');
  var pollTimer=null;
  function show(msg,err){st.textContent=msg;st.className='status show '+(err?'err':'info');}
  function poll(){
    var auth=hasToken?('t='+encodeURIComponent(t)):('pin='+encodeURIComponent(document.getElementById('pin').value.trim()));
    fetch('/status?'+auth).then(function(r){return r.json()}).then(function(d){
      if(d.state==='signed_in'){clearInterval(pollTimer);btn.disabled=false;show('Signed in! You can close this page.',false);}
      else if(d.state==='failed'){clearInterval(pollTimer);btn.disabled=false;show(d.message||'Spotify rejected that cookie. Make sure you copied sp_dc while signed in.',true);}
      else if(d.state==='checking'){show('Checking your session with Spotify...',false);}
    }).catch(function(){});
  }
  document.getElementById('pairForm').addEventListener('submit',function(e){
    e.preventDefault();
    var cookieText=document.getElementById('cookieText').value.trim();
    var pin=document.getElementById('pin').value.replace(/\D/g,'');
    if(!cookieText){show('Paste your sp_dc cookie first.',true);return;}
    if(!hasToken&&pin.length!==6){show('Enter the 6-digit code shown on the device.',true);return;}
    btn.disabled=true;
    show('Sending to device...',false);
    fetch('/submit',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({token:hasToken?t:'',pin:pin,cookieText:cookieText})
    }).then(function(r){return r.json().then(function(d){return {ok:r.ok,d:d}})}).then(function(res){
      if(!res.ok){btn.disabled=false;show((res.d&&res.d.message)||'Could not pair.',true);return;}
      show('Checking your session with Spotify...',false);
      clearInterval(pollTimer);
      pollTimer=setInterval(poll,800);
      poll();
    }).catch(function(){btn.disabled=false;show('Could not reach device on Wi-Fi.',true);});
  });
})();
</script>
</body>
</html>"""
    }
}
