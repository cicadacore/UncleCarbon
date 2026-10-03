package com.hamoon.uncleted.sentinels

import com.hamoon.uncleted.sentinels.SpectralDecisionEngine.CellularRfState
import com.hamoon.uncleted.sentinels.SpectralDecisionEngine.Inputs
import com.hamoon.uncleted.sentinels.SpectralDecisionEngine.QuarantineDecision
import com.hamoon.uncleted.sentinels.SpectralDecisionEngine.WifiEnvironmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the RF-loss sentinel decision rules and quarantine timer.
 * These require no Android framework (mirrors SimPresenceEvaluatorTest).
 */
class SpectralDecisionEngineTest {

    private fun inputs(
        sentinelEnabled: Boolean = true,
        airplaneMode: Boolean = false,
        screenLocked: Boolean = true,
        validatedInternet: Boolean = false,
        cellular: CellularRfState = CellularRfState.UNAVAILABLE,
        wifiRfConfirmationEnabled: Boolean = true,
        wifi: WifiEnvironmentState? = null
    ) = Inputs(
        sentinelEnabled, airplaneMode, screenLocked, validatedInternet,
        cellular, wifiRfConfirmationEnabled, wifi
    )

    // ---- Context / feature gates ----

    @Test fun sentinelDisabled_cancels() {
        assertEquals(QuarantineDecision.CANCEL, SpectralDecisionEngine.decide(inputs(sentinelEnabled = false)))
    }

    @Test fun airplaneMode_isSuppressed_cancels() { // Test 22
        assertEquals(
            QuarantineDecision.CANCEL,
            SpectralDecisionEngine.decide(inputs(airplaneMode = true, cellular = CellularRfState.UNAVAILABLE, wifi = WifiEnvironmentState.NO_APS_VISIBLE))
        )
    }

    @Test fun screenUnlocked_cancels() {
        assertEquals(QuarantineDecision.CANCEL, SpectralDecisionEngine.decide(inputs(screenLocked = false)))
    }

    @Test fun validatedInternet_cancels() {
        assertEquals(QuarantineDecision.CANCEL, SpectralDecisionEngine.decide(inputs(validatedInternet = true)))
    }

    // ---- Cellular ----

    @Test fun cellularAvailable_cancels() { // Test 16 (RF returns)
        assertEquals(QuarantineDecision.CANCEL, SpectralDecisionEngine.decide(inputs(cellular = CellularRfState.AVAILABLE)))
    }

    @Test fun cellularInconclusive_holds() { // Test 8 (permission failure -> INCONCLUSIVE cellular)
        assertEquals(QuarantineDecision.HOLD, SpectralDecisionEngine.decide(inputs(cellular = CellularRfState.INCONCLUSIVE)))
    }

    // ---- Wi-Fi confirmation ENABLED ----

    @Test fun wifiEnabled_apsVisible_cancels() { // Tests 6 & 15 (AP visible -> cancel)
        assertEquals(
            QuarantineDecision.CANCEL,
            SpectralDecisionEngine.decide(inputs(cellular = CellularRfState.UNAVAILABLE, wifi = WifiEnvironmentState.APS_VISIBLE))
        )
    }

    @Test fun wifiEnabled_zeroFreshAps_advances() { // Test 7 (Faraday candidate)
        assertEquals(
            QuarantineDecision.ADVANCE,
            SpectralDecisionEngine.decide(inputs(cellular = CellularRfState.UNAVAILABLE, wifi = WifiEnvironmentState.NO_APS_VISIBLE))
        )
    }

    @Test fun wifiEnabled_inconclusive_holds() { // Tests 9,10,11,17 (throttle/fail/stale/mid-quarantine)
        assertEquals(
            QuarantineDecision.HOLD,
            SpectralDecisionEngine.decide(inputs(cellular = CellularRfState.UNAVAILABLE, wifi = WifiEnvironmentState.INCONCLUSIVE))
        )
    }

