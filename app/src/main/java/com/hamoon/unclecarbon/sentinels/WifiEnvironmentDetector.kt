package com.hamoon.unclecarbon.sentinels

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Dedicated Wi-Fi RF confirmation heuristic.
 *
 * Determines whether nearby Wi-Fi radio activity can be *observed* — it never
 * connects to a network. Visible access points indicate the device is probably
 * NOT inside a Faraday enclosure; a genuinely fresh scan that finds zero APs is a
 * Faraday-like isolation candidate. This is a heuristic, not proof of a Faraday bag.
 *
 * Hard safety rule: any uncertainty (missing permission, Wi-Fi enable failure,
 * scan throttled/failed, stale-only data, API exception, no subsystem) returns
 * [WifiEnvironmentState.INCONCLUSIVE] — never [NO_APS_VISIBLE]. A failed scan is
 * not evidence that no APs exist.
 *
 * Only AP counts/state are logged. SSIDs, BSSIDs and other identifiers are never
 * read for logging or stored.
 */
class WifiEnvironmentDetector(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // Whether THIS detector turned Wi-Fi on for confirmation (so it can restore later).
    @Volatile
    private var weEnabledWifi = false

    // Cache of the last conclusive observation so repeated per-second ticks don't
    // re-scan constantly (avoids scan throttling and rapid toggling). Inconclusive
    // results are never cached.
    @Volatile
    private var cachedResult: WifiEnvironmentResult? = null
    @Volatile
    private var cachedAtElapsedMs = 0L

    companion object {
        private const val TAG = "SpectralWifi"

        // Re-confirmation cadence: reuse a conclusive observation for this long before
        // scanning again. Kept comfortably under Android's foreground scan-throttle
        // budget (~4 scans / 2 min) even for multi-hour quarantines.
        private const val RESULT_REUSE_MS = 90_000L

        // How long to wait for Wi-Fi to actually become enabled after requesting it.
        private const val WIFI_ENABLE_TIMEOUT_MS = 8_000L
        private const val WIFI_POLL_INTERVAL_MS = 400L

        // How long to wait for a scan to complete.
        private const val SCAN_TIMEOUT_MS = 15_000L

        // A scan result counts as "fresh for this incident" if its timestamp is no
        // older than this relative to the moment the scan was initiated.
        private const val FRESH_SLACK_MS = 5_000L
    }

    enum class WifiEnvironmentState { APS_VISIBLE, NO_APS_VISIBLE, INCONCLUSIVE }

    data class WifiEnvironmentResult(
        val state: WifiEnvironmentState,
        val freshApCount: Int,
        val scanCompleted: Boolean,
        val reason: String
    )

    private fun inconclusive(reason: String) =
        WifiEnvironmentResult(WifiEnvironmentState.INCONCLUSIVE, 0, scanCompleted = false, reason = reason)

    /** Scan-result permission: FINE_LOCATION, or NEARBY_WIFI_DEVICES on API 33+. */
    private fun hasScanResultPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    private fun isDeviceOwner(): Boolean = try {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        dpm?.isDeviceOwnerApp(appContext.packageName) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Perform (or reuse) a fresh Wi-Fi environmental observation.
     * Never blocks a thread — all waiting is coroutine [delay].
     */
    suspend fun observe(): WifiEnvironmentResult {
        val wm = wifiManager ?: return inconclusive("NO_WIFI_SUBSYSTEM")

        // Reuse a recent conclusive observation to avoid excessive scanning.
        cachedResult?.let { cached ->
            if (SystemClock.elapsedRealtime() - cachedAtElapsedMs <= RESULT_REUSE_MS) {
                return cached
            }
        }

        if (!hasScanResultPermission()) {
            return inconclusive("MISSING_SCAN_PERMISSION")
        }

        // Ensure Wi-Fi is operational, enabling it via the Device Owner path if needed.
        if (!ensureWifiEnabled(wm)) {
            return inconclusive("WIFI_ENABLE_FAILED")
        }

        val result = performFreshScan(wm)
        if (result.state != WifiEnvironmentState.INCONCLUSIVE) {
            cachedResult = result
            cachedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        return result
    }

    private suspend fun ensureWifiEnabled(wm: WifiManager): Boolean {
        if (wm.isWifiEnabled) return true

        if (!weEnabledWifi) {
            Log.d(TAG, "originalWifiEnabled=false")
        }
        val deviceOwner = isDeviceOwner()
        Log.d(TAG, "requesting Wi-Fi enable; deviceOwner=$deviceOwner")
        try {
            // setWifiEnabled is deprecated for ordinary apps since API 29 but remains
            // the supported mechanism for a Device Owner to toggle Wi-Fi. There is no
            // non-deprecated replacement for this privileged use.
            val accepted = setWifiEnabledCompat(wm, true)
            if (!accepted && !wm.isWifiEnabled) {
                Log.w(TAG, "unable to enable Wi-Fi; confirmation inconclusive")
                return false
            }
            weEnabledWifi = true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException enabling Wi-Fi; confirmation inconclusive: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Exception enabling Wi-Fi; confirmation inconclusive: ${e.message}")
            return false
        }

        // Wait asynchronously for the subsystem to actually become usable.
        val enabled = withTimeoutOrNull(WIFI_ENABLE_TIMEOUT_MS) {
            while (!wm.isWifiEnabled) {
                delay(WIFI_POLL_INTERVAL_MS)
            }
            true
        } ?: false

        Log.d(TAG, "wifiEnabled=$enabled")
        if (!enabled) {
            Log.w(TAG, "unable to enable Wi-Fi; confirmation inconclusive")
        }
        return enabled
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun performFreshScan(wm: WifiManager): WifiEnvironmentResult {
        val scanStartElapsedMs = SystemClock.elapsedRealtime()

        val completed = withTimeoutOrNull(SCAN_TIMEOUT_MS) { awaitScan(wm) } ?: false

        val results = try {
            wm.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            return inconclusive("SCAN_RESULTS_SECURITY_EXCEPTION")
        } catch (e: Exception) {
            return inconclusive("SCAN_RESULTS_EXCEPTION")
        }

        // ScanResult.timestamp is microseconds since boot (elapsedRealtime domain).
        val freshThresholdMicros = (scanStartElapsedMs - FRESH_SLACK_MS) * 1000L
        val freshResults = results.filter { it.timestamp >= freshThresholdMicros }
        val freshApCount = freshResults.size

        return when {
            // A completed scan with fresh APs: RF visible.
            freshApCount > 0 -> {
                Log.d(TAG, "scan completed freshApCount=$freshApCount")
                Log.d(TAG, "fresh AP count=$freshApCount -> APS_VISIBLE")
                WifiEnvironmentResult(WifiEnvironmentState.APS_VISIBLE, freshApCount, scanCompleted = completed, reason = "APS_VISIBLE")
            }

            // Scan did not complete freshly: we cannot conclude zero APs exist.
            !completed -> inconclusive("SCAN_INCOMPLETE")

            // Completed, but every result is stale: not proof that no APs exist now.
            results.isNotEmpty() && freshResults.isEmpty() -> inconclusive("STALE_RESULTS_ONLY")

            // Completed, genuinely fresh, zero APs: Faraday-like candidate.
            else -> {
                Log.d(TAG, "scan completed freshApCount=0")
                Log.d(TAG, "fresh AP count=0 -> NO_WIFI_RF")
                WifiEnvironmentResult(WifiEnvironmentState.NO_APS_VISIBLE, 0, scanCompleted = true, reason = "NO_APS_VISIBLE")
            }
        }
    }

    /**
     * Initiate a scan and wait for SCAN_RESULTS_AVAILABLE, honouring throttling:
     * if startScan() is throttled (returns false) we still wait briefly in case the
     * platform delivers fresh results, but a throttled/failed scan never asserts
     * "no APs" — the freshness check in [performFreshScan] decides that.
     */
    private suspend fun awaitScan(wm: WifiManager): Boolean = suspendCancellableCoroutine { cont ->
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        var receiver: BroadcastReceiver? = null

        fun cleanup() {
            receiver?.let {
                try {
                    appContext.unregisterReceiver(it)
                } catch (_: Exception) {
                }
            }
            receiver = null
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true) ?: true
                cleanup()
                if (cont.isActive) cont.resume(updated)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            cleanup()
            if (cont.isActive) cont.resume(false)
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation { cleanup() }

        val started = try {
            startScanCompat(wm)
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan SecurityException: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "startScan exception: ${e.message}")
            false
        }
        if (!started) {
            Log.d(TAG, "confirmation note reason=SCAN_THROTTLED (startScan returned false)")
            // Do not resume early: the platform may still broadcast fresh results.
            // The overall SCAN_TIMEOUT_MS bound (applied by the caller via the
            // coroutine) limits how long we wait.
        }
    }

    @Suppress("DEPRECATION")
    private fun startScanCompat(wm: WifiManager): Boolean = wm.startScan()

    @Suppress("DEPRECATION")
    private fun setWifiEnabledCompat(wm: WifiManager, enabled: Boolean): Boolean = wm.setWifiEnabled(enabled)

    /**
     * Restore Wi-Fi to its original OFF state if this detector enabled it solely for
     * confirmation. Called when confirmation is no longer needed (RF returned /
     * sentinel disabled). Debounced: only acts when it actually changed the state,
     * so there is no rapid repeated toggling during an ongoing incident.
     */
    fun restoreOriginalWifiStateIfChanged() {
        if (!weEnabledWifi) return
        val wm = wifiManager ?: return
        try {
            setWifiEnabledCompat(wm, false)
            Log.d(TAG, "restored Wi-Fi to original disabled state after confirmation")
        } catch (e: Exception) {
            Log.w(TAG, "Failed restoring original Wi-Fi state: ${e.message}")
        } finally {
            weEnabledWifi = false
            cachedResult = null
            cachedAtElapsedMs = 0L
        }
    }
}
