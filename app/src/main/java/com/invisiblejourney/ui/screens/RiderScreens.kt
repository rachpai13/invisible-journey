package com.invisiblejourney.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.invisiblejourney.R
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.invisiblejourney.domain.model.Phase
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.invisiblejourney.container
import com.invisiblejourney.domain.model.MountType
import com.invisiblejourney.service.ActiveJourney
import com.invisiblejourney.service.ActiveJourneyState
import com.invisiblejourney.service.JourneyTrackingService
import com.invisiblejourney.ui.Dimens
import com.invisiblejourney.ui.Format
import com.invisiblejourney.ui.Num
import com.invisiblejourney.ui.Palette
import com.invisiblejourney.ui.PhaseText
import com.invisiblejourney.ui.components.BigButton
import com.invisiblejourney.ui.components.ConditionStrip
import com.invisiblejourney.ui.components.EffortChart
import com.invisiblejourney.ui.components.FitText
import com.invisiblejourney.ui.components.MeterDial
import com.invisiblejourney.ui.components.PhaseStepper
import com.invisiblejourney.ui.components.SwipeToConfirm
import com.invisiblejourney.ui.components.TopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** ETAs a quick-commerce app typically promises; the slider snaps to these. */
private val ETA_STOPS = listOf(5, 10, 15, 20, 25, 30)

// --- Rider home -------------------------------------------------------------------------------

/**
 * Two sections: where the phone is, then the ETA the customer was shown. Both start unanswered on
 * every visit, so the rider confirms them before each ride; Start stays off until both are answered.
 */
