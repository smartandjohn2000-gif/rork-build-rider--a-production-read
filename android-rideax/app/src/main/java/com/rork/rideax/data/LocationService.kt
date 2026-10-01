package com.rork.rideax.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rork.rideax.model.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform location without Play Services, so it works on every device and
 * emulator. Views only ever see a coordinate and whether we have permission.
 */
class LocationService(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _point = MutableStateFlow<GeoPoint?>(null)
    val point: StateFlow<GeoPoint?> = _point.asStateFlow()

    private val _hasPermission = MutableStateFlow(checkPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private var isUpdating = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _point.value = GeoPoint(location.latitude, location.longitude)
        }

        // Overridden explicitly: these are abstract on API levels below 30.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        _hasPermission.value = checkPermission()
        val locationManager = manager ?: return
        if (!_hasPermission.value || isUpdating) return
        isUpdating = true

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        providers.mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { _point.value = GeoPoint(it.latitude, it.longitude) }

        providers.forEach { provider ->
            runCatching { locationManager.requestLocationUpdates(provider, 4_000L, 10f, listener, Looper.getMainLooper()) }
        }
    }

    fun stop() {
        if (!isUpdating) return
        runCatching { manager?.removeUpdates(listener) }
        isUpdating = false
    }
}
