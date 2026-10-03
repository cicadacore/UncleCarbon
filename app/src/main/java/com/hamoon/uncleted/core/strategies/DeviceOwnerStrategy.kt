package com.hamoon.uncleted.core.strategies

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.hamoon.uncleted.core.DebuggingPolicyLogic
import com.hamoon.uncleted.core.DefenseStrategy
import com.hamoon.uncleted.core.WipeFlagBuilder
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.util.EventLogger

/**
 * GrapheneOS Device Owner strategy. All privileged operations route through
 * DevicePolicyManager supported APIs; this fork never bypasses platform
 * boundaries with root, LSPosed, kernel SysFS, or privileged shell commands.
 *
 * Standard factory reset is the only destructive operation; UncleTed does not
 * perform StrongBox suicide-key preprocessing, partition destruction, raw block
 * device operations, or Level 2/3/4 routines in this fork.
 */
class DeviceOwnerStrategy(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val adminComponent: ComponentName
) : DefenseStrategy {

    companion object {
        private const val TAG = "DeviceOwnerStrategy"
    }

    override val profileName: String = "DEVICE_OWNER_GRAPHENEOS"
    override val isHardwareSecured: Boolean = true
    override val isDeviceOwnerProvisioned: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    init {
        enforcePersistentBaselineRestrictions()
    }

    private fun enforcePersistentBaselineRestrictions() {
        if (!isDeviceOwnerProvisioned) return
        try {
            applySafeBootPolicy(SecurityPreferences.isSafeBootBlocked(context))
            reconcileDebuggingFeaturesRestriction()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply baseline restrictions", e)
        }
    }

    private fun applySafeBootPolicy(blocked: Boolean) {
        if (!isDeviceOwnerProvisioned) return
        try {
            if (blocked) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                Log.i(TAG, "Device Owner applied DISALLOW_SAFE_BOOT restriction.")
                EventLogger.log(context, "POLICY: Safe Boot blocked by Device Owner.")
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                Log.w(TAG, "Device Owner cleared DISALLOW_SAFE_BOOT restriction (Safe Mode permitted).")
                EventLogger.log(context, "POLICY WARNING: Safe Boot restriction removed by user.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update DISALLOW_SAFE_BOOT policy: ${e.message}", e)
        }
    }

    override suspend fun setSafeBootBlocked(blocked: Boolean) {
        applySafeBootPolicy(blocked)
    }

    override suspend fun setDeveloperFeaturesBlocked(blocked: Boolean) {
        // The user's choice is persisted by the caller (SecurityPreferences)
        // before this runs. Reconciliation reads the effective state so Developer
        // Interception and the USB lockdown can never clear each other's policy.
        reconcileDebuggingFeaturesRestriction()
    }

    /**
     * Single source of truth for the DISALLOW_DEBUGGING_FEATURES restriction.
     * The restriction stays set while EITHER the user's Developer Interception
     * policy is enabled OR an active USB data-port lockdown requires it.
     */
    private fun reconcileDebuggingFeaturesRestriction() {
        if (!isDeviceOwnerProvisioned) return
        val mustBlock = DebuggingPolicyLogic.shouldBlockDebugging(
            developerFeaturesBlocked = SecurityPreferences.isDeveloperFeaturesBlocked(context),
            usbDataPortDisabled = SecurityPreferences.isUsbDataPortDisabled(context)
        )
        try {
            if (mustBlock) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                Log.i(TAG, "Device Owner enforcing DISALLOW_DEBUGGING_FEATURES (developer interception and/or USB lockdown active).")
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                Log.i(TAG, "Device Owner cleared DISALLOW_DEBUGGING_FEATURES (no active policy requires it).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reconciling DISALLOW_DEBUGGING_FEATURES policy: ${e.message}", e)
        }
    }

    /**
     * Unified whole-device factory reset routine for Device Owner installations.
     * Uses dpm.wipeDevice() on Android 14+ (API 34+) to avoid IllegalStateException
     * on User 0, and cleanly falls back to dpm.wipeData() on older supported versions.
     */
    private fun requestWholeDeviceWipe(reason: String) {
        if (!isDeviceOwnerProvisioned) {
            Log.w(TAG, "Standard wipe requested but Device Owner is not provisioned: $reason")
            EventLogger.log(context, "WIPE_SKIPPED: Device Owner not provisioned ($reason).")
            return
        }

        // Mandatory flags are always applied. WIPE_EUICC is added ONLY when the
        // user has opted into eSIM erasure on wipe; the flag composition is the
        // single central place this choice takes effect for every wipe caller.
        val eraseEsim = SecurityPreferences.isEraseEsimOnWipeEnabled(context)
        val flags = WipeFlagBuilder.build(
            baseFlags = DevicePolicyManager.WIPE_EXTERNAL_STORAGE or DevicePolicyManager.WIPE_SILENTLY,
            euiccFlag = DevicePolicyManager.WIPE_EUICC,
            eraseEsim = eraseEsim
        )
        Log.i(TAG, "Composed wipe flags (eraseEsim=$eraseEsim).")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.i(TAG, "Executing dpm.wipeDevice() on API 34+ (Reason: $reason)")
                dpm.wipeDevice(flags)
            } else {
                @Suppress("DEPRECATION")
                Log.i(TAG, "Executing dpm.wipeData() on API < 34 (Reason: $reason)")
                dpm.wipeData(flags)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during Device Owner wipe (Reason: $reason): ${e.message}", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException during Device Owner wipe (Reason: $reason): ${e.message}", e)
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "UnsupportedOperationException during Device Owner wipe (Reason: $reason): ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception during Device Owner wipe (Reason: $reason): ${e.message}", e)
        }
    }

    override suspend fun executeStandardWipe(reason: String) {
        Log.i(TAG, "Executing standard platform wipe / factory reset (Reason: $reason)")
        EventLogger.log(context, "STANDARD_WIPE: Executing normal factory reset via Device Owner.")
        requestWholeDeviceWipe(reason)
    }

    override suspend fun setUsbDataPortEnabled(enabled: Boolean) {
        if (!isDeviceOwnerProvisioned) {
            Log.w(TAG, "USB policy change skipped: Device Owner not provisioned.")
            return
        }
        Log.i(TAG, "Configuring hardware USB data signaling: enabled=$enabled")
        EventLogger.log(context, "HARDWARE: USB data signaling toggled: enabled=$enabled")

        // Method 1: Android 12+ (API 31+) USB HAL v1.3+ physical line disconnect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                dpm.setUsbDataSignalingEnabled(enabled)
                Log.i(TAG, "USB Type-C HAL data signaling state set to: $enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed calling setUsbDataSignalingEnabled via USB HAL", e)
            }
        } else {
            Log.w(TAG, "Physical USB HAL disconnect requires Android 12+ (API 31+). Applying user restrictions fallback.")
        }

        // Method 2: Android Enterprise User Restrictions fallback and defense-in-depth.
        //
        // DISALLOW_DEBUGGING_FEATURES is intentionally NOT toggled directly here.
        // It is a shared restriction also owned by the Developer Interception
        // policy, so we only record the USB posture and let the centralized
        // reconciliation decide the effective state. This guarantees that
        // re-enabling USB never clears debugging while Developer Interception is
        // on, and disabling USB keeps debugging blocked while the lockdown holds.
        try {
            if (!enabled) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
                }
            } else {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
                }
            }
            SecurityPreferences.setUsbDataPortDisabled(context, !enabled)
            reconcileDebuggingFeaturesRestriction()
        } catch (e: Exception) {
            Log.e(TAG, "Failed modifying USB policy restrictions", e)
        }
    }

    override suspend fun configureBruteForceThreshold(maxFailedAttempts: Int) {
        if (!isDeviceOwnerProvisioned) return
        try {
            dpm.setMaximumFailedPasswordsForWipe(adminComponent, maxFailedAttempts)
            Log.i(TAG, "Hardware Gatekeeper/Weaver max failed attempts configured to: $maxFailedAttempts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed configuring hardware brute-force threshold", e)
        }
    }

    override suspend fun evictMemoryKeysAndLock() {
        if (!isDeviceOwnerProvisioned) {
            try {
                dpm.lockNow()
            } catch (_: Exception) {}
            return
        }
        Log.w(TAG, "Evicting Credential-Encrypted (CE) keys to cold BFU state via native Device Owner reboot.")
        EventLogger.log(context, "ANTI-FORENSICS: Executing Device Owner native cold reboot to revert into BFU state.")

        try {
            dpm.reboot(adminComponent)
        } catch (e: Exception) {
            Log.e(TAG, "dpm.reboot failed or restricted; falling back to instant Keyguard lockdown", e)
            try {
                dpm.lockNow()
            } catch (lockEx: Exception) {
                Log.e(TAG, "dpm.lockNow failed", lockEx)
            }
        }
    }

    override suspend fun disableBiometrics(disable: Boolean) {
        if (!isDeviceOwnerProvisioned) return
        val flags = if (disable) {
            DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS or
                    DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT or
                    DevicePolicyManager.KEYGUARD_DISABLE_FACE
        } else {
            0
        }
        try {
            dpm.setKeyguardDisabledFeatures(adminComponent, flags)
            Log.i(TAG, "Keyguard biometric disable state: $disable")
        } catch (e: Exception) {
            Log.e(TAG, "Failed modifying Keyguard disabled features", e)
        }
    }
}
