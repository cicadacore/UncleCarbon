package com.hamoon.uncleted.sentinels

import com.hamoon.uncleted.data.SecurityEvent
import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.util.Log
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.sentinels.SpectralDecisionEngine.CellularRfState
import com.hamoon.uncleted.sentinels.SpectralDecisionEngine.QuarantineDecision
import com.hamoon.uncleted.sentinels.SpectralDecisionEngine.SpectralState
import com.hamoon.uncleted.services.PanicActionService
import com.hamoon.uncleted.util.EventLogger
import com.hamoon.uncleted.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Geographic / RF-loss "Faraday" sentinel.
 *
 * Explicit state machine (see [SpectralState]) that advances a quarantine timer
 * only while Faraday-like RF isolation is POSITIVELY confirmed, and fires the
 * configured BFU/WIPE action exactly once when the configured continuous duration
 * elapses. Motion sensing has been removed; confirmation is now an optional, fresh
 * Wi-Fi RF observation (see [WifiEnvironmentDetector]).
 *
 * Safety invariant: uncertainty (permission/API/scan failure, stale data, unknown
 * telephony, Wi-Fi enable failure) is NEVER treated as isolation. Decisions are
 * made by the pure [SpectralDecisionEngine]; timing by the pure [SpectralQuarantine].
 */
