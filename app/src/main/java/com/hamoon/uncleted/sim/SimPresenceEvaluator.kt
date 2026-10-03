package com.hamoon.uncleted.sim

import java.security.MessageDigest

/**
 * Pure, framework-free decision logic for the physical-SIM presence state
 * machine. All framework integration lives in [SimMonitor]; this object is
 * fully unit-testable without Robolectric/Android stubs.
 *
 * Physical-SIM presence is deliberately decoupled from SIM identity. Modern
 * Android (API 29+) restricts [android.telephony.TelephonyManager.getSimSerialNumber]
 * and [android.telephony.SubscriptionInfo.getIccId] to carrier-privileged
 * callers / system apps. On GrapheneOS with Uncle Ted provisioned as Device
 * Owner, those APIs typically return empty / null, so the previous design
 * (store a carrier fingerprint as "the SIM identity") was both unreliable
 * for detection AND dangerous because it would overwrite the baseline with
 * flaky transient data.
 *
 * This evaluator:
 *   * Classifies subscription snapshots into PRESENT / NO_PHYSICAL / AMBIGUOUS
 *     using the `isEmbedded` flag (API 28+), which GrapheneOS exposes without
 *     privileged access.
 *   * Derives a stable per-physical-SIM fingerprint for replacement detection
 *     when sufficient identity material is available, falling back to
 *     "unavailable" (never a wipe) when it is not.
 */
object SimPresenceEvaluator {

    /** One active subscription's identity material, as surfaced to UncleCarbon. */
    data class SubscriptionRecord(
        val subscriptionId: Int,
        val simSlotIndex: Int,
        val isEmbedded: Boolean,
        val iccId: String?,
        val carrierId: Int,
        val mcc: String?,
        val mnc: String?,
    )

    /** Snapshot of all currently active subscriptions. */
    data class SubscriptionSnapshot(
        val subscriptions: List<SubscriptionRecord>,
        val readPhoneStateGranted: Boolean,
        /** True if the system refused to populate the list (SecurityException / null). */
        val listUnavailable: Boolean = false,
    )

    enum class PhysicalSimObservation {
        /** At least one non-embedded (physical) subscription is active. */
        PRESENT,

        /** The telephony layer reported subscriptions, but none are physical. */
        NO_PHYSICAL,

        /**
         * The telephony layer could not be interrogated right now (permission
         * missing, SecurityException, null list). Treat as unknown; never
         * wipe from an AMBIGUOUS result alone.
         */
        AMBIGUOUS,
    }

    sealed class FingerprintResult {
        /** The physical SIM has a stable identity we can compare across time. */
        data class Available(val fingerprint: String) : FingerprintResult()

        /**
         * No physical SIM is present. Replacement comparison must not run.
         * Removal detection handles this case separately.
         */
        object NoPhysicalSim : FingerprintResult()

        /**
         * The physical SIM is present, but Android did not expose enough
         * material to form a trustworthy identity (common on API 29+ for
         * non-system apps). Replacement detection must fail safely rather
         * than inventing a pseudo-identity.
         */
        object Unavailable : FingerprintResult()
    }

    /** Observation derived from a snapshot. Never conflates ABSENT with permission failure. */
    fun observePhysicalSim(snapshot: SubscriptionSnapshot): PhysicalSimObservation {
        if (!snapshot.readPhoneStateGranted) return PhysicalSimObservation.AMBIGUOUS
        if (snapshot.listUnavailable) return PhysicalSimObservation.AMBIGUOUS
        if (snapshot.subscriptions.isEmpty()) return PhysicalSimObservation.AMBIGUOUS
        return if (snapshot.subscriptions.any { !it.isEmbedded }) {
            PhysicalSimObservation.PRESENT
        } else {
            PhysicalSimObservation.NO_PHYSICAL
        }
    }

