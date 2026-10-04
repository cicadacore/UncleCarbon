package com.hamoon.uncleted.services

import com.hamoon.uncleted.data.SecurityEvent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.util.EventLogger
import com.hamoon.uncleted.util.NotificationHelper
import com.hamoon.uncleted.util.PermissionUtils
import com.hamoon.uncleted.util.PolygonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Geographic Sentinel.
 *
 * Continuously monitors the device position against built-in and user-defined
 * destruction zones and, once a breach is confirmed, triggers the standard
 * Device Owner factory reset through the [DefenseCoordinator].
 *
 * Location is provided exclusively by the standard Android framework
 * ([LocationManager] + [LocationListener]). No Google Play Services / Fused
 * Location Provider is used, so the sentinel works on GrapheneOS with no
 * sandboxed Google Play installed.
 *
 * Preserved behaviour from the previous Fused-Location implementation:
 *  - accuracy filtering: fixes worse than [MAX_ACCEPTABLE_ACCURACY_METERS] are ignored;
 *  - consecutive confirmation: [REQUIRED_CONSECUTIVE_BREACHES] in-zone fixes required;
 *  - built-in Evin Prison perimeter + custom user destruction zones;
 *  - foreground-service + notification behaviour;
 *  - Device Owner wipe path via [DefenseCoordinator].
 */
class ZoneWipeService : Service() {

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private val registeredProviders = mutableListOf<String>()

    private var consecutiveBreachCount = 0

    // Monotonic timestamp (elapsedRealtime) of the most recent accepted GPS fix.
    // Used to prefer the accurate GPS provider over the coarser network provider
    // while GPS is actively delivering, instead of blindly trusting whichever
    // provider fired last.
    private var lastGpsFixElapsedMs = 0L

    companion object {
        private const val TAG = "ZoneWipeService"
        private const val NOTIFICATION_ID = 3003
        private const val UPDATE_INTERVAL_MS = 5000L
        private const val MIN_UPDATE_DISTANCE_METERS = 5f
        private const val MAX_ACCEPTABLE_ACCURACY_METERS = 30.0f
        private const val REQUIRED_CONSECUTIVE_BREACHES = 3

        // A fix older than this is considered stale and is never used to drive a
        // destructive action. Guards against acting on an old getLastKnownLocation
        // seed or a provider that has stopped delivering fresh fixes.
        private const val MAX_LOCATION_AGE_MS = 60_000L

        // While a GPS fix has been seen within this window, network-provider fixes
        // are ignored in favour of the more accurate GPS reading.
        private const val GPS_PREFERENCE_WINDOW_MS = 2 * UPDATE_INTERVAL_MS

        // Framework providers we are willing to use, most-accurate first.
        private val DESIRED_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
    }

