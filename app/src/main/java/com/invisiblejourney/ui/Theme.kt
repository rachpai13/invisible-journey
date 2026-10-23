package com.invisiblejourney.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.invisiblejourney.domain.model.Phase
import com.invisiblejourney.domain.model.Roughness
import com.invisiblejourney.domain.model.StopType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Auto-rickshaw palette: meter yellow, body green, black. Tuned for a phone on a handlebar in
 * daylight (PLS LCD on the Galaxy M13): pure black background and bright, saturated accents.
 */
object Palette {
    // Brand palette: near-black, meter yellow, and a 40% tint of the yellow (yellow over white).
    val Ink = Color(0xFF0A0A0A)
    val AutoYellow = Color(0xFFFFC72C)
    val YellowSoft = Color(0xFFFFE9AB)

    val AutoGreen = Color(0xFF43A047)
    val Surface = Color(0xFF161616)
    val SurfaceHigh = Color(0xFF262626)
    /** Dark grey box that groups one section of a screen (title + its controls). */
    val Panel = Color(0xFF1A1A1A)
    val Line = Color(0xFF3A3A3A)
    val Muted = Color(0xFFB3B3B3)
    val Faint = Color(0xFF6E6E6E)
    val Danger = Color(0xFFFF4D4D)
    val Warn = Color(0xFFFF9800)
    val Info = Color(0xFF4FA8FF)
    val Pothole = Color(0xFF8D6E63)

    fun speed(kmh: Float): Color = when {
        kmh < 5f -> Danger
        kmh < 12f -> Warn
        kmh < 22f -> AutoYellow
        else -> AutoGreen
    }

    fun stop(type: StopType): Color = when (type) {
        StopType.STORE_WAIT -> Color(0xFFBA68C8)
        StopType.SIGNAL_WAIT -> Danger
        StopType.TRAFFIC_STOP -> Warn
        StopType.GATE_OR_DOOR_WAIT -> Info
    }

    fun roughness(r: Roughness): Color = when (r) {
        Roughness.SMOOTH -> AutoGreen
        Roughness.FAIR -> AutoYellow
        Roughness.ROUGH -> Warn
        Roughness.VERY_ROUGH -> Danger
    }
}

/** Sizes shared by every screen. Touch targets are sized for gloved thumbs on a moving bike. */
object Dimens {
    val Gutter = 16.dp
    val Gap = 10.dp
    val TopBar = 56.dp
    val Touch = 56.dp
    val ButtonHeight = 60.dp
    val HeroButton = 72.dp
    val Radius = 16.dp
}

/** Tabular digits: numbers keep the same width as they tick, so nothing next to them jumps. */
object Num {
    val Hero = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val Big = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val Small = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
}

@Composable
fun InvisibleJourneyTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.AutoYellow,
            onPrimary = Palette.Ink,
            secondary = Palette.AutoGreen,
            onSecondary = Color.White,
            background = Palette.Ink,
            onBackground = Color.White,
            surface = Palette.Surface,
            onSurface = Color.White,
            surfaceVariant = Palette.SurfaceHigh,
            onSurfaceVariant = Palette.Muted,
            outline = Palette.Line,
            error = Palette.Danger,
        ),
        typography = base.copy(
            bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
            labelLarge = base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}

object Format {
    fun km(km: Float): String = String.format(Locale.US, "%.1f km", km)

    /** 0:42, 4:05, 1:02:10 */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "45 s", "4 min", "1 h 5 min" */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${(s / 60f).roundToInt()} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    fun minutes(min: Float): String =
        if (min >= 10f) "${min.roundToInt()} min" else String.format(Locale.US, "%.1f min", min)

    /** Time left on the promised ETA ("3:20 left") or how far past it ("+1:05 late"). */
    fun etaDelta(elapsedMs: Long, promisedEtaMin: Int): String {
        val delta = promisedEtaMin * 60_000L - elapsedMs
        return if (delta >= 0) "${clock(delta)} left" else "+${clock(abs(delta))} late"
    }
}

/** Short labels for the four steps of a delivery, used by the stepper and the swipe action. */
object PhaseText {
    val steps = listOf(Phase.ASSIGNED, Phase.AT_STORE, Phase.EN_ROUTE, Phase.ARRIVED)

    fun short(phase: Phase): String = when (phase) {
        Phase.ASSIGNED -> "To store"
        Phase.AT_STORE -> "Pickup"
        Phase.EN_ROUTE -> "Riding"
        Phase.ARRIVED -> "Drop"
        Phase.DELIVERED -> "Done"
        Phase.CANCELLED -> "Cancelled"
    }

    /** What the rider confirms to move on from [phase]. */
    fun action(phase: Phase): String = when (phase) {
        Phase.ASSIGNED -> "Reached store"
        Phase.AT_STORE -> "Picked up · ride"
        Phase.EN_ROUTE -> "Reached drop"
        Phase.ARRIVED -> "Delivered"
        Phase.DELIVERED, Phase.CANCELLED -> "Done"
    }
}
