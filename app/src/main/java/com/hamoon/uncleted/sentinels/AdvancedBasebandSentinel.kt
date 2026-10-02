package com.hamoon.uncleted.sentinels

import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.services.PanicActionService
import com.hamoon.uncleted.util.EventLogger
import com.hamoon.uncleted.util.PermissionUtils

/**
 * Baseband downgrade & IMSI-catcher sentinel.
 *
 * This fork does NOT configure or clear `UserManager.DISALLOW_CELLULAR_2G` and
 * does NOT manipulate carrier-privileged allowed-network bitmasks: GrapheneOS
 * ships its own OS-level 2G control, which should be used directly. The sentinel
 * only observes telephony state and raises alerts on suspicious downgrade or
 * IMSI-catcher-style signatures. On a confirmed threat it fires a HIGH-severity
 * PanicActionService notification; it does not initiate a wipe or radio shutdown.
 */
class AdvancedBasebandSentinel(private val context: Context) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var registeredCallback: Any? = null
    private var isMonitoring = false

    companion object {
        private const val TAG = "AdvancedBasebandSentinel"
        private const val ALERT_COOLDOWN_MS = 30_000L
        @Volatile
        private var lastAlertTimestamp = 0L
    }

    fun start() {
        if (isMonitoring || telephonyManager == null) return
        if (!PermissionUtils.hasReadPhoneStatePermission(context)) {
            Log.w(TAG, "READ_PHONE_STATE permission missing; baseband sentinel aborted.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val callback = object : TelephonyCallback(),
                    TelephonyCallback.ServiceStateListener,
                    TelephonyCallback.CellInfoListener {

                    override fun onServiceStateChanged(serviceState: ServiceState) {
                        if (SecurityPreferences.isBasebandSentinelEnabled(context)) {
                            evaluateServiceStateDowngrade(serviceState)
                        }
                    }

                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        if (SecurityPreferences.isBasebandSentinelEnabled(context)) {
                            evaluateRogueBaseStationSignatures(cellInfo)
                        }
                    }
                }

                telephonyManager.registerTelephonyCallback(
                    ContextCompat.getMainExecutor(context),
                    callback
                )
                registeredCallback = callback
                isMonitoring = true
                Log.i(TAG, "Advanced Baseband Sentinel active (Monitoring: ON).")
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering advanced telephony callbacks", e)
            }
        }
    }

    fun stop() {
        if (!isMonitoring || telephonyManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (registeredCallback as? TelephonyCallback)?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
                registeredCallback = null
            }
            isMonitoring = false
            Log.i(TAG, "Advanced Baseband Sentinel disarmed.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed unregistering telephony callbacks", e)
        }
    }

    private fun evaluateServiceStateDowngrade(serviceState: ServiceState) {
        val regInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            serviceState.networkRegistrationInfoList
        } else {
            emptyList()
        }

        for (info in regInfoList) {
            if (info.domain == NetworkRegistrationInfo.DOMAIN_CS || info.domain == NetworkRegistrationInfo.DOMAIN_PS) {
                val tech = info.accessNetworkTechnology
                if (is2GTechnology(tech) && info.isRegistered) {
                    triggerBasebandThreatAlert(
                        "FORCED_2G_LINK_DOWNGRADE",
                        "Device registered to unencrypted 2G cellular network (Tech Code: $tech). " +
                                "Consider enabling GrapheneOS's native 2G disable in system Settings."
                    )
                    return
                }
            }
        }
    }

    private fun evaluateRogueBaseStationSignatures(cellInfoList: List<CellInfo>) {
        if (cellInfoList.isEmpty()) return

        val maxAllowedTimingAdvance = SecurityPreferences.getTimingAdvanceThreshold(context)
        val registeredCells = cellInfoList.filter { it.isRegistered }

        for (info in registeredCells) {
            if (info is CellInfoGsm) {
                triggerBasebandThreatAlert(
                    "SUSPICIOUS_2G_BASE_STATION_DETECTED",
                    "Device connected to active GSM cell. Review GrapheneOS 2G settings."
                )
                return
            }

            if (info is CellInfoLte) {
                val signalStrength = info.cellSignalStrength
                val rsrp = signalStrength.rsrp
                val timingAdvance = signalStrength.timingAdvance

                if (rsrp > -65 && timingAdvance > maxAllowedTimingAdvance && timingAdvance != Int.MAX_VALUE) {
                    val estimatedMeters = timingAdvance * 78
                    triggerBasebandThreatAlert(
                        "STINGRAY_TIMING_ADVANCE_SPOOF",
                        "High power (${rsrp}dBm) with Timing Advance $timingAdvance (~${estimatedMeters}m)."
                    )
                    return
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun is2GTechnology(tech: Int): Boolean {
        return tech == TelephonyManager.NETWORK_TYPE_GSM ||
                tech == TelephonyManager.NETWORK_TYPE_GPRS ||
                tech == TelephonyManager.NETWORK_TYPE_EDGE ||
                tech == TelephonyManager.NETWORK_TYPE_CDMA ||
                tech == TelephonyManager.NETWORK_TYPE_1xRTT ||
                tech == TelephonyManager.NETWORK_TYPE_IDEN
    }

    private fun triggerBasebandThreatAlert(reason: String, description: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTimestamp < ALERT_COOLDOWN_MS) return
        lastAlertTimestamp = now

        Log.e(TAG, "!!! BASEBAND SECURITY ALERT: $reason !!! - $description")
        EventLogger.log(context, "BASEBAND: $reason - $description")

        PanicActionService.trigger(
            context,
            reason,
            PanicActionService.Severity.HIGH
        )
    }
}
