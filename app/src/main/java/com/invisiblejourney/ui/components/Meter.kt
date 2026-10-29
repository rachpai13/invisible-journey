package com.invisiblejourney.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import com.invisiblejourney.R
import com.invisiblejourney.ui.Dimens
import kotlin.math.abs
import kotlin.math.ceil
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.invisiblejourney.domain.model.MeterState
import com.invisiblejourney.domain.model.Phase
import com.invisiblejourney.domain.model.SourceTag
import com.invisiblejourney.domain.model.StopType
import com.invisiblejourney.domain.processor.IndianAqi
import com.invisiblejourney.domain.processor.Mq2
import com.invisiblejourney.ui.Format
import com.invisiblejourney.ui.Num
import com.invisiblejourney.ui.Palette
import com.invisiblejourney.ui.PhaseText
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The signature element: a half-circle gauge in minutes. A grey track with a yellow arc that fills
 * as time passes, minute ticks inside it (longer every 5 minutes, labelled), and the time left on
 * the ETA in the middle. The left half runs from 0 to the promised ETA (marked at the top with a
 * small triangle), the right half is the same span again as buffer; the scale only grows if a ride
 * runs past both.
 *
 * It fills whatever box it is given (labels and text are drawn on the canvas and scale with the
 * gauge), so the live screen can hand it "the rest of the height" without anything overflowing.
 */
@Composable
fun MeterDial(meter: MeterState, promisedEtaMin: Int, modifier: Modifier = Modifier) {
    val elapsedMin = meter.elapsedMs / 60_000f
    val scaleMax = max(promisedEtaMin * 2f, ceil(elapsedMin / 5f) * 5f)
    val leftMs = promisedEtaMin * 60_000L - meter.elapsedMs
    val late = leftMs < 0
    val time = Format.clock(abs(leftMs))
    val measurer = rememberTextMeasurer()

    Canvas(modifier.semantics { contentDescription = "${Format.etaDelta(meter.elapsedMs, promisedEtaMin)}. ETA shown $promisedEtaMin minutes." }) {
        // A half circle is 2r wide and r tall; leave room above for the ETA marker and below for the caption.
        val top = 16.dp.toPx()
        val below = 22.dp.toPx()
        val r = min(size.width / 2 - 4.dp.toPx(), size.height - top - below)
        if (r <= 0f) return@Canvas
        val track = r * 0.09f
        val center = Offset(size.width / 2, top + r + (size.height - top - below - r) / 2)
        val trackR = r - track / 2
        val arcTopLeft = Offset(center.x - trackR, center.y - trackR)
        val arcSize = Size(2 * trackR, 2 * trackR)
        fun angleFor(min: Float) = 180f + 180f * (min / scaleMax).coerceIn(0f, 1f)
        fun at(angle: Float, radius: Float): Offset {
            val a = Math.toRadians(angle.toDouble())
            return Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
        }

        drawArc(Palette.SurfaceHigh, 180f, 180f, false, arcTopLeft, arcSize, style = Stroke(track, cap = StrokeCap.Round))
        if (elapsedMin > 0f) {
            drawArc(Palette.AutoYellow, 180f, angleFor(elapsedMin) - 180f, false, arcTopLeft, arcSize, style = Stroke(track, cap = StrokeCap.Round))
        }

        // Ticks every minute inside the track, longer and labelled every 5.
        val tickOuter = r - track - 6.dp.toPx()
        for (m in 0..scaleMax.toInt()) {
            val angle = angleFor(m.toFloat())
            val major = m % 5 == 0
            val len = if (major) r * 0.09f else r * 0.05f
            drawLine(
                Color.White.copy(alpha = if (major) 0.85f else 0.4f),
                at(angle, tickOuter), at(angle, tickOuter - len),
                strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
            )
            if (major) {
                val eta = m == promisedEtaMin
                val text = measurer.measure(
                    "$m",
                    TextStyle(fontSize = 11.sp, color = if (eta) Color.White else Palette.Muted, fontWeight = if (eta) FontWeight.Bold else FontWeight.Normal),
                )
                val p = at(angle, tickOuter - len - 12.dp.toPx())
                drawText(text, topLeft = Offset(p.x - text.size.width / 2f, p.y - text.size.height / 2f))
            }
        }

        // Small triangle just outside the track, pointing at the promised ETA.
        val etaAngle = angleFor(promisedEtaMin.toFloat())
        val a = Math.toRadians(etaAngle.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        val tip = at(etaAngle, r + 3.dp.toPx())
        val back = at(etaAngle, r + 11.dp.toPx())
        val half = 5.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(back.x - uy * half, back.y + ux * half)
                lineTo(back.x + uy * half, back.y - ux * half)
                close()
            },
            Color.White,
        )

        // Time left on the ETA (or how late), sitting on the gauge's baseline, caption under it.
        val timeLayout = measurer.measure(time, Num.Hero.copy(fontSize = (r * 0.26f).toSp(), color = if (late) Palette.Danger else Color.White))
        drawText(timeLayout, topLeft = Offset(center.x - timeLayout.size.width / 2f, center.y - timeLayout.size.height * 0.85f))
        val captionLayout = measurer.measure(
            if (late) "late" else "left",
            TextStyle(fontSize = 12.sp, color = if (late) Palette.Danger else Palette.Muted, fontWeight = FontWeight.Medium),
        )
        drawText(captionLayout, topLeft = Offset(center.x - captionLayout.size.width / 2f, center.y + timeLayout.size.height * 0.15f))
    }
}

