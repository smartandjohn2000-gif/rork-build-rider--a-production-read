package com.rork.rideax.model

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordinate used across rides, places and the map. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double) {

    /** Great-circle distance in metres. */
    fun distanceTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Linear interpolation used to place a vehicle between two points. */
    fun interpolated(to: GeoPoint, progress: Double): GeoPoint {
        val t = progress.coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude + (to.latitude - latitude) * t,
            longitude + (to.longitude - longitude) * t,
        )
    }

    /** Initial bearing towards [other] in degrees, 0 = north, clockwise. */
    fun bearingTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val degrees = Math.toDegrees(atan2(y, x))
        return if (degrees < 0) degrees + 360 else degrees
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

private fun List<GeoPoint>.segmentLengths(): DoubleArray =
    DoubleArray((size - 1).coerceAtLeast(0)) { this[it].distanceTo(this[it + 1]) }

/** Total length of the polyline in metres. */
fun List<GeoPoint>.lengthMeters(): Double = segmentLengths().sum()

/** The point at [progress] (0..1) along the polyline, measured by distance. */
fun List<GeoPoint>.pointAt(progress: Double): GeoPoint? {
    val first = firstOrNull() ?: return null
    if (size < 2) return first
    val segments = segmentLengths()
    val total = segments.sum()
    if (total <= 0) return first

    var travelled = total * progress.coerceIn(0.0, 1.0)
    for (index in segments.indices) {
        if (travelled <= segments[index]) {
            val ratio = if (segments[index] == 0.0) 0.0 else travelled / segments[index]
            return this[index].interpolated(this[index + 1], ratio)
        }
        travelled -= segments[index]
    }
    return last()
}

/**
 * Direction of travel at [progress], sampled slightly ahead and behind so the
 * value stays stable through dense clusters of route points.
 */
fun List<GeoPoint>.headingAt(progress: Double): Double {
    if (size < 2) return 0.0
    val window = 0.012
    val behind = pointAt((progress - window).coerceAtLeast(0.0)) ?: return 0.0
    val ahead = pointAt((progress + window).coerceAtMost(1.0)) ?: return 0.0
    if (behind == ahead) return 0.0
    return behind.bearingTo(ahead)
}

/** Splits the polyline at [progress] into (already driven, still to come). */
fun List<GeoPoint>.splitAt(progress: Double): Pair<List<GeoPoint>, List<GeoPoint>> {
    if (size < 2) return emptyList<GeoPoint>() to this
    val current = pointAt(progress) ?: return emptyList<GeoPoint>() to this
    val segments = segmentLengths()
    val total = segments.sum()
    if (total <= 0) return emptyList<GeoPoint>() to this

    var travelled = total * progress.coerceIn(0.0, 1.0)
    for (index in segments.indices) {
        if (travelled <= segments[index]) {
            return (subList(0, index + 1) + current) to (listOf(current) + subList(index + 1, size))
        }
        travelled -= segments[index]
    }
    return this to listOf(last())
}

/**
 * A single driver location update — the payload a realtime feed pushes every
 * couple of seconds. The map never draws pings raw; it eases the marker from
 * the previous ping to this one over [expectedIntervalSeconds].
 */
data class DriverPing(
    val sequence: Int,
    val point: GeoPoint,
    val progress: Double,
    val heading: Double,
    val speedMetersPerSecond: Double,
    val timestamp: Long,
    val expectedIntervalSeconds: Double,
) {
    val speedKilometresPerHour: Int get() = Math.round(speedMetersPerSecond * 3.6).toInt()
    val isStationary: Boolean get() = speedMetersPerSecond < 0.6
}
