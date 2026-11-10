package app.myspoti.host.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * The browser this install pretends to be.
 *
 * Every install used to announce the very same user agent and client hints — the same Chrome build,
 * the same Linux version. That is a **group** signature: one rule on Spotify's side would have
 * covered every Myspoti at once, and blocking one profile would have blocked them all. Each install
 * draws its own values once and keeps them; within a run nothing changes (no browser changes its own
 * version between two requests).
 *
 * The version follows the WebView: what is kept is how far behind it this install claims to be
 * ([lag], at most [BACK_RELEASES]), not a frozen number. A real Chrome updates itself, and a version
 * drawn once and kept for ever would have drifted further back at every WebView update.
 */
internal data class Fingerprint(val major: Int, val build: Int, val patch: Int, val kernel: String) {

    /**
     * The user agent the WebView presents, and the one the app's own HTTP calls use. Reduced, like
     * every desktop Chrome since the user-agent reduction: `Chrome/<major>.0.0.0`. The full version
     * only exists in the client hints ([fullVersion]); a build number in this string is something no
     * real Chrome sends, so it would have marked every install instead of hiding it.
     */
    val userAgent: String
        get() = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"

    /** What the client hints announce as this browser's full version. */
    val fullVersion: String get() = "$major.0.$build.$patch"

    /** The brand list, in the order and with the grease brand Chromium itself derives from [major]. */
    val brands: List<Brand> get() = brandsOf(major, fullVersion)

    /** The `sec-ch-ua` header these brands make, as Chromium writes it. */
    val secChUa: String get() = brands.joinToString(", ") { "\"${it.name}\";v=\"${it.major}\"" }

    data class Brand(val name: String, val major: String, val fullVersion: String)

    /** What is kept on disk: the draw, never the version itself (that one comes from the WebView). */
    data class Draw(val lag: Int, val patch: Int, val kernel: String)

    companion object {
        /** How many Chrome releases back this install may claim (two: recent, and still plausible). */
        const val BACK_RELEASES = 2

        /**
         * The oldest Chrome this install may claim.
         *
         * Spotify's web player answers an older agent with its own "this browser is not supported"
         * page, whatever the engine underneath can actually do. Android 6 and 7 WebViews sit at
         * Chrome 87–106 and used to keep their own version here, so a TV got that page and could
         * never sign in — while the page itself is polyfilled for those engines
         * ([WebPlayerHost.COMPAT_POLYFILL_JS]). The claimed version is brought up to a current
         * Chrome; the engine is what it is, and a page it cannot parse still reports itself.
         */
        const val MIN_CLAIMED_MAJOR = 141

        /** Linux versions a desktop Chrome would report (kernel 6.x, the versions in the wild). */
        val KERNELS = listOf("6.8.0", "6.7.2", "6.6.9", "6.5.7", "6.9.1", "6.4.12")

        /**
         * Chrome's build number grows by about this much per release (130 → 6723, 140 → 7339,
         * 143 → 7499): the estimate for a release behind the WebView, whose real build is unknown here.
         */
        const val BUILDS_PER_MAJOR = 55

        /** Used only when the WebView's version cannot be read. */
        internal const val FALLBACK_ENGINE = "141.0.7390.122"

        // Chromium's GREASE (components/embedder_support/user_agent_utils.cc): the fake brand, its
        // version and the order of the three brands all follow from the major version. A fixed
        // grease brand next to a varying major is a combination no real Chrome produces.
        private val GREASE_CHARS = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        private val GREASE_VERSIONS = listOf("8", "99", "24")
        private val ORDERS = listOf(
            intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
            intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0),
        )

        fun brandsOf(major: Int, fullVersion: String): List<Brand> {
            val greaseVersion = GREASE_VERSIONS[major % GREASE_VERSIONS.size]
            val grease = Brand(
                "Not${GREASE_CHARS[major % GREASE_CHARS.size]}A${GREASE_CHARS[(major + 1) % GREASE_CHARS.size]}Brand",
                greaseVersion,
                "$greaseVersion.0.0.0",
            )
            val order = ORDERS[major % ORDERS.size]
            val out = arrayOfNulls<Brand>(3)
            out[order[0]] = grease
            out[order[1]] = Brand("Chromium", "$major", fullVersion)
            out[order[2]] = Brand("Google Chrome", "$major", fullVersion)
            return out.map { it!! }
        }