/** Where the journey is: To store → Pickup → Riding → Drop. Fixed height; nothing reflows. */
@Composable
fun PhaseStepper(phase: Phase, modifier: Modifier = Modifier) {
    val steps = PhaseText.steps
    val current = when (phase) {
        Phase.DELIVERED -> steps.size
        Phase.CANCELLED -> -1
        else -> steps.indexOf(phase)
    }
    val dot = 26.dp
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { contentDescription = "Step: ${phase.label}" }
            .drawBehind {
                // connector line through the dot centres
                val step = size.width / steps.size
                val y = dot.toPx() / 2
                for (i in 0 until steps.size - 1) {
                    val done = i < current
                    drawLine(
                        if (done) Palette.AutoGreen else Palette.Line,
                        Offset(step * (i + 0.5f) + dot.toPx() / 2 + 4.dp.toPx(), y),
                        Offset(step * (i + 1.5f) - dot.toPx() / 2 - 4.dp.toPx(), y),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
            },
    ) {
        steps.forEachIndexed { i, p ->
            val done = i < current
            val now = i == current
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(dot)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> Palette.AutoGreen
                                now -> Palette.AutoYellow
                                else -> Palette.SurfaceHigh
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    } else {
                        Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (now) Palette.Ink else Palette.Muted)
                    }
                }
                Spacer(Modifier.height(4.dp))
                FitText(
                    PhaseText.short(p),
                    maxSize = 13.sp,
                    minSize = 9.sp,
                    fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                    color = if (now) Color.White else Palette.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/** A fixed-size tile: small label, big value, one-line note. Text shrinks rather than wrapping. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    accent: Color = Color.White,
    tone: Color? = null,
) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = tone?.copy(alpha = 0.2f) ?: Palette.Surface) {
        Box {
            if (tone != null) Box(Modifier.fillMaxWidth().height(3.dp).background(tone))
            Column(Modifier.fillMaxHeight().padding(horizontal = 10.dp, vertical = 7.dp)) {
                FitText(label.uppercase(Locale.US), maxSize = 11.sp, minSize = 8.sp, color = Palette.Muted, fontWeight = FontWeight.Bold)
                // The value takes whatever height is left and shrinks to it, so the note below always shows.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    FitText(value, maxSize = 26.sp, minSize = 11.sp, color = accent, style = Num.Big)
                }
                FitText(sub ?: "", maxSize = 12.sp, minSize = 8.sp, color = Palette.Muted)
            }
        }
    }
}

/** Two-column grid of fixed-height tiles (replay screen). */
@Composable
fun StatGrid(meter: MeterState, modifier: Modifier = Modifier, tileHeight: Dp = 88.dp) {
    val rows = listOf(
        listOf(
            Triple("Distance", Format.km(meter.distanceKm), null),
            Triple("Speed", "${meter.speedKmh.roundToInt()} km/h", "avg ${meter.avgMovingKmh.roundToInt()} moving"),
        ),
        listOf(
            Triple("Moving", Format.clock(meter.movingMs), null),
            Triple("Waiting", Format.clock(meter.waitingMs), "${meter.stopCount} stops on the road"),
        ),
        listOf(
            Triple("Potholes", meter.potholeCount.toString(), "${meter.speedHumpCount} speed breakers"),
            Triple("Traffic", congestionValue(meter), congestionNote(meter)),
        ),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, sub) ->
                    StatTile(label, value, Modifier.weight(1f).height(tileHeight), sub)
                }
            }
        }
    }
}

private fun congestionValue(m: MeterState) = m.congestionIndex?.let { String.format(Locale.US, "%.1f×", it) } ?: "—"

private fun congestionNote(m: MeterState) = when (m.congestionSource) {
    null -> "no data yet"
    SourceTag.ESTIMATED -> "estimated"
    else -> "vs empty roads"
}

/**
 * "Exposure" strip: what the rider is out in right now, as five yellow-ringed icon circles with a
 * short value under each. Always the same five, in the same order; unknown values show "—" instead
 * of appearing later and pushing the layout around.
 */
