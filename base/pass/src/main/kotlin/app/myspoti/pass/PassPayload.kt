package app.myspoti.pass

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one device passes to another. Each part is optional: a receiver takes what it knows how
 * to use (a computer has no Android settings) and leaves the rest.
 */
@Serializable
data class PassPayload(
    val version: Int = 1,
    /** The sending device's name, shown on the receiver ("From Pixel 8"). */
    val from: String = "",
    /** The app's settings: preference file → key → value. */
    val prefs: Map<String, Map<String, PassPref>> = emptyMap(),
    val extensions: List<PassExtension> = emptyList(),
    /** The extensions' whole folder as the Android app keeps it (path → base64), for another Android device. */
    val extensionFiles: Map<String, String> = emptyMap(),
    /** The account's web session: its cookies on spotify.com. */
    val cookies: List<PassCookie> = emptyList(),
) {
    fun toBytes(): ByteArray = JSON.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun of(bytes: ByteArray): PassPayload = JSON.decodeFromString(serializer(), bytes.decodeToString())
    }
}

/** One preference: its type (`b`, `i`, `l`, `f`, `s`, `ss` for a set) and its value as text. */
@Serializable
data class PassPref(val t: String, val v: String = "", val set: List<String> = emptyList())

/**
 * An installed extension: its package (base64 zip), what the user granted, on or off, its own
 * store and its settings (each a JSON object of key → JSON text, as the Android app keeps them).
 */
@Serializable
data class PassExtension(
    val id: String, val packageZip: String, val granted: List<String> = emptyList(), val enabled: Boolean = true,
    val storage: String? = null, val settings: String? = null,
)

@Serializable
data class PassCookie(val name: String, val value: String)
