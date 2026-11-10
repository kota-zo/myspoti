/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.annotation.RequiresApi
import app.myspoti.automix.Features

/**
 * A player's three-band EQ for the mixes (low under 180 Hz, mid, high over 2.5 kHz), as Android's
 * DynamicsProcessing on its audio session (API 28+): it acts on what is heard now, not after the
 * player's buffer (an AudioProcessor would be late by the whole buffer). Neutral (0 dB) outside a
 * mix. Null when unsupported (< API 28) or refused by the phone: the mixes then use gains alone.
 */
class DeckEq private constructor(private val impl: Handle) {
    @RequiresApi(Build.VERSION_CODES.P)
    private class Handle(private val dp: DynamicsProcessing) {
        private var low = 0f; private var high = 0f

        fun set(lowDb: Double, highDb: Double) {
            val l = lowDb.toFloat().coerceIn(-40f, 0f); val h = highDb.toFloat().coerceIn(-40f, 0f)
            if (kotlin.math.abs(l - low) < 0.25f && kotlin.math.abs(h - high) < 0.25f) return
            runCatching {
                if (kotlin.math.abs(l - low) >= 0.25f) dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, Features.LOW_HZ.toFloat(), l))
                if (kotlin.math.abs(h - high) >= 0.25f) dp.setPreEqBandAllChannelsTo(2, DynamicsProcessing.EqBand(true, 20_000f, h))
                low = l; high = h
            }
        }

        fun release() { runCatching { dp.release() } }
    }

    /** Band levels in dB (0 = untouched, down to −30). */
    fun set(lowDb: Double, highDb: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) impl.set(lowDb, highDb)
    }

    fun neutral() = set(0.0, 0.0)

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) impl.release()
    }

    companion object {
        fun attach(sessionId: Int): DeckEq? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || sessionId <= 0) return null
            return attachApi28(sessionId)
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun attachApi28(sessionId: Int): DeckEq? {
            return try {
                // The only variant Android creates: the other was refused on every session, so no mix ever had its EQ.
                val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
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
                DeckEq(Handle(dp))
            } catch (e: Throwable) {
                AutoMix.journal("EQ unavailable on session $sessionId: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }
}