@Composable
fun RiderHomeScreen(onBack: () -> Unit, onLive: () -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val active by ActiveJourney.state.collectAsState()

    var granted by remember { mutableStateOf(hasLocation(context)) }
    // Saveable, so the answers survive a trip to History and back (a fresh visit from home starts blank).
    var mount by rememberSaveable { mutableStateOf<MountType?>(null) }
    var eta by rememberSaveable { mutableStateOf<Int?>(null) }

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocation(context)
    }

    val running = active?.let { !it.finished } == true

    Column(Modifier.fillMaxSize()) {
        TopBar("Rider", onBack) {
            IconButton(onClick = onHistory, modifier = Modifier.size(52.dp)) {
                Icon(painterResource(R.drawable.ic_history), contentDescription = "Past journeys", modifier = Modifier.size(24.dp))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            active?.takeIf { running }?.let { RunningBanner(it, onLive) }

            Panel("Phone placement") {
                MountType.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            OptionTile(
                                m.label,
                                selected = mount == m,
                                onClick = { mount = m; c.settings.mountType = m },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            Panel("ETA shown to the customer", trailing = eta?.let { "$it min" } ?: "—") {
                StopSlider(
                    stops = ETA_STOPS,
                    value = eta,
                    onChange = { eta = it; c.settings.promisedEtaMin = it },
                )
            }

            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.Faint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Records only while a journey runs; you stop it any time. No name, no phone number, no audio.",
                    color = Palette.Faint, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Primary action lives at the bottom, under the thumb, and is always the same size.
        Box(Modifier.padding(horizontal = Dimens.Gutter, vertical = 12.dp)) {
            val ready = mount != null && eta != null
            when {
                !granted -> BigButton(
                    "Allow location", sub = "Needed to record the route", icon = Icons.Filled.LocationOn,
                    height = Dimens.HeroButton, onClick = { launcher.launch(permissions) },
                )
                running -> BigButton(
                    "Open journey meter", icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    height = Dimens.HeroButton, container = Palette.AutoGreen, content = Color.White, onClick = onLive,
                )
                else -> BigButton(
                    "Start journey",
                    sub = if (ready) null else "Choose phone placement and ETA first",
                    height = Dimens.HeroButton,
                    enabled = ready,
                    onClick = {
                        val m = mount ?: return@BigButton
                        val e = eta ?: return@BigButton
                        ActiveJourney.clearFinished()
                        JourneyTrackingService.start(context, m, e)
                        onLive()
                    },
                )
            }
        }
    }
}

/** Past rides, newest first; reached from the history icon on rider home. */
@Composable
fun RiderHistoryScreen(onBack: () -> Unit, onOpenStory: (String) -> Unit) {
    val c = LocalContext.current.container
    val journeys by c.store.items.collectAsState()
    LaunchedEffect(Unit) { c.store.refresh() }
    val mine = journeys.filter { !it.isSample }
    val df = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Your journeys", onBack)
        if (mine.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(Dimens.Gutter), contentAlignment = Alignment.Center) {
                Text("No journeys yet. Start one from the rider screen.", color = Palette.Muted, textAlign = TextAlign.Center)
            }
            return@Column
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            mine.forEach { j ->
                JourneyRow(
                    title = df.format(Date(j.startedAt)),
                    sub = j.actualMin?.let { "${Format.minutes(it)} · ETA ${j.promisedEtaMin} min" } ?: "Not completed",
                    late = j.actualMin?.let { it > j.promisedEtaMin },
                    onClick = { onOpenStory(j.id) },
                )
            }
        }
    }
}

/** Dark grey rounded box that groups a section: a title (optionally a value on the right), then its controls. */
@Composable
internal fun Panel(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.Radius), color = Palette.Panel, border = BorderStroke(1.dp, Palette.Line)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (trailing != null) Text(trailing, style = Num.Small.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = Palette.AutoYellow)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

/** Compact single-choice tile used inside a [Panel] (and for the scan confirmation on the story screen). */
@Composable
internal fun OptionTile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, textSize: TextUnit = 15.sp) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(Dimens.Touch),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Palette.AutoYellow.copy(alpha = 0.16f) else Palette.SurfaceHigh,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Palette.AutoYellow else Palette.Line),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (selected) Palette.AutoYellow else Color.Transparent)
                    .border(if (selected) 0.dp else 1.5.dp, Palette.Faint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(10.dp))
            FitText(label, maxSize = textSize, minSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Discrete slider: a thick rounded track with a dot at each stop and a large thumb that snaps to the
 * nearest stop on tap or drag. Before anything is chosen ([value] null) the thumb rests grey on the first stop.
 */
@Composable
private fun StopSlider(stops: List<Int>, value: Int?, onChange: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    // Read through state so the gesture handlers keep running across value changes mid-drag.
    val current by rememberUpdatedState(value)
    val emit by rememberUpdatedState(onChange)
    val index = value?.let { stops.indexOf(it) }?.takeIf { it >= 0 }
    val fraction by animateFloatAsState((index ?: 0) / (stops.size - 1f), label = "thumb")
    val thumbR = 16.dp
    val ring = 4.dp

    fun pick(x: Float, width: Float, inset: Float) {
        val f = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
        val stop = stops[(f * (stops.size - 1)).roundToInt()]
        if (stop != current) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            emit(stop)
        }
    }

    val labels = rememberTextMeasurer()

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(Dimens.Touch + 18.dp)
            .pointerInput(stops) {
                val inset = (thumbR + ring).toPx()
                detectTapGestures { pick(it.x, size.width.toFloat(), inset) }
            }
            .pointerInput(stops) {
                val inset = (thumbR + ring).toPx()
                detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width.toFloat(), inset) }
            },
    ) {
        val inset = (thumbR + ring).toPx()
        val y = Dimens.Touch.toPx() / 2
        val start = Offset(inset, y)
        val end = Offset(size.width - inset, y)
        val thumb = Offset(inset + (size.width - 2 * inset) * fraction, y)
        val track = 14.dp.toPx()
        drawLine(Palette.SurfaceHigh, start, end, strokeWidth = track, cap = StrokeCap.Round)
        if (index != null) drawLine(Palette.AutoYellow, start, thumb, strokeWidth = track, cap = StrokeCap.Round)
        stops.forEachIndexed { i, s ->
            val x = inset + (size.width - 2 * inset) * i / (stops.size - 1f)
            val filled = index != null && x <= thumb.x
            drawCircle(if (filled) Palette.Ink.copy(alpha = 0.55f) else Palette.Faint, radius = 3.dp.toPx(), center = Offset(x, y))
            // Stop label, centred under its dot.
            val picked = s == value
            val text = labels.measure(
                "$s",
                TextStyle(fontSize = 12.sp, color = if (picked) Color.White else Palette.Faint, fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal),
            )
            drawText(text, topLeft = Offset(x - text.size.width / 2f, Dimens.Touch.toPx()))
        }
        drawCircle(Palette.Panel, radius = (thumbR + ring).toPx(), center = thumb)
        drawCircle(if (index != null) Palette.AutoYellow else Palette.Faint, radius = thumbR.toPx(), center = thumb)
    }
}

