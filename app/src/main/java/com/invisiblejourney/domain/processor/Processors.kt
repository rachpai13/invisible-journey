package com.invisiblejourney.domain.processor

import com.invisiblejourney.domain.model.MountType
import com.invisiblejourney.domain.model.Roughness
import com.invisiblejourney.domain.model.TrackPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val G = 9.81f

/** Filters GPS jitter and sums distance. See docs/02_SENSORS.md §1–3. */
class DistanceProcessor(
    private val maxAccuracyM: Float = 25f,
    private val maxJumpKmh: Double = 80.0,
) {
    var totalM: Double = 0.0
        private set
    var last: TrackPoint? = null
        private set

    /** Returns true if the point is accepted into the trail. */
    fun accept(p: TrackPoint, countDistance: Boolean): Boolean {
        if (p.accuracyM > maxAccuracyM) return false
        val prev = last
        if (prev == null) {
            last = p
            return true
        }
        val dtS = (p.t - prev.t) / 1000.0
        if (dtS <= 0) return false
        val d = Geo.distanceM(prev.lat, prev.lng, p.lat, p.lng)
        if (d / dtS * 3.6 > maxJumpKmh) return false
        // Standing still: GPS wanders a few metres; don't count it as distance.
        if (p.speedKmh < 2f && d < 5.0) return false
        if (countDistance) totalM += d
        last = p
        return true
    }
}

/** Moving average over the last [window] speed readings. */
class SpeedSmoother(private val window: Int = 3) {
    private val values = ArrayDeque<Float>()

    fun add(kmh: Float): Float {
        values.addLast(kmh)
        while (values.size > window) values.removeFirst()
        return values.average().toFloat()
    }
}

/**
 * A stop = smoothed speed below [stopBelowKmh] for at least [minStopMs].
 * The stop ends when speed rises above [resumeAboveKmh] (hysteresis).
 */
class StopDetector(
    private val stopBelowKmh: Float = 3f,
    private val resumeAboveKmh: Float = 6f,
    private val minStopMs: Long = 20_000,
) {
    data class Stop(val start: Long, val end: Long, val lat: Double, val lng: Double)

    private var candidateStart: Long? = null
    private var candidateLat = 0.0
    private var candidateLng = 0.0

    /** Start time of the confirmed ongoing stop, or null. */
    fun ongoingStart(now: Long): Long? {
        val s = candidateStart ?: return null
        return if (now - s >= minStopMs) s else null
    }

    /** When the rider dropped below walking pace, even if it's too soon to count as a stop; null while moving. */
    fun stoppedSince(): Long? = candidateStart

    /** Returns a completed stop when the rider starts moving again. */
    fun update(t: Long, speedKmh: Float, lat: Double, lng: Double): Stop? {
        val start = candidateStart
        if (speedKmh < stopBelowKmh) {
            if (start == null) {
                candidateStart = t
                candidateLat = lat
                candidateLng = lng
            }
            return null
        }
        if (speedKmh > resumeAboveKmh && start != null) {
            candidateStart = null
            if (t - start >= minStopMs) return Stop(start, t, candidateLat, candidateLng)
        }
        return null
    }

    /** Ends any ongoing stop (e.g. the rider reached the store). */
    fun flush(t: Long): Stop? {
        val start = candidateStart ?: return null
        candidateStart = null
        return if (t - start >= minStopMs) Stop(start, t, candidateLat, candidateLng) else null
    }
}

/**
 * Finds road impacts (potholes, speed humps) in vertical acceleration. Tuned on a real car ride
 * (docs/02_SENSORS.md §5, tools/pothole_lab.py). Three rules, each from what the ride data showed:
 *
 * 1. **One event per bump.** A trigger opens an event that stays open until the signal has been
 *    quiet for [quietMs] (or [maxEventMs] passes), so one rough patch is one detection, not three.
 * 2. **Must stand out from the road.** A bump counts only if its peak is at least [floorG] *and*
 *    [minProminence] × the background roughness (RMS over the last few seconds). On a bumpy road
 *    the bar rises, so constant rattle doesn't fire over and over.
 * 3. **Must last like a road impact.** Spikes narrower than [minWidthMs] at half height are the
 *    phone being tapped, knocked or rattling in its holder, not the vehicle hitting something.
 *
 * Each bump also reports how its shaking is spread over frequencies (±1 s around the peak), which
 * [BumpClassifier] uses to tell speed breakers from potholes.
 */