        /** A Chrome version string ("141.0.7390.122") as (major, build, patch); null if it is not one. */
        fun parseEngine(version: String?): Triple<Int, Int, Int>? {
            val parts = version?.trim()?.split('.')?.map { it.toIntOrNull() } ?: return null
            if (parts.size != 4 || parts.any { it == null }) return null
            val (major, _, build, patch) = parts.map { it!! }
            // Android 6 WebViews cap around Chrome 87-106; accepting 87+ keeps them matching their real TLS/JS stack.
            return if (major in 87..300 && build > 0) Triple(major, build, patch) else null
        }

        /**
         * `Accept-Language` as Chromium writes it from a language list: the first plain, then
         * q=0.9, 0.8… down to 0.1, joined by a bare comma ("fr-CH,fr;q=0.9,en;q=0.8").
         */
        fun acceptLanguage(tags: List<String>): String = tags.filter { it.isNotBlank() }
            .mapIndexed { i, tag -> if (i == 0) tag else "$tag;q=0.${maxOf(1, 10 - i)}" }
            .joinToString(",")

        fun draw(random: Random = Random.Default) = Draw(
            lag = random.nextInt(0, BACK_RELEASES + 1),
            patch = random.nextInt(1, 200),
            kernel = KERNELS[random.nextInt(KERNELS.size)],
        )

        /**
         * This install's browser on the WebView [engineVersion]. With no lag it is exactly the
         * WebView's own version; behind it, the build is estimated and the patch is the drawn one.
         * A WebView older than [MIN_CLAIMED_MAJOR] claims a current Chrome instead — see there.
         */
        fun resolve(engineVersion: String?, draw: Draw): Fingerprint {
            val (engineMajor, engineBuild, enginePatch) = parseEngine(engineVersion) ?: parseEngine(FALLBACK_ENGINE)!!
            val lag = draw.lag.coerceIn(0, BACK_RELEASES)
            val claimed = engineMajor - lag
            if (engineMajor < MIN_CLAIMED_MAJOR) {
                // A WebView older than [MIN_CLAIMED_MAJOR] (Android 6/7 TVs, old phones): Spotify would
                // answer "this browser is not supported" to its own version. Claim a current Chrome.
                val (major, build) = parseEngine(FALLBACK_ENGINE)!!
                return Fingerprint(major, build, draw.patch, draw.kernel)
            }
            return if (lag == 0) Fingerprint(engineMajor, engineBuild, enginePatch, draw.kernel)
            else Fingerprint(claimed, engineBuild - lag * BUILDS_PER_MAJOR, draw.patch, draw.kernel)
        }

        /**
         * A draw read back from [json]. The file of earlier versions held the claimed version
         * itself (major, build, patch): its lag is recovered against the current WebView, so an
         * install keeps its place behind it rather than drawing a new identity.
         */
        fun drawFrom(json: JSONObject, engineVersion: String?): Draw? {
            val kernel = json.optString("kernel").takeIf { it in KERNELS } ?: return null
            val patch = json.optInt("patch", 0).takeIf { it > 0 } ?: return null
            if (json.has("lag")) return Draw(json.optInt("lag", 0).coerceIn(0, BACK_RELEASES), patch, kernel)
            val major = json.optInt("major", 0).takeIf { it > 0 } ?: return null
            val engineMajor = (parseEngine(engineVersion) ?: parseEngine(FALLBACK_ENGINE)!!).first
            return Draw((engineMajor - major).coerceIn(0, BACK_RELEASES), patch, kernel)
        }

        fun of(context: Context): Fingerprint {
            val engine = runCatching {
                androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName
            }.getOrNull()
            val file = File(context.filesDir, "spotify/browser.json")
            val json = runCatching { JSONObject(file.readText()) }.getOrNull()
            val saved = json?.let { runCatching { drawFrom(it, engine) }.getOrNull() }
            val draw = saved ?: draw()
            // Written once, or once more to move a file of the old shape to the new one.
            if (saved == null || json?.has("lag") != true) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(JSONObject().put("lag", draw.lag).put("patch", draw.patch).put("kernel", draw.kernel).toString())
                }
            }
            // The WebView's own major, whatever lag was drawn: the page's requests leave through that
            // WebView's network stack (and the relay's Chromium routes come from the same family), so a
            // user agent one or two releases behind it ("Chrome/154" on WebView 155) contradicts the
            // TLS and HTTP/2 shape under it. The draw keeps its lag on disk, unused.
            return resolve(engine, draw.copy(lag = 0))
        }
    }
}
