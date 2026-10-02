package com.hamoon.uncleted.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.services.PanicActionService
import com.hamoon.uncleted.util.EventLogger
import com.hamoon.uncleted.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SIM detection on GrapheneOS.
 *
 *  - SIM removed  -> if "Factory Reset on SIM Removal" is enabled, trigger
 *                    the single standard Device Owner factory-reset path.
 *                    Otherwise fire a notification-only alert if armed.
 *  - SIM replaced -> if "Factory Reset on SIM Replacement" is enabled, trigger
 *                    the standard factory-reset path. Otherwise notify.
 *
 * This receiver never invokes any lethal/root destruction path. It calls
 * `executeStandardWipe` exactly once per matched tripwire and does not also
 * dispatch a generic remote-wipe trigger that would duplicate the request.
 */
class SimChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.SIM_STATE_CHANGED") return

        val alertEnabled = SecurityPreferences.isSimChangeAlertEnabled(context)
        val wipeRemovalEnabled = SecurityPreferences.isWipeOnSimRemovalEnabled(context)
        val wipeReplacementEnabled = SecurityPreferences.isWipeOnSimReplacementEnabled(context)

        if (!alertEnabled && !wipeRemovalEnabled && !wipeReplacementEnabled) {
            Log.d(TAG, "SIM sentinels disabled in settings.")
            return
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val currentSimState = telephonyManager.simState

        when (currentSimState) {
            TelephonyManager.SIM_STATE_READY -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        evaluateSimReplacement(context, telephonyManager)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            TelephonyManager.SIM_STATE_ABSENT -> {
                val storedIdentifier = SecurityPreferences.getInitialSimSerial(context)
                val isBootGracePeriod = SystemClock.elapsedRealtime() < 60_000L

                if (!storedIdentifier.isNullOrEmpty() && !isBootGracePeriod) {
                    Log.w(TAG, "SIM card removed after initial setup.")
                    EventLogger.log(context, "HARDWARE ALERT: Physical SIM card removed from socket.")

                    if (wipeRemovalEnabled) {
                        Log.e(TAG, "Factory Reset on SIM Removal armed. Routing to standard Device Owner wipe.")
                        EventLogger.log(context, "CRITICAL: SIM-removal tripwire -> standard factory reset.")

                        val pendingResult = goAsync()
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val strategy = DefenseCoordinator.resolveStrategy(context)
                                strategy.executeStandardWipe("SIM_REMOVED_TRIPWIRE")
                            } finally {
                                pendingResult.finish()
                            }
                        }
                    } else if (alertEnabled) {
                        PanicActionService.trigger(context, "SIM_REMOVED", PanicActionService.Severity.MEDIUM)
                    }
                }
            }
        }
    }

    private suspend fun evaluateSimReplacement(context: Context, telephonyManager: TelephonyManager) {
        val currentIdentifier = retrieveSimIdentifier(context, telephonyManager)

        if (currentIdentifier.isNullOrEmpty()) {
            Log.w(TAG, "Could not determine a reliable SIM hardware identifier on this Android build.")
            return
        }

        val storedIdentifier = SecurityPreferences.getInitialSimSerial(context)

        if (storedIdentifier == null) {
            SecurityPreferences.setInitialSimSerial(context, currentIdentifier)
            Log.i(TAG, "Baseline SIM identifier saved.")
            return
        }

        if (storedIdentifier == currentIdentifier) return

        Log.w(TAG, "SIM card replacement detected (identifier changed).")
        EventLogger.log(context, "HARDWARE ALERT: SIM card hardware identifier changed.")

        if (SecurityPreferences.isWipeOnSimReplacementEnabled(context)) {
            Log.e(TAG, "Factory Reset on SIM Replacement armed. Routing to standard Device Owner wipe.")
            EventLogger.log(context, "CRITICAL: SIM-replacement tripwire -> standard factory reset.")
            val strategy = DefenseCoordinator.resolveStrategy(context)
            strategy.executeStandardWipe("SIM_CHANGED_TRIPWIRE")
        } else if (SecurityPreferences.isSimChangeAlertEnabled(context)) {
            PanicActionService.trigger(context, "SIM_CHANGED", PanicActionService.Severity.MEDIUM)
            SecurityPreferences.setInitialSimSerial(context, currentIdentifier)
        }
    }

    private fun retrieveSimIdentifier(context: Context, telephonyManager: TelephonyManager): String? {
        try {
            if (PermissionUtils.hasReadPhoneStatePermission(context)) {
                @Suppress("DEPRECATION")
                val serial = telephonyManager.simSerialNumber
                if (!serial.isNullOrEmpty()) return serial
            }
        } catch (_: SecurityException) {}

        try {
            if (PermissionUtils.hasReadPhoneStatePermission(context)) {
                val subManager = context.getSystemService(SubscriptionManager::class.java)
                val activeSubs = subManager?.activeSubscriptionInfoList
                if (!activeSubs.isNullOrEmpty()) {
                    val subInfo = activeSubs.first()
                    if (!subInfo.iccId.isNullOrEmpty()) {
                        return subInfo.iccId
                    }
                    val simId = "${subInfo.subscriptionId}_${subInfo.mccString}_${subInfo.mncString}"
                    if (simId.isNotEmpty()) return simId
                }
            }
        } catch (_: SecurityException) {}

        val fallbackFingerprint = "${telephonyManager.simOperator}_${telephonyManager.simCountryIso}"
        return if (fallbackFingerprint.length > 2 && !fallbackFingerprint.startsWith("_")) {
            fallbackFingerprint
        } else {
            null
        }
    }
}