class BumpDetector(
    mount: MountType,
    private val minSpeedKmh: Float = 5f,
    private val quietMs: Long = 800,
    private val maxEventMs: Long = 3_000,
    private val minWidthMs: Long = 25,
    private val backgroundTauMs: Float = 5_000f,
) {
    /** A finished bump event. [prominence] = peak ÷ background roughness. */
    data class Bump(
        val tStart: Long,
        val tPeak: Long,
        val tEnd: Long,
        val peakG: Float,
        val prominence: Float,
        val widthMs: Long,
        val minSpeedKmh: Float,
        /** Energy-weighted mean frequency of the shaking (Hz). */
        val centroidHz: Float,
        /** Share of the shaking energy between 0.5 and 4 Hz (0..1). */
        val lowFraction: Float,
    )

    /** Minimum peak (g). Car suspension soaks up most of an impact; a two-wheeler passes it straight on. */
    val floorG: Float = when (mount) {
        MountType.CAR -> 0.45f // tuned on the car test ride
        MountType.POCKET -> 0.8f // two-wheeler values are first guesses until a marked bike ride
        MountType.HANDLEBAR -> 1.2f
        MountType.BAG -> 0.7f
    }

    /** How many times rougher than the road around it a bump must be. Car value tuned on the test ride. */
    private val minProminence: Float = if (mount == MountType.CAR) 5f else 6f

    /** [v] is signed vertical acceleration in g; [g] its magnitude. */
    private class Sample(val t: Long, val v: Float) {
        val g: Float get() = abs(v)
    }

    private var bg2 = (0.05f * 0.05f) // background mean square, g²
    private var lastT = 0L
    private val pre = ArrayDeque<Sample>() // last 1 s before a trigger, for the width and frequency checks
    private var event: MutableList<Sample>? = null
    private var eventStart = 0L
    private var lastLoud = 0L
    private var eventMinSpeed = 0f
    private var bgAtStart = 0f

    /** Feed every sample. Returns a bump when one finishes (up to ~[maxEventMs] after it started). */
    fun onSample(t: Long, verticalMs2: Float, speedKmh: Float): Bump? {
        val vg = verticalMs2 / G
        val g = abs(vg)
        val dt = if (lastT == 0L) 10L else (t - lastT).coerceIn(1, 100)
        lastT = t
        val bg = sqrt(bg2)
        val ev = event
        // Background roughness follows the road continuously (a long rough stretch raises the bar).
        bg2 += dt / (backgroundTauMs + dt) * (g * g - bg2)

        if (ev == null) {
            if (g >= max(floorG, 4f * bg) && speedKmh >= minSpeedKmh) {
                event = (pre.toMutableList() + Sample(t, vg)).toMutableList()
                eventStart = t
                lastLoud = t
                eventMinSpeed = speedKmh
                bgAtStart = bg
                pre.clear()
                return null
            }
            pre.addLast(Sample(t, vg))
            while (pre.isNotEmpty() && t - pre.first().t > 1_000) pre.removeFirst()
            return null
        }

        ev += Sample(t, vg)
        eventMinSpeed = min(eventMinSpeed, speedKmh)
        if (g >= max(floorG * 0.5f, 2.5f * bgAtStart)) lastLoud = t
        if (t - lastLoud < quietMs && t - eventStart < maxEventMs) return null

        event = null
        val peak = ev.maxBy { it.g }
        val half = peak.g / 2
        val near = ev.filter { kotlin.math.abs(it.t - peak.t) <= 150 }
        val width = near.count { it.g >= half } * (if (near.size > 1) (near.last().t - near.first().t) / (near.size - 1) else 10L)
        val prominence = peak.g / max(bgAtStart, 0.03f)
        val accepted = peak.g >= floorG && prominence >= minProminence && width >= minWidthMs
        if (!accepted) return null
        val window = ev.filter { kotlin.math.abs(it.t - peak.t) <= 1_000 }
        val (centroid, low) = Spectrum.shape(window.map { it.t }, window.map { it.v })
        return Bump(eventStart, peak.t, t, peak.g, prominence, width, eventMinSpeed, centroid, low)
    }
}

