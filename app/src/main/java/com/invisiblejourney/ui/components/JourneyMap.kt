package com.invisiblejourney.ui.components

import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.invisiblejourney.domain.model.PotholeEvent
import com.invisiblejourney.domain.model.PotholeMark
import com.invisiblejourney.domain.model.StopEvent
import com.invisiblejourney.domain.model.StopType
import com.invisiblejourney.domain.model.TrackPoint
import com.invisiblejourney.ui.Palette
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** The five colours the journey-lapse map uses, shared with its legend. */
object MapColors {
    val Fast = Palette.AutoGreen
    val Slow = Palette.AutoYellow
    val Signal = Palette.Danger
    val Stopped = Color(0xFF000000)
    val Pothole = Palette.Pothole

    /** Trail colour: fast above 22 km/h, slow down to walking pace, stopped below 5 km/h. */
    fun trail(kmh: Float): Color = when {
        kmh < 5f -> Stopped
        kmh < 22f -> Slow
        else -> Fast
    }
}

/**
 * OpenStreetMap (osmdroid, free, no key). The trail is coloured by speed; potholes and stops are dots.
 * [follow] keeps the camera on the latest point (live / replay).
 */
@Composable
fun JourneyMap(
    track: List<TrackPoint>,
    potholes: List<PotholeEvent>,
    stops: List<StopEvent>,
    modifier: Modifier = Modifier,
    follow: Boolean = true,
    marks: List<PotholeMark> = emptyList(),
) {
    val context = LocalContext.current
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }

    AndroidView(factory = { map }, modifier = modifier) { view ->
        view.overlays.clear()

        // Group consecutive points of the same speed colour into one polyline.
        var run = mutableListOf<GeoPoint>()
        var runColor: Color? = null
        fun flush() {
            if (run.size >= 2 && runColor != null) {
                view.overlays.add(
                    Polyline(view).apply {
                        setPoints(run)
                        outlinePaint.color = runColor!!.toArgb()
                        outlinePaint.strokeWidth = 12f
                    },
                )
            }
        }
        for (p in track) {
            val gp = GeoPoint(p.lat, p.lng)
            val c = MapColors.trail(p.speedKmh)
            if (c != runColor) {
                run.add(gp)
                flush()
                run = mutableListOf(gp)
                runColor = c
            } else {
                run.add(gp)
            }
        }
        flush()

        stops.forEach { s ->
            val color = if (s.type == StopType.SIGNAL_WAIT) MapColors.Signal else MapColors.Stopped
            view.overlays.add(dot(view, s.lat, s.lng, color, 34, s.type.label))
        }
        marks.forEach { mk ->
            if (mk.lat != null && mk.lng != null) view.overlays.add(dot(view, mk.lat, mk.lng, MapColors.Pothole, 30, "Marked by rider"))
        }
        potholes.forEach { p ->
            view.overlays.add(dot(view, p.lat, p.lng, MapColors.Pothole, 22 + p.severity * 6, p.kind.label))
        }

        track.lastOrNull()?.let { last ->
            view.overlays.add(dot(view, last.lat, last.lng, Palette.AutoYellow, 44, "Rider"))
            if (follow) view.controller.setCenter(GeoPoint(last.lat, last.lng))
        }
        view.invalidate()
    }
}

private fun dot(view: MapView, lat: Double, lng: Double, color: Color, sizePx: Int, title: String) =
    Marker(view).apply {
        position = GeoPoint(lat, lng)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        this.title = title
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color.toArgb())
            setStroke(3, android.graphics.Color.WHITE)
            setSize(sizePx, sizePx)
        }
    }
