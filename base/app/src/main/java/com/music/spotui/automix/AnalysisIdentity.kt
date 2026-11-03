/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

/** Cache the recording, not the expiring access token or just its catalogue track id. */
internal object AnalysisIdentity {
    fun stream(track: String, provider: String?, durationMs: Long?, codec: String?, source: String?, url: String, manifest: String?): String =
        "$track|$provider|$durationMs|$codec|$source|${manifest?.let(::manifestIdentity) ?: stableAddress(url)}"

    fun file(uri: String, size: Long, modifiedAt: Long): String = "$uri|$size|$modifiedAt"

    private val signedParameter = Regex("([?&](?:amp;)?(?:token|sig|signature|expires|policy|key-pair-id|hdnts|X-Amz-[^=\\s]+)=)[^&\\s\"<>]*", RegexOption.IGNORE_CASE)

    private fun stableAddress(value: String): String = signedParameter.replace(value) { it.groupValues[1] }

    /** Read once on the worker, never for every player tick. Preserve media paths and non-auth parameters. */
    private fun manifestIdentity(encoded: String): String = runCatching {
        stableAddress(String(java.util.Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8))
    }.getOrElse { stableAddress(encoded) }
}
