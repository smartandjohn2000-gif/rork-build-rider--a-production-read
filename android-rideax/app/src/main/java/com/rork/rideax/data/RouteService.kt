package com.rork.rideax.data

import android.util.Log
import com.rork.rideax.model.GeoPoint
import com.rork.rideax.model.Place
import com.rork.rideax.model.PlaceKind
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class RouteEstimate(
    val polyline: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
) {
    val minutes: Int get() = max(1, (durationSeconds / 60).roundToInt())
}

/**
 * Driving directions and place search on OpenStreetMap data (OSRM + Nominatim),
 * so Android needs no proprietary map key. The interface mirrors the iOS
 * RouteService, and routing falls back to a straight-line estimate so the
 * booking flow never stalls offline.
 */
class RouteService {

    suspend fun route(from: GeoPoint, to: GeoPoint): RouteEstimate {
        return try {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
                "?overview=full&geometries=geojson"
            val text = Http.client.get(url) { header("User-Agent", USER_AGENT) }.textOrThrow()
            val route = Http.json.parseToJsonElement(text).jsonObject["routes"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return fallback(from, to)
            val coordinates = route["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return fallback(from, to)
            val points = coordinates.mapNotNull { pair ->
                val values = pair.jsonArray
                val lon = values.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                val lat = values.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                if (lon != null && lat != null) GeoPoint(lat, lon) else null
            }
            val distance = route["distance"]?.jsonPrimitive?.doubleOrNull ?: return fallback(from, to)
            val duration = route["duration"]?.jsonPrimitive?.doubleOrNull ?: return fallback(from, to)
            if (points.size < 2) fallback(from, to) else RouteEstimate(points.thinned(), distance, duration)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.d(TAG, "Directions unavailable, using estimate: ${error.javaClass.simpleName}")
            fallback(from, to)
        }
    }

    /** Place search biased to the area around [near]. */
    suspend fun search(query: String, near: GeoPoint): List<Place> {
        return try {
            val box = "${near.longitude - 0.3},${near.latitude + 0.2},${near.longitude + 0.3},${near.latitude - 0.2}"
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=12&addressdetails=0" +
                "&viewbox=$box&bounded=1&q=${query.encodeURLParameter()}"
            val text = Http.client.get(url) {
                header("User-Agent", USER_AGENT)
                header("Accept-Language", Locale.getDefault().toLanguageTag())
            }.textOrThrow()
            Http.json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val item = element.jsonObject
                val lat = item["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = item["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                val display = item["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val parts = display.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: parts.firstOrNull() ?: "Dropped pin"
                val subtitle = parts.filter { it != name }.take(3).joinToString(", ")
                val category = item["category"]?.jsonPrimitive?.contentOrNull
                val type = item["type"]?.jsonPrimitive?.contentOrNull
                Place(
                    id = "osm-$lat,$lon",
                    name = name,
                    subtitle = subtitle,
                    point = GeoPoint(lat, lon),
                    kind = kindFor(category, type),
                )
            }.distinctBy { it.name + it.subtitle }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.d(TAG, "Search failed: ${error.javaClass.simpleName}")
            emptyList()
        }
    }

    /** Reverse geocode used to label the rider's live pickup point. */
    suspend fun placeName(point: GeoPoint): String? {
        return try {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18" +
                "&lat=${point.latitude}&lon=${point.longitude}"
            val text = Http.client.get(url) { header("User-Agent", USER_AGENT) }.textOrThrow()
            val json = Http.json.parseToJsonElement(text).jsonObject
            val address = json["address"]?.jsonObject
            json["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: address?.get("road")?.jsonPrimitive?.contentOrNull
                ?: address?.get("suburb")?.jsonPrimitive?.contentOrNull
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.d(TAG, "Reverse geocode failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun kindFor(category: String?, type: String?): PlaceKind = when {
        type == "aerodrome" || category == "aeroway" -> PlaceKind.AIRPORT
        category == "railway" || type == "station" -> PlaceKind.TRAIN
        type == "museum" || type == "gallery" -> PlaceKind.MUSEUM
        type == "theatre" || type == "cinema" -> PlaceKind.THEATRE
        category == "tourism" || category == "historic" -> PlaceKind.LANDMARK
        else -> PlaceKind.PIN
    }

    /** Keeps long routes light enough to redraw every frame. */
    private fun List<GeoPoint>.thinned(maxPoints: Int = 400): List<GeoPoint> {
        if (size <= maxPoints) return this
        val step = size.toDouble() / maxPoints
        return (0 until maxPoints).map { this[(it * step).toInt()] } + last()
    }

    /** Straight line plus typical urban speed, so fares stay plausible offline. */
    private fun fallback(from: GeoPoint, to: GeoPoint): RouteEstimate {
        val road = from.distanceTo(to) * 1.32
        val steps = 24
        return RouteEstimate(
            polyline = (0..steps).map { from.interpolated(to, it.toDouble() / steps) },
            distanceMeters = road,
            durationSeconds = max(road / 7.5, 120.0),
        )
    }

    private companion object {
        const val TAG = "RideAX.Route"
        const val USER_AGENT = "RideAX/1.0 (Android; com.rork.rideax)"
    }
}
