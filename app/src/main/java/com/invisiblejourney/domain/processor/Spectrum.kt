package com.invisiblejourney.domain.processor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Frequency shape of a short vibration clip, by a direct DFT (clips are ~200 samples, so this is cheap). */
object Spectrum {
    /**
     * Returns (centroid Hz, share of energy in 0.5–4 Hz) for a clip of signed samples.
     * Mean removed, Hann-windowed, frequencies 0.5 Hz up to Nyquist in 0.5 Hz steps.
     */
    fun shape(times: List<Long>, values: List<Float>): Pair<Float, Float> {
        val n = values.size
        if (n < 16) return 0f to 0f
        val dtS = (times.last() - times.first()) / 1000.0 / (n - 1)
        if (dtS <= 0) return 0f to 0f
        val mean = values.average()
        val x = DoubleArray(n) { i -> (values[i] - mean) * (0.5 - 0.5 * cos(2 * PI * i / (n - 1))) }
        val nyquist = 0.5 / dtS
        var total = 0.0
        var low = 0.0
        var weighted = 0.0
        var f = 0.5
        while (f <= nyquist) {
            var re = 0.0
            var im = 0.0
            val w = 2 * PI * f * dtS
            for (i in 0 until n) {
                re += x[i] * cos(w * i)
                im -= x[i] * sin(w * i)
            }
            val p = re * re + im * im
            total += p
            weighted += p * f
            if (f < 4.0) low += p
            f += 0.5
        }
        if (total <= 0) return 0f to 0f
        return (weighted / total).toFloat() to (low / total).toFloat()
    }
}
