package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.LibraryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collaborative-playlist invitations: `open.spotify.com/playlist/{id}?si=…&pt={token}`, as the
 * official apps share them (or a spotify.link short link that redirects to it). `pt` is the
 * permission-grant token; claiming it makes the account a collaborator. The link is kept here
 * until the user taps "Join" on the playlist page (see [join]). Blend invitations
 * (`/blend/taste-match/{id}`) are kept as [blend] and answered from a sheet.
 */
object InviteLinks {
    private const val TAG = "InviteLinks"

    /** A new object for every link opened, so the same link opened twice navigates twice. */
    class Invite(val playlistId: String, val token: String) {
        val uri get() = "spotify:playlist:$playlistId"
        /** The playlist page has been opened on it (not again when the screen is rebuilt). */
        @Volatile var opened = false
    }

    /** The invitation waiting for the user's answer; cleared once joined. */
    val pending = MutableStateFlow<Invite?>(null)

    /** A Blend invitation (`open.spotify.com/blend/taste-match/{id}`), a new object per link opened. */
    class BlendLink(val id: String)

    /** The Blend invitation opened, shown as a sheet over the app until answered (see BlendInviteSheet). */
    val blend = MutableStateFlow<BlendLink?>(null)

    /** A shared playlist / album / artist / podcast link: its page, opened by App.kt (not played). */
    val page = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { app.myspoti.host.android.TlsCompat.apply(okhttp3.OkHttpClient.Builder()).followRedirects(true).build() }

    private val PLAYLIST = Regex("""(?:open|www|play)\.spotify\.com/(?:intl-[a-z-]+/)?playlist/([A-Za-z0-9]{22})([^\s"'<>]*)""")
    private val TOKEN = Regex("""[?&]pt=([A-Za-z0-9_-]+)""")
    private val BLEND = Regex("""spotify\.com/(?:intl-[a-z-]+/)?blend/(?:taste-match|invitation)/([A-Za-z0-9]+)""")
    private val SHORT = Regex("""https?://(?:spotify\.link|spoti\.fi)/[^\s"'<>]+""")

    /** The invitation inside [link] (a playlist link carrying `pt`), or null. */
    fun parse(link: String): Invite? {
        val m = PLAYLIST.find(link.replace("&amp;", "&").replace("\\u0026", "&")) ?: return null
        val token = TOKEN.find(m.groupValues[2])?.groupValues?.get(1) ?: return null
        return Invite(m.groupValues[1], token)
    }

    /**
     * An incoming link. True when it is taken here: a playlist invitation (kept as [pending], the
     * playlist page opens on it) or a short link (resolved on a background thread, then handled as
     * the link it leads to; one that is not an invitation plays like any Spotify link).
     */
    fun handle(link: String): Boolean {
        parse(link)?.let { pending.value = it; return true }
        BLEND.find(link)?.let { blend.value = BlendLink(it.groupValues[1]); return true }
        val short = SHORT.find(link)?.value ?: return false
        scope.launch {
            val target = resolve(short) ?: return@launch
            val invite = parse(target)
            if (invite != null) { pending.value = invite; return@launch }
            BLEND.find(target)?.let { blend.value = BlendLink(it.groupValues[1]); return@launch }
            // Not an invitation: the item the short link leads to is played, as a direct link is.
            val m = Regex("""spotify\.com/(?:intl-[a-z-]+/)?(track|album|playlist|artist|show|episode)/([A-Za-z0-9]{22})""").find(target) ?: return@launch
            runCatching {
                MyspotiRuntime.api.ready.first { it }
                MyspotiRuntime.api.player.play("spotify:${m.groupValues[1]}:${m.groupValues[2]}")
            }
        }
        return true
    }

    /** Where a short link leads: its final URL, or the playlist link inside the page it answers with. */
    private suspend fun resolve(link: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(okhttp3.Request.Builder().url(link).header("user-agent", "Mozilla/5.0").build()).execute().use { r ->
                val final = r.request.url.toString()
                if (PLAYLIST.containsMatchIn(final) && TOKEN.containsMatchIn(final) || BLEND.containsMatchIn(final)) final
                else {
                    val body = r.body.string().replace("\\/", "/")
                    PLAYLIST.find(body.replace("&amp;", "&").replace("\\u0026", "&"))?.value?.let { "https://$it" } ?: final
                }
            }
        }.onFailure { Log.w(TAG, "short link not resolved: ${it.message}") }.getOrNull()
    }

    /**
     * Joins the playlist of [invite] ([page] is that playlist's page, for the library row shown at
     * once): claims the token, follows the playlist when needed, and has the library read again.
     * A refused token (wrong or expired link) undoes the row and says so.
     */
    fun join(context: Context, invite: Invite, page: ContextPageModel, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        val edit = LibraryEdits.Add(null, LibraryEntry(
            spotifyId = invite.playlistId, name = page.name,
            subtitle = app.getString(R.string.assisted_curation_search_playlist) + page.ownerName.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty(),
            coverUri = page.coverUri, isPlaylist = true, uri = invite.uri,
            addedAt = java.time.Instant.now().toString(),
        ))
        LibraryEdits.run(app, edit, write = {
            MyspotiRuntime.api.playlists.join(invite.uri, invite.token)
            Api.HomeCache.libraryRoot = null
            Api.HomeCache.folders.clear()
        }, onDone = { ok ->
            if (ok && pending.value === invite) pending.value = null
            onDone(ok)
        })
    }
}