class SpectralSentinel(private val context: Context) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiDetector = WifiEnvironmentDetector(context)

    private var currentState: SpectralState = SpectralState.NORMAL
    private val actionInFlight = AtomicBoolean(false)

    // Log throttling (elapsedRealtime millis).
    private var lastConfigLogMs = 0L
    private var lastProgressLogMs = 0L
    private var lastInconclusiveLogMs = 0L
    private var lastDeviceOwnerDiagMs = 0L

    companion object {
        private const val TAG = "SpectralSentinel"
        private const val RSRP_DEAD_ZONE_THRESHOLD = -135 // dBm
        private const val MIN_QUARANTINE_MS = 15_000L
        private const val CONFIG_LOG_INTERVAL_MS = 60_000L
        private const val PROGRESS_LOG_INTERVAL_MS = 30_000L
        private const val INCONCLUSIVE_LOG_INTERVAL_MS = 30_000L
        private const val DEVICE_OWNER_DIAG_INTERVAL_MS = 30_000L
        private const val CELL_INFO_TIMEOUT_MS = 6_000L
    }

    /**
     * Single evaluation tick, driven by the MonitoringService poller (already on a
     * background coroutine). Suspends while a Wi-Fi observation is in progress;
     * never blocks a thread.
     */
    suspend fun evaluateRfLoss() {
        val enabled = SecurityPreferences.isSpectralSentinelEnabled(context)
        if (!enabled) {
            if (currentState != SpectralState.NORMAL) {
                transitionTo(SpectralState.NORMAL)
            }
            clearQuarantineState()
            wifiDetector.restoreOriginalWifiStateIfChanged()
            return
        }

        val action = SecurityPreferences.getSpectralAction(context)
        val quarantineMs = maxOf(SecurityPreferences.getSpectralQuarantineMs(context), MIN_QUARANTINE_MS)
        val wifiConfirm = SecurityPreferences.isWifiRfConfirmationEnabled(context)
        logConfigThrottled(enabled, action, quarantineMs, wifiConfirm)

        val airplane = isAirplaneModeOn()
        val locked = isDeviceLocked()
        val validatedInternet = hasValidatedInternet()

        // Cheap gates: if any says "not isolated", decide without touching telephony/Wi-Fi.
        val quickCancel = airplane || !locked || validatedInternet
        val cellular: CellularRfState
        var wifiState: WifiEnvironmentDetector.WifiEnvironmentState? = null

        if (quickCancel) {
            cellular = CellularRfState.INCONCLUSIVE // unused; decision short-circuits on the gates
        } else {
            cellular = evaluateCellular()
            // Only perform a Wi-Fi observation when it can actually change the outcome:
            // cellular positively unavailable AND confirmation enabled.
            if (wifiConfirm && cellular == CellularRfState.UNAVAILABLE) {
                if (currentState == SpectralState.NORMAL || currentState == SpectralState.RF_LOSS_SUSPECTED) {
                    transitionTo(SpectralState.WIFI_CONFIRMING)
                }
                val result = wifiDetector.observe()
                wifiState = result.state
                if (result.state == WifiEnvironmentDetector.WifiEnvironmentState.INCONCLUSIVE) {
                    logInconclusiveThrottled(result.reason)
                }
            } else if (cellular == CellularRfState.UNAVAILABLE && currentState == SpectralState.NORMAL) {
                transitionTo(SpectralState.RF_LOSS_SUSPECTED)
            }
        }

        val inputs = SpectralDecisionEngine.Inputs(
            sentinelEnabled = true,
            airplaneMode = airplane,
            screenLocked = locked,
            validatedInternet = validatedInternet,
            cellular = cellular,
            wifiRfConfirmationEnabled = wifiConfirm,
            wifi = wifiState?.let { mapWifiState(it) }
        )

        when (SpectralDecisionEngine.decide(inputs)) {
            QuarantineDecision.CANCEL -> onCancel(airplane)
            QuarantineDecision.HOLD -> onHold()
            QuarantineDecision.ADVANCE -> onAdvance(action, quarantineMs, wifiState)
        }
    }

    // ---- Decision outcomes ----

    private fun onCancel(airplane: Boolean) {
        val wasActive = SecurityPreferences.isSpectralQuarantineActive(context)
        clearQuarantineState()
        wifiDetector.restoreOriginalWifiStateIfChanged()
        if (airplane) {
            // Safe policy: a user intentionally enabling airplane mode must not wipe.
            Log.i(TAG, "airplane mode active; Faraday action suppressed")
        }
        if (wasActive) {
            Log.w(TAG, "RF returned; quarantine cancelled")
            EventLogger.log(context, SecurityEvent.RF_RECOVERED)
        }
        if (currentState != SpectralState.NORMAL) transitionTo(SpectralState.NORMAL)
    }

    private fun onHold() {
        // Inconclusive evidence: fail safe. Never advance toward a wipe on uncertainty.
        val wasActive = SecurityPreferences.isSpectralQuarantineActive(context)
        if (wasActive) {
            logInconclusiveThrottled("QUARANTINE_HELD")
            Log.w(TAG, "destructive action suppressed because RF state is inconclusive")
            clearQuarantineState()
        }
        if (currentState != SpectralState.RF_LOSS_SUSPECTED && currentState != SpectralState.WIFI_CONFIRMING) {
            transitionTo(SpectralState.RF_LOSS_SUSPECTED)
        }
    }

    private suspend fun onAdvance(action: String, quarantineMs: Long, wifiState: WifiEnvironmentDetector.WifiEnvironmentState?) {
        if (currentState != SpectralState.QUARANTINE && currentState != SpectralState.ACTION_TRIGGERED) {
            transitionTo(SpectralState.FARADAY_CONFIRMED)
            Log.e(TAG, "Faraday-like isolation confirmed" + (wifiState?.let { " (wifi=$it)" } ?: ""))
            EventLogger.log(context, SecurityEvent.RF_ISOLATED)
        }

        val prev = loadQuarantineState()
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowBootRef = currentBootRef()
        val step = SpectralQuarantine.onAdvance(prev, nowElapsed, nowBootRef, quarantineMs)

        if (!prev.active && step.state.active) {
            Log.w(TAG, "quarantine started duration=${quarantineMs}ms action=$action")
            EventLogger.log(context, SecurityEvent.RF_QUARANTINE)
            if (currentState != SpectralState.QUARANTINE) transitionTo(SpectralState.QUARANTINE)
        } else if (currentState != SpectralState.QUARANTINE && currentState != SpectralState.ACTION_TRIGGERED) {
            transitionTo(SpectralState.QUARANTINE)
        }

        saveQuarantineState(step.state)

        if (step.fire) {
            fireAction(action, step.elapsedMs)
        } else {
            logProgressThrottled(step.elapsedMs, quarantineMs)
        }
    }

    private suspend fun fireAction(action: String, elapsedMs: Long) {
        val strategy = DefenseCoordinator.resolveStrategy(context)

        if (action == "WIPE" && !strategy.isDeviceOwnerProvisioned) {
            if (SystemClock.elapsedRealtime() - lastDeviceOwnerDiagMs >= DEVICE_OWNER_DIAG_INTERVAL_MS) {
                lastDeviceOwnerDiagMs = SystemClock.elapsedRealtime()
                Log.e(TAG, "ACTION BLOCKED: WIPE requires Device Owner but app is not provisioned. No wipe performed.")
                EventLogger.log(context, SecurityEvent.RF_WIPE_BLOCKED)
            }
            // Keep the confirmed quarantine active (do not pretend success); if Device
            // Owner is granted later and isolation still holds, it will fire then.
            return
        }

        if (!actionInFlight.compareAndSet(false, true)) {
            // Already dispatched for this confirmed event; ignore repeated poll callbacks.
            return
        }

        transitionTo(SpectralState.ACTION_TRIGGERED)
        Log.e(TAG, "ACTION CONFIRMED action=$action elapsed=${elapsedMs}ms")
        EventLogger.log(context, SecurityEvent.RF_TRIGGERED)

        // The event is complete; clear the persisted quarantine so a later genuine
        // event re-arms cleanly.
        clearQuarantineState()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (action == "WIPE") {
                    strategy.executeStandardWipe("RF_LOSS_SENTINEL_WIPE")
                } else {
                    strategy.evictMemoryKeysAndLock()
                }
                PanicActionService.trigger(
                    context,
                    "RF_LOSS_SENTINEL_TRIGGERED",
                    PanicActionService.Severity.CRITICAL
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during RF loss sentinel action", e)
            } finally {
                // BFU reboots (new boot clears this process); for a blocked/failed path
                // allow re-arming on a future confirmed event.
                actionInFlight.set(false)
            }
        }
    }

    // ---- Telemetry gathering ----

    private fun isAirplaneModeOn(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (_: Exception) {
        false
    }

    private fun isDeviceLocked(): Boolean = try {
        keyguardManager?.isDeviceLocked ?: false
    } catch (_: Exception) {
        false
    }

    /** True only when Android reports a VALIDATED internet-capable network. */
    private fun hasValidatedInternet(): Boolean {
        return try {
            val activeNetwork = connectivityManager?.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Positively classify cellular RF. Permission/API failure => INCONCLUSIVE (never
     * AVAILABLE or UNAVAILABLE). An inserted SIM with zero registered cells is NOT
     * assumed to mean cellular is available.
     */
    private suspend fun evaluateCellular(): CellularRfState {
        if (!PermissionUtils.hasReadPhoneStatePermission(context) || !PermissionUtils.hasLocationPermissions(context)) {
            return CellularRfState.INCONCLUSIVE
        }
        val tm = telephonyManager ?: return CellularRfState.INCONCLUSIVE

        val serviceState: ServiceState? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.serviceState else null
        } catch (_: SecurityException) {
            return CellularRfState.INCONCLUSIVE
        } catch (_: Exception) {
            null
        }

        when (serviceState?.state) {
            ServiceState.STATE_IN_SERVICE, ServiceState.STATE_EMERGENCY_ONLY -> return CellularRfState.AVAILABLE
            ServiceState.STATE_POWER_OFF -> return CellularRfState.INCONCLUSIVE // radio intentionally off
            // STATE_OUT_OF_SERVICE or null: corroborate with cell info below.
            else -> {}
        }

        val simState = try {
            tm.simState
        } catch (_: Exception) {
            TelephonyManager.SIM_STATE_UNKNOWN
        }
        // No usable SIM => cannot use cellular loss as isolation evidence.
        if (simState == TelephonyManager.SIM_STATE_ABSENT) return CellularRfState.INCONCLUSIVE

        val cells = requestFreshCellInfo(tm) ?: return CellularRfState.INCONCLUSIVE

        if (cells.isEmpty()) {
            // Empty list alone is not proof; only trust it when ServiceState agrees.
            return if (serviceState?.state == ServiceState.STATE_OUT_OF_SERVICE) {
                CellularRfState.UNAVAILABLE
            } else {
                CellularRfState.INCONCLUSIVE
            }
        }

        val registered = cells.filter { it.isRegistered }
        if (registered.isEmpty()) {
            // Seeing neighbour cells but none registered, with OUT_OF_SERVICE, is a dead state.
            return if (serviceState?.state == ServiceState.STATE_OUT_OF_SERVICE) {
                CellularRfState.UNAVAILABLE
            } else {
                CellularRfState.INCONCLUSIVE
            }
        }

        val anyUsable = registered.any { info -> signalIsUsable(info) }
        return if (anyUsable) CellularRfState.AVAILABLE else CellularRfState.UNAVAILABLE
    }

    private fun signalIsUsable(info: CellInfo): Boolean = try {
        when (info) {
            is CellInfoLte -> info.cellSignalStrength.rsrp >= RSRP_DEAD_ZONE_THRESHOLD
            is CellInfoNr -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val nr = info.cellSignalStrength as? CellSignalStrengthNr
                    (nr?.ssRsrp ?: Int.MIN_VALUE) >= RSRP_DEAD_ZONE_THRESHOLD
                } else {
                    true
                }
            }
            is CellInfoWcdma -> info.cellSignalStrength.dbm >= RSRP_DEAD_ZONE_THRESHOLD
            is CellInfoGsm -> info.cellSignalStrength.dbm >= RSRP_DEAD_ZONE_THRESHOLD
            else -> true // unknown registered tech: assume usable (bias against false isolation)
        }
    } catch (_: Exception) {
        true
    }

    /** Fresh cell info via requestCellInfoUpdate (API 29+), falling back to allCellInfo. */
    private suspend fun requestFreshCellInfo(tm: TelephonyManager): List<CellInfo>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fresh = withTimeoutOrNull(CELL_INFO_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<CellInfo>?> { cont ->
                    try {
                        tm.requestCellInfoUpdate(
                            context.mainExecutor,
                            object : TelephonyManager.CellInfoCallback() {
                                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                    if (cont.isActive) cont.resume(cellInfo)
                                }

                                override fun onError(errorCode: Int, detail: Throwable?) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                        )
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resume(null)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (fresh != null) return fresh
        }
        return try {
            @Suppress("DEPRECATION")
            tm.allCellInfo
        } catch (_: Exception) {
            null
        }
    }

    private fun mapWifiState(s: WifiEnvironmentDetector.WifiEnvironmentState): SpectralDecisionEngine.WifiEnvironmentState =
        when (s) {
            WifiEnvironmentDetector.WifiEnvironmentState.APS_VISIBLE -> SpectralDecisionEngine.WifiEnvironmentState.APS_VISIBLE
            WifiEnvironmentDetector.WifiEnvironmentState.NO_APS_VISIBLE -> SpectralDecisionEngine.WifiEnvironmentState.NO_APS_VISIBLE
            WifiEnvironmentDetector.WifiEnvironmentState.INCONCLUSIVE -> SpectralDecisionEngine.WifiEnvironmentState.INCONCLUSIVE
        }

    // ---- Quarantine persistence (device-protected; survives process death/reboot) ----

    private fun loadQuarantineState(): SpectralQuarantine.State =
        SpectralQuarantine.State(
            active = SecurityPreferences.isSpectralQuarantineActive(context),
            startElapsedMs = SecurityPreferences.getSpectralQuarantineStartElapsed(context),
            bootRef = SecurityPreferences.getSpectralQuarantineBootRef(context)
        )

    private fun saveQuarantineState(state: SpectralQuarantine.State) {
        SecurityPreferences.setSpectralQuarantine(context, state.active, state.startElapsedMs, state.bootRef)
    }

    private fun clearQuarantineState() {
        if (SecurityPreferences.isSpectralQuarantineActive(context) ||
            SecurityPreferences.getSpectralQuarantineStartElapsed(context) != 0L
        ) {
            SecurityPreferences.setSpectralQuarantine(context, active = false, startElapsedMs = 0L, bootRef = 0L)
        }
    }

    private fun currentBootRef(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    // ---- Logging helpers (throttled to avoid flooding logcat) ----

    private fun transitionTo(newState: SpectralState) {
        if (newState == currentState) return
        Log.i(TAG, "state ${currentState} -> ${newState}")
        currentState = newState
    }

    private fun logConfigThrottled(enabled: Boolean, action: String, quarantineMs: Long, wifiConfirm: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfigLogMs >= CONFIG_LOG_INTERVAL_MS || lastConfigLogMs == 0L) {
            lastConfigLogMs = now
            Log.d(TAG, "SpectralConfig: enabled=$enabled action=$action quarantineMs=$quarantineMs wifiRfConfirmation=$wifiConfirm")
        }
    }

    private fun logProgressThrottled(elapsedMs: Long, quarantineMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressLogMs >= PROGRESS_LOG_INTERVAL_MS) {
            lastProgressLogMs = now
            Log.i(TAG, "quarantine elapsed=$elapsedMs/$quarantineMs")
        }
    }

    private fun logInconclusiveThrottled(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInconclusiveLogMs >= INCONCLUSIVE_LOG_INTERVAL_MS) {
            lastInconclusiveLogMs = now
            Log.w(TAG, "confirmation INCONCLUSIVE reason=$reason")
        }
    }
}