    override fun onCreate() {
        super.onCreate()

        if (!PermissionUtils.hasLocationPermissions(this)) {
            Log.e(TAG, "Location permissions missing. Cannot start ZoneWipeService.")
            stopSelf()
            return
        }

        val notification = NotificationHelper.createBasicNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting ZoneWipeService in foreground", e)
            stopSelf()
            return
        }

        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Log.e(TAG, "LocationManager unavailable. Cannot start ZoneWipeService.")
            stopSelf()
            return
        }

        startLocationMonitoring()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationMonitoring() {
        if (!PermissionUtils.hasLocationPermissions(this)) {
            Log.e(TAG, "Missing location permissions. Zone Wipe monitoring aborted.")
            stopSelf()
            return
        }

        val lm = locationManager ?: run {
            stopSelf()
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                processLocationSample(location)
            }

            // Required for API < 30 compatibility; no action needed.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {
                Log.i(TAG, "Location provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "Location provider disabled: $provider")
                // Losing a provider must not crash or wipe; monitoring simply
                // continues on whatever providers remain. A stale breach count is
                // reset so a later re-enable starts a fresh confirmation run.
                consecutiveBreachCount = 0
            }
        }
        locationListener = listener

        // Only register on providers that actually exist on this device; never
        // assume both GPS and network are present or enabled.
        val availableProviders = try {
            lm.allProviders
        } catch (e: Exception) {
            Log.e(TAG, "Failed querying available providers", e)
            emptyList<String>()
        }

        for (provider in DESIRED_PROVIDERS) {
            if (!availableProviders.contains(provider)) {
                Log.w(TAG, "Provider '$provider' not present on this device. Skipping.")
                continue
            }
            try {
                lm.requestLocationUpdates(
                    provider,
                    UPDATE_INTERVAL_MS,
                    MIN_UPDATE_DISTANCE_METERS,
                    listener,
                    Looper.getMainLooper()
                )
                registeredProviders.add(provider)

                // Seed with the last known fix. The freshness gate in
                // processLocationSample prevents a stale seed from triggering a wipe.
                val lastKnown = lm.getLastKnownLocation(provider)
                if (lastKnown != null) {
                    processLocationSample(lastKnown)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException registering provider '$provider'", e)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Provider '$provider' unavailable", e)
            }
        }

        if (registeredProviders.isEmpty()) {
            Log.e(TAG, "No usable location providers. Zone Wipe monitoring inactive.")
            EventLogger.log(this, SecurityEvent.ZONE_UNAVAILABLE)
            stopSelf()
            return
        }

        Log.i(TAG, "Zone Wipe Service armed on providers: $registeredProviders")
        EventLogger.log(this, SecurityEvent.ZONE_ARMED)
    }

    private fun processLocationSample(location: Location?) {
        if (location == null) return

        // Accuracy filter: reject imprecise fixes (preserved 30 m threshold).
        // An ignored fix does not reset the consecutive-breach count.
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) {
            Log.w(TAG, "GPS accuracy insufficient (${location.accuracy}m > ${MAX_ACCEPTABLE_ACCURACY_METERS}m). Ignoring fix.")
            return
        }

        // Freshness filter: never drive a destructive action from a stale fix.
        val age = System.currentTimeMillis() - location.time
        if (age > MAX_LOCATION_AGE_MS) {
            Log.w(TAG, "Location too old (${age}ms > ${MAX_LOCATION_AGE_MS}ms). Ignoring stale fix.")
            return
        }

        // Provider preference: when GPS is actively delivering, prefer it over the
        // coarser network provider instead of blindly trusting whichever fired last.
        val isGps = location.provider == LocationManager.GPS_PROVIDER
        val nowElapsed = SystemClock.elapsedRealtime()
        if (isGps) {
            lastGpsFixElapsedMs = nowElapsed
        } else if (lastGpsFixElapsedMs > 0L &&
            (nowElapsed - lastGpsFixElapsedMs) <= GPS_PREFERENCE_WINDOW_MS
        ) {
            Log.d(TAG, "GPS active; ignoring '${location.provider}' fix in favour of GPS.")
            return
        }

        val activeZones = mutableListOf<PolygonUtils.WipeZone>()

        // 1. Built-in Evin Prison perimeter
        if (SecurityPreferences.isGeofenceSuicideEnabled(this)) {
            activeZones.add(
                PolygonUtils.WipeZone(
                    id = "builtin_evin",
                    name = "Evin Prison Perimeter",
                    polygon = PolygonUtils.EVIN_PRISON_PERIMETER,
                    isEnabled = true
                )
            )
        }

        // 2. Custom user-defined destruction zones
        activeZones.addAll(SecurityPreferences.getCustomWipeZones(this).filter { it.isEnabled })

        var breachedZoneName: String? = null
        for (zone in activeZones) {
            if (PolygonUtils.isLocationInWipeZone(location, zone)) {
                breachedZoneName = zone.name
                break
            }
        }

        if (breachedZoneName != null) {
            consecutiveBreachCount++
            Log.e(TAG, "Destruction zone breach detected.")

            if (consecutiveBreachCount >= REQUIRED_CONSECUTIVE_BREACHES) {
                Log.e(TAG, "Destruction zone breach confirmed.")
                Log.e(TAG, "!!! INITIATING IMMEDIATE GEOGRAPHIC SUICIDE !!!")
                EventLogger.log(this, SecurityEvent.ZONE_BREACHED)

                stopLocationUpdates()

                CoroutineScope(Dispatchers.IO).launch {
                    val strategy = DefenseCoordinator.resolveStrategy(this@ZoneWipeService)
                    strategy.executeStandardWipe("GEOFENCE_SUICIDE_$breachedZoneName")
                }
                stopSelf()
            }
        } else {
            consecutiveBreachCount = 0
        }
    }

    private fun stopLocationUpdates() {
        val lm = locationManager
        val listener = locationListener
        if (lm != null && listener != null) {
            try {
                lm.removeUpdates(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed removing location updates", e)
            }
        }
        registeredProviders.clear()
        locationListener = null
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
