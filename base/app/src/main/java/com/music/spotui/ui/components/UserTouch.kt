package com.music.spotui.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When the user last touched the app's window (uptime ms), from MainActivity's touch dispatch. */
object UserTouch {
    private val _lastAt = MutableStateFlow(android.os.SystemClock.uptimeMillis())
    val lastAt: StateFlow<Long> = _lastAt.asStateFlow()

    /** A finger went down on the window. */
    fun touched() { _lastAt.value = android.os.SystemClock.uptimeMillis() }
}
