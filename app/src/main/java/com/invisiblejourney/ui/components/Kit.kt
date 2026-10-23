package com.invisiblejourney.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.invisiblejourney.ui.Dimens
import com.invisiblejourney.ui.Palette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One line of text that shrinks to fit its slot instead of wrapping or pushing neighbours.
 * Every number and label that lives in a fixed-size tile goes through this.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 18.sp,
    minSize: TextUnit = 10.sp,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Start,
) {
    val c = if (color != Color.Unspecified) color else LocalContentColor.current
    BasicText(
        text,
        modifier,
        style = style.copy(color = c, textAlign = textAlign, fontWeight = fontWeight ?: style.fontWeight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 1.sp),
    )
}

/** Fixed-height top bar. The trailing slot is always laid out, so the title never jumps. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.TopBar).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(30.dp))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        trailing()
    }
}

/** Small fixed-height label ("SAMPLE", "DEMO"). */
@Composable
fun Tag(text: String, color: Color = Palette.AutoYellow, contentColor: Color = Palette.Ink, modifier: Modifier = Modifier) {
    Box(
        modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = 1.sp)
    }
}

/** Full-width button with a title that never wraps and an optional one-line subtitle. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector? = null,
    height: Dp = Dimens.ButtonHeight,
    primary: Boolean = true,
    container: Color = Palette.AutoYellow,
    content: Color = Palette.Ink,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(Dimens.Radius)
    val padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
    val inner: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(text, maxSize = 20.sp, minSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (sub != null) FitText(sub, maxSize = 13.sp, minSize = 10.sp, textAlign = TextAlign.Center, color = LocalContentColor.current.copy(alpha = 0.75f))
        }
    }
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth().height(height),
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = content,
                disabledContainerColor = Palette.SurfaceHigh,
                disabledContentColor = Palette.Faint,
            ),
            content = inner,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth().height(height),
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(1.5.dp, if (enabled) Palette.Line else Palette.SurfaceHigh),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            content = inner,
        )
    }
}

/**
 * Slide-to-confirm, like the delivery apps riders already use: a bump on the road can't
 * trigger it by accident. The drag offset is read only in layout/draw, so dragging doesn't
 * recompose anything. TalkBack users get a plain click action.
 */
@Composable
fun SwipeToConfirm(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.AutoYellow,
    height: Dp = Dimens.HeroButton,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val inset = 6.dp
    val thumb = height - inset * 2
    var drag by remember { mutableFloatStateOf(0f) }
    var maxDrag by remember { mutableFloatStateOf(1f) }

    val dragState = rememberDraggableState { delta -> drag = (drag + delta).coerceIn(0f, maxDrag) }
    val shape = RoundedCornerShape(height / 2)

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .onSizeChanged { maxDrag = (it.width - with(density) { (thumb + inset * 2).toPx() }).coerceAtLeast(1f) }
            .drawBehind {
                // Fill follows the thumb.
                val w = drag + with(density) { (thumb + inset * 2).toPx() }
                drawRoundRect(color.copy(alpha = 0.35f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
            }
            .semantics {
                role = Role.Button
                onClick(label) { onConfirm(); true }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    val confirmed = drag >= maxDrag * 0.85f
                    if (confirmed) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirm()
                    }
                    scope.launch { animate(drag, 0f) { v, _ -> drag = v } }
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        FitText(
            label,
            maxSize = 16.sp,
            minSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = thumb + inset * 2, end = 20.dp)
                .graphicsLayer { alpha = 1f - (drag / maxDrag).coerceIn(0f, 1f) * 0.8f },
        )
        Box(
            Modifier
                .offset { IntOffset(drag.roundToInt(), 0) }
                .padding(inset)
                .size(thumb)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(32.dp))
        }
    }
}

/** A large selectable tile. The check slot is always there, so selecting doesn't move text. */
@Composable
fun SelectTile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(if (sub != null) 68.dp else Dimens.Touch),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Palette.AutoYellow.copy(alpha = 0.16f) else Palette.Surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Palette.AutoYellow else Palette.Line),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) Palette.AutoYellow else Color.Transparent)
                    .then(if (selected) Modifier else Modifier.drawBehind { drawCircle(Palette.Faint, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                FitText(label, maxSize = 17.sp, minSize = 11.sp, fontWeight = FontWeight.Bold)
                if (sub != null) FitText(sub, maxSize = 12.sp, minSize = 9.sp, color = Palette.Muted)
            }
        }
    }
}

/** Tiny status light with a label: green ok, amber degraded, grey unknown. */
@Composable
fun StatusLight(label: String, ok: Boolean?, modifier: Modifier = Modifier) {
    val color = when (ok) {
        true -> Palette.AutoGreen
        false -> Palette.Warn
        null -> Palette.Faint
    }
    Column(modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        FitText(label, maxSize = 11.sp, minSize = 8.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Card surface used for grouped content. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.Radius), color = Palette.Surface, content = content)
}
