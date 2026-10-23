package com.invisiblejourney.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.invisiblejourney.InvisibleJourneyApp
import com.invisiblejourney.MainActivity
import com.invisiblejourney.R
import com.invisiblejourney.container
import com.invisiblejourney.domain.SampleJourneys
import com.invisiblejourney.domain.model.BumpKind
import com.invisiblejourney.domain.model.MountType
import com.invisiblejourney.ui.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service (type `location`) that owns the [JourneyRecorder] while a delivery is recorded,
 * so recording survives the screen turning off. Controlled by the intents built in [Companion].
 */
class JourneyTrackingService : LifecycleService() {

    private var recorder: JourneyRecorder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(
                mount = intent.getStringExtra(EXTRA_MOUNT)?.let { runCatching { MountType.valueOf(it) }.getOrNull() }
                    ?: MountType.POCKET,
                eta = intent.getIntExtra(EXTRA_ETA, 10),
                simulation = intent.getStringExtra(EXTRA_SIM)?.let { runCatching { SampleJourneys.Scenario.valueOf(it) }.getOrNull() },
                simSpeed = intent.getIntExtra(EXTRA_SIM_SPEED, 1),
            )
            ACTION_NEXT_PHASE -> recorder?.advancePhase()
            ACTION_MARK_POTHOLE, ACTION_MARK_HUMP -> recorder?.let {
                val hump = intent.action == ACTION_MARK_HUMP
                it.markBump(if (hump) BumpKind.SPEED_HUMP else BumpKind.POTHOLE)
                buzz(if (hump) 2 else 1) // one buzz = pothole, two = speed breaker
            }
            ACTION_CANCEL -> recorder?.cancel()
        }
        return START_NOT_STICKY
    }

    private fun start(mount: MountType, eta: Int, simulation: SampleJourneys.Scenario?, simSpeed: Int) {
        if (recorder != null) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), type)

        val r = JourneyRecorder(container, mount, eta, simulation, simSpeed) { _ ->
            lifecycleScope.launch(Dispatchers.Main) {
                recorder = null
                ServiceCompat.stopForeground(this@JourneyTrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        recorder = r
        r.start()

        // Refresh the notification meter every 5 s.
        lifecycleScope.launch {
            ActiveJourney.state.filterNotNull().first { it.journeyId == r.journeyId }
            while (recorder === r) {
                val s = ActiveJourney.state.value
                if (s != null && !s.finished && hasNotificationPermission()) {
                    @Suppress("MissingPermission")
                    NotificationManagerCompat.from(this@JourneyTrackingService).notify(NOTIFICATION_ID, buildNotification(s))
                }
                delay(5_000)
            }
        }
    }

    private fun buzz(times: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.os.Vibrator::class.java)
        }
        val effect = if (times <= 1) {
            android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            android.os.VibrationEffect.createWaveform(longArrayOf(0, 100, 120, 100), -1)
        }
        vibrator?.vibrate(effect)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun buildNotification(s: ActiveJourneyState?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = PendingIntent.getService(
            this, 1, Intent(this, JourneyTrackingService::class.java).setAction(ACTION_NEXT_PHASE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val markIntent = PendingIntent.getService(
            this, 2, Intent(this, JourneyTrackingService::class.java).setAction(ACTION_MARK_POTHOLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val humpIntent = PendingIntent.getService(
            this, 3, Intent(this, JourneyTrackingService::class.java).setAction(ACTION_MARK_HUMP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val m = s?.meter
        val text = if (m == null) {
            "Starting sensors…"
        } else {
            "${Format.km(m.distanceKm)} · moving ${Format.duration(m.movingMs)} · waiting ${Format.duration(m.waitingMs)}"
        }
        val builder = NotificationCompat.Builder(this, InvisibleJourneyApp.CHANNEL_JOURNEY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(m?.phase?.label ?: "Journey meter running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (s?.simulated != true) {
            m?.phase?.takeIf { !it.isFinal }?.let { builder.addAction(0, "Next: ${it.next().label}", next) }
            builder.addAction(0, "Pothole!", markIntent)
            builder.addAction(0, "Speed breaker", humpIntent)
        }
        return builder.build()
    }

    override fun onDestroy() {
        recorder?.dispose()
        recorder = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "start"
        private const val ACTION_NEXT_PHASE = "next_phase"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_MARK_POTHOLE = "mark_pothole"
        private const val ACTION_MARK_HUMP = "mark_hump"
        private const val EXTRA_MOUNT = "mount"
        private const val EXTRA_ETA = "eta"
        private const val EXTRA_SIM = "sim"
        private const val EXTRA_SIM_SPEED = "sim_speed"

        fun start(
            context: Context,
            mount: MountType,
            etaMin: Int,
            simulation: SampleJourneys.Scenario? = null,
            simSpeed: Int = 1,
        ) {
            val i = Intent(context, JourneyTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MOUNT, mount.name)
                .putExtra(EXTRA_ETA, etaMin)
                .putExtra(EXTRA_SIM, simulation?.name)
                .putExtra(EXTRA_SIM_SPEED, simSpeed)
            ContextCompat.startForegroundService(context, i)
        }

        fun nextPhase(context: Context) =
            context.startService(Intent(context, JourneyTrackingService::class.java).setAction(ACTION_NEXT_PHASE))

        fun markPothole(context: Context) =
            context.startService(Intent(context, JourneyTrackingService::class.java).setAction(ACTION_MARK_POTHOLE))

        fun markSpeedHump(context: Context) =
            context.startService(Intent(context, JourneyTrackingService::class.java).setAction(ACTION_MARK_HUMP))

        fun cancel(context: Context) =
            context.startService(Intent(context, JourneyTrackingService::class.java).setAction(ACTION_CANCEL))
    }
}
