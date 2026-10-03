package com.hamoon.uncleted.util

import android.content.Context
import android.location.Location
import android.util.Log

object LocationTracker {

    private const val TAG = "LocationTracker"

    // Preserve the previous "recent" window for an acceptable cached fix.
    private const val MAX_CACHE_AGE_MS = 5 * 60 * 1000L // 5 minutes

    data class LocationInfo(
        val location: Location,
        val address: String? = null,
        val accuracy: Float,
        val timestamp: Long
    )

    /**
     * Obtains the current location for SMS / emergency location reporting using
     * framework providers only (no Google Play Services). Tries a fresh GPS/network
     * fix and falls back to a recent cached fix (≤ 5 min), matching the previous
     * behaviour and 15s overall bound. All listeners are owned and released by
     * [FrameworkLocationProvider]; nothing is leaked here.
     */
    suspend fun getCurrentLocationDetailed(context: Context): LocationInfo? {
        val location = FrameworkLocationProvider.getCurrentLocation(
            context = context,
            timeoutMs = 15_000L,
            allowCachedFallback = true,
            maxCacheAgeMs = MAX_CACHE_AGE_MS
        )
        if (location == null) {
            Log.e(TAG, "Could not obtain a location fix.")
            return null
        }
        return LocationInfo(
            location = location,
            accuracy = location.accuracy,
            timestamp = location.time
        )
    }

    fun formatLocationForSms(locationInfo: LocationInfo): String {
        return "Location: ${locationInfo.location.latitude},${locationInfo.location.longitude} " +
                "(±${locationInfo.accuracy.toInt()}m) " +
                "Maps: https://maps.google.com/maps?q=${locationInfo.location.latitude},${locationInfo.location.longitude}"
    }

    fun formatLocationForEmail(locationInfo: LocationInfo): String {
        return """
            GPS Coordinates: ${locationInfo.location.latitude}, ${locationInfo.location.longitude}
            Accuracy: ±${locationInfo.accuracy.toInt()} meters
            Altitude: ${if (locationInfo.location.hasAltitude()) "${locationInfo.location.altitude}m" else "Unknown"}
            Speed: ${if (locationInfo.location.hasSpeed()) "${locationInfo.location.speed} m/s" else "Unknown"}
            Bearing: ${if (locationInfo.location.hasBearing()) "${locationInfo.location.bearing}°" else "Unknown"}
            Timestamp: ${java.util.Date(locationInfo.timestamp)}
            
            Google Maps Link: https://www.google.com/maps/search/?api=1&query=${locationInfo.location.latitude},${locationInfo.location.longitude}
        """.trimIndent()
    }
}