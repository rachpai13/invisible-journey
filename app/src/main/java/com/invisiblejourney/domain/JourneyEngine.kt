package com.invisiblejourney.domain

import com.invisiblejourney.domain.model.BumpKind
import com.invisiblejourney.domain.model.ConditionReading
import com.invisiblejourney.domain.model.CongestionSegment
import com.invisiblejourney.domain.model.JourneyRecord
import com.invisiblejourney.domain.model.LatLng
import com.invisiblejourney.domain.model.MeterState
import com.invisiblejourney.domain.model.MountType
import com.invisiblejourney.domain.model.Phase
import com.invisiblejourney.domain.model.PhaseChange
import com.invisiblejourney.domain.model.PotholeEvent
import com.invisiblejourney.domain.model.PotholeMark

import com.invisiblejourney.domain.model.RoughnessWindow
import com.invisiblejourney.domain.model.SourceTag
import com.invisiblejourney.domain.model.StopEvent
import com.invisiblejourney.domain.model.StopType
import com.invisiblejourney.domain.model.TrackPoint
import com.invisiblejourney.domain.processor.CongestionProcessor
import com.invisiblejourney.domain.processor.DistanceProcessor
import com.invisiblejourney.domain.processor.Geo
import com.invisiblejourney.domain.processor.BumpClassifier
import com.invisiblejourney.domain.processor.BumpDetector
import com.invisiblejourney.domain.processor.RoughnessProcessor
import com.invisiblejourney.domain.processor.SpeedSmoother
import com.invisiblejourney.domain.processor.StopDetector
import com.invisiblejourney.domain.processor.SummaryBuilder

/** Async lookups the engine asks for. The answers come back via [JourneyEngine.onSignalResult] / [JourneyEngine.onFreeFlow]. */
interface EngineRequests {
    fun checkSignal(stopId: Int, lat: Double, lng: Double)
    fun fetchFreeFlow(segmentId: Int, lat: Double, lng: Double)
}

/**
 * Turns raw samples into the journey meter. Pure Kotlin and **not thread-safe**:
 * the caller must call it from one thread or coroutine at a time.
 *
 * Feed it: [onLocation] (~1 Hz), [onImu] (50–100 Hz), [onConditions] (every ~10 min),
 * [advancePhase] (rider taps), and [onTick] (1 Hz clock).
 */
