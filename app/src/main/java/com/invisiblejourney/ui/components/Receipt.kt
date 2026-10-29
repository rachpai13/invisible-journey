package com.invisiblejourney.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.invisiblejourney.R
import com.invisiblejourney.domain.model.JourneyRecord
import com.invisiblejourney.domain.model.JourneySummary
import com.invisiblejourney.domain.model.SourceTag
import com.invisiblejourney.domain.model.StopType
import com.invisiblejourney.ui.Format
import com.invisiblejourney.ui.Num
import com.invisiblejourney.ui.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Paper colours: the receipt is the one light surface in the app, like a printed slip on a dark table.
private val Paper = Color(0xFFF4F4F2)
private val PaperShade = Color(0xFFE8E8E5)
private val InkMuted = Color(0xFF6B6B6B)
private val Notch = 10.dp

/** One numbered line of the receipt, with optional smaller detail lines under it. */
private class Item(val name: String, val value: String, val details: List<String> = emptyList(), val bold: Boolean = false)

/**
 * A finished journey as a till receipt: header with the app and journey number, the ETA promise,
 * numbered line items for the effort behind it, then a tear line and a stub with the total. Once
 * [scanned] the stub tears off along that line and hangs slightly askew; nothing else changes.
 * Missing data shows "—", never a made-up zero.
 */
@Composable
fun JourneyReceipt(record: JourneyRecord, summary: JourneySummary, scanned: Boolean, modifier: Modifier = Modifier) {
    val number = record.id.take(8).uppercase(Locale.US)
    val date = remember(record.startedAt) { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(record.startedAt)) }
    val items = receiptItems(summary, averageKmh(record), congestionEstimated(record))
    val gap by animateDpAsState(if (scanned) 8.dp else 0.dp, label = "tear gap")
    val tilt by animateFloatAsState(if (scanned) -2.5f else 0f, label = "tear tilt")

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Paper, TicketShape(notchTop = false, notchBottom = true))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ReceiptHeader(number, date, summary)
            Spacer(Modifier.height(10.dp))
            HorizontalRule()
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { i, item -> ReceiptLine(i + 1, item) }
            }
            Spacer(Modifier.height(10.dp))
            Text("No rider name, photo or phone number is recorded.", color = InkMuted, fontSize = 9.sp)
        }
        // The stub with the total, below the tear line. It comes away once the customer has scanned.
        Box(
            Modifier
                .padding(top = gap)
                .rotate(tilt)
                .fillMaxWidth()
                .background(Paper, TicketShape(notchTop = true, notchBottom = false))
                .drawBehind { tearLine() }
                .padding(12.dp),
        ) {
            TotalBox(summary, Modifier.clip(RoundedCornerShape(10.dp)))
        }
    }
}

/**
 * The QR code, in its own section under the receipt: plain text with the same summary, so any
 * phone camera can read it without this app.
 */
@Composable
fun ReceiptQr(record: JourneyRecord, summary: JourneySummary, modifier: Modifier = Modifier) {
    val number = record.id.take(8).uppercase(Locale.US)
    val qrText = remember(record.id) { qrPayload(number, summary, receiptItems(summary, averageKmh(record), congestionEstimated(record))) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Panel)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Scan to keep this journey", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("Point your camera at the code to save the summary.", color = Palette.Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        QrCode(qrText, Modifier.size(80.dp))
    }
}

/** Average moving speed as the meter last showed it (the engine's own number). */
private fun averageKmh(record: JourneyRecord): Float? = record.snapshots.lastOrNull()?.avgMovingKmh

private fun congestionEstimated(record: JourneyRecord) =
    record.congestion.isNotEmpty() && record.congestion.all { it.source == SourceTag.ESTIMATED }

@Composable
private fun ReceiptHeader(number: String, date: String, summary: JourneySummary) {
    val overMin = summary.actualMin - summary.promisedEtaMin
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_launcher), contentDescription = null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(8.dp))
        Text("The Invisible Journey", color = Palette.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("Journey № $number", color = InkMuted, fontSize = 10.sp)
            Text(date, color = InkMuted, fontSize = 10.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Your ${summary.promisedEtaMin}-minute delivery", color = Palette.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(3.dp))
    Text(
        if (overMin > 0.05f) "It took ${Format.minutes(summary.actualMin)}, ${Format.minutes(overMin)} over the ETA. Here is what the countdown didn't show."
        else "It arrived in ${Format.minutes(summary.actualMin)}, within the ETA. Here is what it took.",
        color = InkMuted, fontSize = 11.sp, lineHeight = 15.sp,
    )
}

@Composable
private fun TotalBox(summary: JourneySummary, modifier: Modifier = Modifier) {
    val overMin = summary.actualMin - summary.promisedEtaMin
    Column(modifier.fillMaxWidth().background(PaperShade).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total time", color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(Format.minutes(summary.actualMin), style = Num.Big.copy(fontSize = 17.sp), color = Palette.Ink)
        }
        Spacer(Modifier.height(4.dp))
        SmallRow("ETA shown to you", "${summary.promisedEtaMin} min")
        SmallRow(
            if (overMin > 0.05f) "Over the ETA" else "Within the ETA",
            if (overMin > 0.05f) "+${Format.minutes(overMin)}" else Format.minutes(abs(overMin)),
            valueColor = if (overMin > 0.05f) Palette.Danger else Palette.Ink,
        )
    }
}

