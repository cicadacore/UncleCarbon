package com.hamoon.uncleted.data

import org.junit.Assert.*
import org.junit.Test

class SecurityMonitoringRulesTest {
    private fun pkg(internet: Boolean = false, signer: String = "old", lineage: Set<String> = setOf(signer)) =
        MonitoredPackage("org.example.app", "Example", "1.0", 1, 10, 10, internet,
            if (internet) setOf("android.permission.INTERNET") else emptySet(), internet, true, signer, lineage)

    @Test fun onlyManifestTransitionToInternetIsEscalation() {
        assertTrue(SecurityMonitoringRules.newlyRequestsInternet(pkg(false), pkg(true)))
        assertFalse(SecurityMonitoringRules.newlyRequestsInternet(pkg(true), pkg(true)))
        assertFalse(SecurityMonitoringRules.newlyRequestsInternet(null, pkg(true)))
        assertFalse(SecurityMonitoringRules.newlyRequestsInternet(pkg(false), pkg(false)))
    }

    @Test fun signingLineageDistinguishesRotationFromUnrelatedSigner() {
        assertFalse(SecurityMonitoringRules.unexpectedSignerChange(pkg(signer = "old"), pkg(signer = "new", lineage = setOf("old", "new"))))
        assertTrue(SecurityMonitoringRules.unexpectedSignerChange(pkg(signer = "old"), pkg(signer = "new", lineage = setOf("new"))))
        assertFalse(SecurityMonitoringRules.unexpectedSignerChange(null, pkg(signer = "new")))
    }

    @Test fun thresholdsAreRestrictedToSupportedChoices() {
        assertEquals(setOf(1, 3, 5, 10), SecurityMonitoringRules.validFailureThresholds)
        listOf(1, 3, 5, 10).forEach { assertTrue(SecurityMonitoringRules.validateFailureThreshold(it)) }
        listOf(-1, 0, 2, 4, 11, Int.MAX_VALUE).forEach { assertFalse(SecurityMonitoringRules.validateFailureThreshold(it)) }
    }

    @Test fun aThresholdAlertsOnceUntilTheFailureStreakResets() {
        assertFalse(SecurityMonitoringRules.shouldAlertFailure(2, 3, 0, true))
        assertTrue(SecurityMonitoringRules.shouldAlertFailure(3, 3, 0, true))
        assertFalse(SecurityMonitoringRules.shouldAlertFailure(4, 3, 3, true))
        assertFalse(SecurityMonitoringRules.shouldAlertFailure(5, 3, 0, false))
        assertFalse(SecurityMonitoringRules.shouldAlertFailure(5, 4, 0, true))
    }

    @Test fun platformCounterDeduplicatesCallbacksAndLocalCounterCanCountWhenUnavailable() {
        assertFalse(SecurityMonitoringRules.acceptsPlatformFailure(3, 3))
        assertTrue(SecurityMonitoringRules.acceptsPlatformFailure(4, 3))
        assertTrue(SecurityMonitoringRules.acceptsPlatformFailure(null, 3))
        assertEquals(4, SecurityMonitoringRules.nextFailureCount(null, 3))
        assertEquals(4, SecurityMonitoringRules.nextFailureCount(4, 3))
        assertEquals(0, SecurityMonitoringRules.failureCountAfterSuccess())
        assertEquals(1, SecurityMonitoringRules.nextFailureCount(null, SecurityMonitoringRules.failureCountAfterSuccess()))
        assertFalse(SecurityMonitoringRules.passwordCallbacksAvailable(false))
        assertTrue(SecurityMonitoringRules.passwordCallbacksAvailable(true))
    }

    @Test fun packageTransitionsCoverInstallUpdateRemovalAndStateWithoutCallingThemThreats() {
        val old = pkg(false)
        val updated = pkg(true)
        val install = SecurityMonitoringRules.classifyPackageTransition(null, updated, "ADDED")
        assertEquals(listOf("APP_INSTALLED"), install.map { it.type })
        assertEquals(MonitoringSeverity.LOW, install.single().severity)
        val update = SecurityMonitoringRules.classifyPackageTransition(old, updated, "UPDATED")
        assertEquals(setOf("APP_UPDATED", "INTERNET_DECLARED"), update.map { it.type }.toSet())
        assertTrue(update.first { it.type == "INTERNET_DECLARED" }.explanation.contains("normal permission"))
        assertEquals("APP_REMOVED", SecurityMonitoringRules.classifyPackageTransition(old, null, "REMOVED").single().type)
        assertTrue(SecurityMonitoringRules.classifyPackageTransition(old, old, "UPDATED").isEmpty())
        assertTrue(SecurityMonitoringRules.classifyPackageTransition(old, old, "CHANGED").isEmpty())
        assertEquals("APP_STATE_CHANGED", SecurityMonitoringRules.classifyPackageTransition(old, old.copy(enabled = false), "CHANGED").single().type)
        assertTrue(SecurityMonitoringRules.classifyPackageTransition(old, updated, "UPDATED", trusted = true).isEmpty())
        assertTrue(SecurityMonitoringRules.isTrustedPackage("org.example.app", setOf("org.example.app")))
        assertFalse(SecurityMonitoringRules.isTrustedPackage("org.example.other", setOf("org.example.app")))
    }
}
