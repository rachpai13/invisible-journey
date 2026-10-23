package com.invisiblejourney.domain.processor

import kotlin.math.sqrt

/**
 * Vertical (up/down) acceleration from a raw accelerometer, without a gyroscope.
 *
 * Gravity is tracked with a slow low-pass filter (time constant ≈ [tauMs]). Its direction is "down"
 * in the phone's own axes, whatever way the phone is held. The bump signal is the gravity-removed
 * acceleration projected onto that direction, so braking, accelerating and leaning into turns
 * (mostly sideways/forward) contribute much less than a pothole (mostly vertical).
 */
class VerticalAccel(private val tauMs: Float = 500f) {
    private val g = FloatArray(3)
    private var lastT = 0L
    private var initialised = false

    /** Raw accelerometer in m/s² (gravity included). Returns vertical linear acceleration in m/s² (+ = up). */
    fun update(t: Long, ax: Float, ay: Float, az: Float): Float {
        if (!initialised) {
            g[0] = ax; g[1] = ay; g[2] = az
            lastT = t
            initialised = true
            return 0f
        }
        val dt = (t - lastT).coerceIn(1, 100).toFloat()
        lastT = t
        val alpha = dt / (tauMs + dt)
        g[0] += alpha * (ax - g[0])
        g[1] += alpha * (ay - g[1])
        g[2] += alpha * (az - g[2])

        val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (norm < 1f) return 0f // free fall / bad data
        val lx = ax - g[0]
        val ly = ay - g[1]
        val lz = az - g[2]
        // The accelerometer reads +g "up" when at rest, so projecting onto g gives + for upward jolts.
        return (lx * g[0] + ly * g[1] + lz * g[2]) / norm
    }
}
