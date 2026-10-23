package com.invisiblejourney.domain.processor

import com.invisiblejourney.domain.model.ConditionReading
import com.invisiblejourney.domain.model.CongestionSegment
import com.invisiblejourney.domain.model.JourneySummary
import com.invisiblejourney.domain.model.MeterState
import com.invisiblejourney.domain.model.Roughness
import com.invisiblejourney.domain.model.RoughnessWindow
import com.invisiblejourney.domain.model.SourceTag
import com.invisiblejourney.domain.model.StopType
import java.util.Locale
import kotlin.math.roundToInt

/** Builds the post-delivery "journey story": numbers plus one sentence per hidden factor. */
object SummaryBuilder {

    fun build(
        m: MeterState,
        promisedEtaMin: Int,
        roughness: List<RoughnessWindow>,
        congestion: List<CongestionSegment>,
        conditions: List<ConditionReading>,
    ): JourneySummary {
        val min = { ms: Long -> ms / 60_000f }
        val waitingByType = m.waitingByType.mapValues { min(it.value) }

        val measuredM = roughness.sumOf { it.distanceM.toDouble() }
        val roughM = roughness.filter { it.bucket >= Roughness.ROUGH }.sumOf { it.distanceM.toDouble() }
        val roughShare = if (measuredM > 0) (roughM / measuredM).toFloat() else 0f

        val totalSegMs = congestion.sumOf { it.end - it.start }
        val avgCongestion = if (totalSegMs > 0) {
            (congestion.sumOf { it.index.toDouble() * (it.end - it.start) } / totalSegMs).toFloat()
        } else {
            null
        }
        val congestionEstimated = congestion.isNotEmpty() && congestion.all { it.source == SourceTag.ESTIMATED }

        val maxFeelsLike = conditions.mapNotNull { it.feelsLikeC ?: it.tempC }.maxOrNull()
        val aqiValues = conditions.mapNotNull { it.aqi }
        val avgAqi = if (aqiValues.isNotEmpty()) aqiValues.average().roundToInt() else null

        val actualMin = min(m.elapsedMs)
        val lines = mutableListOf<String>()

        lines += "ETA shown: $promisedEtaMin min · Actual: ${fmt(actualMin)} min."
        waitingByType[StopType.STORE_WAIT]?.takeIf { it >= 0.5f }?.let {
            lines += "${fmt(it)} min were spent waiting at the store while your order was packed."
        }
        val roadWait = (waitingByType[StopType.SIGNAL_WAIT] ?: 0f) + (waitingByType[StopType.TRAFFIC_STOP] ?: 0f)
        if (roadWait >= 0.5f) {
            lines += "${fmt(roadWait)} min stuck at signals and in traffic, across ${m.stopCount} stops."
        }
        if (avgCongestion != null && avgCongestion >= 1.2f) {
            val note = if (congestionEstimated) " (estimated)" else ""
            lines += "Roads were ${fmt1(avgCongestion)}× slower than empty roads$note."
        }
        if (m.potholeCount > 0) {
            val share = (roughShare * 100).roundToInt()
            lines += "Your rider hit ${m.potholeCount} potholes and bumps; $share% of the road was rough."
        }
        if (m.speedHumpCount > 0) {
            lines += "They slowed down for ${m.speedHumpCount} speed breakers."
        }
        if (maxFeelsLike != null && maxFeelsLike >= 32f) {
            lines += "It felt like ${maxFeelsLike.roundToInt()} °C on the road."
        }
        if (m.rainMinutes >= 0.5f) {
            lines += "They rode ${fmt(m.rainMinutes)} min in the rain."
        }
        if (avgAqi != null && avgAqi > 100) {
            lines += "They breathed air with AQI $avgAqi (${IndianAqi.category(avgAqi)}) for ${fmt(actualMin)} min."
        }
        waitingByType[StopType.GATE_OR_DOOR_WAIT]?.takeIf { it >= 0.5f }?.let {
            lines += "${fmt(it)} min getting past the gate, the lift and the stairs to your door."
        }
        lines += "They covered ${fmt1(m.distanceKm)} km to bring it to you."

        return JourneySummary(
            promisedEtaMin = promisedEtaMin,
            actualMin = actualMin,
            distanceKm = m.distanceKm,
            movingMin = min(m.movingMs),
            waitingMin = min(m.waitingMs),
            waitingByTypeMin = waitingByType,
            stopCount = m.stopCount,
            potholes = m.potholeCount,
            speedHumps = m.speedHumpCount,
            roughShare = roughShare,
            avgCongestion = avgCongestion,
            maxFeelsLikeC = maxFeelsLike,
            rainMinutes = m.rainMinutes,
            heatMinutes = m.heatMinutes,
            aqiMinutes = m.aqiMinutes,
            avgAqi = avgAqi,
            storyLines = lines,
        )
    }

    private fun fmt(v: Float): String = if (v >= 10f) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v).removeSuffix(".0")
    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)
}
