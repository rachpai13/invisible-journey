package com.invisiblejourney.data.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.invisiblejourney.domain.processor.VerticalAccel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

data class LocationSample(
    val t: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    /** km/h, or null when the fix has no Doppler speed. */
    val speedKmh: Float?,
)

/**
 * One accelerometer reading. [ax]/[ay]/[az] are raw phone-axis values in m/s² (gravity included),
 * kept for the research log; [verticalMs2] is up/down acceleration with gravity removed.
 */
data class ImuSample(val t: Long, val ax: Float, val ay: Float, val az: Float, val verticalMs2: Float)

/** GPS via the Fused Location Provider. Caller must hold ACCESS_FINE_LOCATION. */
class GpsSource(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun samples(intervalMs: Long = 1_000): Flow<LocationSample> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (l in result.locations) {
                    trySend(
                        LocationSample(
                            t = System.currentTimeMillis(),
                            lat = l.latitude,
                            lng = l.longitude,
                            accuracyM = if (l.hasAccuracy()) l.accuracy else 99f,
                            speedKmh = if (l.hasSpeed()) l.speed * 3.6f else null,
                        ),
                    )
                }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

/**
 * Accelerometer pipeline from docs/02_SENSORS.md §5, built on the raw accelerometer only
 * (budget phones like the Galaxy M13 have no gyroscope, linear-acceleration or rotation-vector sensor).
 * Gravity direction is estimated by [VerticalAccel]; samples are timestamped from the sensor clock.
 */
class ImuSource(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val isAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /** Default 100 Hz. Potholes on a two-wheeler are 20–80 ms jolts, so 50 Hz is the bare minimum. */
    fun samples(samplingPeriodUs: Int = 10_000): Flow<ImuSample> = callbackFlow {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val vertical = VerticalAccel()
        // Sensor timestamps are nanoseconds since boot; convert once to wall-clock milliseconds.
        val bootToWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos() / 1_000_000

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = bootToWallMs + e.timestamp / 1_000_000
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                trySend(ImuSample(t, x, y, z, vertical.update(t, x, y, z)))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (accel != null) sensorManager.registerListener(listener, accel, samplingPeriodUs)
        awaitClose { sensorManager.unregisterListener(listener) }
    }.buffer(2048) // ~20 s of headroom so a busy engine thread never drops samples
}
