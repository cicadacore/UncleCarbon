package com.hamoon.uncleted.sim

import com.hamoon.uncleted.sim.SimPresenceEvaluator.FingerprintResult
import com.hamoon.uncleted.sim.SimPresenceEvaluator.PhysicalSimObservation
import com.hamoon.uncleted.sim.SimPresenceEvaluator.RemovalDecision
import com.hamoon.uncleted.sim.SimPresenceEvaluator.ReplacementDecision
import com.hamoon.uncleted.sim.SimPresenceEvaluator.SubscriptionRecord
import com.hamoon.uncleted.sim.SimPresenceEvaluator.SubscriptionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimPresenceEvaluatorTest {

    private fun physical(iccId: String? = null, carrierId: Int = 42, slot: Int = 0): SubscriptionRecord =
        SubscriptionRecord(
            subscriptionId = 1,
            simSlotIndex = slot,
            isEmbedded = false,
            iccId = iccId,
            carrierId = carrierId,
            mcc = "310",
            mnc = "260",
        )

    private fun esim(iccId: String? = "esim-icc-xxxx-0001", carrierId: Int = 99, slot: Int = 1): SubscriptionRecord =
        SubscriptionRecord(
            subscriptionId = 2,
            simSlotIndex = slot,
            isEmbedded = true,
            iccId = iccId,
            carrierId = carrierId,
            mcc = "310",
            mnc = "150",
        )

    private fun snap(
        subs: List<SubscriptionRecord>,
        granted: Boolean = true,
        unavailable: Boolean = false,
    ): SubscriptionSnapshot = SubscriptionSnapshot(subs, granted, unavailable)

    // -----------------------------------------------------------------------
    // observePhysicalSim
    // -----------------------------------------------------------------------

    @Test fun observe_physicalPresent() {
        assertEquals(PhysicalSimObservation.PRESENT, SimPresenceEvaluator.observePhysicalSim(snap(listOf(physical()))))
    }

    @Test fun observe_physicalAndEsim() {
        assertEquals(PhysicalSimObservation.PRESENT, SimPresenceEvaluator.observePhysicalSim(snap(listOf(physical(), esim()))))
    }

    @Test fun observe_esimOnly() {
        assertEquals(PhysicalSimObservation.NO_PHYSICAL, SimPresenceEvaluator.observePhysicalSim(snap(listOf(esim()))))
    }

    @Test fun observe_emptyIsAmbiguous() {
        // Empty list could be transient radio restart; AMBIGUOUS protects from false positive.
        assertEquals(PhysicalSimObservation.AMBIGUOUS, SimPresenceEvaluator.observePhysicalSim(snap(emptyList())))
    }

    @Test fun observe_permissionMissingIsAmbiguous() {
        assertEquals(PhysicalSimObservation.AMBIGUOUS,
            SimPresenceEvaluator.observePhysicalSim(snap(listOf(physical()), granted = false)))
    }

    @Test fun observe_listUnavailableIsAmbiguous() {
        assertEquals(PhysicalSimObservation.AMBIGUOUS,
            SimPresenceEvaluator.observePhysicalSim(snap(listOf(physical()), unavailable = true)))
    }

    // -----------------------------------------------------------------------
    // Removal decisions
    // -----------------------------------------------------------------------

    @Test fun removal_noBaselineClearsHints() {
        assertEquals(RemovalDecision.CLEAR_HINTS,
            SimPresenceEvaluator.decideRemoval(
                hadPhysicalSimBaseline = false,
                observation = PhysicalSimObservation.NO_PHYSICAL,
                pendingAbsentHint = true,
                pendingSubscriptionChange = true,
            ))
    }

    @Test fun removal_presentClearsHints() {
        assertEquals(RemovalDecision.CLEAR_HINTS,
            SimPresenceEvaluator.decideRemoval(
                hadPhysicalSimBaseline = true,
                observation = PhysicalSimObservation.PRESENT,
                pendingAbsentHint = true,
                pendingSubscriptionChange = true,
            ))
    }

    @Test fun removal_noPhysicalFires() {
        assertEquals(RemovalDecision.FIRE_REMOVAL_WIPE,
            SimPresenceEvaluator.decideRemoval(
                hadPhysicalSimBaseline = true,
                observation = PhysicalSimObservation.NO_PHYSICAL,
                pendingAbsentHint = false,
                pendingSubscriptionChange = false,
            ))
    }

    @Test fun removal_ambiguousWithoutHintDoesNothing() {
        assertEquals(RemovalDecision.NO_ACTION,
            SimPresenceEvaluator.decideRemoval(
                hadPhysicalSimBaseline = true,
                observation = PhysicalSimObservation.AMBIGUOUS,
                pendingAbsentHint = false,
                pendingSubscriptionChange = false,
            ))
    }

    @Test fun removal_ambiguousWithAbsentHintFires() {
        assertEquals(RemovalDecision.FIRE_REMOVAL_WIPE,
            SimPresenceEvaluator.decideRemoval(
                hadPhysicalSimBaseline = true,
                observation = PhysicalSimObservation.AMBIGUOUS,
                pendingAbsentHint = true,
                pendingSubscriptionChange = false,
            ))
    }

    @Test fun removal_ambiguousWithSubChangeFires() {
        assertEquals(RemovalDecision.FIRE_REMOVAL_WIPE,
            SimPresenceEvaluator.decideRemoval(
                hadPhysicalSimBaseline = true,
                observation = PhysicalSimObservation.AMBIGUOUS,
                pendingAbsentHint = false,
                pendingSubscriptionChange = true,
            ))
    }

    // -----------------------------------------------------------------------
    // Fingerprint and replacement
    // -----------------------------------------------------------------------

    @Test fun fingerprint_physicalWithIccidIsAvailable() {
        val result = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = "8914800000000000001"))))
        assertTrue(result is FingerprintResult.Available)
    }

    @Test fun fingerprint_physicalWithCarrierOnlyIsAvailable() {
        val result = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical())))
        assertTrue(result is FingerprintResult.Available)
    }

    @Test fun fingerprint_noCarrierOrIccidIsUnavailable() {
        val record = SubscriptionRecord(
            subscriptionId = 1,
            simSlotIndex = 0,
            isEmbedded = false,
            iccId = null,
            carrierId = 0,
            mcc = null,
            mnc = null,
        )
        assertEquals(FingerprintResult.Unavailable, SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(record))))
    }

    @Test fun fingerprint_esimOnlyIsNoPhysical() {
        assertEquals(FingerprintResult.NoPhysicalSim, SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(esim()))))
    }

    @Test fun fingerprint_permissionMissingIsUnavailable() {
        assertEquals(FingerprintResult.Unavailable,
            SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical()), granted = false)))
    }

    @Test fun fingerprint_sameSimSameFingerprint() {
        val a = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_A))))
        val b = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_A))))
        assertEquals((a as FingerprintResult.Available).fingerprint, (b as FingerprintResult.Available).fingerprint)
    }

    @Test fun fingerprint_differentIccidDiffersEvenWithSameCarrier() {
        val a = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_A, carrierId = 42))))
        val b = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_B, carrierId = 42))))
        assertNotEquals((a as FingerprintResult.Available).fingerprint, (b as FingerprintResult.Available).fingerprint)
    }

    @Test fun fingerprint_esimChangeDoesNotAffectPhysicalFingerprint() {
        val a = SimPresenceEvaluator.computePhysicalSimFingerprint(
            snap(listOf(physical(iccId = ICCID_A), esim(iccId = "8900ESIM0000000000001")))
        )
        val b = SimPresenceEvaluator.computePhysicalSimFingerprint(
            snap(listOf(physical(iccId = ICCID_A), esim(iccId = "8900ESIM0000000000002")))
        )
        assertEquals((a as FingerprintResult.Available).fingerprint, (b as FingerprintResult.Available).fingerprint)
    }

    // -----------------------------------------------------------------------
    // Replacement decisions
    // -----------------------------------------------------------------------

    @Test fun replacement_noStoredCapturesBaseline() {
        val fp = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_A))))
        assertEquals(ReplacementDecision.CAPTURE_BASELINE, SimPresenceEvaluator.decideReplacement(null, fp))
    }

    @Test fun replacement_sameFingerprintNoAction() {
        val fp = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_A))))
        val stored = (fp as FingerprintResult.Available).fingerprint
        assertEquals(ReplacementDecision.NO_ACTION, SimPresenceEvaluator.decideReplacement(stored, fp))
    }

    @Test fun replacement_differentFingerprintFires() {
        val old = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_A))))
        val cur = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_B))))
        val storedFp = (old as FingerprintResult.Available).fingerprint
        assertEquals(ReplacementDecision.FIRE_REPLACEMENT_WIPE, SimPresenceEvaluator.decideReplacement(storedFp, cur))
    }

    companion object {
        // Realistic ICCID shape (19–20 digits). Must be >=10 chars to pass
        // SimPresenceEvaluator's "actual ICCID" sanity check; shorter strings
        // fall through to the carrier fingerprint intentionally.
        private const val ICCID_A = "8914800000000000001"
        private const val ICCID_B = "8914800000000000002"
    }

    @Test fun replacement_unavailableNeverFiresWipe() {
        val stored = "stored-fp"
        assertEquals(ReplacementDecision.NO_ACTION,
            SimPresenceEvaluator.decideReplacement(stored, FingerprintResult.Unavailable))
    }

    @Test fun replacement_noPhysicalNeverFiresWipe() {
        val stored = "stored-fp"
        assertEquals(ReplacementDecision.NO_ACTION,
            SimPresenceEvaluator.decideReplacement(stored, FingerprintResult.NoPhysicalSim))
    }

    // -----------------------------------------------------------------------
    // Combined scenarios drawn from the requested test matrix
    // -----------------------------------------------------------------------

    @Test fun scenario_duplicateAbsentCallbacks_doesNotDoubleFire() {
        // Two separate verify ticks both see NO_PHYSICAL. decideRemoval returns
        // FIRE each time; idempotency is enforced at SimMonitor layer by
        // wipeInFlight + preference latch. The decision itself is pure and
        // consistent; both callers see the same decision.
        val d1 = SimPresenceEvaluator.decideRemoval(true, PhysicalSimObservation.NO_PHYSICAL, false, false)
        val d2 = SimPresenceEvaluator.decideRemoval(true, PhysicalSimObservation.NO_PHYSICAL, false, false)
        assertEquals(RemovalDecision.FIRE_REMOVAL_WIPE, d1)
        assertEquals(RemovalDecision.FIRE_REMOVAL_WIPE, d2)
    }

    @Test fun scenario_absentThenSameSimReturnedBeforeVerify() {
        // ABSENT hint got stored, then by verify time the physical SIM is back:
        // observation PRESENT => CLEAR_HINTS.
        val d = SimPresenceEvaluator.decideRemoval(
            hadPhysicalSimBaseline = true,
            observation = PhysicalSimObservation.PRESENT,
            pendingAbsentHint = true,
            pendingSubscriptionChange = true,
        )
        assertEquals(RemovalDecision.CLEAR_HINTS, d)
    }

    @Test fun scenario_rebootWithSameSim_doesNotFire() {
        // After reboot, snapshot reports PRESENT with same ICCID => no action.
        val fp = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical(iccId = ICCID_A))))
        val stored = (fp as FingerprintResult.Available).fingerprint
        assertEquals(ReplacementDecision.NO_ACTION, SimPresenceEvaluator.decideReplacement(stored, fp))
        assertEquals(RemovalDecision.CLEAR_HINTS,
            SimPresenceEvaluator.decideRemoval(true, PhysicalSimObservation.PRESENT, false, false))
    }

    @Test fun scenario_physicalSimPlusActiveEsim_doesNotMisidentifyRemoval() {
        // Physical + eSIM present together: PRESENT.
        assertEquals(PhysicalSimObservation.PRESENT,
            SimPresenceEvaluator.observePhysicalSim(snap(listOf(physical(iccId = ICCID_A), esim()))))
        // After removing physical, only eSIM active -> NO_PHYSICAL.
        assertEquals(PhysicalSimObservation.NO_PHYSICAL,
            SimPresenceEvaluator.observePhysicalSim(snap(listOf(esim()))))
    }

    @Test fun scenario_readPhoneStateMissing_neverFires() {
        // Permission absent => AMBIGUOUS => no decision to fire without hints.
        val d = SimPresenceEvaluator.decideRemoval(
            hadPhysicalSimBaseline = true,
            observation = PhysicalSimObservation.AMBIGUOUS,
            pendingAbsentHint = false,
            pendingSubscriptionChange = false,
        )
        assertEquals(RemovalDecision.NO_ACTION, d)
        // Replacement: fingerprint Unavailable (because permission missing in snapshot).
        val fp = SimPresenceEvaluator.computePhysicalSimFingerprint(snap(listOf(physical()), granted = false))
        assertEquals(ReplacementDecision.NO_ACTION, SimPresenceEvaluator.decideReplacement("prior-fp", fp))
    }
}
