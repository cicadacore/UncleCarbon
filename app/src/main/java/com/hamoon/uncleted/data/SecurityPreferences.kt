package com.hamoon.uncleted.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hamoon.uncleted.util.PolygonUtils
import java.text.SimpleDateFormat
import java.util.*

object SecurityPreferences {

    private const val TAG = "SecurityPreferences"

    @Volatile
    private var encryptedInstance: SharedPreferences? = null
    @Volatile
    private var deInstance: SharedPreferences? = null

    private val LOCK = Any()
    private const val PREFS_FILE_NAME = "secure_app_prefs"
    private const val DE_PREFS_FILE_NAME = "device_encrypted_prefs"
    private const val EVENT_LOG_KEY = "event_log"
    private const val MAX_LOG_ENTRIES = 150
    private const val CUSTOM_WIPE_ZONES_KEY = "CUSTOM_WIPE_ZONES"

    fun isUserUnlocked(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = context.getSystemService(UserManager::class.java)
            userManager?.isUserUnlocked ?: true
        } else {
            true
        }
    }

    fun getDeviceProtectedPrefs(context: Context): SharedPreferences {
        return deInstance ?: synchronized(LOCK) {
            deInstance ?: run {
                val deContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context
                }
                deContext.getSharedPreferences(DE_PREFS_FILE_NAME, Context.MODE_PRIVATE).also {
                    deInstance = it
                }
            }
        }
    }

    internal fun getInstance(context: Context): SharedPreferences {
        if (!isUserUnlocked(context)) {
            return getDeviceProtectedPrefs(context)
        }

        return encryptedInstance ?: synchronized(LOCK) {
            encryptedInstance ?: try {
                createEncryptedPrefs(context.applicationContext).also {
                    encryptedInstance = it
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing EncryptedSharedPreferences. Falling back to DE storage.", e)
                getDeviceProtectedPrefs(context)
            }
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Removes legacy preference keys that correspond to features removed from this
     * GrapheneOS fork (root/LSPosed hooks, PIN/Honeypot subsystem, PMIC telemetry,
     * raw USB UDC tripwire, 2G user restriction, Level 2/3/4 destruction protocols,
     * kernel memory hardening). Safe to call repeatedly.
     */
    fun migrateObsoletePreferences(context: Context) {
        val obsoleteKeys = listOf(
            // PIN / Honeypot / Decoy subsystem
            "NORMAL_PIN", "DURESS_PIN", "WIPE_PIN", "HONEYPOT_PIN", "DECOY_USER_ID",
            "BFU_NORMAL_PIN", "BFU_DURESS_PIN", "BFU_WIPE_PIN", "BFU_HONEYPOT_PIN", "BFU_DECOY_USER_ID",
            "HONEYPOT_INTEL",
            "DECOY_WHATSAPP_ENABLED", "DECOY_SIGNAL_ENABLED", "DECOY_TELEGRAM_ENABLED",
            "DECOY_THREEMA_ENABLED", "DECOY_SESSION_ENABLED", "DECOY_APP_ACTION",
            "BFU_DECOY_WHATSAPP_ENABLED", "BFU_DECOY_SIGNAL_ENABLED",
            "BFU_DECOY_TELEGRAM_ENABLED", "BFU_DECOY_THREEMA_ENABLED",
            "BFU_DECOY_SESSION_ENABLED", "BFU_DECOY_APP_ACTION",
            "AIRPLANE_PIN_CHALLENGE", "BFU_AIRPLANE_PIN_CHALLENGE",
            // Root-exclusive features
            "ROOT_GPS_SPOOFING", "ROOT_DECOY_GPS_LOCATION", "ROOT_SILENT_INSTALL",
            "ROOT_REMOTE_APK_URL", "ROOT_FIREWALL_TRIPWIRE", "ROOT_SECURE_WIPE",
            "ROOT_SYSTEM_APP", "ROOT_UNKILLABLE_SERVICE", "ROOT_PROCESS_HIDDEN",
            "ROOT_STEALTH_SCREENSHOT", "ROOT_KEYLOGGER", "ROOT_STEALTH_MEDIA",
            "KEYLOG_DATA",
            // ZRAM / kernel memory scrubbing
            "ZRAM_SCRUBBING_ENABLED", "BFU_ZRAM_SCRUBBING_ENABLED",
            "ZRAM_REKEYING_ENABLED", "BFU_ZRAM_REKEYING_ENABLED",
            // PMIC telemetry
            "PMIC_TAMPER_ENABLED", "BFU_PMIC_TAMPER_ENABLED",
            "PMIC_IMPEDANCE_DELTA", "BFU_PMIC_IMPEDANCE_DELTA",
            "PMIC_THERMAL_DELTA", "BFU_PMIC_THERMAL_DELTA",
            // Raw USB UDC tripwire
            "USB_TRIPWIRE_ENABLED", "BFU_USB_TRIPWIRE_ENABLED",
            "USB_REQUIRED_HITS", "BFU_USB_REQUIRED_HITS",
            // UncleTed 2G restriction (GrapheneOS has its own OS-level 2G control)
            "HARDWARE_2G_DISABLED", "BFU_HARDWARE_2G_DISABLED",
            // Faraday blackout receiver removed
            "FARADAY_BLACKOUT_ENABLED", "BFU_FARADAY_BLACKOUT_ENABLED",
            "FARADAY_DURATION_HOURS", "BFU_FARADAY_DURATION_HOURS",
            // OHTTP canary removed
            "OHTTP_CANARY_ENABLED", "BFU_OHTTP_CANARY_ENABLED",
            "OHTTP_RELAY_URL", "BFU_OHTTP_RELAY_URL",
            "OHTTP_GATEWAY_PUBKEY", "BFU_OHTTP_GATEWAY_PUBKEY",
            "OHTTP_MASQUERADE_PROFILE", "BFU_OHTTP_MASQUERADE_PROFILE",
            // Ed25519 remote command removed
            "ED25519_COMMAND_ENABLED", "BFU_ED25519_COMMAND_ENABLED",
            "ED25519_PUBKEY", "BFU_ED25519_PUBKEY",
            // Plausible Deniability Vault removed
            "VAULT_CARRIER_FILENAME", "BFU_VAULT_CARRIER_FILENAME",
            "VAULT_SECRET_LABEL",
            // Crypto engine / PQC / Anti-Rollback removed
            "PQC_ENABLED", "BFU_PQC_ENABLED",
            "ANTI_ROLLBACK_ENABLED", "BFU_ANTI_ROLLBACK_ENABLED",
            "HSM_STATUS_MONITORING", "BFU_HSM_STATUS_MONITORING",
            // RF/network-loss sentinel: motion confirmation replaced by Wi-Fi RF confirmation
            "SPECTRAL_MOTION_REQUIRED", "BFU_SPECTRAL_MOTION_REQUIRED"
        )

        try {
            if (isUserUnlocked(context)) {
                val editor = getInstance(context).edit()
                obsoleteKeys.forEach { editor.remove(it) }
                editor.apply()
            }
        } catch (_: Exception) {
            // Encrypted prefs may not initialize for stale installs; ignore.
        }

        try {
            val deEditor = getDeviceProtectedPrefs(context).edit()
            obsoleteKeys.forEach { deEditor.remove(it) }
            deEditor.apply()
        } catch (_: Exception) {}

        // Migrate single-toggle SIM wipe preference into separate removal/replacement toggles
        try {
            val dePrefs = getDeviceProtectedPrefs(context)
            if (dePrefs.contains("BFU_WIPE_ON_SIM_REMOVAL") &&
                !dePrefs.contains("BFU_WIPE_ON_SIM_REPLACEMENT")) {
                val legacy = dePrefs.getBoolean("BFU_WIPE_ON_SIM_REMOVAL", false)
                dePrefs.edit().putBoolean("BFU_WIPE_ON_SIM_REPLACEMENT", legacy).apply()
            }
            if (isUserUnlocked(context)) {
                val prefs = getInstance(context)
                if (prefs.contains("WIPE_ON_SIM_REMOVAL") &&
                    !prefs.contains("WIPE_ON_SIM_REPLACEMENT")) {
                    val legacy = prefs.getBoolean("WIPE_ON_SIM_REMOVAL", false)
                    prefs.edit().putBoolean("WIPE_ON_SIM_REPLACEMENT", legacy).apply()
                }
            }
        } catch (_: Exception) {}

        // Retire the legacy SIM_SERIAL / BFU_SIM_SERIAL baseline. The old
        // SimChangeReceiver stored whatever the carrier-only fallback happened
        // to return (simOperator + simCountryIso) and treated that as the SIM
        // identity. That value is not a per-SIM identifier (same carrier =>
        // same string) and could cause destructive false positives or missed
        // detections after upgrade. SimMonitor will recapture a fresh baseline
        // using SubscriptionInfo + isEmbedded on the next active-subscription
        // observation.
        try {
            getDeviceProtectedPrefs(context).edit()
                .remove("BFU_SIM_SERIAL")
                .apply()
            if (isUserUnlocked(context)) {
                getInstance(context).edit()
                    .remove("SIM_SERIAL")
                    .apply()
            }
        } catch (_: Exception) {}

        // Clear any stale in-flight latch left over from a wipe path that was
        // dispatched but did not complete (reboot interrupted, exception).
        try {
            getDeviceProtectedPrefs(context).edit()
                .putBoolean("BFU_SIM_WIPE_IN_FLIGHT", false)
                .apply()
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 0. Surveillance Durations & Optical Configuration
    // =========================================================================
    fun setVideoRecordingDurationSeconds(context: Context, seconds: Int) {
        val bounded = seconds.coerceIn(5, 120)
        getDeviceProtectedPrefs(context).edit().putInt("BFU_VIDEO_DURATION_SEC", bounded).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putInt("VIDEO_DURATION_SEC", bounded).apply()
        }
    }

    fun getVideoRecordingDurationSeconds(context: Context): Int {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getInt("BFU_VIDEO_DURATION_SEC", 15)
        } else {
            getInstance(context).getInt("VIDEO_DURATION_SEC", 15)
        }
    }

    fun setAudioRecordingDurationSeconds(context: Context, seconds: Int) {
        val bounded = seconds.coerceIn(5, 600)
        getDeviceProtectedPrefs(context).edit().putInt("BFU_AUDIO_DURATION_SEC", bounded).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putInt("AUDIO_DURATION_SEC", bounded).apply()
        }
    }

    fun getAudioRecordingDurationSeconds(context: Context): Int {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getInt("BFU_AUDIO_DURATION_SEC", 30)
        } else {
            getInstance(context).getInt("AUDIO_DURATION_SEC", 30)
        }
    }

    fun setFrontCameraCaptureEnabled(context: Context, enabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_ENABLE_FRONT_CAM", enabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("ENABLE_FRONT_CAM", enabled).apply()
        }
    }

    fun isFrontCameraCaptureEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_ENABLE_FRONT_CAM", false)
        } else {
            getInstance(context).getBoolean("ENABLE_FRONT_CAM", false)
        }
    }

    fun setBackCameraCaptureEnabled(context: Context, enabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_ENABLE_BACK_CAM", enabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("ENABLE_BACK_CAM", enabled).apply()
        }
    }

    fun isBackCameraCaptureEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_ENABLE_BACK_CAM", false)
        } else {
            getInstance(context).getBoolean("ENABLE_BACK_CAM", false)
        }
    }

    // =========================================================================
    // 0.1 Safe Boot Policy (Anti-Bypass)
    // =========================================================================
    fun setSafeBootBlocked(context: Context, blocked: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_BLOCK_SAFE_BOOT", blocked).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("BLOCK_SAFE_BOOT", blocked).apply()
        }
    }

    fun isSafeBootBlocked(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_BLOCK_SAFE_BOOT", true)
        } else {
            getDeviceProtectedPrefs(context).getBoolean("BFU_BLOCK_SAFE_BOOT", true) &&
                    getInstance(context).getBoolean("BLOCK_SAFE_BOOT", true)
        }
    }

    // =========================================================================
    // 0.1b Developer / Debugging Interception Policy (Anti-Debug)
    //
    // Independent of Safe Boot. Default FALSE: existing installs and fresh
    // installs alike treat debugging interception as disabled unless the user
    // explicitly opts in. Never silently enabled on upgrade.
    // =========================================================================
    fun setDeveloperFeaturesBlocked(context: Context, blocked: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_BLOCK_DEVELOPER_FEATURES", blocked).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("BLOCK_DEVELOPER_FEATURES", blocked).apply()
        }
    }

    fun isDeveloperFeaturesBlocked(context: Context): Boolean {
        // Fail-safe OR: if either mirror records the opt-in, treat it as enabled.
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_BLOCK_DEVELOPER_FEATURES", false)
        } else {
            getDeviceProtectedPrefs(context).getBoolean("BFU_BLOCK_DEVELOPER_FEATURES", false) ||
                    getInstance(context).getBoolean("BLOCK_DEVELOPER_FEATURES", false)
        }
    }

    // Runtime USB data-port posture (not a user setting). Persisted in DE storage
    // so the centralized DISALLOW_DEBUGGING_FEATURES reconciliation knows whether
    // an active USB lockdown still requires debugging to stay blocked, even across
    // process restarts and before credential unlock. Default FALSE.
    fun setUsbDataPortDisabled(context: Context, disabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_USB_DATA_PORT_DISABLED", disabled).apply()
    }

    fun isUsbDataPortDisabled(context: Context): Boolean =
        getDeviceProtectedPrefs(context).getBoolean("BFU_USB_DATA_PORT_DISABLED", false)

    // =========================================================================
    // 0.2 Max Failed Passwords Threshold for Wipe
    // =========================================================================
    fun getMaxFailedAttemptsForWipe(context: Context): Int {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getInt("BFU_MAX_FAILED_ATTEMPTS_WIPE", 5)
        } else {
            getInstance(context).getInt("MAX_FAILED_ATTEMPTS_WIPE", 5)
        }
    }

    fun setMaxFailedAttemptsForWipe(context: Context, count: Int) {
        getDeviceProtectedPrefs(context).edit().putInt("BFU_MAX_FAILED_ATTEMPTS_WIPE", count).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putInt("MAX_FAILED_ATTEMPTS_WIPE", count).apply()
        }
    }

    // =========================================================================
    // 0.3 SIM Removal / SIM Replacement Standard Factory Reset
    // =========================================================================
    fun isWipeOnSimRemovalEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_WIPE_ON_SIM_REMOVAL", false)
        } else {
            getInstance(context).getBoolean("WIPE_ON_SIM_REMOVAL", false)
        }
    }

    fun setWipeOnSimRemovalEnabled(context: Context, enabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_WIPE_ON_SIM_REMOVAL", enabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("WIPE_ON_SIM_REMOVAL", enabled).apply()
        }
    }

    fun isWipeOnSimReplacementEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_WIPE_ON_SIM_REPLACEMENT", false)
        } else {
            getInstance(context).getBoolean("WIPE_ON_SIM_REPLACEMENT", false)
        }
    }

    fun setWipeOnSimReplacementEnabled(context: Context, enabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_WIPE_ON_SIM_REPLACEMENT", enabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("WIPE_ON_SIM_REPLACEMENT", enabled).apply()
        }
    }

    // =========================================================================
    // 0.4 Fake Airplane Mode Quick Settings Safety & Trap Controls
    // =========================================================================
    fun setFakeAirplaneAction(context: Context, action: String) {
        getDeviceProtectedPrefs(context).edit().putString("BFU_AIRPLANE_TILE_ACTION", action).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putString("AIRPLANE_TILE_ACTION", action).apply()
        }
    }

    fun getFakeAirplaneAction(context: Context): String {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getString("BFU_AIRPLANE_TILE_ACTION", "STANDARD_WIPE") ?: "STANDARD_WIPE"
        } else {
            getInstance(context).getString("AIRPLANE_TILE_ACTION", "STANDARD_WIPE") ?: "STANDARD_WIPE"
        }
    }

    // =========================================================================
    // 2. BLE Proximity Sentinel
    // =========================================================================
    fun setProximityShardingEnabled(context: Context, isEnabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_PROXIMITY_SHARDING_ENABLED", isEnabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("PROXIMITY_SHARDING_ENABLED", isEnabled).apply()
        }
    }

    fun isProximityShardingEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_PROXIMITY_SHARDING_ENABLED", false)
        } else {
            getDeviceProtectedPrefs(context).getBoolean("BFU_PROXIMITY_SHARDING_ENABLED", false) &&
                    getInstance(context).getBoolean("PROXIMITY_SHARDING_ENABLED", false)
        }
    }

    fun setProximityBleTargetAddress(context: Context, address: String?) {
        getDeviceProtectedPrefs(context).edit().putString("BFU_PROXIMITY_BLE_MAC", address?.trim()?.uppercase()).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putString("PROXIMITY_BLE_MAC", address?.trim()?.uppercase()).apply()
        }
    }

    fun getProximityBleTargetAddress(context: Context): String? {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getString("BFU_PROXIMITY_BLE_MAC", null)
        } else {
            getDeviceProtectedPrefs(context).getString(
                "BFU_PROXIMITY_BLE_MAC",
                getInstance(context).getString("PROXIMITY_BLE_MAC", null)
            )
        }
    }

    fun setProximityRssiThreshold(context: Context, rssiThresholdDbm: Int) {
        getDeviceProtectedPrefs(context).edit().putInt("BFU_PROXIMITY_RSSI_THRESHOLD", rssiThresholdDbm).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putInt("PROXIMITY_RSSI_THRESHOLD", rssiThresholdDbm).apply()
        }
    }

    fun getProximityRssiThreshold(context: Context): Int {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getInt("BFU_PROXIMITY_RSSI_THRESHOLD", -85)
        } else {
            getDeviceProtectedPrefs(context).getInt(
                "BFU_PROXIMITY_RSSI_THRESHOLD",
                getInstance(context).getInt("PROXIMITY_RSSI_THRESHOLD", -85)
            )
        }
    }

    fun setProximityMissedHeartbeatThreshold(context: Context, count: Int) {
        getDeviceProtectedPrefs(context).edit().putInt("BFU_PROXIMITY_BREACH_LIMIT", count).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putInt("PROXIMITY_BREACH_LIMIT", count).apply()
        }
    }

    fun getProximityMissedHeartbeatThreshold(context: Context): Int {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getInt("BFU_PROXIMITY_BREACH_LIMIT", 3)
        } else {
            getDeviceProtectedPrefs(context).getInt(
                "BFU_PROXIMITY_BREACH_LIMIT",
                getInstance(context).getInt("PROXIMITY_BREACH_LIMIT", 3)
            )
        }
    }

    fun setStoredShardA(context: Context, serializedShardA: String) {
        getDeviceProtectedPrefs(context).edit().putString("BFU_SEALED_SHARD_A", serializedShardA).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putString("SEALED_SHARD_A", serializedShardA).apply()
        }
    }

    fun getStoredShardA(context: Context): String? {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getString("BFU_SEALED_SHARD_A", null)
        } else {
            getDeviceProtectedPrefs(context).getString(
                "BFU_SEALED_SHARD_A",
                getInstance(context).getString("SEALED_SHARD_A", null)
            )
        }
    }

    // =========================================================================
    // 5. Baseband IMSI-Catcher Sentinel (Advanced only, no 2G user restriction)
    // =========================================================================
    fun setBasebandSentinelEnabled(context: Context, isEnabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_BASEBAND_SENTINEL_ENABLED", isEnabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("BASEBAND_SENTINEL_ENABLED", isEnabled).apply()
        }
    }

    fun isBasebandSentinelEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_BASEBAND_SENTINEL_ENABLED", true)
        } else {
            getDeviceProtectedPrefs(context).getBoolean("BFU_BASEBAND_SENTINEL_ENABLED", true) &&
                    getInstance(context).getBoolean("BASEBAND_SENTINEL_ENABLED", true)
        }
    }

    fun setTimingAdvanceThreshold(context: Context, maxTA: Int) {
        getDeviceProtectedPrefs(context).edit().putInt("BFU_TIMING_ADVANCE_MAX", maxTA).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putInt("TIMING_ADVANCE_MAX", maxTA).apply()
        }
    }

    fun getTimingAdvanceThreshold(context: Context): Int {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getInt("BFU_TIMING_ADVANCE_MAX", 30)
        } else {
            getDeviceProtectedPrefs(context).getInt(
                "BFU_TIMING_ADVANCE_MAX",
                getInstance(context).getInt("TIMING_ADVANCE_MAX", 30)
            )
        }
    }

    // =========================================================================
    // 6. RF/Network-loss Sentinel
    // =========================================================================
    fun setSpectralSentinelEnabled(context: Context, isEnabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_SPECTRAL_SENTINEL_ENABLED", isEnabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("SPECTRAL_SENTINEL_ENABLED", isEnabled).apply()
        }
    }

    fun isSpectralSentinelEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_SPECTRAL_SENTINEL_ENABLED", false)
        } else {
            getDeviceProtectedPrefs(context).getBoolean("BFU_SPECTRAL_SENTINEL_ENABLED", false) &&
                    getInstance(context).getBoolean("SPECTRAL_SENTINEL_ENABLED", false)
        }
    }

    fun setSpectralQuarantineMs(context: Context, ms: Long) {
        getDeviceProtectedPrefs(context).edit().putLong("BFU_SPECTRAL_QUARANTINE_MS", ms).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putLong("SPECTRAL_QUARANTINE_MS", ms).apply()
        }
    }

    fun getSpectralQuarantineMs(context: Context): Long {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getLong("BFU_SPECTRAL_QUARANTINE_MS", 1800000L)
        } else {
            getDeviceProtectedPrefs(context).getLong(
                "BFU_SPECTRAL_QUARANTINE_MS",
                getInstance(context).getLong("SPECTRAL_QUARANTINE_MS", 1800000L)
            )
        }
    }

    fun setSpectralAction(context: Context, action: String) {
        getDeviceProtectedPrefs(context).edit().putString("BFU_SPECTRAL_ACTION", action).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putString("SPECTRAL_ACTION", action).apply()
        }
    }

    fun getSpectralAction(context: Context): String {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getString("BFU_SPECTRAL_ACTION", "BFU") ?: "BFU"
        } else {
            getDeviceProtectedPrefs(context).getString(
                "BFU_SPECTRAL_ACTION",
                getInstance(context).getString("SPECTRAL_ACTION", "BFU")
            ) ?: "BFU"
        }
    }

    // Wi-Fi RF Confirmation: distinguishes an ordinary network outage from possible
    // RF isolation by checking for nearby Wi-Fi radio activity. Defaults to ENABLED.
    // A dedicated key — the removed motion preference is never reused.
    fun setWifiRfConfirmationEnabled(context: Context, enabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_SPECTRAL_WIFI_RF_CONFIRMATION", enabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("SPECTRAL_WIFI_RF_CONFIRMATION", enabled).apply()
        }
    }

    fun isWifiRfConfirmationEnabled(context: Context): Boolean {
        return getDeviceProtectedPrefs(context).getBoolean("BFU_SPECTRAL_WIFI_RF_CONFIRMATION", true)
    }

    // Quarantine recovery metadata. Device-protected only: it must survive process
    // death and be readable before first unlock. bootRef (= wallClock - elapsedRealtime)
    // lets the sentinel detect a reboot and refuse to trust a stale elapsedRealtime
    // baseline across boots.
    fun setSpectralQuarantine(context: Context, active: Boolean, startElapsedMs: Long, bootRef: Long) {
        getDeviceProtectedPrefs(context).edit()
            .putBoolean("BFU_SPECTRAL_Q_ACTIVE", active)
            .putLong("BFU_SPECTRAL_Q_START_ELAPSED", startElapsedMs)
            .putLong("BFU_SPECTRAL_Q_BOOT_REF", bootRef)
            .apply()
    }

    fun isSpectralQuarantineActive(context: Context): Boolean =
        getDeviceProtectedPrefs(context).getBoolean("BFU_SPECTRAL_Q_ACTIVE", false)

    fun getSpectralQuarantineStartElapsed(context: Context): Long =
        getDeviceProtectedPrefs(context).getLong("BFU_SPECTRAL_Q_START_ELAPSED", 0L)

    fun getSpectralQuarantineBootRef(context: Context): Long =
        getDeviceProtectedPrefs(context).getLong("BFU_SPECTRAL_Q_BOOT_REF", 0L)

    // =========================================================================
    // 9. Event Logging
    // =========================================================================
    fun logEvent(context: Context, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val newEntry = "$timestamp - $message"

        val prefs = getDeviceProtectedPrefs(context)
        val existingLogs = prefs.getStringSet(EVENT_LOG_KEY, mutableSetOf())?.toMutableList() ?: mutableListOf()

        existingLogs.add(0, newEntry)

        while (existingLogs.size > MAX_LOG_ENTRIES) {
            existingLogs.removeAt(existingLogs.size - 1)
        }

        prefs.edit().putStringSet(EVENT_LOG_KEY, existingLogs.toSet()).apply()
    }

    fun getLogs(context: Context): List<String> {
        return getDeviceProtectedPrefs(context).getStringSet(EVENT_LOG_KEY, setOf())?.sortedDescending() ?: emptyList()
    }

    fun clearLogs(context: Context) {
        getDeviceProtectedPrefs(context).edit().remove(EVENT_LOG_KEY).apply()
    }

    // =========================================================================
    // 10. Core Protection & Maintenance Mode
    // =========================================================================
    fun setProtectionEnabled(context: Context, isEnabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("PROTECTION_ENABLED", isEnabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("PROTECTION_ENABLED", isEnabled).apply()
        }
    }

    fun isProtectionEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("PROTECTION_ENABLED", true)
        } else {
            getInstance(context).getBoolean("PROTECTION_ENABLED", true)
        }
    }

    fun setMaintenanceMode(context: Context, isEnabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("MAINTENANCE_MODE", isEnabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("MAINTENANCE_MODE", isEnabled).apply()
        }
    }

    fun isMaintenanceMode(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("MAINTENANCE_MODE", false)
        } else {
            getInstance(context).getBoolean("MAINTENANCE_MODE", false)
        }
    }

    // =========================================================================
    // 11. Authentication Counters (Device credential tracked by Android itself;
    //     this just mirrors the hardware Keyguard failure count for intruder
    //     capture decisions.)
    // =========================================================================
    fun getFailedAttempts(context: Context): Int =
        getDeviceProtectedPrefs(context).getInt("FAILED_ATTEMPTS", 0)

    fun incrementFailedAttempts(context: Context) {
        val current = getFailedAttempts(context)
        getDeviceProtectedPrefs(context).edit().putInt("FAILED_ATTEMPTS", current + 1).apply()
    }

    fun resetFailedAttempts(context: Context) =
        getDeviceProtectedPrefs(context).edit().putInt("FAILED_ATTEMPTS", 0).apply()

    fun getFailedBiometricAttempts(context: Context): Int =
        getDeviceProtectedPrefs(context).getInt("FAILED_BIOMETRIC_ATTEMPTS", 0)

    fun incrementFailedBiometricAttempts(context: Context) {
        val current = getFailedBiometricAttempts(context)
        getDeviceProtectedPrefs(context).edit().putInt("FAILED_BIOMETRIC_ATTEMPTS", current + 1).apply()
    }

    fun resetFailedBiometricAttempts(context: Context) =
        getDeviceProtectedPrefs(context).edit().putInt("FAILED_BIOMETRIC_ATTEMPTS", 0).apply()

    // =========================================================================
    // 12. Remote Controls & Telephony
    // =========================================================================
    fun setEmergencyContact(context: Context, contact: String) {
        getDeviceProtectedPrefs(context).edit().putString("BFU_EMERGENCY_CONTACT", contact.trim()).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putString("EMERGENCY_CONTACT", contact.trim()).apply()
        }
    }

    fun getEmergencyContact(context: Context): String? {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getString("BFU_EMERGENCY_CONTACT", null)
        } else {
            getDeviceProtectedPrefs(context).getString(
                "BFU_EMERGENCY_CONTACT",
                getInstance(context).getString("EMERGENCY_CONTACT", null)
            )
        }
    }

    fun setSmsMasterPassword(context: Context, password: String) {
        getInstance(context).edit().putString("SMS_MASTER_PASSWORD", password).apply()
        getDeviceProtectedPrefs(context).edit().putString("BFU_SMS_MASTER_PASSWORD", password).apply()
    }

    fun getSmsMasterPassword(context: Context): String? {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getString("BFU_SMS_MASTER_PASSWORD", null)
        } else {
            getInstance(context).getString("SMS_MASTER_PASSWORD", null)
        }
    }

    fun setRemoteInstallCode(context: Context, code: String) =
        getInstance(context).edit().putString("INSTALL_CODE", code).apply()

    fun getRemoteInstallCode(context: Context): String? =
        getInstance(context).getString("INSTALL_CODE", null)

    // =========================================================================
    // 13. Panic Features, Media & Environmental Triggers
    // =========================================================================
    fun setRecordVideoEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("RECORD_VIDEO", isEnabled).apply()

    fun isRecordVideoEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("RECORD_VIDEO", false)

    fun setAmbientAudioEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("AMBIENT_AUDIO_ENABLED", isEnabled).apply()

    fun isAmbientAudioEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("AMBIENT_AUDIO_ENABLED", false)

    fun setWipeDeviceEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("WIPE_DEVICE", isEnabled).apply()

    fun isWipeDeviceEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("WIPE_DEVICE", false)

    fun setIntruderSelfieEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("INTRUDER_SELFIE", isEnabled).apply()

    fun isIntruderSelfieEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("INTRUDER_SELFIE", false)

    fun setSaveSelfieToStorage(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("SAVE_SELFIE_TO_STORAGE", isEnabled).apply()

    fun isSaveSelfieToStorageEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("SAVE_SELFIE_TO_STORAGE", false)

    fun setSimChangeAlertEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("SIM_CHANGE", isEnabled).apply()

    fun isSimChangeAlertEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("SIM_CHANGE", false)

    // -------------------------------------------------------------------------
    // Physical-SIM presence baseline (used by SimMonitor state machine).
    // Replaces the legacy SIM_SERIAL keys which stored unreliable carrier
    // fingerprints and could cause destructive false positives.
    // -------------------------------------------------------------------------

    fun hadPhysicalSimBaseline(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_SIM_HAD_PHYSICAL", false)
        } else {
            getInstance(context).getBoolean("SIM_HAD_PHYSICAL", false)
        }
    }

    fun setHadPhysicalSimBaseline(context: Context, value: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_SIM_HAD_PHYSICAL", value).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("SIM_HAD_PHYSICAL", value).apply()
        }
    }

    fun getPhysicalSimFingerprint(context: Context): String? {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getString("BFU_SIM_PHYSICAL_FP", null)
        } else {
            getInstance(context).getString("SIM_PHYSICAL_FP", null)
        }
    }

    fun setPhysicalSimFingerprint(context: Context, fp: String?) {
        getDeviceProtectedPrefs(context).edit().putString("BFU_SIM_PHYSICAL_FP", fp).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putString("SIM_PHYSICAL_FP", fp).apply()
        }
    }

    fun getSimPendingAbsentHint(context: Context): Boolean =
        getDeviceProtectedPrefs(context).getBoolean("BFU_SIM_PENDING_ABSENT_HINT", false)

    fun setSimPendingAbsentHint(context: Context, value: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_SIM_PENDING_ABSENT_HINT", value).apply()
    }

    fun getSimPendingSubscriptionChange(context: Context): Boolean =
        getDeviceProtectedPrefs(context).getBoolean("BFU_SIM_PENDING_SUB_CHANGE", false)

    fun setSimPendingSubscriptionChange(context: Context, value: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_SIM_PENDING_SUB_CHANGE", value).apply()
    }

    fun isSimWipeInFlight(context: Context): Boolean =
        getDeviceProtectedPrefs(context).getBoolean("BFU_SIM_WIPE_IN_FLIGHT", false)

    fun setSimWipeInFlight(context: Context, value: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_SIM_WIPE_IN_FLIGHT", value).apply()
    }

    fun setShakeToPanicEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("SHAKE_TO_PANIC", isEnabled).apply()

    fun isShakeToPanicEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("SHAKE_TO_PANIC", false)

    fun setShakeSensitivity(context: Context, level: Int) =
        getInstance(context).edit().putInt("SHAKE_SENSITIVITY", level).apply()

    fun getShakeSensitivity(context: Context): Int =
        getInstance(context).getInt("SHAKE_SENSITIVITY", 3)

    fun setHardwareWipeEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("HARDWARE_WIPE_ENABLED", isEnabled).apply()

    fun isHardwareWipeEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("HARDWARE_WIPE_ENABLED", false)

    // =========================================================================
    // 13.1 Destruction Protocols: optional eSIM/eUICC erasure on WIPE
    //
    // Default FALSE and opt-in only. Mirrored into DE/BFU storage so a wipe
    // triggered while the device is in Direct Boot / BFU state (before credential
    // unlock) can still read the user's choice. Applies ONLY to actual
    // factory-reset WIPE operations, never to Lock/BFU/reboot actions.
    // =========================================================================
    fun setEraseEsimOnWipeEnabled(context: Context, isEnabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_ERASE_ESIM_ON_WIPE", isEnabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("ERASE_ESIM_ON_WIPE", isEnabled).apply()
        }
    }

    fun isEraseEsimOnWipeEnabled(context: Context): Boolean {
        // The DE/BFU mirror is authoritative for the centralized wipe path, which
        // may run before unlock. Fail-safe OR keeps the opt-in visible whichever
        // store the caller can currently read.
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_ERASE_ESIM_ON_WIPE", false)
        } else {
            getDeviceProtectedPrefs(context).getBoolean("BFU_ERASE_ESIM_ON_WIPE", false) ||
                    getInstance(context).getBoolean("ERASE_ESIM_ON_WIPE", false)
        }
    }

    // =========================================================================
    // 14. Geographic Suicide, Geofencing & Custom Wipe Zones
    // =========================================================================
    fun setGeofenceSuicideEnabled(context: Context, isEnabled: Boolean) {
        getInstance(context).edit().putBoolean("GEOFENCE_SUICIDE_ENABLED", isEnabled).apply()
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_GEOFENCE_SUICIDE_ENABLED", isEnabled).apply()
    }

    fun isGeofenceSuicideEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_GEOFENCE_SUICIDE_ENABLED", false)
        } else {
            getInstance(context).getBoolean("GEOFENCE_SUICIDE_ENABLED", false)
        }
    }

    fun setGeofenceEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("GEOFENCE_ENABLED", isEnabled).apply()

    fun isGeofenceEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("GEOFENCE_ENABLED", false)

    fun setGeofenceLocation(context: Context, lat: Double, lon: Double) {
        getInstance(context).edit()
            .putLong("GEOFENCE_LAT", lat.toRawBits())
            .putLong("GEOFENCE_LON", lon.toRawBits())
            .apply()
    }

    fun getGeofenceLocation(context: Context): Pair<Double, Double>? {
        val prefs = getInstance(context)
        if (!prefs.contains("GEOFENCE_LAT") || !prefs.contains("GEOFENCE_LON")) return null
        val lat = Double.fromBits(prefs.getLong("GEOFENCE_LAT", 0))
        val lon = Double.fromBits(prefs.getLong("GEOFENCE_LON", 0))
        return lat to lon
    }

    fun getCustomWipeZones(context: Context): List<PolygonUtils.WipeZone> {
        val json = if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getString(CUSTOM_WIPE_ZONES_KEY, "") ?: ""
        } else {
            getInstance(context).getString(CUSTOM_WIPE_ZONES_KEY, "") ?: ""
        }
        return PolygonUtils.deserializeZones(json)
    }

    fun saveCustomWipeZones(context: Context, zones: List<PolygonUtils.WipeZone>) {
        val json = PolygonUtils.serializeZones(zones)
        getDeviceProtectedPrefs(context).edit().putString(CUSTOM_WIPE_ZONES_KEY, json).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putString(CUSTOM_WIPE_ZONES_KEY, json).apply()
        }
    }

    fun addCustomWipeZone(context: Context, zone: PolygonUtils.WipeZone) {
        val current = getCustomWipeZones(context).toMutableList()
        current.add(zone)
        saveCustomWipeZones(context, current)
    }

    fun removeCustomWipeZone(context: Context, zoneId: String) {
        val current = getCustomWipeZones(context).filter { it.id != zoneId }
        saveCustomWipeZones(context, current)
    }

    // =========================================================================
    // 15. Biometric Lock, Stealth Mode & Dialer Code
    // =========================================================================
    fun setAppHidden(context: Context, isHidden: Boolean) =
        getInstance(context).edit().putBoolean("APP_HIDDEN", isHidden).apply()

    fun isAppHidden(context: Context): Boolean =
        getInstance(context).getBoolean("APP_HIDDEN", false)

    fun setSecretDialerCode(context: Context, code: String) =
        getInstance(context).edit().putString("SECRET_DIALER_CODE", code).apply()

    fun getSecretDialerCode(context: Context): String? =
        getInstance(context).getString("SECRET_DIALER_CODE", null)

    fun setBiometricLockEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("BIOMETRIC_LOCK_ENABLED", isEnabled).apply()

    fun isBiometricLockEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("BIOMETRIC_LOCK_ENABLED", false)

    fun setTrustedVpnEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("TRUSTED_VPN_ENABLED", isEnabled).apply()

    fun isTrustedVpnEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("TRUSTED_VPN_ENABLED", false)

    // =========================================================================
    // 16. Dead-Man & Watchdog Timers
    // =========================================================================
    fun setWatchdogModeEnabled(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("WATCHDOG_ENABLED", isEnabled).apply()

    fun isWatchdogModeEnabled(context: Context): Boolean =
        getInstance(context).getBoolean("WATCHDOG_ENABLED", false)

    fun setWatchdogInterval(context: Context, intervalMinutes: Int) =
        getInstance(context).edit().putInt("WATCHDOG_INTERVAL", intervalMinutes).apply()

    fun getWatchdogInterval(context: Context): Int =
        getInstance(context).getInt("WATCHDOG_INTERVAL", 30)

    fun setTripwireEnabled(context: Context, isEnabled: Boolean) {
        getDeviceProtectedPrefs(context).edit().putBoolean("BFU_TRIPWIRE_ENABLED", isEnabled).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putBoolean("TRIPWIRE_ENABLED", isEnabled).apply()
        }
    }

    fun isTripwireEnabled(context: Context): Boolean {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getBoolean("BFU_TRIPWIRE_ENABLED", false)
        } else {
            getDeviceProtectedPrefs(context).getBoolean("BFU_TRIPWIRE_ENABLED", false) ||
                    getInstance(context).getBoolean("TRIPWIRE_ENABLED", false)
        }
    }

    fun setTripwireDuration(context: Context, durationHours: Int) {
        getDeviceProtectedPrefs(context).edit().putInt("BFU_TRIPWIRE_DURATION", durationHours).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putInt("TRIPWIRE_DURATION", durationHours).apply()
        }
    }

    fun getTripwireDuration(context: Context): Int {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getInt("BFU_TRIPWIRE_DURATION", 24)
        } else {
            getDeviceProtectedPrefs(context).getInt(
                "BFU_TRIPWIRE_DURATION",
                getInstance(context).getInt("TRIPWIRE_DURATION", 24)
            )
        }
    }

    fun setLastTripwireCheckIn(context: Context, timestamp: Long) {
        getDeviceProtectedPrefs(context).edit().putLong("BFU_TRIPWIRE_LAST_CHECKIN", timestamp).apply()
        if (isUserUnlocked(context)) {
            getInstance(context).edit().putLong("TRIPWIRE_LAST_CHECKIN", timestamp).apply()
        }
    }

    fun getLastTripwireCheckIn(context: Context): Long {
        return if (!isUserUnlocked(context)) {
            getDeviceProtectedPrefs(context).getLong("BFU_TRIPWIRE_LAST_CHECKIN", 0L)
        } else {
            val deTimestamp = getDeviceProtectedPrefs(context).getLong("BFU_TRIPWIRE_LAST_CHECKIN", 0L)
            if (deTimestamp > 0L) deTimestamp else getInstance(context).getLong("TRIPWIRE_LAST_CHECKIN", 0L)
        }
    }

    // =========================================================================
    // 18. SMTP Email Alert Configuration
    // =========================================================================
    fun setEmailHost(context: Context, host: String) =
        getInstance(context).edit().putString("EMAIL_HOST", host).apply()

    fun getEmailHost(context: Context): String? =
        getInstance(context).getString("EMAIL_HOST", null)

    fun setEmailPort(context: Context, port: Int) =
        getInstance(context).edit().putInt("EMAIL_PORT", port).apply()

    fun getEmailPort(context: Context): Int =
        getInstance(context).getInt("EMAIL_PORT", 0)

    fun setEmailUsername(context: Context, username: String) =
        getInstance(context).edit().putString("EMAIL_USERNAME", username).apply()

    fun getEmailUsername(context: Context): String? =
        getInstance(context).getString("EMAIL_USERNAME", null)

    fun setEmailPassword(context: Context, password: String) =
        getInstance(context).edit().putString("EMAIL_PASSWORD", password).apply()

    fun getEmailPassword(context: Context): String? =
        getInstance(context).getString("EMAIL_PASSWORD", null)

    fun setEnableSslTls(context: Context, isEnabled: Boolean) =
        getInstance(context).edit().putBoolean("EMAIL_SSL_TLS", isEnabled).apply()

    fun isEnableSslTls(context: Context): Boolean =
        getInstance(context).getBoolean("EMAIL_SSL_TLS", true)
}
