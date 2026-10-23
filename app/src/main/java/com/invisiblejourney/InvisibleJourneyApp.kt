package com.invisiblejourney

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.osmdroid.config.Configuration
import java.io.File

class InvisibleJourneyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        Configuration.getInstance().apply {
            userAgentValue = AppContainer.USER_AGENT
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_JOURNEY, "Active journey", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the journey meter while a delivery is being recorded"
            },
        )
    }

    companion object {
        const val CHANNEL_JOURNEY = "journey"
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as InvisibleJourneyApp).container
