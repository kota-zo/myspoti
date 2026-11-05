/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import android.media.audiofx.DynamicsProcessing
import app.myspoti.automix.Features

/**
 * A player's three-band EQ for the mixes (low under 180 Hz, mid, high over 2.5 kHz), as Android's
 * DynamicsProcessing on its audio session: it acts on what is heard now, not after the player's
 * buffer (an AudioProcessor would be late by the whole buffer). Neutral (0 dB) outside a mix. Null
 * when the phone refuses the effect: the mixes then use the gains alone.
 */
class DeckEq private constructor(private val dp: DynamicsProcessing) {
    private var low = 0f; private var high = 0f

    /** Band levels in dB (0 = untouched, down to −30). */
    fun set(lowDb: Double, highDb: Double) {
        val l = lowDb.toFloat().coerceIn(-40f, 0f); val h = highDb.toFloat().coerceIn(-40f, 0f)
        if (kotlin.math.abs(l - low) < 0.25f && kotlin.math.abs(h - high) < 0.25f) return
        runCatching {
            if (kotlin.math.abs(l - low) >= 0.25f) dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, Features.LOW_HZ.toFloat(), l))
            if (kotlin.math.abs(h - high) >= 0.25f) dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 20_000f, h))
            low = l; high = h
        }
    }

    fun neutral() = set(0.0, 0.0)

    fun release() { runCatching { dp.release() } }

    companion object {
        fun attach(sessionId: Int): DeckEq? {
            if (sessionId <= 0) return null
            return try {
                val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2,
                    true, 3, false, 0, false, 0, false)
                    .setPreferredFrameDuration(10f)
                    .build()
                val eq = DynamicsProcessing.Eq(true, true, 3)
                eq.setBand(0, DynamicsProcessing.EqBand(true, Features.LOW_HZ.toFloat(), 0f))
                eq.setBand(1, DynamicsProcessing.EqBand(true, Features.HIGH_HZ.toFloat(), 0f))
                eq.setBand(2, DynamicsProcessing.EqBand(true, 20_000f, 0f))
                cfg.setPreEqAllChannelsTo(eq)
                val dp = DynamicsProcessing(0, sessionId, cfg)
                dp.enabled = true
                DeckEq(dp)
            } catch (e: Throwable) {
                AutoMix.journal("EQ unavailable on session $sessionId: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }
}
