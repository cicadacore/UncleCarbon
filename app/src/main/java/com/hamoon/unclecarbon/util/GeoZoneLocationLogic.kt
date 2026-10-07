package com.hamoon.unclecarbon.util

/**
 * Pure, framework-free decision logic for the interactive "Add Current Location
 * as Wipe Zone" flow.
 *
 * This logic ONLY helps choose a coordinate for zone creation. It can never, by
 * construction, start a wipe: it returns booleans, provider-name lists and error
 * classifications — nothing here touches [android.app.admin.DevicePolicyManager]
 * or the defense strategy. Keeping it Android-free also makes it unit-testable.
 */
object GeoZoneLocationLogic {

    /** How old a cached fix may be before it is rejected as a fast path. */
    const val MAX_CACHE_AGE_MS: Long = 120_000L

    /** Worst accuracy (metres) accepted for a cached fast-path fix. */
    const val MAX_CACHE_ACCURACY_M: Float = 100f

    /** Classified reasons an interactive location acquisition can fail. */
    enum class LocationAcquisitionError {
        PERMISSION_DENIED,
        SERVICES_DISABLED,
        NO_PROVIDER,
        TIMEOUT,
        UNAVAILABLE
    }

    /** Presence/enabled state of a single framework location provider. */
    data class ProviderAvailability(
        val name: String,
        val present: Boolean,
        val enabled: Boolean
    )

    /**
     * Decides whether a cached last-known fix is fresh and accurate enough to use
     * immediately instead of requesting a new one.
     *
     * A missing cached location is represented by the caller simply not calling
     * this; here we only judge an existing fix. Obviously stale fixes, fixes with
     * no accuracy, and imprecise fixes are rejected so a fresh request is made.
     */
    fun isCachedFixUsable(
        ageMs: Long,
        hasAccuracy: Boolean,
        accuracyMeters: Float,
        maxAgeMs: Long = MAX_CACHE_AGE_MS,
        maxAccuracyMeters: Float = MAX_CACHE_ACCURACY_M
    ): Boolean {
        if (ageMs < 0L) return false            // clock skew / future timestamp
        if (ageMs > maxAgeMs) return false       // stale
        if (!hasAccuracy) return false           // unknown accuracy is not trustworthy
        if (accuracyMeters <= 0f) return false   // invalid
        return accuracyMeters <= maxAccuracyMeters
    }

    /**
     * Returns the usable providers in priority order (GPS preferred, then
     * network), keeping only those that are both present on the device and
     * currently enabled. When GPS is unavailable this naturally falls back to the
     * network provider rather than yielding nothing.
     *
     * [ordered] must already be in the caller's preference order.
     */
    fun selectProviders(ordered: List<ProviderAvailability>): List<String> =
        ordered.filter { it.present && it.enabled }.map { it.name }
}
