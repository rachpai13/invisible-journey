package com.invisiblejourney.domain.processor

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** Snap a coordinate to a grid (~[gridM] metres) so the exact location isn't revealed. */
    fun coarsen(lat: Double, lng: Double, gridM: Double = 100.0): Pair<Double, Double> {
        val dLat = gridM / 111_320.0
        val dLng = gridM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return Pair(Math.round(lat / dLat) * dLat, Math.round(lng / dLng) * dLng)
    }
}
