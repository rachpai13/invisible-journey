package com.invisiblejourney

import android.content.Context
import com.invisiblejourney.data.JourneyStore
import com.invisiblejourney.data.remote.OpenMeteoApi
import com.invisiblejourney.data.remote.OverpassApi
import com.invisiblejourney.data.remote.TomTomTrafficApi
import com.invisiblejourney.data.sensors.GpsSource
import com.invisiblejourney.data.sensors.ImuSource
import com.invisiblejourney.domain.model.MountType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-written dependency container (instead of Hilt) — one instance per process. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val filesDir: java.io.File get() = app.filesDir

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // OSM / Overpass usage policies require an identifying User-Agent.
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val store = JourneyStore(app)
    val openMeteo = OpenMeteoApi(http)
    val overpass = OverpassApi(http)
    val tomTom = TomTomTrafficApi(http, BuildConfig.TOMTOM_API_KEY)
    val gps = GpsSource(app)
    val imu = ImuSource(app)
    val settings = Settings(app)

    companion object {
        const val USER_AGENT = "InvisibleJourney/0.1 (Android; class project)"
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mountType: MountType
        get() = runCatching { MountType.valueOf(prefs.getString("mount", null) ?: "") }.getOrDefault(MountType.POCKET)
        set(v) = prefs.edit().putString("mount", v.name).apply()

    var promisedEtaMin: Int
        get() = prefs.getInt("eta", 10)
        set(v) = prefs.edit().putInt("eta", v).apply()

    /** Journeys whose receipt the customer has scanned (the rider confirms it); their QR stub is torn off. */
    fun isScanned(journeyId: String): Boolean = journeyId in prefs.getStringSet("scanned", emptySet()).orEmpty()

    fun markScanned(journeyId: String) {
        val ids = prefs.getStringSet("scanned", emptySet()).orEmpty() + journeyId
        prefs.edit().putStringSet("scanned", ids).apply()
    }
}
