package com.invisiblejourney.domain.processor

import kotlin.math.roundToInt

/**
 * Indian National AQI (CPCB) from PM2.5 and PM10 (µg/m³).
 * CPCB breakpoints are defined on 24 h averages; we apply them to current values, so treat it as an approximation.
 */
object IndianAqi {
    private class Bp(val cLo: Float, val cHi: Float, val iLo: Int, val iHi: Int)

    private val PM25 = listOf(
        Bp(0f, 30f, 0, 50), Bp(30f, 60f, 50, 100), Bp(60f, 90f, 100, 200),
        Bp(90f, 120f, 200, 300), Bp(120f, 250f, 300, 400), Bp(250f, 380f, 400, 500),
    )
    private val PM10 = listOf(
        Bp(0f, 50f, 0, 50), Bp(50f, 100f, 50, 100), Bp(100f, 250f, 100, 200),
        Bp(250f, 350f, 200, 300), Bp(350f, 430f, 300, 400), Bp(430f, 510f, 400, 500),
    )

    private fun subIndex(c: Float, table: List<Bp>): Int {
        if (c <= 0f) return 0
        val bp = table.firstOrNull { c <= it.cHi } ?: return 500
        return (bp.iLo + (c - bp.cLo) * (bp.iHi - bp.iLo) / (bp.cHi - bp.cLo)).roundToInt()
    }

    fun compute(pm25: Float?, pm10: Float?): Int? {
        if (pm25 == null && pm10 == null) return null
        return maxOf(pm25?.let { subIndex(it, PM25) } ?: 0, pm10?.let { subIndex(it, PM10) } ?: 0)
    }

    fun category(aqi: Int): String = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Satisfactory"
        aqi <= 200 -> "Moderate"
        aqi <= 300 -> "Poor"
        aqi <= 400 -> "Very poor"
        else -> "Severe"
    }
}
