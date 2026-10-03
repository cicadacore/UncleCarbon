package com.hamoon.uncleted.sentinels

import kotlin.math.abs

/**
 * Pure, Android-free decision logic for the RF / network-loss (Faraday) sentinel.
 *
 * Isolating the decision rules here keeps the irreversible-action logic unit
 * testable without a device or Robolectric (mirrors SimPresenceEvaluator). The
 * stateful [SpectralSentinel] gathers the live telemetry and feeds it in.
 *
 * Guiding principle: uncertainty must NEVER become justification for a wipe.
 * Any inconclusive input yields [QuarantineDecision.HOLD], never [ADVANCE].
 */
object SpectralDecisionEngine {

    /** Explicit state machine for the RF-loss sentinel. */
    enum class SpectralState {
        NORMAL,
        RF_LOSS_SUSPECTED,
        WIFI_CONFIRMING,
        FARADAY_CONFIRMED,
        QUARANTINE,
        ACTION_TRIGGERED
    }

    /** Positively-determined cellular RF availability. Permission/API failure is INCONCLUSIVE. */
    enum class CellularRfState { AVAILABLE, UNAVAILABLE, INCONCLUSIVE }

    /** Result of a fresh Wi-Fi environmental observation. */
    enum class WifiEnvironmentState { APS_VISIBLE, NO_APS_VISIBLE, INCONCLUSIVE }

    /**
     * What the per-tick evaluation wants the quarantine timer to do.
     *  - CANCEL  : relevant RF is present (or feature off/unlocked/airplane) -> clear the timer.
     *  - ADVANCE : isolation is positively confirmed -> start or continue the timer.
     *  - HOLD    : state is inconclusive -> never advance toward a wipe; fail safe.
     */
    enum class QuarantineDecision { CANCEL, ADVANCE, HOLD }

    data class Inputs(
        val sentinelEnabled: Boolean,
        val airplaneMode: Boolean,
        val screenLocked: Boolean,
        val validatedInternet: Boolean,
        val cellular: CellularRfState,
        val wifiRfConfirmationEnabled: Boolean,
        /** Wi-Fi observation; null when confirmation is disabled or not yet performed. */
        val wifi: WifiEnvironmentState?
    )

    /**
     * Core rule. Order matters: cheap, unambiguous "not isolated" gates first,
     * then positive cellular evidence, then (optionally) Wi-Fi confirmation.
     */
    fun decide(i: Inputs): QuarantineDecision {
        // Feature / context gates — none of these are RF isolation.
        if (!i.sentinelEnabled) return QuarantineDecision.CANCEL
        if (i.airplaneMode) return QuarantineDecision.CANCEL          // intentional radio disablement
        if (!i.screenLocked) return QuarantineDecision.CANCEL
        if (i.validatedInternet) return QuarantineDecision.CANCEL     // real connectivity => not isolated

        // Cellular must be POSITIVELY unavailable. Unknown telephony never advances.
        when (i.cellular) {
            CellularRfState.AVAILABLE -> return QuarantineDecision.CANCEL
            CellularRfState.INCONCLUSIVE -> return QuarantineDecision.HOLD
            CellularRfState.UNAVAILABLE -> { /* continue */ }
        }

        // Wi-Fi confirmation disabled: use the positively confirmed cellular-loss state directly.
        if (!i.wifiRfConfirmationEnabled) return QuarantineDecision.ADVANCE

        // Wi-Fi confirmation enabled: a fresh observation decides.
        return when (i.wifi) {
            WifiEnvironmentState.APS_VISIBLE -> QuarantineDecision.CANCEL
            WifiEnvironmentState.NO_APS_VISIBLE -> QuarantineDecision.ADVANCE
            WifiEnvironmentState.INCONCLUSIVE, null -> QuarantineDecision.HOLD
        }
    }
}

/**
 * Pure quarantine-timer arithmetic, kept Android-free for testing.
 *
 * Timing uses [android.os.SystemClock.elapsedRealtime] values supplied by the
 * caller. [bootRef] (= wallClock - elapsedRealtime at the moment of capture) lets
 * the caller detect a reboot: elapsedRealtime resets across reboot, so a persisted
 * start from a previous boot must never be trusted as elapsed time.
 */
object SpectralQuarantine {

    /** Tolerance for treating two bootRef samples as the same boot (clock drift). */
    const val BOOT_REF_TOLERANCE_MS = 10_000L

    data class State(
        val active: Boolean,
        val startElapsedMs: Long,
        val bootRef: Long
    )

    val INACTIVE = State(active = false, startElapsedMs = 0L, bootRef = 0L)

    data class Step(
        val state: State,
        val fire: Boolean,
        val elapsedMs: Long
    )

    fun sameBoot(a: Long, b: Long): Boolean = abs(a - b) <= BOOT_REF_TOLERANCE_MS

    /**
     * Apply an ADVANCE tick. Resumes a valid same-boot quarantine, otherwise starts
     * a fresh one. [fire] is true only when a valid, continuous quarantine has met
     * [durationMs]; a reboot discontinuity restarts the window rather than firing.
     */
    fun onAdvance(prev: State, nowElapsedMs: Long, nowBootRef: Long, durationMs: Long): Step {
        val continuous = prev.active && sameBoot(prev.bootRef, nowBootRef) && nowElapsedMs >= prev.startElapsedMs
        return if (continuous) {
            val elapsed = nowElapsedMs - prev.startElapsedMs
            Step(prev, fire = elapsed >= durationMs, elapsedMs = elapsed)
        } else {
            // New quarantine, or a reboot/invalid baseline: start the window now.
            Step(State(active = true, startElapsedMs = nowElapsedMs, bootRef = nowBootRef), fire = false, elapsedMs = 0L)
        }
    }

    /** Apply a CANCEL or HOLD tick: the qualifying isolation is not positively true, so clear. */
    fun onNonAdvance(): Step = Step(INACTIVE, fire = false, elapsedMs = 0L)
}
