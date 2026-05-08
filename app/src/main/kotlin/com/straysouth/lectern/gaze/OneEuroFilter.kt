package com.straysouth.lectern.gaze

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1€ filter for gaze smoothing.
 *
 * Balances lag (low cutoff = smooth) and jitter (high cutoff = responsive)
 * by adapting the cutoff frequency based on the speed of change.
 * At 30Hz with default params, reduces jitter without noticeable lag
 * at typical reading-distance head distances (~30-40cm).
 *
 * Reference: Casiez et al., "1€ Filter: A Simple Speed-based Low-pass Filter
 * for Noisy Input in Interactive Systems", CHI 2012.
 */
class OneEuroFilter(
    private val freq: Double,
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.007,
    private val dCutoff: Double = 1.0,
) {
    private var xPrev: Double? = null
    private var dxPrev: Double = 0.0

    fun filter(x: Double): Double {
        val dx = if (xPrev == null) 0.0 else (x - requireNotNull(xPrev)) * freq
        val dxHat = lerp(dxPrev, dx, alpha(freq, dCutoff))
        val cutoff = minCutoff + beta * abs(dxHat)
        val xHat = lerp(xPrev ?: x, x, alpha(freq, cutoff))
        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }

    fun reset() {
        xPrev = null
        dxPrev = 0.0
    }

    private fun alpha(f: Double, c: Double): Double {
        val r = 2.0 * PI * c / f
        return r / (r + 1.0)
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + t * (b - a)
}