@Composable
private fun HorizontalRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PaperShade))
}

private fun receiptItems(s: JourneySummary, avgKmh: Float?, congestionEstimated: Boolean): List<Item> {
    val waits = StopType.entries.mapNotNull { t ->
        s.waitingByTypeMin[t]?.takeIf { it >= 0.1f }?.let { "${t.label} · ${Format.minutes(it)}" }
    }
    return listOf(
        Item("Average speed", avgKmh?.let { "${it.roundToInt()} km/h" } ?: "—", listOf("while moving"), bold = true),
        Item("Distance ridden", Format.km(s.distanceKm), bold = true),
        Item("Time riding", Format.minutes(s.movingMin), bold = true),
        Item("Time waiting", Format.minutes(s.waitingMin), waits + "${s.stopCount} stops", bold = true),
        Item("Potholes and bumps", "${s.potholes}", listOf("${s.speedHumps} speed breakers", "${(s.roughShare * 100).roundToInt()}% of the road was rough")),
        Item(
            "Traffic", s.avgCongestion?.let { String.format(Locale.US, "%.1f× slower", it) } ?: "—",
            if (s.avgCongestion != null && congestionEstimated) listOf("estimated") else emptyList(),
        ),
        Item(
            "Heat", s.maxFeelsLikeC?.let { "feels ${it.roundToInt()}°" } ?: "—",
            if (s.heatMinutes >= 0.5f) listOf("${Format.minutes(s.heatMinutes)} above 35°") else emptyList(),
        ),
        Item("Rain", if (s.rainMinutes >= 0.5f) Format.minutes(s.rainMinutes) else if (s.maxFeelsLikeC == null) "—" else "Dry"),
        Item("Air quality", s.avgAqi?.let { "AQI $it" } ?: "—"),
    ) + listOfNotNull(
        // Only with the ESP32 module: the MQ-2 on the bike, as ppm above clean air × minutes.
        s.gasPpmMinutes?.let { dose ->
            Item(
                "Exhaust and smoke", "${dose.roundToInt()} ppm·min",
                listOfNotNull(s.avgGasPpm?.let { "avg ${it.roundToInt()} ppm above clean air" }, "sensor on the bike, estimated"),
            )
        },
    )
}

/** Plain text, so any phone camera can read it without this app. */
private fun qrPayload(number: String, s: JourneySummary, items: List<Item>): String = buildString {
    appendLine("The Invisible Journey · № $number")
    appendLine("ETA ${s.promisedEtaMin} min · took ${Format.minutes(s.actualMin)}")
    items.forEach { appendLine("${it.name}: ${it.value}") }
}.trim()

@Composable
private fun ReceiptLine(n: Int, item: Item) {
    Row {
        Text("$n", color = InkMuted, fontSize = 11.sp, modifier = Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row {
                val weight = if (item.bold) FontWeight.Bold else FontWeight.Medium
                Text(item.name, color = Palette.Ink, fontSize = 12.sp, fontWeight = weight, modifier = Modifier.weight(1f))
                Text(item.value, color = Palette.Ink, fontSize = 12.sp, fontWeight = weight)
            }
            if (item.details.isNotEmpty()) Text(item.details.joinToString(" · "), color = InkMuted, fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun SmallRow(label: String, value: String, valueColor: Color = Palette.Ink) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(label, color = InkMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 11.sp)
    }
}

/** Dashed tear line along the top edge of the lower half, inset from the notches. */
private fun DrawScope.tearLine() {
    val inset = Notch.toPx() + 6.dp.toPx()
    drawLine(
        Color(0xFFC9C9C5), Offset(inset, 0f), Offset(size.width - inset, 0f),
        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
    )
}

/** Ticket half: rounded on its outer edge, with half-circle notches bitten out of the corners at the tear line. */
private class TicketShape(private val notchTop: Boolean, private val notchBottom: Boolean, private val corner: Dp = 16.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { corner.toPx() }
        val n = with(density) { Notch.toPx() }
        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(Offset.Zero, size),
                    topLeft = CornerRadius(if (notchTop) 0f else c), topRight = CornerRadius(if (notchTop) 0f else c),
                    bottomLeft = CornerRadius(if (notchBottom) 0f else c), bottomRight = CornerRadius(if (notchBottom) 0f else c),
                ),
            )
        }
        val bites = Path().apply {
            val y = if (notchTop) 0f else size.height
            if (notchTop || notchBottom) {
                addOval(Rect(Offset(0f, y), n))
                addOval(Rect(Offset(size.width, y), n))
            }
        }
        return Outline.Generic(Path().apply { op(body, bites, PathOperation.Difference) })
    }
}

/** QR code drawn straight onto a canvas (ZXing only encodes; no bitmap needed). */
@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"))
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(5.dp)) {
        Canvas(Modifier.fillMaxWidth().height(70.dp).semantics { contentDescription = "QR code with the journey summary" }) {
            val cell = size.minDimension / matrix.width
            val left = (size.width - cell * matrix.width) / 2
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Palette.Ink, Offset(left + x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
