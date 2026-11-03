/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.max

/**
 * A conservative cue-selection hint from existing spectral measurements, not source separation.
 * Absolute centre energy matters too: a voice continuing through most of a song has no excess
 * above its own median. Mono has no useful stereo centre, so it retains uncertainty.
 */
internal object VocalRisk {
    fun estimate(level: FloatArray, midBand: FloatArray, centre: FloatArray, relative: FloatArray, stereo: Boolean): FloatArray =
        FloatArray(level.size) { i ->
            val active = ((level[i] + 60f) / 25f).coerceIn(0f, 1f)
            val prominence = ((midBand.getOrElse(i) { level[i] } - level[i] + 12f) / 10f).coerceIn(0f, 1f)
            val risk = if (stereo) {
                val absolute = ((centre.getOrElse(i) { 0f } + 1f) / 11f).coerceIn(0f, 1f)
                val excess = ((relative.getOrElse(i) { 0f } + 1f) / 7f).coerceIn(0f, 1f)
                max(absolute, excess) * prominence
            } else {
                // Do not declare a mono song voice-free or pretend to detect a voice from it.
                0.2f + prominence * 0.35f
            }
            (risk * active).coerceIn(0f, 1f)
        }
}
