package com.invisiblejourney

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.invisiblejourney.service.ActiveJourney
import com.invisiblejourney.service.JourneyTrackingService
import com.invisiblejourney.ui.InvisibleJourneyTheme
import com.invisiblejourney.ui.screens.LibraryScreen
import com.invisiblejourney.ui.screens.LiveJourneyScreen
import com.invisiblejourney.ui.screens.ModeSelectScreen
import com.invisiblejourney.ui.screens.ReplayScreen
import com.invisiblejourney.ui.screens.RiderHistoryScreen
import com.invisiblejourney.ui.screens.RiderHomeScreen
import com.invisiblejourney.ui.screens.StoryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InvisibleJourneyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) { AppNav() }
                }
            }
        }
    }

    /** During a real ride: volume down marks a pothole, volume up a speed breaker. No need to look at the screen. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val s = ActiveJourney.state.value
        val riding = s != null && !s.finished && !s.simulated
        if (riding && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event?.repeatCount == 0) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) JourneyTrackingService.markPothole(this)
                else JourneyTrackingService.markSpeedHump(this)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@androidx.compose.runtime.Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "mode") {
        composable("mode") {
            ModeSelectScreen(
                onRider = { nav.navigate("rider") },
                onCustomer = { nav.navigate("library") },
            )
        }
        composable("rider") {
            RiderHomeScreen(
                onBack = { nav.popBackStack() },
                onLive = { nav.navigate("live") },
                onHistory = { nav.navigate("history") },
            )
        }
        composable("history") {
            RiderHistoryScreen(
                onBack = { nav.popBackStack() },
                onOpenStory = { id -> nav.navigate("story/$id") },
            )
        }
        composable("live") {
            LiveJourneyScreen(
                onBack = { nav.popBackStack() },
                onFinished = { id ->
                    nav.navigate("story/$id") { popUpTo("rider") }
                },
            )
        }
        composable("library") {
            LibraryScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("replay/$id") },
            )
        }
        composable("replay/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ReplayScreen(
                journeyId = id,
                onBack = { nav.popBackStack() },
            )
        }
        composable("story/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            StoryScreen(
                journeyId = id,
                onBack = { nav.popBackStack() },
                onReplay = { nav.navigate("replay/$id") },
            )
        }
    }
}
