package com.pickle.blackbox.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import android.util.Log

/**
 * Speeds readout for the HUD. Uses the platform LocationManager (no Play Services needed),
 * prefers GPS, falls back to the network provider. Permission-safe: silently does nothing
 * when the location permission was not granted.
 */
class SpeedTracker(
    private val context: Context,
    private val onSpeedKmh: (Float) -> Unit
) {

    private val locationManager: LocationManager? =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val kmh = if (location.hasSpeed()) location.speed * 3.6f else -1f
            onSpeedKmh(kmh)
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) {
            onSpeedKmh(-1f)
        }

        @Deprecated("Deprecated in the platform API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun start() {
        val manager = locationManager ?: return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Location permission not granted — speed readout disabled")
            return
        }
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        runCatching {
            manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
            started = true
        }.onFailure { Log.w(TAG, "requestLocationUpdates failed", it) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager?.removeUpdates(listener) }
    }

    companion object {
        private const val TAG = "SpeedTracker"
    }
}
