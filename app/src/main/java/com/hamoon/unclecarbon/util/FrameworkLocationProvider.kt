package com.hamoon.unclecarbon.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Shared, framework-only location access.
 *
 * Uses exclusively standard Android APIs (`android.location.*`) — no Google Play
 * Services / Fused Location Provider — so every location feature in UncleCarbon
 * works on a GrapheneOS install where sandboxed Google Play has never existed.
 *
 * Provider selection is explicit (GPS first, then network); [LocationManager.NETWORK_PROVIDER]
 * is never assumed to exist and GPS-only operation is fully supported. Location
 * choice follows freshness → accuracy → availability → provider rather than
 * blindly trusting whichever provider returned last.
 */
object FrameworkLocationProvider {

    private const val TAG = "FrameworkLocationProvider"

    const val DEFAULT_TIMEOUT_MS = 10_000L
    const val DEFAULT_MAX_CACHE_AGE_MS = 2 * 60_000L // 2 minutes

    // Preferred framework providers, most accurate first.
    val PREFERRED_PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    // When comparing two fixes, a difference larger than this makes the newer one win
    // outright; within the window we fall back to comparing accuracy.
    private const val SIGNIFICANT_TIME_DELTA_MS = 30_000L

    // ---- Freshness / accuracy helpers (shared security logic) ----

    fun locationAgeMs(location: Location): Long = System.currentTimeMillis() - location.time

    fun isFresh(location: Location, maxAgeMs: Long): Boolean = locationAgeMs(location) in 0..maxAgeMs

    fun isAccurateEnough(location: Location, maxAccuracyMeters: Float?): Boolean {
        if (maxAccuracyMeters == null) return true
        return location.hasAccuracy() && location.accuracy <= maxAccuracyMeters
    }

    /**
     * Picks the best of several candidate fixes, preferring fresher and then more
     * accurate readings. Returns null for an empty list.
     */
    fun selectBest(candidates: List<Location>): Location? =
        candidates.reduceOrNull { best, next -> if (isBetter(next, best)) next else best }

    private fun isBetter(candidate: Location, current: Location): Boolean {
        val timeDelta = candidate.time - current.time
        if (timeDelta > SIGNIFICANT_TIME_DELTA_MS) return true
        if (timeDelta < -SIGNIFICANT_TIME_DELTA_MS) return false

        val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAccuracy = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
        if (candidateAccuracy < currentAccuracy) return true
        if (candidateAccuracy > currentAccuracy) return false

        // Equal accuracy within the time window: prefer the newer fix.
        return timeDelta > 0
    }

    private fun safeIsEnabled(lm: LocationManager, provider: String): Boolean =
        try {
            lm.allProviders.contains(provider) && lm.isProviderEnabled(provider)
        } catch (e: Exception) {
            false
        }

    fun enabledPreferredProviders(
        lm: LocationManager,
        preferred: List<String> = PREFERRED_PROVIDERS
    ): List<String> = preferred.filter { safeIsEnabled(lm, it) }

    // ---- One-shot current location ----

    /**
     * Obtains a single current location using framework providers only.
     *
     * Requests a fresh fix from every enabled preferred provider concurrently,
     * then selects the best valid result. A cached [LocationManager.getLastKnownLocation]
     * is used only as a fallback, and only when it is fresh enough
     * ([maxCacheAgeMs]) and (when requested) accurate enough ([maxAccuracyMeters]).
     *
     * Returns null — never a stale location presented as current — if no acceptable
     * fix is available within [timeoutMs]. Never throws for missing permission.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxAccuracyMeters: Float? = null,
        allowCachedFallback: Boolean = true,
        maxCacheAgeMs: Long = DEFAULT_MAX_CACHE_AGE_MS,
        preferredProviders: List<String> = PREFERRED_PROVIDERS
    ): Location? {
        if (!PermissionUtils.hasLocationPermissions(context)) {
            Log.e(TAG, "Location permission missing; cannot obtain location.")
            return null
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.e(TAG, "LocationManager unavailable.")
            return null
        }

        val providers = enabledPreferredProviders(lm, preferredProviders)
        if (providers.isEmpty()) {
            Log.w(TAG, "No enabled framework providers available.")
            return if (allowCachedFallback) {
                validatedLastKnown(lm, preferredProviders, maxAccuracyMeters, maxCacheAgeMs)
            } else {
                null
            }
        }

        val fresh = requestFreshFromProviders(context, lm, providers, timeoutMs)
        val acceptable = fresh.filter { isAccurateEnough(it, maxAccuracyMeters) }
        selectBest(acceptable)?.let { return it }

        if (allowCachedFallback) {
            validatedLastKnown(lm, preferredProviders, maxAccuracyMeters, maxCacheAgeMs)?.let { return it }
        }

        // Only relax accuracy if the caller imposed none; otherwise fail safely.
        return if (maxAccuracyMeters == null) selectBest(fresh) else null
    }

    private suspend fun requestFreshFromProviders(
        context: Context,
        lm: LocationManager,
        providers: List<String>,
        timeoutMs: Long
    ): List<Location> = coroutineScope {
        providers
            .map { provider ->
                async { withTimeoutOrNull(timeoutMs) { requestSingleFix(context, lm, provider) } }
            }
            .awaitAll()
            .filterNotNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFix(
        context: Context,
        lm: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { cont ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    }

                    override fun onProviderEnabled(provider: String) {}

                    override fun onProviderDisabled(disabled: String) {
                        if (disabled == provider) {
                            runCatching { lm.removeUpdates(this) }
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting fix from '$provider'", e)
            if (cont.isActive) cont.resume(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed requesting fix from '$provider'", e)
            if (cont.isActive) cont.resume(null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun validatedLastKnown(
        lm: LocationManager,
        preferredProviders: List<String>,
        maxAccuracyMeters: Float?,
        maxCacheAgeMs: Long
    ): Location? {
        val cached = preferredProviders.mapNotNull { provider ->
            try {
                if (lm.allProviders.contains(provider)) lm.getLastKnownLocation(provider) else null
            } catch (e: SecurityException) {
                null
            } catch (e: Exception) {
                null
            }
        }
        val valid = cached.filter { isFresh(it, maxCacheAgeMs) && isAccurateEnough(it, maxAccuracyMeters) }
        return selectBest(valid)
    }
}
