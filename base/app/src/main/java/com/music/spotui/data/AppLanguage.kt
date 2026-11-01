package com.music.spotui.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** The app's own language (per-app locale), independent of the phone's. */
object AppLanguage {
    /** Follow the phone. */
    const val SYSTEM = ""

    /** Languages the app has strings for, as BCP 47 tags, after "automatic". */
    val AVAILABLE: List<String> = listOf(SYSTEM) + listOf(
        "af", "am", "ar", "az", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "en-GB", "es", "es-AR", "es-MX",
        "et", "eu", "fa", "fi", "fr", "fr-CA", "gl", "gu", "hi", "hr", "hu", "id", "is", "it", "he", "ja", "kn", "ko",
        "lt", "lv", "mk", "ml", "mr", "ms", "nb", "ne", "nl", "or", "pa", "pl", "pt", "pt-BR", "pt-PT", "ro", "ru", "sk", "sl",
        "sr", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh", "zh-HK", "zh-TW", "zu",
    ).sortedBy { nativeName(it).lowercase() }

    fun current(context: Context): String =
        AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.toLanguageTag() ?: SYSTEM

    fun apply(context: Context, tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** The language's name in itself ("Français", "Deutsch"…). */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
