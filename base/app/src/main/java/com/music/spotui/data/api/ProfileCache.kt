package com.music.spotui.data.api

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.api.best
import app.myspoti.host.android.MyspotiRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** The signed-in profile (name, avatar, user id), fetched once per session. Compose state. */
object ProfileCache {
    var name by mutableStateOf<String?>(null)
        private set
    var imageUrl by mutableStateOf<String?>(null)
        private set
    /** Spotify user id (owner of the playlists the user can edit). */
    @Volatile var id: String? = null
        private set

    private val fetching = AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicLong()

    fun clearSession() {
        generation.incrementAndGet()
        name = null
        imageUrl = null
        id = null
    }

    fun ensure(context: Context) {
        if (name != null) return
        if (!fetching.compareAndSet(false, true)) return
        val requestGeneration = generation.get()
        MyspotiRuntime.scope.launch {
            try {
                MyspotiRuntime.api.ready.first { it }
                val me = runCatching { MyspotiRuntime.api.users.me() }.getOrNull() ?: return@launch
                if (generation.get() != requestGeneration) return@launch
                name = me.name
                imageUrl = me.images.best(160)?.url
                id = me.username.takeIf { it.isNotBlank() }
                // The downloads shown are this account's own (see OfflineAccounts).
                id?.let { com.music.spotui.data.offline.OfflineAccounts.claim(context.applicationContext, it) }
            } finally {
                fetching.set(false)
            }
        }
    }
}
