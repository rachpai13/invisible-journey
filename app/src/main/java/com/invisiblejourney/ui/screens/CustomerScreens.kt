package com.invisiblejourney.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.material3.IconButton
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.invisiblejourney.R
import com.invisiblejourney.container
import com.invisiblejourney.domain.model.JourneyRecord
import com.invisiblejourney.domain.model.MeterState
import com.invisiblejourney.domain.model.StopType
import com.invisiblejourney.ui.Dimens
import com.invisiblejourney.ui.Format
import com.invisiblejourney.ui.Num
import com.invisiblejourney.ui.Palette
import com.invisiblejourney.ui.components.BigButton
import com.invisiblejourney.ui.components.Card
import com.invisiblejourney.ui.components.ConditionStrip
import com.invisiblejourney.ui.components.FitText
import com.invisiblejourney.ui.components.JourneyMap
import com.invisiblejourney.ui.components.JourneyReceipt
import com.invisiblejourney.ui.components.MapColors
import com.invisiblejourney.ui.components.MeterDial
import com.invisiblejourney.ui.components.PhaseStepper
import com.invisiblejourney.ui.components.ReceiptQr
import com.invisiblejourney.ui.components.SectionTitle
import com.invisiblejourney.ui.components.StatGrid
import com.invisiblejourney.ui.components.Tag
import com.invisiblejourney.ui.components.TimeBreakdownBar
import com.invisiblejourney.ui.components.TopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun Loading(text: String = "Loading…") {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Palette.AutoYellow)
        Spacer(Modifier.height(16.dp))
        Text(text, color = Palette.Muted)
    }
}

// --- Library ----------------------------------------------------------------------------------

@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val store = context.container.store
    val items by store.items.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    // Long-press a journey to start selecting; taps then toggle. Samples are built in and can't be deleted.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    BackHandler(enabled = selecting) { selected = emptySet() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { store.importFrom(uri) }
                .onSuccess { onOpen(it) }
                .onFailure { Toast.makeText(context, "Couldn't import: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(Unit) {
        store.refresh()
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            TopBar("${selected.size} selected", onBack = { selected = emptySet() }) {
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected journeys", modifier = Modifier.size(26.dp))
                }
            }
        } else {
            TopBar("See a journey", onBack)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            Text(
                "Open a journey your rider shared with you, or try a sample to see how the meter works.",
                color = Palette.Muted,
            )
            if (loading && items.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.AutoYellow, modifier = Modifier.size(28.dp))
                }
            }
            val df = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
            items.forEach { j ->
                JourneyRow(
                    title = j.title,
                    sub = buildString {
                        append(df.format(Date(j.startedAt)))
                        j.actualMin?.let { append(" · ${Format.minutes(it)} of ${j.promisedEtaMin}") }
                    },
                    late = j.actualMin?.let { it > j.promisedEtaMin },
                    tag = if (j.isSample) ({ Tag("SAMPLE") }) else null,
                    onClick = {
                        when {
                            !selecting -> onOpen(j.id)
                            !j.isSample -> toggle(j.id)
                        }
                    },
                    onLongClick = if (j.isSample) null else ({ toggle(j.id) }),
                    selecting = selecting,
                    selected = j.id in selected,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.padding(horizontal = Dimens.Gutter, vertical = 12.dp)) {
            BigButton(
                "Open a shared journey file",
                sub = ".journey.json from your rider",
                primary = false,
                onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
            )
        }
    }

    if (confirmDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (n == 1) "Delete this journey?" else "Delete $n journeys?") },
            text = { Text("This removes the recordings from this phone. Files you already shared aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val ids = selected
                    scope.launch {
                        ids.forEach { store.delete(it) }
                        selected = emptySet()
                    }
                }, modifier = Modifier.height(48.dp)) { Text("Delete", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, modifier = Modifier.height(48.dp)) { Text("Keep") } },
        )
    }
}

