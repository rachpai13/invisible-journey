package com.invisiblejourney.data.remote

import com.invisiblejourney.domain.model.ConditionReading
import com.invisiblejourney.domain.model.SourceTag
import com.invisiblejourney.domain.processor.IndianAqi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

private suspend fun OkHttpClient.getString(request: Request): String = withContext(Dispatchers.IO) {
    newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${request.url.host}")
        resp.body?.string() ?: throw IOException("Empty body")
    }
}

private fun Double.fmt(): String = String.format(Locale.US, "%.5f", this)

// --- Open-Meteo: weather + air quality (free, no key) ----------------------------------------

class OpenMeteoApi(private val http: OkHttpClient) {

    @Serializable
    private data class ForecastResponse(val current: Current? = null) {
        @Serializable
        data class Current(
            @SerialName("temperature_2m") val temperature: Float? = null,
            @SerialName("apparent_temperature") val apparentTemperature: Float? = null,
            @SerialName("relative_humidity_2m") val humidity: Float? = null,
            val precipitation: Float? = null,
            @SerialName("weather_code") val weatherCode: Int? = null,
            @SerialName("wind_speed_10m") val wind: Float? = null,
            @SerialName("is_day") val isDay: Int? = null,
        )
    }

    @Serializable
    private data class AirResponse(val current: Current? = null) {
        @Serializable
        data class Current(
            @SerialName("pm2_5") val pm25: Float? = null,
            val pm10: Float? = null,
        )
    }

    /** Weather and air quality in one reading. Either half may be missing if its call fails. */
    suspend fun conditions(lat: Double, lng: Double, now: Long): ConditionReading = coroutineScope {
        val weather = async { runCatching { forecast(lat, lng) }.getOrNull() }
        val air = async { runCatching { air(lat, lng) }.getOrNull() }
        val w = weather.await()
        val a = air.await()
        if (w == null && a == null) throw IOException("Open-Meteo unavailable")
        ConditionReading(
            t = now,
            source = SourceTag.API,
            tempC = w?.temperature,
            feelsLikeC = w?.apparentTemperature,
            humidityPct = w?.humidity,
            precipitationMm = w?.precipitation,
            weatherCode = w?.weatherCode,
            windKmh = w?.wind,
            isDay = w?.isDay?.let { it == 1 },
            pm25 = a?.pm25,
            pm10 = a?.pm10,
            aqi = IndianAqi.compute(a?.pm25, a?.pm10),
        )
    }

    private suspend fun forecast(lat: Double, lng: Double): ForecastResponse.Current? {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.fmt())
            .addQueryParameter("longitude", lng.fmt())
            .addQueryParameter(
                "current",
                "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,is_day",
            )
            .build()
        return json.decodeFromString<ForecastResponse>(http.getString(Request.Builder().url(url).build())).current
    }

    private suspend fun air(lat: Double, lng: Double): AirResponse.Current? {
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.fmt())
            .addQueryParameter("longitude", lng.fmt())
            .addQueryParameter("current", "pm10,pm2_5")
            .build()
        return json.decodeFromString<AirResponse>(http.getString(Request.Builder().url(url).build())).current
    }
}

// --- OpenStreetMap Overpass: is there a traffic signal near this stop? (free, no key) -------

class OverpassApi(private val http: OkHttpClient) {

    @Serializable
    private data class Response(val elements: List<Element> = emptyList()) {
        @Serializable
        data class Element(val id: Long)
    }

    suspend fun hasTrafficSignalNear(lat: Double, lng: Double, radiusM: Int = 50): Boolean {
        val query = "[out:json][timeout:10];node(around:$radiusM,${lat.fmt()},${lng.fmt()})[highway=traffic_signals];out ids;"
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(FormBody.Builder().add("data", query).build())
            .build()
        return json.decodeFromString<Response>(http.getString(request)).elements.isNotEmpty()
    }
}

// --- TomTom Traffic Flow: free-flow speed (free key, optional) -------------------------------

class TomTomTrafficApi(private val http: OkHttpClient, private val apiKey: String) {

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    @Serializable
    private data class Response(val flowSegmentData: Flow? = null) {
        @Serializable
        data class Flow(val currentSpeed: Float? = null, val freeFlowSpeed: Float? = null)
    }

    /** Free-flow speed in km/h for the road nearest to the point. */
    suspend fun freeFlowKmh(lat: Double, lng: Double): Float? {
        if (!isConfigured) return null
        val url = "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json".toHttpUrl().newBuilder()
            .addQueryParameter("point", "${lat.fmt()},${lng.fmt()}")
            .addQueryParameter("unit", "KMPH")
            .addQueryParameter("key", apiKey)
            .build()
        return json.decodeFromString<Response>(http.getString(Request.Builder().url(url).build()))
            .flowSegmentData?.freeFlowSpeed
    }
}
