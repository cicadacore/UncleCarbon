package com.hamoon.unclecarbon.util

import com.hamoon.unclecarbon.data.SecurityEvent
import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hamoon.unclecarbon.receivers.GeofenceBroadcastReceiver

/**
 * Safe-zone geofencing using the Android framework proximity-alert API only.
 *
 * Replaces the previous Google Play Services Geofencing implementation
 * (GeofencingClient/Geofence). [LocationManager.addProximityAlert] cleanly
 * expresses the existing feature — a circular safe zone whose EXIT triggers the
 * panic workflow — without any dependency on Google Play Services, so it works on
 * GrapheneOS with sandboxed Google Play absent.
 *
 * Each zone owns a distinct [PendingIntent] (unique request code + zone id in the
 * intent) so multiple zones never overwrite one another.
 */
object GeofenceHelper {

    private const val TAG = "GeofenceHelper"

    /** Default/legacy single safe-zone id, preserved from the previous implementation. */
    const val GEOFENCE_ID = "UNCLECARBON_SAFE_ZONE"

    const val ACTION_PROXIMITY_ALERT = "com.hamoon.unclecarbon.action.PROXIMITY_ALERT"
    const val EXTRA_ZONE_ID = "com.hamoon.unclecarbon.extra.ZONE_ID"

    private const val GEOFENCE_RADIUS_METERS = 100f
    private const val NEVER_EXPIRE = -1L

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun proximityPendingIntent(zoneId: String): PendingIntent {
        val intent = Intent(appContext, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_PROXIMITY_ALERT
            putExtra(EXTRA_ZONE_ID, zoneId)
        }
        // Proximity alerts require a MUTABLE PendingIntent on S+ so the system can
        // attach the KEY_PROXIMITY_ENTERING extra when it fires.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // Unique request code per zone id keeps each zone's PendingIntent distinct.
        return PendingIntent.getBroadcast(appContext, zoneId.hashCode(), intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun addGeofence(
        lat: Double,
        lon: Double,
        zoneId: String = GEOFENCE_ID,
        radiusMeters: Float = GEOFENCE_RADIUS_METERS
    ) {
        if (!::appContext.isInitialized) {
            Log.e(TAG, "GeofenceHelper is not initialized with context.")
            return
        }

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot add safe zone: ACCESS_FINE_LOCATION permission missing.")
            return
        }

        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.e(TAG, "LocationManager unavailable; cannot add safe zone.")
            return
        }

        try {
            lm.addProximityAlert(lat, lon, radiusMeters, NEVER_EXPIRE, proximityPendingIntent(zoneId))
            Log.i(TAG, "Safe zone proximity alert registered.")
            EventLogger.log(appContext, SecurityEvent.GEOFENCE_ADDED)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException adding proximity alert", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding proximity alert", e)
        }
    }

    fun removeGeofence(zoneId: String = GEOFENCE_ID) {
        if (!::appContext.isInitialized) return

        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.removeProximityAlert(proximityPendingIntent(zoneId))
            Log.i(TAG, "Safe zone proximity alert removed.")
            EventLogger.log(appContext, SecurityEvent.GEOFENCE_REMOVED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing proximity alert", e)
        }
    }
}