// --- Replay (the customer's view of a journey) -----------------------------------------------

private val SPEEDS = listOf(1, 10, 30, 60)

/** Latest snapshot at or before [t], with the clock interpolated so the dial moves smoothly. */
private fun meterAt(r: JourneyRecord, t: Long): MeterState? {
    val snaps = r.snapshots
    if (snaps.isEmpty()) return null
    var lo = 0
    var hi = snaps.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (snaps[mid].t <= t) lo = mid else hi = mid - 1
    }
    val s = snaps[lo]
    return if (s.phase.isFinal) s else s.copy(elapsedMs = (t - r.startedAt).coerceAtLeast(s.elapsedMs))
}

@Composable
fun ReplayScreen(journeyId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var record by remember { mutableStateOf<JourneyRecord?>(null) }
    LaunchedEffect(journeyId) { record = context.container.store.load(journeyId) }

    val r = record
    if (r == null) {
        Column(Modifier.fillMaxSize()) { TopBar("Journey lapse", onBack); Loading() }
        return
    }
    val start = r.startedAt
    val end = r.endedAt ?: r.snapshots.lastOrNull()?.t ?: start
    var t by remember(r) { mutableLongStateOf(start) }
    var playing by remember(r) { mutableStateOf(true) }
    var speed by remember { mutableIntStateOf(30) }

    LaunchedEffect(playing, speed, r) {
        while (playing && t < end) {
            delay(100)
            t = min(end, t + 100L * speed)
        }
        if (t >= end) playing = false
    }

    val meter = remember(r, t) { meterAt(r, t) }
    // Rebuilding the map overlays is the expensive part on a mid-range phone, so the map only
    // catches up about twice a second of real time, whatever the playback speed.
    val mapStep = 500L * speed
    val mapT = if (t >= end) end else start + ((t - start) / mapStep) * mapStep
    val track = remember(r, mapT) { r.track.takeWhile { it.t <= mapT } }
    val potholes = remember(r, mapT) { r.potholes.filter { it.t <= mapT } }
    val stops = remember(r, mapT) { r.stops.filter { it.end <= mapT } }
    val marks = remember(r, mapT) { r.marks.filter { it.t <= mapT } }

    Column(Modifier.fillMaxSize()) {
        TopBar("Journey lapse", onBack) { if (r.isSample) Tag("SAMPLE", modifier = Modifier.padding(end = 12.dp)) }
        if (meter == null) {
            Text("This journey has no meter data.", color = Palette.Muted, modifier = Modifier.padding(Dimens.Gutter))
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            Panel(r.title) {
                PlaybackBar(
                    playing = playing,
                    progress = if (end > start) (t - start).toFloat() / (end - start) else 1f,
                    position = Format.clock(t - start),
                    total = Format.clock(end - start),
                    speed = speed,
                    onToggle = {
                        if (t >= end) t = start
                        playing = !playing
                    },
                    onSpeed = { speed = it },
                    onSeek = { v ->
                        playing = false
                        t = start + ((end - start) * v).toLong()
                    },
                )
                JourneyMap(track, potholes, stops, Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)), marks = marks)
                MapLegend()
            }
            Panel("Where the time went") {
                TimeBreakdownBar(meter.movingMs, meter.waitingByType)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlaybackBar(
    playing: Boolean,
    progress: Float,
    position: String,
    total: String,
    speed: Int,
    onToggle: () -> Unit,
    onSpeed: (Int) -> Unit,
    onSeek: (Float) -> Unit,
) {
    Column {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onToggle,
                    modifier = Modifier.size(Dimens.Touch).semantics { contentDescription = if (playing) "Pause" else "Play" },
                    shape = CircleShape,
                    color = Palette.AutoYellow,
                    contentColor = Palette.Ink,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playing) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                repeat(2) { Box(Modifier.width(6.dp).height(20.dp).background(Palette.Ink)) }
                            }
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SPEEDS.forEach { s ->
                        val selected = speed == s
                        Surface(
                            onClick = { onSpeed(s) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) Palette.AutoYellow.copy(alpha = 0.2f) else Palette.SurfaceHigh,
                            contentColor = if (selected) Palette.AutoYellow else Color.White,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                FitText("$s×", maxSize = 15.sp, minSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Slider(
                value = progress,
                onValueChange = onSeek,
                colors = SliderDefaults.colors(thumbColor = Palette.AutoYellow, activeTrackColor = Palette.AutoYellow),
            )
            Row {
                Text(position, style = Num.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
                Text(total, style = Num.Small, color = Palette.Muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapLegend() {
    val items = listOf(
        "Fast" to MapColors.Fast, "Slow" to MapColors.Slow, "Signal" to MapColors.Signal,
        "Stopped" to MapColors.Stopped, "Pothole" to MapColors.Pothole,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(label, fontSize = 13.sp, color = Palette.Muted, maxLines = 1)
            }
        }
    }
}

// --- Story (post-delivery summary) -------------------------------------------------------------

@Composable
fun StoryScreen(journeyId: String, onBack: () -> Unit, onReplay: () -> Unit) {
    val context = LocalContext.current
    val store = context.container.store
    val scope = rememberCoroutineScope()
    var record by remember { mutableStateOf<JourneyRecord?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val settings = context.container.settings
    var scanned by remember(journeyId) { mutableStateOf(settings.isScanned(journeyId)) }
    LaunchedEffect(journeyId) { record = store.load(journeyId) }


    val r = record
    Column(Modifier.fillMaxSize()) {
        TopBar("Journey story", onBack) {
            if (r?.isSample == true) Tag("SAMPLE", modifier = Modifier.padding(end = 12.dp))
        }
        if (r == null) {
            Loading()
            return@Column
        }
        val s = r.summary
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter, vertical = 4.dp),
        ) {
            if (s == null) {
                Text("This journey wasn't completed, so there is no receipt.", color = Palette.Muted)
            } else {
                JourneyReceipt(r, s, scanned = scanned)
                Spacer(Modifier.height(12.dp))
                ReceiptQr(r, s)
            }
            Spacer(Modifier.height(8.dp))
        }
        // Actions at the bottom, same place on every story. The rider confirms the scan (the phone
        // can't tell on its own); that tears the stub off the receipt.
        Column(
            Modifier.padding(horizontal = Dimens.Gutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            if (s != null && !r.isSample) {
                // Same tile as the phone-placement options: ticks itself once tapped, and stays ticked.
                OptionTile(
                    "Customer scanned it",
                    selected = scanned,
                    onClick = { if (!scanned) { settings.markScanned(journeyId); scanned = true } },
                    modifier = Modifier.fillMaxWidth(),
                    textSize = 14.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)) {
                StoryAction(rememberVectorPainter(Icons.Filled.PlayArrow), "View journey lapse", onClick = onReplay, modifier = Modifier.weight(1f))
                if (!r.isSample) {
                    StoryAction(
                        rememberVectorPainter(Icons.Filled.Delete), "Delete this journey",
                        onClick = { confirmDelete = true }, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete journey?") },
            text = { Text("This removes the recording from this phone. Files you already shared aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        store.delete(journeyId)
                        onBack()
                    }
                }, modifier = Modifier.height(48.dp)) { Text("Delete", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, modifier = Modifier.height(48.dp)) { Text("Keep") } },
        )
    }
}

/** Story-screen button in the same style as the role cards on the welcome screen: icon in a circle, then the label. */
@Composable
private fun StoryAction(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Palette.Surface,
    content: Color = Color.White,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        border = if (container == Palette.Surface) BorderStroke(1.dp, Palette.Line) else null,
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(content.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            // One fixed size for every action button; long labels wrap to two lines instead of shrinking.
            Text(label, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
        }
    }
}
