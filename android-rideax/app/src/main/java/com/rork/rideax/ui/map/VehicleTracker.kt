package com.rork.rideax.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rork.rideax.model.DriverPing
import com.rork.rideax.model.GeoPoint
import com.rork.rideax.model.pointAt
import kotlinx.coroutines.launch

/** Where the car is drawn right now, between two sparse location pings. */
data class VehicleState(
    val point: GeoPoint,
    val heading: Double,
    val progress: Double,
    val speedKmh: Int,
    val isMoving: Boolean,
)

/**
 * Eases the vehicle along the route polyline between pings, so it follows
 * corners instead of cutting across blocks and never teleports. Heading turns
 * the shortest way round. A new leg (route change) snaps instead of flying.
 */
@Composable
fun rememberVehicleState(ping: DriverPing?, route: List<GeoPoint>): VehicleState? {
    val progress = remember { Animatable(0f) }
    val heading = remember { Animatable(0f) }
    val routeKey = route.size to route.firstOrNull()
    var lastRouteKey by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(ping?.sequence, routeKey) {
        val target = ping ?: return@LaunchedEffect
        val isNewLeg = lastRouteKey != routeKey
        lastRouteKey = routeKey

        launch {
            if (isNewLeg) {
                heading.snapTo(target.heading.toFloat())
            } else {
                val currentHeading = heading.value.toDouble()
                val delta = (((target.heading - currentHeading) % 360) + 540) % 360 - 180
                heading.animateTo((currentHeading + delta).toFloat(), tween(450))
            }
        }

        if (isNewLeg || target.sequence == 0 || target.progress < progress.value) {
            progress.snapTo(target.progress.toFloat())
        } else {
            val duration = (target.expectedIntervalSeconds * 1000 * 1.05).toInt().coerceAtLeast(120)
            progress.animateTo(target.progress.toFloat(), tween(duration, easing = LinearEasing))
        }
    }

    val current = ping ?: return null
    val point = if (route.size >= 2) route.pointAt(progress.value.toDouble()) ?: current.point else current.point
    return VehicleState(
        point = point,
        heading = ((heading.value % 360) + 360) % 360.0,
        progress = progress.value.toDouble(),
        speedKmh = current.speedKilometresPerHour,
        isMoving = !current.isStationary,
    )
}
