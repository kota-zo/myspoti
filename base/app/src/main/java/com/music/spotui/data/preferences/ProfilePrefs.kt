/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.preferences

import android.content.SharedPreferences

/**
 * Settings that belong to the Spotify account in use (the accounts kept on the phone, each its
 * WebView profile): the first account keeps the keys it always had, another one reads and writes
 * `key@profile`, and goes on with the first account's value until it sets its own. Asked: the
 * quality and the extensions' settings follow the account, not the phone.
 */
object ProfilePrefs {
    /** The active account's profile; the first one before the engine has started. */
    fun profile(): String = runCatching {
        val runtime = app.myspoti.host.android.MyspotiRuntime
        if (runtime.isStarted) runtime.host.accounts.list.value.active else null
    }.getOrNull() ?: app.myspoti.host.android.AccountList.DEFAULT_PROFILE

    val isDefault: Boolean get() = profile() == app.myspoti.host.android.AccountList.DEFAULT_PROFILE

    /** [base] as the active account stores it. */
    fun key(base: String): String = profile().let { if (it == app.myspoti.host.android.AccountList.DEFAULT_PROFILE) base else "$base@$it" }

    fun getString(p: SharedPreferences, base: String, default: String?): String? {
        val k = key(base)
        return if (k != base && p.contains(k)) p.getString(k, default) else p.getString(base, default)
    }

    fun getBoolean(p: SharedPreferences, base: String, default: Boolean): Boolean {
        val k = key(base)
        return if (k != base && p.contains(k)) p.getBoolean(k, default) else p.getBoolean(base, default)
    }
}
