// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.host.android

import android.content.Context

/**
 * The name this phone shows in Spotify's device lists: the one its owner gave it (Paramètres → À
 * propos → Nom de l'appareil), the same string Bluetooth and Quick Share show, which Spotify's own
 * app on the phone takes too. Falls back to the model, then to a neutral name.
 */
object PhoneName {
    @Volatile private var known: String? = null

    fun of(context: Context): String = known ?: run {
        val resolver = context.applicationContext.contentResolver
        fun setting(key: String): String? = runCatching {
            android.provider.Settings.Global.getString(resolver, key)
                ?: android.provider.Settings.Secure.getString(resolver, key)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        (setting("device_name") ?: setting("bluetooth_name") ?: android.os.Build.MODEL.ifBlank { "Spotify" }).also { known = it }
    }
}
