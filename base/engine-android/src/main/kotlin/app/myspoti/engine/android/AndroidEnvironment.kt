// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine.android

import app.myspoti.engine.Environment
import java.security.SecureRandom

/** Wall clock and ids: device ids and command ids are random hex, as the browser's crypto made them. */
object AndroidEnvironment : Environment {
    private val random = SecureRandom()
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun randomHex(chars: Int): String {
        val bytes = ByteArray((chars + 1) / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.take(chars)
    }
}

/** The phone's Widevine for diagnostics: security level and CDM version, as MediaDrm reports them. */
object WidevineInfo {
    val UUID: java.util.UUID = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    /** "L1 18.0.0@343213000", "L3 16.1.0"... or "unavailable: <why>". Read once. */
    val summary: String by lazy {
        runCatching {
            val drm = android.media.MediaDrm(UUID)
            try { "${drm.getPropertyString("securityLevel")} ${drm.getPropertyString("version")}" }
            finally { drm.close() }
        }.getOrElse { "unavailable: ${it.message ?: it.javaClass.simpleName}" }
    }
}
