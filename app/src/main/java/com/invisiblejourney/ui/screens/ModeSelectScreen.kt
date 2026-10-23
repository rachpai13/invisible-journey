package com.invisiblejourney.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.invisiblejourney.R
import com.invisiblejourney.ui.Dimens
import com.invisiblejourney.ui.Palette
import com.invisiblejourney.ui.components.FitText

/** Welcome screen: flat fills, hairline borders, 12dp corners and no shadows, on the app's dark canvas. */
@Composable
fun ModeSelectScreen(onRider: () -> Unit, onCustomer: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.Gutter)) {
        Spacer(Modifier.height(12.dp))
        AppNetwork(Modifier.fillMaxWidth().aspectRatio(1.07f))
        Spacer(Modifier.height(24.dp))
        Text(
            "THE INVISIBLE JOURNEY",
            color = Palette.AutoYellow,
            fontSize = 11.sp,
            letterSpacing = 1.8.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Know your service better",
            fontSize = 28.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.8).sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Exposing to you what it takes from the gig economy to deliver your order on an instant basis.",
            color = Palette.Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        // Spare height goes here, so the buttons stay in the thumb zone at the bottom.
        Spacer(Modifier.height(40.dp))
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RoleCard(
                icon = painterResource(R.drawable.ic_scooter),
                title = "I'm a rider",
                sub = "Record a delivery journey",
                onClick = onRider,
                container = Palette.AutoYellow,
                content = Palette.Ink,
                border = null,
            )
            RoleCard(
                icon = painterResource(R.drawable.ic_parcel_hand),
                title = "I'm a customer",
                sub = "See what a journey took",
                onClick = onCustomer,
                container = Palette.Surface,
                content = Color.White,
                border = BorderStroke(1.dp, Palette.Line),
            )
        }
    }
}

/** A delivery app around the edge of the header, placed as a fraction of the header's width and height. */
private class Brand(val name: String, val logo: Int, val x: Float, val y: Float, val fill: Boolean = false)

// Logos from Wikimedia Commons. App icons (fill = true) cover the whole tile; wordmarks sit inside it.
// Order matters: the connector routes in AppNetwork refer to these by index.
private val brands = listOf(
    Brand("Blinkit", R.drawable.logo_blinkit, 0.20f, 0.27f, fill = true),
    Brand("Swiggy", R.drawable.logo_swiggy, 0.80f, 0.12f, fill = true),
    Brand("Zepto", R.drawable.logo_zepto, 0.12f, 0.55f),
    Brand("Zomato", R.drawable.logo_zomato, 0.88f, 0.64f),
    Brand("bigbasket", R.drawable.logo_bigbasket, 0.22f, 0.87f),
    Brand("Flipkart Minutes", R.drawable.logo_minutes, 0.76f, 0.88f),
)

private val TileSize = 60.dp
private val HubSize = 88.dp

/** Partner logos are shown without colour, so the app's own yellow dial is the only colour in the header. */
private val greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Header card: the app's dial in the middle, wired to the instant-delivery apps whose riders it measures. */
@Composable
private fun AppNetwork(modifier: Modifier) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)),
    ) {
        val w = maxWidth
        val h = maxHeight
        val hubX = 0.5f
        val hubY = 0.48f

        // Circuit-style wiring: straight runs leave the hub's edges, turn on soft corners, and tiles
        // branch off a shared trunk (Blinkit off the top one, bigbasket off the bottom one).
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * hubX
            val cy = size.height * hubY
            val k = HubSize.toPx() / 2
            val t = TileSize.toPx() / 2
            val p = brands.map { Offset(size.width * it.x, size.height * it.y) }
            val (blinkit, swiggy, zepto, zomato, bigbasket) = p
            val minutes = p[5]
            val wires = Path().apply {
                moveTo(cx, cy - k); lineTo(cx, swiggy.y); lineTo(swiggy.x - t, swiggy.y)
                moveTo(blinkit.x + t, blinkit.y); lineTo(cx, blinkit.y)
                val elbow = (zepto.x + t + cx - k) / 2
                moveTo(cx - k, cy); lineTo(elbow, cy); lineTo(elbow, zepto.y); lineTo(zepto.x + t, zepto.y)
                moveTo(cx + k, cy); lineTo(zomato.x, cy); lineTo(zomato.x, zomato.y - t)
                moveTo(cx, cy + k); lineTo(cx, minutes.y); lineTo(minutes.x - t, minutes.y)
                val branch = (cy + k + bigbasket.y - t) / 2
                moveTo(cx, branch); lineTo(bigbasket.x, branch); lineTo(bigbasket.x, bigbasket.y - t)
            }
            val line = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.cornerPathEffect(10.dp.toPx()))
            drawPath(wires, Color.White.copy(alpha = 0.22f), style = line)
        }

        brands.forEach { b ->
            BrandTile(b, Modifier.centredAt(w * b.x, h * b.y, TileSize))
        }

        Box(
            Modifier
                .centredAt(w * hubX, h * hubY, HubSize)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Ink)
                .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = "The Invisible Journey", Modifier.size(80.dp))
        }
    }
}

private fun Modifier.centredAt(x: Dp, y: Dp, size: Dp): Modifier =
    offset(x - size / 2, y - size / 2).size(size)

@Composable
private fun BrandTile(b: Brand, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFEDEDED))
            .padding(if (b.fill) 0.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(b.logo),
            contentDescription = b.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = if (b.fill) ContentScale.Crop else ContentScale.Fit,
            colorFilter = greyscale,
        )
    }
}

@Composable
private fun RoleCard(
    icon: Painter,
    title: String,
    sub: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    border: BorderStroke?,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(80.dp),
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        border = border,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(content.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                FitText(title, maxSize = 19.sp, minSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FitText(sub, maxSize = 14.sp, minSize = 10.sp, color = content.copy(alpha = 0.7f))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}