    /**
     * Returns the fingerprint for the current *physical* SIM, if the Android
     * APIs provide enough material to produce one. Multi-SIM configurations
     * (physical + eSIM) correctly ignore the eSIM. Multiple physical SIMs
     * contribute a stable per-slot composite.
     */
    fun computePhysicalSimFingerprint(snapshot: SubscriptionSnapshot): FingerprintResult {
        if (!snapshot.readPhoneStateGranted || snapshot.listUnavailable) {
            return FingerprintResult.Unavailable
        }

        val physical = snapshot.subscriptions.filter { !it.isEmbedded }
        if (physical.isEmpty()) {
            // We cannot tell NO_PHYSICAL vs. radio-transient here reliably,
            // but the caller already took the AMBIGUOUS path when needed.
            // An observation of NO_PHYSICAL means "no physical SIM present now."
            return if (snapshot.subscriptions.isNotEmpty()) {
                FingerprintResult.NoPhysicalSim
            } else {
                FingerprintResult.Unavailable
            }
        }

        // Prefer per-SIM ICCID when it is actually available (device owner with
        // carrier privileges, system images). Otherwise fall back to the most
        // stable per-slot material Android exposes without privilege.
        val material = physical
            .sortedBy { it.simSlotIndex }
            .joinToString(separator = "|") { record ->
                val iccId = record.iccId
                if (!iccId.isNullOrBlank() && iccId.length >= 10) {
                    "slot=${record.simSlotIndex};iccid=$iccId"
                } else if (record.carrierId > 0) {
                    // carrierId is a stable Android-assigned identifier for the
                    // carrier the physical SIM belongs to (API 28+). It is NOT
                    // a per-SIM identifier, so by itself it is not enough.
                    "slot=${record.simSlotIndex};carrier=${record.carrierId};mcc=${record.mcc.orEmpty()};mnc=${record.mnc.orEmpty()}"
                } else {
                    "slot=${record.simSlotIndex};mcc=${record.mcc.orEmpty()};mnc=${record.mnc.orEmpty()}"
                }
            }

        // Require that at least one physical SIM gave us an actual ICCID OR
        // (carrierId AND MCC/MNC). Pure MCC/MNC is not a SIM identifier.
        val meaningful = physical.any { r ->
            (!r.iccId.isNullOrBlank() && r.iccId.length >= 10) ||
                (r.carrierId > 0 && !r.mcc.isNullOrBlank() && !r.mnc.isNullOrBlank())
        }
        if (!meaningful) return FingerprintResult.Unavailable

        return FingerprintResult.Available(sha256Hex(material))
    }

    /**
     * Decision for the delayed-verification job: given the stored baseline
     * and the current observation, do we fire the removal tripwire?
     */
    enum class RemovalDecision {
        FIRE_REMOVAL_WIPE,
        NO_ACTION,
        CLEAR_HINTS,
    }

    fun decideRemoval(
        hadPhysicalSimBaseline: Boolean,
        observation: PhysicalSimObservation,
        pendingAbsentHint: Boolean,
        pendingSubscriptionChange: Boolean,
    ): RemovalDecision {
        if (!hadPhysicalSimBaseline) return RemovalDecision.CLEAR_HINTS
        return when (observation) {
            PhysicalSimObservation.PRESENT -> RemovalDecision.CLEAR_HINTS
            PhysicalSimObservation.NO_PHYSICAL -> RemovalDecision.FIRE_REMOVAL_WIPE
            PhysicalSimObservation.AMBIGUOUS -> {
                // Only trust an AMBIGUOUS verification if we previously observed
                // a corroborating hint (an explicit ABSENT broadcast OR a
                // subscription-list change). Pure AMBIGUOUS never wipes.
                if (pendingAbsentHint || pendingSubscriptionChange) {
                    RemovalDecision.FIRE_REMOVAL_WIPE
                } else {
                    RemovalDecision.NO_ACTION
                }
            }
        }
    }

    enum class ReplacementDecision {
        FIRE_REPLACEMENT_WIPE,
        NO_ACTION,
        CAPTURE_BASELINE,
    }

    /**
     * Decides on replacement given the baseline and the current fingerprint.
     *
     * Rules:
     *   * No stored baseline + replacement enabled + fingerprint present
     *       -> CAPTURE_BASELINE (first arming).
     *   * Baseline matches current fingerprint -> NO_ACTION.
     *   * Baseline differs AND current fingerprint is Available -> FIRE wipe.
     *   * Current fingerprint is Unavailable or NoPhysicalSim -> NO_ACTION.
     *     Replacement does NOT fire from identity-retrieval failure, and does
     *     NOT fire from mere absence (that is removal's job).
     */
    fun decideReplacement(
        storedFingerprint: String?,
        current: FingerprintResult,
    ): ReplacementDecision {
        return when (current) {
            is FingerprintResult.Available -> {
                if (storedFingerprint.isNullOrBlank()) {
                    ReplacementDecision.CAPTURE_BASELINE
                } else if (storedFingerprint == current.fingerprint) {
                    ReplacementDecision.NO_ACTION
                } else {
                    ReplacementDecision.FIRE_REPLACEMENT_WIPE
                }
            }
            FingerprintResult.NoPhysicalSim,
            FingerprintResult.Unavailable -> ReplacementDecision.NO_ACTION
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )
}
