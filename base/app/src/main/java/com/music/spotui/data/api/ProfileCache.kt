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

    /**
     * The account is no longer known: at sign-out, and when the session is lost without one
     * (SessionActions.sessionLost), when another account may sign in next. A profile still being
     * read is dropped, and read again for the session that follows.
     */
    fun clearSession() {
        generation.incrementAndGet()
        name = null
        imageUrl = null
        id = null
    }

    fun ensure(context: Context) {
        if (name != null) return
        if (!fetching.compareAndSet(false, true)) return
        MyspotiRuntime.scope.launch {
            try {
                while (true) {
                    val requestGeneration = generation.get()
                    MyspotiRuntime.api.ready.first { it }
                    val me = runCatching { MyspotiRuntime.api.users.me() }.getOrNull()
                    // The session was lost meanwhile: this profile may be the account before, the next one's is read.
                    if (generation.get() != requestGeneration) continue
                    // No account (an anonymous token): nothing is kept, the next session's is read.
                    if (me == null || me.username.isBlank()) return@launch
                    name = me.name
                    imageUrl = me.images.best(160)?.url
                    id = me.username
                    // The downloads shown are this account's own (see OfflineAccounts).
                    id?.let { com.music.spotui.data.offline.OfflineAccounts.claim(context.applicationContext, it) }
                    return@launch
                }
            } finally {
                fetching.set(false)
            }
        }
    }

    /**
     * The signed-in account's id, read first when it is not known yet ([ensure]): waited for at most
     * [timeoutMs] (offline, Spotify not answering), null then.
     */
    suspend fun awaitId(context: Context, timeoutMs: Long): String? {
        id?.let { return it }
        ensure(context)
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (id == null) kotlinx.coroutines.delay(100)
            id
        }
    }
}
