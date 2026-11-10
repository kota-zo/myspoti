package com.music.spotui.di

/**
 * What `ACTION_AUDIO_BECOMING_NOISY` means for this device, without Android ([NoisyAudio]).
 *
 * The broadcast is sent when an audio output goes away and the sound is about to move to
 * another one. Every music app pauses there: the case it is for is headphones pulled out of a
 * phone, where the music would otherwise blare out of its speaker.
 *
 * A TV is the case it is *not* for. Its sound leaves over HDMI (or optical), and an HDMI output
 * is disconnected and reconnected for reasons the user never asked for: a frame rate or HDR
 * switch when a video starts, a receiver or soundbar waking up or re-negotiating, the TV
 * re-selecting its own speakers for a moment. Pausing there is the music stopping by itself in
 * the middle of a song — reported on a TV as "it plays a little and then stops".
 *
 * So a TV pauses only when a headset went away (the broadcast's real purpose), and never when
 * an HDMI or line output did: that is a link change, and the music goes on.
 */
object AudioRouteRules {
    /** An output that went away, as far as the meaning of the broadcast goes. */
    enum class Output {
        /** Wired headphones, a headset (wired, USB or wireless), earbuds. */
        HEADSET,

        /** HDMI out (a receiver, a soundbar, the TV itself), optical, line out, a dock: a link. */
        LINK,

        /** The device's own speakers or earpiece, or anything else. */
        OTHER,
    }

    /**
     * Whether the music pauses. [removed]: the outputs the system said just went away
     * (`emptySet()` when nothing said); [linkedNow]: an HDMI or line output is still there;
     * [tv]: the device is a TV.
     *
     * A phone keeps the old behaviour in every case: pausing is the safe answer there, and the
     * broadcast exists for it.
     */
    fun pauseWhenNoisy(removed: Set<Output>, linkedNow: Boolean, tv: Boolean): Boolean = when {
        // Headphones went: the sound is about to come out of the speakers. Pause, TV or not.
        Output.HEADSET in removed -> true
        // A phone, nothing known: pause, as it always did.
        !tv -> true
        // An HDMI or line output went on a TV: the link changed, the user asked for nothing.
        Output.LINK in removed -> false
        // The TV's sound is still on an HDMI or line output: the sound did not fall back.
        linkedNow -> false
        // A TV with nothing on a link and nothing known about what went: the sound moved to the
        // TV's own speaker, which a headset leaving its jack does — pause as before.
        else -> true
    }
}