/**
 * Speed breaker or pothole? From the labelled car ride (8 speed breakers on one road, 6 marked potholes):
 *
 * - A speed breaker is wide and smooth, so the vehicle **heaves slowly** over it: the shaking sits at
 *   2–3 Hz, with 80–93% of its energy under 4 Hz.
 * - A pothole has a hard edge, so the hit is **sharp**: 4–12 Hz, mostly above 4 Hz.
 * - Braking is *not* a useful clue: drivers brake for visible potholes too.
 *
 * Rule: mean frequency ≤ [MAX_HUMP_CENTROID_HZ] or ≥ [MIN_HUMP_LOW_FRACTION] of energy under 4 Hz,
 * and slow enough that it was taken carefully. 7/8 breakers and 6/6 potholes right on that ride.
 */
object BumpClassifier {
    const val MAX_HUMP_CENTROID_HZ = 3.5f
    const val MIN_HUMP_LOW_FRACTION = 0.8f

    fun isSpeedHump(mount: MountType, centroidHz: Float, lowFraction: Float, minSpeedDuring: Float): Boolean {
        val slowEnough = minSpeedDuring <= if (mount == MountType.CAR) 30f else 25f
        return slowEnough && (centroidHz <= MAX_HUMP_CENTROID_HZ || lowFraction >= MIN_HUMP_LOW_FRACTION)
    }

    /** 1 = bump, 2 = pothole, 3 = severe, from how far it stands out. */
    fun severity(prominence: Float): Int = when {
        prominence < 10f -> 1
        prominence < 18f -> 2
        else -> 3
    }
}

/** RMS of vertical acceleration over fixed windows while moving. */
class RoughnessProcessor(
    mount: MountType,
    private val windowMs: Long = 5_000,
    private val minSpeedKmh: Float = 8f,
) {
    data class Window(val start: Long, val rms: Float, val bucket: Roughness)

    // Pocket and bag readings are damped by the body, so thresholds are scaled down.
    private val scale = when (mount) {
        MountType.POCKET -> 0.7f
        MountType.HANDLEBAR -> 1.0f
        MountType.BAG -> 0.6f
        MountType.CAR -> 0.35f
    }
    private val thresholds = floatArrayOf(1.0f * scale, 2.0f * scale, 3.5f * scale)

    private var windowStart: Long? = null
    private var sumSq = 0.0
    private var count = 0

    fun onSample(t: Long, verticalMs2: Float, speedKmh: Float): Window? {
        val start = windowStart ?: t.also { windowStart = it }
        if (speedKmh >= minSpeedKmh) {
            sumSq += verticalMs2.toDouble() * verticalMs2
            count++
        }
        if (t - start < windowMs) return null

        val result = if (count >= 20) {
            val rms = sqrt(sumSq / count).toFloat()
            Window(start, rms, bucketFor(rms))
        } else {
            null
        }
        windowStart = t
        sumSq = 0.0
        count = 0
        return result
    }

    fun bucketFor(rms: Float): Roughness = when {
        rms < thresholds[0] -> Roughness.SMOOTH
        rms < thresholds[1] -> Roughness.FAIR
        rms < thresholds[2] -> Roughness.ROUGH
        else -> Roughness.VERY_ROUGH
    }
}

/** Cuts the ride into ~60 s / ~500 m segments and measures actual speed on each. */
class CongestionProcessor(
    private val maxSegmentMs: Long = 60_000,
    private val maxSegmentM: Double = 500.0,
    private val minSegmentM: Double = 50.0,
) {
    data class Segment(val start: Long, val end: Long, val lat: Double, val lng: Double, val actualKmh: Float)

    private var segStartT: Long? = null
    private var segStartM = 0.0

    fun onPoint(t: Long, totalM: Double, lat: Double, lng: Double): Segment? {
        val startT = segStartT
        if (startT == null) {
            segStartT = t
            segStartM = totalM
            return null
        }
        val dt = t - startT
        val dm = totalM - segStartM
        if (dt < maxSegmentMs && dm < maxSegmentM) return null
        segStartT = t
        segStartM = totalM
        if (dm < minSegmentM || dt <= 0) return null
        val kmh = (dm / 1000.0 / (dt / 3_600_000.0)).toFloat()
        return Segment(startT, t, lat, lng, kmh)
    }

    fun reset() {
        segStartT = null
    }

    companion object {
        /** Used when no traffic API key is set. Rough urban free-flow speed for a two-wheeler. */
        const val ESTIMATED_FREE_FLOW_KMH = 30f

        fun index(freeFlowKmh: Float, actualKmh: Float): Float =
            (freeFlowKmh / actualKmh.coerceAtLeast(0.5f)).coerceIn(1f, 5f)
    }
}
