package com.hamoon.unclecarbon.sim

import com.hamoon.unclecarbon.data.SecurityEvent
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hamoon.unclecarbon.core.DefenseCoordinator
import com.hamoon.unclecarbon.data.SecurityPreferences
import com.hamoon.unclecarbon.receivers.AdminReceiver
import com.hamoon.unclecarbon.services.PanicActionService
import com.hamoon.unclecarbon.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Framework-integration layer for the physical-SIM state machine.
 *
 * Pure decisions live in [SimPresenceEvaluator]. This class owns:
 *   * Permission probing (and best-effort Device Owner self-grant of
 *     READ_PHONE_STATE when the user has not granted it).
 *   * Reading the current [SubscriptionManager] snapshot.
 *   * Receiving broadcast / subscription-listener hints.
 *   * Scheduling a delayed verification [SimRemovalVerifyJobService] so a
 *     transient ABSENT from a modem reset / airplane-mode flip never wipes.
 *   * Idempotent dispatch to [DefenseCoordinator.resolveStrategy] so one
 *     physical event cannot cause several concurrent wipe requests from the
 *     receiver, the subscription listener, the verify job, and the boot
 *     reconciler all firing in parallel.
 *
 * Preferences layout (device-protected + CE-mirrored where available):
 *   BFU_SIM_HAD_PHYSICAL          : Boolean   baseline presence
 *   BFU_SIM_PHYSICAL_FP           : String?   SHA-256 fingerprint
 *   BFU_SIM_PENDING_ABSENT_HINT   : Boolean
 *   BFU_SIM_PENDING_SUB_CHANGE    : Boolean
 *   BFU_SIM_WIPE_IN_FLIGHT        : Boolean   idempotency latch
 */
object SimMonitor {

    private const val TAG = "SimMonitor"

    /** Delay before verifying an ABSENT hint. Short enough to feel immediate, */
    /** long enough to outlast normal radio restarts / airplane-mode flips. */
    const val ABSENT_VERIFY_DELAY_MS = 7_000L

    /** Grace after boot during which raw ABSENT broadcasts never fire. */
    private const val BOOT_GRACE_MS = 90_000L

    const val VERIFY_JOB_ID = 60301

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val wipeInFlight = AtomicBoolean(false)

    // -----------------------------------------------------------------------
    // Hint entry points
    // -----------------------------------------------------------------------

    /** Called by [com.hamoon.unclecarbon.receivers.SimChangeReceiver]. */
    fun onSimStateChangedHint(context: Context, simStateExtra: String?) {
        if (!anyFeatureEnabled(context)) return
        when (simStateExtra?.uppercase()) {
            "READY", "LOADED", "IMSI" -> scope.launch { syncBaselineAndReplacement(context) }
            "ABSENT" -> scope.launch { maybeScheduleRemovalVerify(context, explicitAbsentHint = true) }
            else -> {
                // UNKNOWN, NOT_READY, PIN_REQUIRED, PUK_REQUIRED, NETWORK_LOCKED,
                // CARD_IO_ERROR, CARD_RESTRICTED: do not touch the baseline, do
                // not fire removal; these are transient radio states.
                Log.d(TAG, "Ignoring transient SIM state hint: $simStateExtra")
            }
        }
    }