@Composable
private fun RunningBanner(s: ActiveJourneyState, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(top = 4.dp),
        shape = RoundedCornerShape(Dimens.Radius),
        color = Palette.AutoGreen.copy(alpha = 0.18f),
        border = BorderStroke(2.dp, Palette.AutoGreen),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FitText(if (s.simulated) "Demo ride running" else "Journey running", maxSize = 17.sp, fontWeight = FontWeight.Bold)
                FitText(s.meter.phase.label, maxSize = 14.sp, color = Palette.Muted)
            }
            Text(Format.clock(s.meter.elapsedMs), style = Num.Big, maxLines = 1)
        }
    }
}

/** One saved journey in a list. Fixed height; long titles ellipsize instead of growing the row. */
@Composable
internal fun JourneyRow(title: String, sub: String, onClick: () -> Unit, late: Boolean? = null, tag: (@Composable () -> Unit)? = null) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = RoundedCornerShape(14.dp),
        color = Palette.Surface,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // late / on time marker, reserved even when unknown
            Box(
                Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(
                    when (late) {
                        true -> Palette.Danger
                        false -> Palette.AutoGreen
                        null -> Palette.Line
                    },
                ),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = Palette.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tag != null) {
                Spacer(Modifier.width(8.dp))
                tag()
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(28.dp))
        }
    }
}

// --- Live meter -------------------------------------------------------------------------------

/**
 * The screen a rider glances at on the handlebar. Nothing scrolls and every block has a fixed
 * height; only the dial flexes to use whatever space the phone has left.
 */
@Composable
fun LiveJourneyScreen(onBack: () -> Unit, onFinished: (String) -> Unit) {
    val context = LocalContext.current
    val state by ActiveJourney.state.collectAsState()
    var confirmCancel by remember { mutableStateOf(false) }
    val promisedEta = remember { context.container.settings.promisedEtaMin }

    // Keep the screen on while the meter is open, so the bump buttons and volume keys work.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state?.finished, state?.journeyId) {
        val s = state
        if (s != null && s.finished) onFinished(s.journeyId)
    }

    val s = state
    Column(Modifier.fillMaxSize()) {
        TopBar(if (s?.simulated == true) "Demo ride" else "Live journey", onBack) {
            IconButton(onClick = { confirmCancel = true }, enabled = s != null && !s.finished, modifier = Modifier.size(52.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel journey", tint = Palette.Ink, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (s == null || s.finished) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = Palette.AutoYellow)
                Spacer(Modifier.height(16.dp))
                Text(if (s?.finished == true) "Saving journey…" else "Starting sensors…", color = Palette.Muted)
            }
            return@Column
        }

        val m = s.meter
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhaseStepper(m.phase)
            MeterDial(m, promisedEta, Modifier.weight(1f).fillMaxWidth())
            ConditionStrip(m)
            EffortChart(m, promisedEta)
            // Potholes and speed breakers are still marked by hand with the volume keys (see MainActivity).

            if (s.simulated) {
                Box(
                    Modifier.fillMaxWidth().height(Dimens.HeroButton),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(Dimens.HeroButton / 2), color = Palette.SurfaceHigh) {
                        Box(contentAlignment = Alignment.Center) {
                            FitText("Demo · steps move on their own", maxSize = 17.sp, color = Palette.Muted)
                        }
                    }
                }
            } else {
                key(m.phase) {
                    SwipeToConfirm(
                        "Swipe · ${PhaseText.action(m.phase)}",
                        onConfirm = { JourneyTrackingService.nextPhase(context) },
                        color = if (m.phase == Phase.ARRIVED) Palette.AutoGreen else Palette.AutoYellow,
                    )
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this journey?") },
            text = { Text("Recording stops. What was recorded so far is kept, but there'll be no delivery story.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    JourneyTrackingService.cancel(context)
                }, modifier = Modifier.height(48.dp)) { Text("Cancel journey", color = Palette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }, modifier = Modifier.height(48.dp)) { Text("Keep recording") }
            },
        )
    }
}

