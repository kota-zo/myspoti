/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.exp
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

    /**
     * A logistic model over the bar measurements, each but the first against the song's own median.
     * Fitted on bars labelled from timed lyrics over a corpus of mostly rap and pop, and checked on
     * songs left out (ranking 0.86, against 0.71 for [estimate] alone). The result is scaled so that
     * [VOICE_LINE], where the policy calls a bar sung, sits where the model is right about 94 % of the
     * time and still finds about 90 % of the sung bars.
     */
    fun learned(risk: FloatArray, level: FloatArray, low: FloatArray, high: FloatArray, centre: FloatArray, novelty: FloatArray): FloatArray {
        val medLevel = median(level); val medLow = median(low); val medHigh = median(high); val medCentre = median(centre); val medNovelty = median(novelty)
        return FloatArray(risk.size) { i ->
            val x = W_RISK * risk[i] + W_LEVEL * (level[i] - medLevel) + W_LOW * (low.at(i) - medLow) + W_HIGH * (high.at(i) - medHigh) +
                W_CENTRE * (centre.at(i) - medCentre) + W_NOVELTY * (novelty.at(i) - medNovelty) + W_ABSOLUTE * level[i] + W_BIAS
            val p = 1.0 / (1.0 + exp(-x))
            (if (p < P_AT_LINE) p / P_AT_LINE * VOICE_LINE else VOICE_LINE + (p - P_AT_LINE) / (1 - P_AT_LINE) * (1 - VOICE_LINE)).toFloat()
        }
    }

    private fun FloatArray.at(i: Int) = getOrElse(i) { 0f }
    private fun median(x: FloatArray): Float = if (x.isEmpty()) 0f else x.sortedArray()[x.size / 2]

    private const val VOICE_LINE = 0.6
    private const val P_AT_LINE = 0.8
    private const val W_RISK = 1.92184
    private const val W_LEVEL = -0.10513
    private const val W_LOW = -0.01616
    private const val W_HIGH = 0.10098
    private const val W_CENTRE = 0.07179
    private const val W_NOVELTY = -0.09322
    private const val W_ABSOLUTE = 0.20900
    private const val W_BIAS = 2.55669
}
