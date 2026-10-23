package com.invisiblejourney.domain.model

import kotlinx.serialization.Serializable

/** All timestamps are epoch milliseconds. All durations are milliseconds. */

@Serializable
enum class Phase(val label: String, val riding: Boolean) {
    ASSIGNED("Heading to store", riding = true),
    AT_STORE("Waiting at store", riding = false),
    EN_ROUTE("On the way to you", riding = true),
    ARRIVED("At your building", riding = false),
    DELIVERED("Delivered", riding = false),
    CANCELLED("Cancelled", riding = false);

    fun next(): Phase = when (this) {
        ASSIGNED -> AT_STORE
        AT_STORE -> EN_ROUTE
        EN_ROUTE -> ARRIVED
        ARRIVED -> DELIVERED
        DELIVERED, CANCELLED -> this
    }

    val isFinal: Boolean get() = this == DELIVERED || this == CANCELLED
}

@Serializable
enum class SourceTag { PHONE, ESP32, API, ESTIMATED }

@Serializable
enum class MountType(val label: String) {
    POCKET("Pocket"),
    HANDLEBAR("Handlebar"),
    BAG("Bag"),

    /**
     * Test rides in a car. Car suspension soaks up most of a pothole before it reaches the phone,
     * so jolts are much smaller than on a two-wheeler: car data needs its own thresholds and
     * can't be used directly to tune the bike profiles.
     */
    CAR("Car"),
}

@Serializable
enum class StopType(val label: String) {
    STORE_WAIT("Waiting at the store"),
    SIGNAL_WAIT("Waiting at signals"),
    TRAFFIC_STOP("Stuck in traffic"),
    GATE_OR_DOOR_WAIT("Gate, lift & stairs"),
}

@Serializable
enum class Roughness(val label: String) {
    SMOOTH("Smooth"),
    FAIR("Fair"),
    ROUGH("Rough"),
    VERY_ROUGH("Very rough"),
}

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val speedKmh: Float,
)

@Serializable
data class PhaseChange(val phase: Phase, val t: Long)

@Serializable
data class StopEvent(
    val id: Int,
    val type: StopType,
    val start: Long,
    val end: Long,
    val lat: Double,
    val lng: Double,
) {
    val durationMs: Long get() = end - start
}

@Serializable
enum class BumpKind(val label: String) {
    POTHOLE("Pothole"),
    SPEED_HUMP("Speed breaker"),
}

@Serializable
data class PotholeEvent(
    val t: Long,
    val lat: Double,
    val lng: Double,
    /** 1 = bump, 2 = pothole, 3 = severe. */
    val severity: Int,
    val peakG: Float,
    val source: SourceTag,
    val kind: BumpKind = BumpKind.POTHOLE,
    /** Peak ÷ background roughness of the road just before it. */
    val prominence: Float = 0f,
)

/** The rider tapped "Pothole!": ground truth for tuning the detector. */
@Serializable
data class PotholeMark(
    val t: Long,
    val lat: Double?,
    val lng: Double?,
    val speedKmh: Float,
    val kind: BumpKind = BumpKind.POTHOLE,
)

@Serializable
data class RoughnessWindow(
    val start: Long,
    val rmsMs2: Float,
    val bucket: Roughness,
    val distanceM: Float,
    val source: SourceTag,
)

@Serializable
data class CongestionSegment(
    val id: Int,
    val start: Long,
    val end: Long,
    val lat: Double,
    val lng: Double,
    val actualKmh: Float,
    val freeFlowKmh: Float,
    /** freeFlow / actual, clamped to 1..5. 1.0 = empty roads. */
    val index: Float,
    val source: SourceTag,
)

@Serializable
data class ConditionReading(
    val t: Long,
    val source: SourceTag,
    val tempC: Float? = null,
    val feelsLikeC: Float? = null,
    val humidityPct: Float? = null,
    val precipitationMm: Float? = null,
    val weatherCode: Int? = null,
    val windKmh: Float? = null,
    val isDay: Boolean? = null,
    val pm25: Float? = null,
    val pm10: Float? = null,
    /** Indian (CPCB) AQI computed from PM2.5 / PM10. */
    val aqi: Int? = null,
) {
    val raining: Boolean
        get() = (precipitationMm ?: 0f) >= 0.1f || (weatherCode != null && weatherCode in RAIN_CODES)

    companion object {
        /** WMO weather codes for drizzle, rain, showers and thunderstorms. */
        val RAIN_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
    }
}

/** Everything the meter shows at one instant. Snapshots of this power replay. */
@Serializable
data class MeterState(
    val t: Long,
    val phase: Phase,
    val elapsedMs: Long,
    val movingMs: Long,
    val waitingMs: Long,
    val waitingByType: Map<StopType, Long>,
    val distanceKm: Float,
    val speedKmh: Float,
    val avgMovingKmh: Float,
    val stopCount: Int,
    val potholeCount: Int,
    /** Speed breakers detected (counted separately from potholes). */
    val speedHumpCount: Int = 0,
    /** Potholes / speed breakers the rider marked by hand (research rides). */
    val markedPotholes: Int = 0,
    val markedHumps: Int = 0,
    val roughnessNow: Roughness?,
    val congestionIndex: Float?,
    val congestionSource: SourceTag?,
    val tempC: Float?,
    val feelsLikeC: Float?,
    val raining: Boolean,
    val aqiNow: Int?,
    val aqiMinutes: Float,
    val heatMinutes: Float,
    val rainMinutes: Float,
    val location: LatLng?,
)

@Serializable
data class JourneySummary(
    val promisedEtaMin: Int,
    val actualMin: Float,
    val distanceKm: Float,
    val movingMin: Float,
    val waitingMin: Float,
    val waitingByTypeMin: Map<StopType, Float>,
    val stopCount: Int,
    val potholes: Int,
    val speedHumps: Int = 0,
    /** Share of measured distance on ROUGH or VERY_ROUGH road, 0..1. */
    val roughShare: Float,
    val avgCongestion: Float?,
    val maxFeelsLikeC: Float?,
    val rainMinutes: Float,
    val heatMinutes: Float,
    val aqiMinutes: Float,
    val avgAqi: Int?,
    val storyLines: List<String>,
)

/** The whole journey: on-disk storage format and the shareable export format. */
@Serializable
data class JourneyRecord(
    val format: String = FORMAT,
    val version: Int = 1,
    val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val promisedEtaMin: Int,
    val mountType: MountType,
    val isSample: Boolean = false,
    val phases: List<PhaseChange> = emptyList(),
    val track: List<TrackPoint> = emptyList(),
    val stops: List<StopEvent> = emptyList(),
    val potholes: List<PotholeEvent> = emptyList(),
    val marks: List<PotholeMark> = emptyList(),
    val roughness: List<RoughnessWindow> = emptyList(),
    val congestion: List<CongestionSegment> = emptyList(),
    val conditions: List<ConditionReading> = emptyList(),
    val snapshots: List<MeterState> = emptyList(),
    val summary: JourneySummary? = null,
) {
    companion object { const val FORMAT = "invisible-journey" }
}