    @Test fun wifiEnabled_noObservationYet_holds() {
        assertEquals(
            QuarantineDecision.HOLD,
            SpectralDecisionEngine.decide(inputs(cellular = CellularRfState.UNAVAILABLE, wifi = null))
        )
    }

    // ---- Wi-Fi confirmation DISABLED ----

    @Test fun wifiDisabled_confirmedCellularLoss_advances() { // Test 12
        assertEquals(
            QuarantineDecision.ADVANCE,
            SpectralDecisionEngine.decide(inputs(wifiRfConfirmationEnabled = false, cellular = CellularRfState.UNAVAILABLE, wifi = null))
        )
    }

    @Test fun wifiDisabled_cellularInconclusive_holds() {
        assertEquals(
            QuarantineDecision.HOLD,
            SpectralDecisionEngine.decide(inputs(wifiRfConfirmationEnabled = false, cellular = CellularRfState.INCONCLUSIVE))
        )
    }

    @Test fun wifiDisabled_cellularAvailable_cancels() {
        assertEquals(
            QuarantineDecision.CANCEL,
            SpectralDecisionEngine.decide(inputs(wifiRfConfirmationEnabled = false, cellular = CellularRfState.AVAILABLE))
        )
    }
}

class SpectralQuarantineTest {

    private val durationMs = 1_800_000L // 30 min
    private val bootRef = 1_000_000L

    @Test fun advanceFromInactive_startsWithoutFiring() {
        val step = SpectralQuarantine.onAdvance(SpectralQuarantine.INACTIVE, nowElapsedMs = 5_000L, nowBootRef = bootRef, durationMs = durationMs)
        assertTrue(step.state.active)
        assertFalse(step.fire)
        assertEquals(5_000L, step.state.startElapsedMs)
        assertEquals(0L, step.elapsedMs)
    }

    @Test fun advanceContinues_belowDuration_doesNotFire() {
        val start = SpectralQuarantine.State(active = true, startElapsedMs = 1_000L, bootRef = bootRef)
        val step = SpectralQuarantine.onAdvance(start, nowElapsedMs = 1_000L + 60_000L, nowBootRef = bootRef, durationMs = durationMs)
        assertFalse(step.fire)
        assertEquals(60_000L, step.elapsedMs)
        assertEquals(start, step.state)
    }

    @Test fun advanceReachesDuration_firesOnce() { // Test 18
        val start = SpectralQuarantine.State(active = true, startElapsedMs = 1_000L, bootRef = bootRef)
        val step = SpectralQuarantine.onAdvance(start, nowElapsedMs = 1_000L + durationMs, nowBootRef = bootRef, durationMs = durationMs)
        assertTrue(step.fire)
        assertEquals(durationMs, step.elapsedMs)
    }

    @Test fun rebootDiscontinuity_restartsInsteadOfFiring() { // Test 19
        // Persisted start from a previous boot; current bootRef differs beyond tolerance.
        val persisted = SpectralQuarantine.State(active = true, startElapsedMs = 1_000L, bootRef = bootRef)
        val newBootRef = bootRef + 10 * 60_000L // clearly a different boot
        val step = SpectralQuarantine.onAdvance(persisted, nowElapsedMs = 2_000L, nowBootRef = newBootRef, durationMs = durationMs)
        assertFalse("must not fire from a stale cross-boot deadline", step.fire)
        assertTrue(step.state.active)
        assertEquals(2_000L, step.state.startElapsedMs)
        assertEquals(newBootRef, step.state.bootRef)
    }

    @Test fun nonAdvance_clearsQuarantine() {
        val step = SpectralQuarantine.onNonAdvance()
        assertFalse(step.state.active)
        assertFalse(step.fire)
    }

    @Test fun sameBoot_withinTolerance() {
        assertTrue(SpectralQuarantine.sameBoot(1_000_000L, 1_000_000L + 5_000L))
        assertFalse(SpectralQuarantine.sameBoot(1_000_000L, 1_000_000L + 60_000L))
    }
}
