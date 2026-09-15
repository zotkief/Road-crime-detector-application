package com.example.roadcrimedetector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.roadcrimedetector.model.LocationSnapshot

class LocationProvider(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun lastKnownLocation(): LocationSnapshot? {
        val hasFinePermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFinePermission && !hasCoarsePermission) return null

        val locations = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }
        return locations.maxByOrNull(Location::getTime)?.toSnapshot()
    }

    private fun Location.toSnapshot() = LocationSnapshot(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        altitudeMeters = if (hasAltitude()) altitude else null,
        provider = provider,
        locationTimeMillis = time
    )
}