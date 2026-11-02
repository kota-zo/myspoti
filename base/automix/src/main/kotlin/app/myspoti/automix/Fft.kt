/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex FFT of size [n] (a power of two), tables built once. */
internal class Fft(val n: Int) {
    init { require(n >= 2 && n and (n - 1) == 0) { "FFT size $n" } }

    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }

    /** Magnitudes of bins 0..n/2 of a real frame (Hann-windowed by the caller or not). */
    fun magnitudes(frame: DoubleArray, out: DoubleArray, re: DoubleArray = DoubleArray(n), im: DoubleArray = DoubleArray(n)) {
        System.arraycopy(frame, 0, re, 0, n)
        java.util.Arrays.fill(im, 0.0)
        transform(re, im)
        for (k in 0..n / 2) out[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k])
    }
}

internal fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
