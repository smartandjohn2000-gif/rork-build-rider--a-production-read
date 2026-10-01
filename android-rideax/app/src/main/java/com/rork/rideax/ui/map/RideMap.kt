package com.rork.rideax.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rork.rideax.model.GeoPoint
import com.rork.rideax.model.ServiceArea
import com.rork.rideax.ui.theme.RideColors
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.max
import org.osmdroid.util.GeoPoint as OsmPoint

enum class PinKind { PICKUP, DROPOFF }

data class MapPin(val point: GeoPoint, val kind: PinKind)

/** The live vehicle: upright car glyph, rotating heading cone and a caption. */
data class VehicleMarker(val point: GeoPoint, val heading: Double, val caption: String?)

data class PickupZone(val center: GeoPoint, val radiusMeters: Double)

/** Everything drawn above the tiles, rebuilt from screen state on every change. */
data class MapScene(
    val pins: List<MapPin> = emptyList(),
    val drivenRoute: List<GeoPoint> = emptyList(),
    val route: List<GeoPoint> = emptyList(),
    val nearbyCars: List<GeoPoint> = emptyList(),
    val vehicle: VehicleMarker? = null,
    val zone: PickupZone? = null,
)

/**
 * Points to keep framed. Insets are fractions of the map height reserved for
 * floating chrome, so the route lands in the visible part above a sheet.
 * Change [nonce] to force a re-fit with identical points.
 */
data class CameraTarget(
    val points: List<GeoPoint>,
    val topInset: Double = 0.18,
    val bottomInset: Double = 0.45,
    val animate: Boolean = true,
    val nonce: Int = 0,
)

/** Dark CARTO basemap, tinted toward the RideAX navy below. */
private val NavyTiles = XYTileSource(
    "CartoDarkRideAX",
    0,
    19,
    256,
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/",
    ),
    "© OpenStreetMap contributors © CARTO",
)

private val NavyFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            0.80f, 0f, 0f, 0f, 4f,
            0f, 0.90f, 0f, 0f, 12f,
            0f, 0f, 1.12f, 0f, 30f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

/**
 * Full-bleed dark map. Tiles come from OpenStreetMap data so no proprietary
 * key is needed; everything RideAX-specific is drawn by [SceneOverlay].
 */
@Composable
fun RideMap(
    scene: MapScene,
    camera: CameraTarget?,
    modifier: Modifier = Modifier,
    onUserGesture: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val gesture by rememberUpdatedState(onUserGesture)
    val overlay = remember { SceneOverlay(context) { gesture() } }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(NavyTiles)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTilesScaledToDpi(true)
            setMinZoomLevel(4.0)
            setMaxZoomLevel(19.0)
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
            setScrollableAreaLimitLatitude(85.0, -85.0, 0)
            setBackgroundColor(RideColors.Canvas.toArgb())
            overlayManager.tilesOverlay.setLoadingBackgroundColor(RideColors.Canvas.toArgb())
            overlayManager.tilesOverlay.setLoadingLineColor(RideColors.Surface.toArgb())
            overlayManager.tilesOverlay.setColorFilter(NavyFilter)
            controller.setZoom(14.5)
            controller.setCenter(OsmPoint(ServiceArea.center.latitude, ServiceArea.center.longitude))
            overlays.add(overlay)
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    LaunchedEffect(camera) {
        val target = camera ?: return@LaunchedEffect
        if (mapView.isLayoutOccurred) {
            mapView.fit(target)
        } else {
            mapView.addOnFirstLayoutListener { _, _, _, _, _ -> mapView.fit(target.copy(animate = false)) }
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = {
                overlay.scene = scene
                it.invalidate()
            },
        )
        Text(
            text = "© OpenStreetMap · CARTO",
            fontSize = 9.sp,
            color = RideColors.TextSecondary.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 2.dp, end = 8.dp),
        )
    }
}

private fun MapView.fit(target: CameraTarget) {
    if (target.points.isEmpty()) return
    var north = target.points.maxOf { it.latitude }
    var south = target.points.minOf { it.latitude }
    var east = target.points.maxOf { it.longitude }
    var west = target.points.minOf { it.longitude }

    // Never zoom in tighter than a few streets.
    val minSpan = 0.007
    if (north - south < minSpan) {
        val mid = (north + south) / 2; north = mid + minSpan / 2; south = mid - minSpan / 2
    }
    if (east - west < minSpan) {
        val mid = (east + west) / 2; east = mid + minSpan / 2; west = mid - minSpan / 2
    }

    val latSpan = (north - south) * 1.2
    val visible = max(0.25, 1.0 - target.topInset - target.bottomInset)
    val total = latSpan / visible
    val centerLat = (north + south) / 2
    val lonPad = (east - west) * 0.18

    val box = BoundingBox(
        centerLat + latSpan / 2 + total * target.topInset,
        east + lonPad,
        centerLat - latSpan / 2 - total * target.bottomInset,
        west - lonPad,
    )
    zoomToBoundingBox(box, target.animate, 0)
}