@Composable
fun ConditionStrip(meter: MeterState, modifier: Modifier = Modifier) {
    val feels = meter.feelsLikeC ?: meter.tempC
    val aqi = meter.aqiNow
    val traffic = meter.congestionIndex

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Condition(R.drawable.ic_cond_heat, "Feels like", feels?.let { "${it.roundToInt()}°" } ?: "—")
        Condition(R.drawable.ic_cond_rain, "Rain", if (feels == null) "—" else if (meter.raining) "Rain" else "Dry")
        // The bike's own gas sensor (ESP32 MQ-2) beats the city-grid AQI when it's connected.
        val gas = meter.gasPpm
        if (gas != null) {
            Condition(R.drawable.ic_cond_air, "Exhaust and smoke, ${Mq2.level(gas)}, estimated", "${gas.roundToInt()} ppm")
        } else {
            Condition(R.drawable.ic_cond_air, "Air quality", aqi?.let { "AQI $it" } ?: "—")
        }
        Condition(
            R.drawable.ic_cond_traffic, "Traffic",
            when {
                traffic == null -> "—"
                meter.congestionSource == SourceTag.ESTIMATED -> "${congestionValue(meter)} est."
                else -> congestionValue(meter)
            },
        )
        Condition(R.drawable.ic_cond_road, "Road", meter.roughnessNow?.label ?: "—")
    }
}

@Composable
private fun Condition(icon: Int, name: String, value: String) {
    Column(
        Modifier.width(60.dp).semantics(mergeDescendants = true) { contentDescription = "$name: $value" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Palette.Panel)
                .border(1.5.dp, Palette.AutoYellow, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(5.dp))
        FitText(value, maxSize = 11.sp, minSize = 8.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** One bar of [EffortChart]: its value as text, and how full the bar is against [cap]. */
private class Effort(val label: String, val name: String, val value: String, val amount: Float, val cap: Float)

/**
 * The ride so far as a small bar chart: distance, speed, waiting, travelling and potholes. The
 * metrics have different units, so each bar is scaled against its own everyday ceiling (5 km,
 * 40 km/h, the promised ETA for both times, 10 potholes); the exact value of the selected bar is
 * shown at the top. Tap a bar to select it.
 */
@Composable
fun EffortChart(meter: MeterState, promisedEtaMin: Int, modifier: Modifier = Modifier) {
    val etaMs = promisedEtaMin * 60_000f
    val bars = listOf(
        Effort("Dist", "Distance", Format.km(meter.distanceKm), meter.distanceKm, 5f),
        Effort("Speed", "Speed", "${meter.speedKmh.roundToInt()} km/h", meter.speedKmh, 40f),
        Effort("Wait", "Waiting", Format.clock(meter.waitingMs), meter.waitingMs.toFloat(), etaMs),
        Effort("Travel", "Travelling", Format.clock(meter.movingMs), meter.movingMs.toFloat(), etaMs),
        Effort("Holes", "Potholes", meter.potholeCount.toString(), meter.potholeCount.toFloat(), 10f),
    )
    var selected by remember { mutableIntStateOf(0) }
    val pick = bars[selected]

    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.Radius), color = Palette.Panel) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pick.value, style = Num.Big.copy(fontSize = 22.sp), maxLines = 1, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh).padding(horizontal = 12.dp, vertical = 4.dp),
                ) { Text(pick.name, fontSize = 12.sp, color = Color.White) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bars.forEachIndexed { i, b ->
                    val on = i == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { selected = i }
                            .semantics(mergeDescendants = true) { contentDescription = "${b.name}: ${b.value}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight((b.amount / b.cap).coerceIn(0.06f, 1f))
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (on) Brush.verticalGradient(listOf(Palette.YellowSoft, Palette.AutoYellow))
                                        else SolidColor(Palette.SurfaceHigh),
                                    ),
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(b.label, fontSize = 11.sp, color = if (on) Color.White else Palette.Muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Stacked bar: where the time went. Every category is always listed so rows never pop in. */
@Composable
fun TimeBreakdownBar(movingMs: Long, waitingByType: Map<StopType, Long>, modifier: Modifier = Modifier) {
    val parts = buildList {
        add(Triple("Riding", movingMs, Palette.AutoGreen))
        StopType.entries.forEach { t -> add(Triple(t.label, waitingByType[t] ?: 0L, Palette.stop(t))) }
    }
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)) {
            parts.forEach { (_, ms, color) ->
                if (ms > 0) Box(Modifier.weight(ms.toFloat() / total).fillMaxHeight().background(color))
            }
        }
        Spacer(Modifier.height(10.dp))
        parts.forEach { (label, ms, color) ->
            val empty = ms <= 0
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(30.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (empty) Palette.SurfaceHigh else color))
                Spacer(Modifier.width(10.dp))
                Text(
                    label, modifier = Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (empty) Palette.Faint else Color.White,
                )
                Text(
                    if (empty) "—" else Format.duration(ms), style = Num.Small,
                    color = if (empty) Palette.Faint else Palette.Muted, maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.US),
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
        color = Palette.AutoYellow,
        fontSize = 13.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
