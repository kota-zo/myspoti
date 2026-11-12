package app.myspoti.power

/** Playback transport and listening events are independent of this visual policy. */
data class EnergyPolicy(
    val saverEnabled: Boolean = false,
    val oledBlack: Boolean = false,
    val allowCanvas: Boolean = true,
    val videoMaxHeight: Int = 1080,
    val allowBackgroundLookups: Boolean = true,
    val progressFps: Int = 30,
    /** The word-by-word sweep of synced lyrics: 60 frames a second (the screen ran it at 120), 30 with the saver. */
    val lyricsFps: Int = 60,
    /** 0 = no preference: the LTPO screen already drops its rate when nothing moves. */
    val displayRefreshCap: Float = 0f,
) {
    companion object {
        /**
         * Only the app's own switch turns the saver on: Android's battery saver is not
         * followed (it already lowers the screen's refresh rate by itself).
         */
        fun resolve(manualSaver: Boolean, blackPreference: Boolean, canvasEnabled: Boolean = true): EnergyPolicy {
            val saver = manualSaver
            return EnergyPolicy(
                saverEnabled = saver,
                oledBlack = blackPreference,
                allowCanvas = !saver && canvasEnabled,
                videoMaxHeight = if (saver) 480 else 1080,
                allowBackgroundLookups = !saver,
                progressFps = if (saver) 20 else 30,
                lyricsFps = if (saver) 30 else 60,
                displayRefreshCap = if (saver) 60f else 0f,
            )
        }
    }
}

data class EnergyState(
    val manualSaver: Boolean = false,
    /** AMOLED appearance stays user-controlled while saver limits visual work. */
    val blackPreference: Boolean = false,
    /** The user's Canvas switch (Settings), independent of the saver. */
    val canvasEnabled: Boolean = true,
) {
    val policy: EnergyPolicy get() = EnergyPolicy.resolve(manualSaver, blackPreference, canvasEnabled)
}
