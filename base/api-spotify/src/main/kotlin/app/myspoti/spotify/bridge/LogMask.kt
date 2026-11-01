package app.myspoti.spotify.bridge

/**
 * What a log line may keep of a bridge call or a host message. The diagnostic is handed to other
 * people, so the values that open an account (tokens, cookies, the Authorization header, passwords,
 * sign-in codes) and the e-mail address are hidden before a line is written; everything a bug report
 * reads (names, durations, status codes, track ids, context uris) is left as it is.
 */
object LogMask {
    private const val HIDDEN = "(hidden)"

    /** A key whose value is a secret, in JSON (`"access_token":"…"`) or as a header or form field. */
    private const val SECRET_KEY = "[\\w-]*(?:token|cookie|authorization|password|passwd|secret|csrf|otp|verifier|auth_?code|sp_dc|sp_key)[\\w-]*|e-?mail"

    private val jsonValue = Regex("(?i)(\"(?:$SECRET_KEY)\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)")
    private val headerValue = Regex("(?i)\\b(authorization|cookie|set-cookie|proxy-authorization)(\\s*[:=]\\s*)[^\\r\\n]*")
    private val queryValue = Regex("(?i)([?&;](?:$SECRET_KEY|code)=)[^&\\s\"']+")
    private val bearer = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val address = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")

    /** A run this long without a space is a token or a blob, never a title or an id (a track id has 22 characters). */
    private val blob = Regex("[A-Za-z0-9_=.-]{120,}")

    /** [text] with its secrets replaced by "(hidden)". */
    fun redact(text: String): String {
        var out = text
        if (out.contains('"')) out = jsonValue.replace(out) { "${it.groupValues[1]}\"$HIDDEN\"" }
        out = headerValue.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$HIDDEN" }
        out = queryValue.replace(out) { "${it.groupValues[1]}$HIDDEN" }
        out = bearer.replace(out) { "${it.groupValues[1]} $HIDDEN" }
        if (out.contains('@')) out = address.replace(out, "(e-mail hidden)")
        return blob.replace(out, "(long value hidden)")
    }

    /** [text] redacted, then cut to [max] characters with the number cut. */
    fun shown(text: String, max: Int = 200): String {
        val clean = redact(text)
        return if (clean.length <= max) clean else clean.take(max) + "… (+${clean.length - max})"
    }
}
