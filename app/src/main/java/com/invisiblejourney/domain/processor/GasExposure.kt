package com.invisiblejourney.domain.processor

import kotlin.math.pow

/**
 * MQ-2 gas sensor on the ESP32 module → how much exhaust and smoke the rider is riding through.
 *
 * The ESP32 sends Rs/R0: the sensor's resistance now over its baseline R0. R0 is defined the way
 * the datasheet does (Rs at 1000 ppm LPG), so clean air reads Rs/R0 ≈ [CLEAN_AIR_RATIO]; exhaust,
 * smoke and fuel vapour lower Rs and so lower the ratio.
 *
 * The MQ-2 has one output and cannot tell gases apart, so this is an **indicator, not a
 * measurement**: we read the ratio on the datasheet's smoke curve, ppm = a · (Rs/R0)^b, and keep
 * only what is above clean air. Exposure is that excess integrated over time (ppm·min), the same
 * way AQI-minutes are counted for the city-grid air quality.
 */
object Mq2 {
    /** Datasheet Rs/R0 in clean air. */
    const val CLEAN_AIR_RATIO = 9.83f

    // Smoke curve, same constants as the MQ2_Browser test sketch.
    private const val SMOKE_A = 1200f
    private const val SMOKE_B = -1.9f

    /** Smoke-equivalent ppm the curve gives for clean air (~15.6 ppm); treated as zero pollution. */
    val cleanAirPpm: Float = SMOKE_A * CLEAN_AIR_RATIO.pow(SMOKE_B)

    /** Smoke-equivalent ppm for a ratio, or null for a reading that can't be real. */
    fun smokePpm(ratio: Float): Float? {
        if (!ratio.isFinite() || ratio <= 0.01f) return null
        return (SMOKE_A * ratio.pow(SMOKE_B)).coerceAtMost(MAX_PPM)
    }

    /** ppm above clean air, never negative (clean air or cleaner reads 0). */
    fun excessPpm(ratio: Float): Float? = smokePpm(ratio)?.let { (it - cleanAirPpm).coerceAtLeast(0f) }

    /** Plain-word band for the excess. The cut-offs are for display only; the MQ-2 has no official scale. */
    fun level(excessPpm: Float): String = when {
        excessPpm < 25f -> "Clean"
        excessPpm < 100f -> "Light"
        excessPpm < 300f -> "Moderate"
        excessPpm < 1000f -> "Heavy"
        else -> "Very heavy"
    }

    private const val MAX_PPM = 10_000f // top of the datasheet curves
}
