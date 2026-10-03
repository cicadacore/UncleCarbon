package com.hamoon.uncleted.util

import com.hamoon.uncleted.util.GeoZoneLocationLogic.ProviderAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the "Add Current Location as Wipe Zone" decisions:
 * cached-fix freshness (recent cache succeeds / stale or missing-quality cache
 * forces a fresh request) and provider selection with GPS→network fallback.
 */
class GeoZoneLocationLogicTest {

    private val gps = "gps"
    private val network = "network"

    // ---- Cached fix usability ------------------------------------------------

    @Test fun recentAccurateCache_isUsable() {
        assertTrue(
            GeoZoneLocationLogic.isCachedFixUsable(
                ageMs = 10_000L, hasAccuracy = true, accuracyMeters = 20f
            )
        )
    }

    @Test fun staleCache_isNotUsable() {
        // Older than the 2-minute cap => must request a fresh fix instead.
        assertFalse(
            GeoZoneLocationLogic.isCachedFixUsable(
                ageMs = GeoZoneLocationLogic.MAX_CACHE_AGE_MS + 1L, hasAccuracy = true, accuracyMeters = 20f
            )
        )
    }

    @Test fun cacheWithoutAccuracy_isNotUsable() {
        assertFalse(
            GeoZoneLocationLogic.isCachedFixUsable(
                ageMs = 1_000L, hasAccuracy = false, accuracyMeters = 0f
            )
        )
    }

    @Test fun impreciseCache_isNotUsable() {
        assertFalse(
            GeoZoneLocationLogic.isCachedFixUsable(
                ageMs = 1_000L, hasAccuracy = true, accuracyMeters = GeoZoneLocationLogic.MAX_CACHE_ACCURACY_M + 1f
            )
        )
    }

    @Test fun futureTimestampCache_isNotUsable() {
        assertFalse(
            GeoZoneLocationLogic.isCachedFixUsable(
                ageMs = -5_000L, hasAccuracy = true, accuracyMeters = 10f
            )
        )
    }

    // ---- Provider selection / fallback --------------------------------------

    @Test fun bothProvidersEnabled_gpsPreferredFirst() {
        val result = GeoZoneLocationLogic.selectProviders(
            listOf(
                ProviderAvailability(gps, present = true, enabled = true),
                ProviderAvailability(network, present = true, enabled = true)
            )
        )
        assertEquals(listOf(gps, network), result)
    }

    @Test fun gpsUnavailable_fallsBackToNetwork() {
        val result = GeoZoneLocationLogic.selectProviders(
            listOf(
                ProviderAvailability(gps, present = false, enabled = false),
                ProviderAvailability(network, present = true, enabled = true)
            )
        )
        assertEquals(listOf(network), result)
    }

    @Test fun gpsPresentButDisabled_fallsBackToNetwork() {
        val result = GeoZoneLocationLogic.selectProviders(
            listOf(
                ProviderAvailability(gps, present = true, enabled = false),
                ProviderAvailability(network, present = true, enabled = true)
            )
        )
        assertEquals(listOf(network), result)
    }

    @Test fun noProvidersEnabled_returnsEmpty() {
        val result = GeoZoneLocationLogic.selectProviders(
            listOf(
                ProviderAvailability(gps, present = true, enabled = false),
                ProviderAvailability(network, present = false, enabled = false)
            )
        )
        assertTrue(result.isEmpty())
    }
}
