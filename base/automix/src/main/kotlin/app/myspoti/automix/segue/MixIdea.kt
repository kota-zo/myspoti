/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

/**
 * Ideas tried on top of the Orpheon planner, each on or off on its own so that one can be heard
 * against the planner without it. With none of them the planner places and shapes its transitions
 * as before; none changes what the deck can play.
 */
enum class MixIdea(val wire: String, val title: String, val detail: String) {
    /** The incoming song's low end is kept out until the outgoing one's leaves: one bass line at any time. */
    BASS_HANDOVER("bass-handover", "Bass handover", "The next song's bass waits until the current one's is gone"),
    /** A lead vocal starting over another is avoided much harder, and where it cannot be the outgoing song's middle is carved under it. */
    VOCAL_CLEAR("vocal-clear", "Clear vocals", "Keeps two lead voices apart, and makes room where they meet"),
    /** The outgoing song's last beat is thrown into an echo instead of being cut dry. */
    ECHO_TAIL("echo-tail", "Echo tail", "The current song leaves on an echo of its last beat"),
    /** Transitions that are not beat-locked still land the incoming song's first downbeat on the outgoing song's pulse. */
    PHRASED_FADE("phrased-fade", "Fades on the beat", "The next song's first downbeat lands on the current song's pulse"),
    /** The style follows what the songs do: a quiet opening is blended into, a loud one is arrived on. */
    ENERGY_STYLE("energy-style", "Style by energy", "Long blends into quiet openings, short arrivals on loud ones"),
    /** Neither song is sped up or slowed down by much: a pair further apart is not locked. */
    GENTLE_TEMPO("gentle-tempo", "Gentle tempo", "No large speed change: distant tempos are not beat-matched");

    val bit: Int get() = 1 shl ordinal

    companion object {
        /** What the Mix Lab starts with: none, the planner alone was preferred by ear (build 3127, 5 pairs to 1). Each is tried on its own. */
        val DEFAULT: Int = 0

        fun mask(ideas: Set<MixIdea>): Int = ideas.fold(0) { m, i -> m or i.bit }
        fun of(mask: Int): Set<MixIdea> = values().filter { mask and it.bit != 0 }.toSet()
        fun parse(wire: String): MixIdea? = values().firstOrNull { it.wire == wire }
    }
}