    /** Called by the [SubscriptionManager.OnSubscriptionsChangedListener]. */
    fun onSubscriptionsChanged(context: Context) {
        if (!anyFeatureEnabled(context)) return
        scope.launch {
            mutex.withLock {
                val snapshot = readSnapshot(context)
                val observation = SimPresenceEvaluator.observePhysicalSim(snapshot)
                when (observation) {
                    SimPresenceEvaluator.PhysicalSimObservation.PRESENT -> {
                        clearRemovalHints(context)
                        captureBaselineIfNeeded(context, snapshot)
                        maybeCaptureReplacementBaselineOrEvaluate(context, snapshot)
                    }
                    SimPresenceEvaluator.PhysicalSimObservation.NO_PHYSICAL,
                    SimPresenceEvaluator.PhysicalSimObservation.AMBIGUOUS -> {
                        if (SecurityPreferences.hadPhysicalSimBaseline(context)) {
                            SecurityPreferences.setSimPendingSubscriptionChange(context, true)
                            scheduleRemovalVerify(context)
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Verification job entry point
    // -----------------------------------------------------------------------

    /** Called by [SimRemovalVerifyJobService] after [ABSENT_VERIFY_DELAY_MS]. */
    fun verifyPendingRemoval(context: Context) {
        scope.launch { verifyPendingRemovalLocked(context) }
    }

    private suspend fun verifyPendingRemovalLocked(context: Context) {
        mutex.withLock {
            if (!anyRemovalFeatureEnabled(context)) return
            if (!SecurityPreferences.hadPhysicalSimBaseline(context)) {
                clearRemovalHints(context)
                return
            }

            val snapshot = readSnapshot(context)
            val observation = SimPresenceEvaluator.observePhysicalSim(snapshot)
            val decision = SimPresenceEvaluator.decideRemoval(
                hadPhysicalSimBaseline = true,
                observation = observation,
                pendingAbsentHint = SecurityPreferences.getSimPendingAbsentHint(context),
                pendingSubscriptionChange = SecurityPreferences.getSimPendingSubscriptionChange(context),
            )

            when (decision) {
                SimPresenceEvaluator.RemovalDecision.FIRE_REMOVAL_WIPE -> {
                    Log.e(TAG, "Verified SIM removal (observation=$observation). Dispatching wipe.")
                    EventLogger.log(context, SecurityEvent.SIM_REMOVED)
                    clearRemovalHints(context)
                    SecurityPreferences.setHadPhysicalSimBaseline(context, false)
                    dispatchStandardWipe(context, reason = "SIM_REMOVED_TRIPWIRE")
                }
                SimPresenceEvaluator.RemovalDecision.NO_ACTION -> {
                    Log.i(TAG, "Removal verification ambiguous without corroborating hint. Holding.")
                }
                SimPresenceEvaluator.RemovalDecision.CLEAR_HINTS -> {
                    clearRemovalHints(context)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Boot reconciliation
    // -----------------------------------------------------------------------

    /**
     * Called from [com.hamoon.unclecarbon.receivers.BootCompletedReceiver] once
     * per boot. Handles the "SIM removed while powered off" and "SIM replaced
     * while powered off" cases without ever discarding a trustworthy stored
     * identity before comparison.
     */
    fun reconcileOnBoot(context: Context) {
        if (!anyFeatureEnabled(context)) return
        scope.launch {
            mutex.withLock {
                val snapshot = readSnapshot(context)
                val observation = SimPresenceEvaluator.observePhysicalSim(snapshot)
                Log.i(TAG, "Boot reconciliation: observation=$observation, hadBaseline=${SecurityPreferences.hadPhysicalSimBaseline(context)}")

                when (observation) {
                    SimPresenceEvaluator.PhysicalSimObservation.PRESENT -> {
                        // Compare before overwriting the baseline.
                        maybeCaptureReplacementBaselineOrEvaluate(context, snapshot)
                        if (!SecurityPreferences.hadPhysicalSimBaseline(context)) {
                            SecurityPreferences.setHadPhysicalSimBaseline(context, true)
                        }
                        clearRemovalHints(context)
                    }
                    SimPresenceEvaluator.PhysicalSimObservation.NO_PHYSICAL -> {
                        if (SecurityPreferences.hadPhysicalSimBaseline(context) &&
                            anyRemovalFeatureEnabled(context)) {
                            // Powered-off removal: schedule a verify (not an immediate wipe)
                            // so transient boot modem states don't false-positive.
                            SecurityPreferences.setSimPendingSubscriptionChange(context, true)
                            scheduleRemovalVerify(context)
                        }
                    }
                    SimPresenceEvaluator.PhysicalSimObservation.AMBIGUOUS -> {
                        // Permission not yet granted, or telephony not up yet.
                        // Try to self-grant READ_PHONE_STATE, then re-schedule
                        // a verify so we re-evaluate once the radio settles.
                        selfGrantReadPhoneStateIfDeviceOwner(context)
                        if (SecurityPreferences.hadPhysicalSimBaseline(context) &&
                            anyRemovalFeatureEnabled(context)) {
                            scheduleRemovalVerify(context)
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Baseline helpers
    // -----------------------------------------------------------------------

    private fun maybeCaptureReplacementBaselineOrEvaluate(
        context: Context,
        snapshot: SimPresenceEvaluator.SubscriptionSnapshot,
    ) {
        val fingerprint = SimPresenceEvaluator.computePhysicalSimFingerprint(snapshot)
        val stored = SecurityPreferences.getPhysicalSimFingerprint(context)
        val decision = SimPresenceEvaluator.decideReplacement(stored, fingerprint)
        when (decision) {
            SimPresenceEvaluator.ReplacementDecision.CAPTURE_BASELINE -> {
                val fp = (fingerprint as SimPresenceEvaluator.FingerprintResult.Available).fingerprint
                SecurityPreferences.setPhysicalSimFingerprint(context, fp)
                Log.i(TAG, "Captured physical-SIM fingerprint baseline.")
                EventLogger.log(context, SecurityEvent.SIM_BASELINE)
            }
            SimPresenceEvaluator.ReplacementDecision.NO_ACTION -> {
                // Same SIM, OR unavailable identity, OR no physical SIM.
                // Never overwrite a known-good baseline with an Unavailable result.
            }
            SimPresenceEvaluator.ReplacementDecision.FIRE_REPLACEMENT_WIPE -> {
                if (SecurityPreferences.isWipeOnSimReplacementEnabled(context)) {
                    Log.e(TAG, "Verified SIM replacement (fingerprint changed). Dispatching wipe.")
                    EventLogger.log(context, SecurityEvent.SIM_REPLACED)
                    dispatchStandardWipe(context, reason = "SIM_CHANGED_TRIPWIRE")
                } else if (SecurityPreferences.isSimChangeAlertEnabled(context)) {
                    PanicActionService.trigger(context, "SIM_CHANGED", PanicActionService.Severity.MEDIUM)
                    val newFp = (fingerprint as SimPresenceEvaluator.FingerprintResult.Available).fingerprint
                    SecurityPreferences.setPhysicalSimFingerprint(context, newFp)
                }
            }
        }
    }

    private fun captureBaselineIfNeeded(context: Context, snapshot: SimPresenceEvaluator.SubscriptionSnapshot) {
        if (SimPresenceEvaluator.observePhysicalSim(snapshot) ==
            SimPresenceEvaluator.PhysicalSimObservation.PRESENT &&
            !SecurityPreferences.hadPhysicalSimBaseline(context)) {
            SecurityPreferences.setHadPhysicalSimBaseline(context, true)
            Log.i(TAG, "Captured physical-SIM presence baseline.")
        }
    }

    private suspend fun syncBaselineAndReplacement(context: Context) {
        mutex.withLock {
            val snapshot = readSnapshot(context)
            if (SimPresenceEvaluator.observePhysicalSim(snapshot) ==
                SimPresenceEvaluator.PhysicalSimObservation.PRESENT) {
                clearRemovalHints(context)
                captureBaselineIfNeeded(context, snapshot)
                maybeCaptureReplacementBaselineOrEvaluate(context, snapshot)
            }
        }
    }

    private suspend fun maybeScheduleRemovalVerify(context: Context, explicitAbsentHint: Boolean) {
        mutex.withLock {
            // Boot-grace protection: raw ABSENT within the first N seconds is
            // ignored. We'll pick up the state via boot reconciliation and
            // JobScheduler on a clean cadence.
            if (android.os.SystemClock.elapsedRealtime() < BOOT_GRACE_MS) {
                Log.d(TAG, "Within boot grace; not scheduling removal verify.")
                return
            }
            if (!SecurityPreferences.hadPhysicalSimBaseline(context)) return
            if (!anyRemovalFeatureEnabled(context)) return

            if (explicitAbsentHint) {
                SecurityPreferences.setSimPendingAbsentHint(context, true)
            }
            scheduleRemovalVerify(context)
        }
    }

    private fun scheduleRemovalVerify(context: Context) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val builder = JobInfo.Builder(
                VERIFY_JOB_ID,
                ComponentName(context, SimRemovalVerifyJobService::class.java),
            )
                .setPersisted(true)
                .setMinimumLatency(ABSENT_VERIFY_DELAY_MS)
                .setOverrideDeadline(ABSENT_VERIFY_DELAY_MS * 3)
            scheduler.schedule(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to schedule SIM removal verification: ${e.message}")
        }
    }

    private fun clearRemovalHints(context: Context) {
        SecurityPreferences.setSimPendingAbsentHint(context, false)
        SecurityPreferences.setSimPendingSubscriptionChange(context, false)
    }

    // -----------------------------------------------------------------------
    // Snapshot reading
    // -----------------------------------------------------------------------

    private fun readSnapshot(context: Context): SimPresenceEvaluator.SubscriptionSnapshot {
        val granted = hasReadPhoneState(context)
        if (!granted) {
            // One best-effort self-grant attempt; if we are Device Owner the
            // runtime grant is applied without any user interaction. If we
            // aren't Device Owner (not yet provisioned), fail safely.
            val now = selfGrantReadPhoneStateIfDeviceOwner(context)
            if (!now) {
                return SimPresenceEvaluator.SubscriptionSnapshot(emptyList(), readPhoneStateGranted = false)
            }
        }
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: return SimPresenceEvaluator.SubscriptionSnapshot(emptyList(), readPhoneStateGranted = true, listUnavailable = true)

        return try {
            @Suppress("MissingPermission")
            val list: List<SubscriptionInfo>? = subscriptionManager.activeSubscriptionInfoList
            if (list == null) {
                SimPresenceEvaluator.SubscriptionSnapshot(emptyList(), readPhoneStateGranted = true, listUnavailable = true)
            } else {
                SimPresenceEvaluator.SubscriptionSnapshot(
                    subscriptions = list.map(::toRecord),
                    readPhoneStateGranted = true,
                    listUnavailable = false,
                )
            }
        } catch (_: SecurityException) {
            SimPresenceEvaluator.SubscriptionSnapshot(emptyList(), readPhoneStateGranted = true, listUnavailable = true)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error reading subscriptions: ${e.message}")
            SimPresenceEvaluator.SubscriptionSnapshot(emptyList(), readPhoneStateGranted = true, listUnavailable = true)
        }
    }

    private fun toRecord(info: SubscriptionInfo): SimPresenceEvaluator.SubscriptionRecord {
        val isEmbedded = try {
            info.isEmbedded
        } catch (_: Throwable) {
            false
        }
        val iccId = try {
            @Suppress("DEPRECATION")
            info.iccId?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        val carrierId = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.carrierId else 0
        } catch (_: Throwable) {
            0
        }
        val mcc = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.mccString else null
        } catch (_: Throwable) {
            null
        }
        val mnc = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.mncString else null
        } catch (_: Throwable) {
            null
        }
        return SimPresenceEvaluator.SubscriptionRecord(
            subscriptionId = info.subscriptionId,
            simSlotIndex = info.simSlotIndex,
            isEmbedded = isEmbedded,
            iccId = iccId,
            carrierId = carrierId,
            mcc = mcc,
            mnc = mnc,
        )
    }

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    private fun hasReadPhoneState(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Device Owner can grant READ_PHONE_STATE to itself without user
     * interaction via [DevicePolicyManager.setPermissionGrantState]. This
     * mirrors GGuard's approach and is critical for SIM monitoring to work
     * reliably in a Direct-Boot / before-first-unlock window and for the
     * verify JobService.
     *
     * Returns true if the permission is granted after the call.
     */
    fun selfGrantReadPhoneStateIfDeviceOwner(context: Context): Boolean {
        if (hasReadPhoneState(context)) return true
        return try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            val admin = ComponentName(context, AdminReceiver::class.java)
            val ok = dpm.setPermissionGrantState(
                admin,
                context.packageName,
                Manifest.permission.READ_PHONE_STATE,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            if (ok) Log.i(TAG, "Device Owner self-granted READ_PHONE_STATE.")
            ok && hasReadPhoneState(context)
        } catch (e: Exception) {
            Log.w(TAG, "Self-grant of READ_PHONE_STATE failed: ${e.message}")
            false
        }
    }

    // -----------------------------------------------------------------------
    // Feature-enable gates
    // -----------------------------------------------------------------------

    private fun anyFeatureEnabled(context: Context): Boolean =
        SecurityPreferences.isSimChangeAlertEnabled(context) ||
            SecurityPreferences.isWipeOnSimRemovalEnabled(context) ||
            SecurityPreferences.isWipeOnSimReplacementEnabled(context)

    private fun anyRemovalFeatureEnabled(context: Context): Boolean =
        SecurityPreferences.isSimChangeAlertEnabled(context) ||
            SecurityPreferences.isWipeOnSimRemovalEnabled(context)

    // -----------------------------------------------------------------------
    // Wipe dispatch (idempotent)
    // -----------------------------------------------------------------------

    private fun dispatchStandardWipe(context: Context, reason: String) {
        if (!wipeInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "Suppressing duplicate wipe dispatch: $reason (already in flight)")
            return
        }
        if (SecurityPreferences.isSimWipeInFlight(context)) {
            Log.w(TAG, "Preference-level wipe-in-flight latch set; suppressing: $reason")
            return
        }
        SecurityPreferences.setSimWipeInFlight(context, true)
        scope.launch {
            try {
                val strategy = DefenseCoordinator.resolveStrategy(context)
                strategy.executeStandardWipe(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Standard wipe dispatch threw: ${e.message}", e)
                // Do NOT clear the in-flight latch permanently; the device is
                // either rebooting (success) or we deliberately drop the
                // request. The latch is cleared on next boot by
                // reconcileOnBoot().
            }
        }
    }

    /** Called from boot reconciliation to clear a stale in-flight latch. */
    internal fun clearInFlightLatchForBoot(context: Context) {
        wipeInFlight.set(false)
        SecurityPreferences.setSimWipeInFlight(context, false)
    }
}