class JourneyEngine(
    val id: String,
    private val title: String,
    val startedAt: Long,
    val promisedEtaMin: Int,
    private val mount: MountType,
    private val requests: EngineRequests,
    private val isSample: Boolean = false,
    private val snapshotEveryMs: Long = 5_000,
) {
    var phase: Phase = Phase.ASSIGNED
        private set
    private var endedAt: Long? = null

    private val distance = DistanceProcessor()
    private val speedSmoother = SpeedSmoother()
    private val stopDetector = StopDetector()
    private val bumpDetector = BumpDetector(mount)

    /** Recent (time, speed) pairs, to see whether the rider slowed down before a bump. */
    private val speedHistory = ArrayDeque<Pair<Long, Float>>()
    private val roughnessProcessor = RoughnessProcessor(mount)
    private val congestionProcessor = CongestionProcessor()

    private val phases = mutableListOf(PhaseChange(Phase.ASSIGNED, startedAt))
    private val track = mutableListOf<TrackPoint>()
    private val stops = mutableListOf<StopEvent>()
    private val potholes = mutableListOf<PotholeEvent>()
    private val marks = mutableListOf<PotholeMark>()
    private val roughness = mutableListOf<RoughnessWindow>()
    private val congestion = mutableListOf<CongestionSegment>()
    private val conditions = mutableListOf<ConditionReading>()
    private val snapshots = mutableListOf<MeterState>()

    private var speedKmh = 0f
    private var location: LatLng? = null
    private var roughnessWindowStartM = 0.0

    private var lastTick = startedAt
    private var lastSnapshot = Long.MIN_VALUE / 2
    private var ridingMs = 0L
    private var storeWaitMs = 0L
    private var doorWaitMs = 0L
    private var heatMs = 0L
    private var rainMs = 0L
    private var aqiMs = 0.0 // Σ AQI × ms

    private var nextStopId = 1
    private var nextSegmentId = 1

    // --- inputs -------------------------------------------------------------------------------

    fun onLocation(t: Long, lat: Double, lng: Double, accuracyM: Float, rawSpeedKmh: Float?) {
        if (phase.isFinal) return
        val derived = rawSpeedKmh ?: distance.last?.let { prev ->
            val dt = (t - prev.t) / 1000.0
            if (dt > 0) (Geo.distanceM(prev.lat, prev.lng, lat, lng) / dt * 3.6).toFloat() else 0f
        } ?: 0f
        speedKmh = speedSmoother.add(derived)
        speedHistory.addLast(t to speedKmh)
        while (speedHistory.isNotEmpty() && t - speedHistory.first().first > 20_000) speedHistory.removeFirst()
        val point = TrackPoint(t, lat, lng, accuracyM, speedKmh)

        if (distance.accept(point, countDistance = phase.riding)) {
            track += point
            location = LatLng(lat, lng)
        } else if (location == null && accuracyM <= 50f) {
            location = LatLng(lat, lng)
        }

        if (!phase.riding) return

        stopDetector.update(t, speedKmh, lat, lng)?.let(::recordStop)

        congestionProcessor.onPoint(t, distance.totalM, lat, lng)?.let { seg ->
            val id = nextSegmentId++
            val free = CongestionProcessor.ESTIMATED_FREE_FLOW_KMH
            congestion += CongestionSegment(
                id, seg.start, seg.end, seg.lat, seg.lng, seg.actualKmh, free,
                CongestionProcessor.index(free, seg.actualKmh), SourceTag.ESTIMATED,
            )
            requests.fetchFreeFlow(id, seg.lat, seg.lng)
        }
    }

    /** Current smoothed speed (km/h). */
    val currentSpeedKmh: Float get() = speedKmh

    /**
     * [verticalMs2]: vertical acceleration with gravity removed.
     * Returns the pothole / speed-breaker event if a bump finished on this sample.
     */
    fun onImu(t: Long, verticalMs2: Float, source: SourceTag = SourceTag.PHONE): PotholeEvent? {
        if (phase.isFinal || !phase.riding) return null
        var event: PotholeEvent? = null
        bumpDetector.onSample(t, verticalMs2, speedKmh)?.let { b ->
            val loc = locationAt(b.tPeak) ?: return@let
            val during = minOf(b.minSpeedKmh, speedHistory.filter { it.first in b.tStart..b.tEnd }.minOfOrNull { it.second } ?: b.minSpeedKmh)
            val hump = BumpClassifier.isSpeedHump(mount, b.centroidHz, b.lowFraction, during)
            val kind = if (hump) BumpKind.SPEED_HUMP else BumpKind.POTHOLE
            event = PotholeEvent(
                b.tPeak, loc.lat, loc.lng, BumpClassifier.severity(b.prominence), b.peakG, source, kind, b.prominence,
            ).also { potholes += it }
        }
        roughnessProcessor.onSample(t, verticalMs2, speedKmh)?.let { w ->
            val dm = (distance.totalM - roughnessWindowStartM).toFloat()
            roughnessWindowStartM = distance.totalM
            roughness += RoughnessWindow(w.start, w.rms, w.bucket, dm, source)
        }
        return event
    }

    /** The rider marked a pothole or speed breaker (ground truth for tuning). Allowed in any phase before the end. */
    fun onPotholeMark(t: Long, kind: BumpKind = BumpKind.POTHOLE): PotholeMark? {
        if (phase.isFinal) return null
        return PotholeMark(t, location?.lat, location?.lng, speedKmh, kind).also { marks += it }
    }

    /** Where the rider was at time [t] (nearest track point), for events reported a moment later. */
    private fun locationAt(t: Long): LatLng? {
        val p = track.lastOrNull { it.t <= t } ?: track.firstOrNull()
        return p?.let { LatLng(it.lat, it.lng) } ?: location
    }

    fun onConditions(reading: ConditionReading) {
        if (phase.isFinal) return
        conditions += reading
    }

    fun onSignalResult(stopId: Int, isSignal: Boolean) {
        if (!isSignal) return
        val i = stops.indexOfFirst { it.id == stopId }
        if (i >= 0 && stops[i].type == StopType.TRAFFIC_STOP) stops[i] = stops[i].copy(type = StopType.SIGNAL_WAIT)
    }

    fun onFreeFlow(segmentId: Int, freeFlowKmh: Float, source: SourceTag) {
        val i = congestion.indexOfFirst { it.id == segmentId }
        if (i < 0 || freeFlowKmh <= 0f) return
        val s = congestion[i]
        congestion[i] = s.copy(freeFlowKmh = freeFlowKmh, index = CongestionProcessor.index(freeFlowKmh, s.actualKmh), source = source)
    }

    fun advancePhase(t: Long): Phase {
        if (phase.isFinal) return phase
        setPhase(phase.next(), t)
        return phase
    }

    fun cancel(t: Long) {
        if (!phase.isFinal) setPhase(Phase.CANCELLED, t)
    }

    /** Call about once per second with the current time. */
    fun onTick(t: Long) {
        if (phase.isFinal) return
        val dt = (t - lastTick).coerceAtLeast(0)
        lastTick = t
        when {
            phase.riding -> ridingMs += dt
            phase == Phase.AT_STORE -> storeWaitMs += dt
            phase == Phase.ARRIVED -> doorWaitMs += dt
            else -> Unit
        }
        conditions.lastOrNull()?.let { c ->
            if ((c.feelsLikeC ?: c.tempC ?: 0f) >= HEAT_THRESHOLD_C) heatMs += dt
            if (c.raining) rainMs += dt
            c.aqi?.let { aqiMs += it.toDouble() * dt }
        }
        if (t - lastSnapshot >= snapshotEveryMs) {
            lastSnapshot = t
            snapshots += meter(t)
        }
    }

    // --- outputs ------------------------------------------------------------------------------

    fun meter(now: Long = lastTick): MeterState {
        val ongoing = if (phase.riding) stopDetector.ongoingStart(now)?.let { now - it } ?: 0L else 0L
        val byType = StopType.entries.associateWith { type -> stops.filter { it.type == type }.sumOf { it.durationMs } }
            .toMutableMap()
        byType[StopType.STORE_WAIT] = byType.getValue(StopType.STORE_WAIT) + storeWaitMs
        byType[StopType.GATE_OR_DOOR_WAIT] = byType.getValue(StopType.GATE_OR_DOOR_WAIT) + doorWaitMs
        byType[StopType.TRAFFIC_STOP] = byType.getValue(StopType.TRAFFIC_STOP) + ongoing

        val ridingStopMs = stops.sumOf { it.durationMs } + ongoing
        val movingMs = (ridingMs - ridingStopMs).coerceAtLeast(0)
        val km = (distance.totalM / 1000.0).toFloat()
        val c = conditions.lastOrNull()
        val lastSeg = congestion.lastOrNull()

        return MeterState(
            t = now,
            phase = phase,
            elapsedMs = now - startedAt,
            movingMs = movingMs,
            waitingMs = byType.values.sum(),
            waitingByType = byType,
            distanceKm = km,
            speedKmh = if (phase.riding) speedKmh else 0f,
            avgMovingKmh = if (movingMs > 0) km / (movingMs / 3_600_000f) else 0f,
            stopCount = stops.size + if (ongoing > 0) 1 else 0,
            potholeCount = potholes.count { it.kind == BumpKind.POTHOLE },
            speedHumpCount = potholes.count { it.kind == BumpKind.SPEED_HUMP },
            markedPotholes = marks.count { it.kind == BumpKind.POTHOLE },
            markedHumps = marks.count { it.kind == BumpKind.SPEED_HUMP },
            roughnessNow = roughness.lastOrNull()?.bucket,
            congestionIndex = lastSeg?.index,
            congestionSource = lastSeg?.source,
            tempC = c?.tempC,
            feelsLikeC = c?.feelsLikeC,
            raining = c?.raining ?: false,
            aqiNow = c?.aqi,
            aqiMinutes = (aqiMs / 60_000.0).toFloat(),
            heatMinutes = heatMs / 60_000f,
            rainMinutes = rainMs / 60_000f,
            location = location,
        )
    }

    fun record(): JourneyRecord {
        val final = if (phase.isFinal) meter(endedAt ?: lastTick) else null
        return JourneyRecord(
            id = id,
            title = title,
            startedAt = startedAt,
            endedAt = endedAt,
            promisedEtaMin = promisedEtaMin,
            mountType = mount,
            isSample = isSample,
            phases = phases.toList(),
            track = track.toList(),
            stops = stops.toList(),
            potholes = potholes.toList(),
            marks = marks.toList(),
            roughness = roughness.toList(),
            congestion = congestion.toList(),
            conditions = conditions.toList(),
            snapshots = snapshots.toList(),
            summary = if (phase == Phase.DELIVERED && final != null) {
                SummaryBuilder.build(final, promisedEtaMin, roughness, congestion, conditions)
            } else {
                null
            },
        )
    }

    // --- internals ----------------------------------------------------------------------------

    private fun setPhase(next: Phase, t: Long) {
        onTick(t) // account time up to the change under the old phase
        if (phase.riding && !next.riding) {
            stopDetector.flush(t)?.let(::recordStop)
            congestionProcessor.reset()
        }
        phase = next
        phases += PhaseChange(next, t)
        if (next.isFinal) {
            endedAt = t
            snapshots += meter(t)
        }
    }

    private fun recordStop(s: StopDetector.Stop) {
        val id = nextStopId++
        stops += StopEvent(id, StopType.TRAFFIC_STOP, s.start, s.end, s.lat, s.lng)
        requests.checkSignal(id, s.lat, s.lng)
    }

    companion object {
        const val HEAT_THRESHOLD_C = 35f
    }
}
