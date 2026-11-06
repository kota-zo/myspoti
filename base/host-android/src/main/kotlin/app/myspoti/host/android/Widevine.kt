package app.myspoti.host.android

/**
 * The phone's Widevine, as MediaDrm reports it. Every diagnostic with Spotify refusing each song's
 * audio licence (403, from the very first song, before any ad) came from a phone on Widevine 15.0
 * or 16.0 (Lenovo TB-X606F, Pixel 3, Xiaomi Mi 9T, OnePlus 5T; L1 and L3 alike, all free accounts,
 * like the ones that play); none of the phones on 18.0 had it.
 */
object Widevine {
    /** "15.0.0", "18.0.0@343213000", or null when MediaDrm does not answer. Read once. */
    val version: String? by lazy {
        runCatching {
            val drm = android.media.MediaDrm(java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
            try { drm.getPropertyString("version") }
            finally { if (android.os.Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release() }
        }.getOrNull()
    }

    /** True for a version below 16.1, the ones seen refused; false when it cannot be read. */
    fun tooOld(version: String?): Boolean {
        val m = Regex("""^(\d+)\.(\d+)""").find(version ?: return false) ?: return false
        val major = m.groupValues[1].toInt()
        return major < 16 || (major == 16 && m.groupValues[2].toInt() < 1)
    }
}
