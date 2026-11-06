/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.di

/**
 * Music through a call. The web player's audio holds no audio focus, so a call only muted it: the
 * song went on unheard (reported). It pauses when the call comes and plays again after, unless the
 * user touched the player meanwhile or the call lasted so long that music coming back would surprise.
 */
internal class CallPause(private val longestMs: Long = 30 * 60_000L) {
    enum class Action { PAUSE, RESUME, NONE }

    private var heldAt: Long? = null

    fun modeChanged(busy: Boolean, playing: Boolean, nowMs: Long): Action {
        val held = heldAt
        return when {
            busy && held == null && playing -> { heldAt = nowMs; Action.PAUSE }
            busy -> Action.NONE
            held == null -> Action.NONE
            else -> { heldAt = null; if (nowMs - held <= longestMs) Action.RESUME else Action.NONE }
        }
    }

    fun userActed() { heldAt = null }
}
