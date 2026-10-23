package com.invisiblejourney.data

import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Research log for tuning pothole detection on real rides. Writes, per journey, to
 * `files/research/<journeyId>/`:
 *
 * - `imu.csv`: every accelerometer sample of the ride (t_ms, raw ax/ay/az, vertical, speed)
 * - `clips/mark_<n>.csv`: [CLIP_BEFORE_MS] before to [CLIP_AFTER_MS] after each "Pothole!" tap
 * - `clips/auto_<n>.csv`: the same window around each automatic detection
 * - `events.csv`: one line per tap / detection, for lining them up with the full log
 *
 * Not thread-safe: call from the journey engine thread only.
 */
class RideLogger(root: File, journeyId: String) {

    private class Row(val t: Long, val line: String)
    private class PendingClip(val name: String, val t: Long)

    val dir = File(root, "research/$journeyId").apply { mkdirs() }
    private val clipsDir = File(dir, "clips").apply { mkdirs() }
    private val imu: BufferedWriter = File(dir, "imu.csv").bufferedWriter(bufferSize = 1 shl 16)
    private val events: BufferedWriter = File(dir, "events.csv").bufferedWriter()
    private val ring = ArrayDeque<Row>()
    private val pending = mutableListOf<PendingClip>()
    private var marks = 0
    private var autos = 0
    private var closed = false

    init {
        imu.write(HEADER)
        events.write("t_ms,kind,clip,lat,lng,speed_kmh,peak_g,severity\n")
    }

    fun onSample(t: Long, ax: Float, ay: Float, az: Float, vertical: Float, speedKmh: Float) {
        if (closed) return
        val line = String.format(Locale.US, "%d,%.4f,%.4f,%.4f,%.4f,%.1f\n", t, ax, ay, az, vertical, speedKmh)
        imu.write(line)
        ring.addLast(Row(t, line))
        while (ring.isNotEmpty() && t - ring.first().t > CLIP_BEFORE_MS + CLIP_AFTER_MS) ring.removeFirst()

        if (pending.isNotEmpty()) {
            val ready = pending.filter { t - it.t >= CLIP_AFTER_MS }
            ready.forEach(::writeClip)
            pending.removeAll(ready)
        }
    }

    /** Rider marked a pothole ([kind] = "pothole") or speed breaker ("hump"). Returns the mark number. */
    fun mark(t: Long, lat: Double?, lng: Double?, speedKmh: Float, kind: String = "pothole"): Int {
        marks++
        val name = "mark_$marks"
        event(t, "mark_$kind", name, lat, lng, speedKmh, null, null)
        pending += PendingClip(name, t)
        return marks
    }

    /** The detector found a bump ([kind] = "pothole" or "hump"). */
    fun detected(t: Long, lat: Double?, lng: Double?, speedKmh: Float, peakG: Float, severity: Int, kind: String = "pothole") {
        autos++
        val name = "auto_$autos"
        event(t, "auto_$kind", name, lat, lng, speedKmh, peakG, severity)
        pending += PendingClip(name, t)
    }

    fun close() {
        if (closed) return
        pending.forEach(::writeClip) // whatever "after" data we have
        pending.clear()
        imu.close()
        events.close()
        closed = true
    }

    private fun writeClip(c: PendingClip) {
        File(clipsDir, "${c.name}.csv").bufferedWriter().use { w ->
            w.write("# ${c.name} at t_ms=${c.t}; window ${CLIP_BEFORE_MS / 1000}s before to ${CLIP_AFTER_MS / 1000}s after\n")
            w.write(HEADER)
            ring.filter { it.t >= c.t - CLIP_BEFORE_MS && it.t <= c.t + CLIP_AFTER_MS }.forEach { w.write(it.line) }
        }
        events.flush()
        imu.flush()
    }

    private fun event(t: Long, kind: String, clip: String, lat: Double?, lng: Double?, speed: Float, peakG: Float?, severity: Int?) {
        events.write(
            String.format(
                Locale.US, "%d,%s,%s,%s,%s,%.1f,%s,%s\n", t, kind, clip,
                lat?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                lng?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                speed, peakG?.let { String.format(Locale.US, "%.2f", it) } ?: "", severity?.toString() ?: "",
            ),
        )
        events.flush()
    }

    companion object {
        /** People tap a second or more after the hit, so most of the window is *before* the tap. */
        const val CLIP_BEFORE_MS = 7_000L
        const val CLIP_AFTER_MS = 3_000L
        private const val HEADER = "t_ms,ax,ay,az,vertical,speed_kmh\n"
    }
}
