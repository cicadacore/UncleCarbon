@file:Suppress("DEPRECATION")

package com.hamoon.uncleted.receivers

import com.hamoon.uncleted.data.SecurityEvent
import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import com.hamoon.uncleted.R
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.core.LockdownManager
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.services.PanicActionService
import com.hamoon.uncleted.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AdminReceiver"
        private const val ATTEMPT_DEDUPLICATION_WINDOW_MS = 1500L

        @Volatile
        private var lastHandledAttemptTime = 0L

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, AdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled. Initializing hardware baseline policies.")
        EventLogger.log(context, SecurityEvent.ADMIN_ENABLED)

        val dpm = getManager(context)
        val admin = getWho(context)

        CoroutineScope(Dispatchers.IO).launch {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    dpm.setStorageEncryption(admin, true)

                    val maxFailedWipe = SecurityPreferences.getMaxFailedAttemptsForWipe(context)
                    dpm.setMaximumFailedPasswordsForWipe(admin, maxFailedWipe)
                    Log.i(TAG, "Device Owner Gatekeeper wipe limit configured: $maxFailedWipe")

                    dpm.setPasswordQuality(admin, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX)
                    dpm.setPasswordMinimumLength(admin, 6)

                    // Use the same guarded policy path as the settings UI.
                    LockdownManager.setSafeBootBlocked(context, SecurityPreferences.isSafeBootBlocked(context)).join()
                    LockdownManager.setDeveloperFeaturesBlocked(context, SecurityPreferences.isDeveloperFeaturesBlocked(context)).join()

                    // Becoming Device Owner turns the backup service off.
                    DefenseCoordinator.resolveStrategy(context).allowBackupAndUserCreation()

                    // Self-grant READ_PHONE_STATE so the SIM state machine
                    // works without user interaction and remains functional
                    // in the Direct-Boot window (before first unlock) when
                    // the user cannot answer a runtime-permission dialog.
                    try {
                        val granted = dpm.setPermissionGrantState(
                            admin,
                            context.packageName,
                            Manifest.permission.READ_PHONE_STATE,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                        )
                        Log.i(TAG, "Device Owner self-grant READ_PHONE_STATE: $granted")
                    } catch (e: Exception) {
                        Log.w(TAG, "Device Owner self-grant of READ_PHONE_STATE failed: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed configuring initial Device Owner policies", e)
                }
            }
        }
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        handlePasswordFailure(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        handlePasswordFailure(context)
    }

    private fun handlePasswordFailure(context: Context) {
        val now = System.currentTimeMillis()
        synchronized(AdminReceiver::class.java) {
            if (now - lastHandledAttemptTime < ATTEMPT_DEDUPLICATION_WINDOW_MS) {
                return
            }
            lastHandledAttemptTime = now
        }

        val dpm = getManager(context)
        val currentFailed = dpm.getCurrentFailedPasswordAttempts()
        Log.w(TAG, "Authentication failure detected. Hardware count: $currentFailed")
        EventLogger.log(context, SecurityEvent.KEYGUARD_FAILED)

        SecurityPreferences.incrementFailedAttempts(context)

        CoroutineScope(Dispatchers.IO).launch {
            val strategy = DefenseCoordinator.resolveStrategy(context)
            val maxAllowedBeforeWipe = SecurityPreferences.getMaxFailedAttemptsForWipe(context)

            // 1. Check if user-configured brute-force wipe limit is exceeded.
            //    Routes through the single standard Device Owner factory-reset path.
            if (maxAllowedBeforeWipe in 1..currentFailed) {
                Log.e(TAG, "Hardware failure count ($currentFailed) reached user wipe limit ($maxAllowedBeforeWipe). Initiating standard factory reset.")
                EventLogger.log(context, SecurityEvent.KEYGUARD_WIPE)
                strategy.executeStandardWipe("MAX_FAILED_PASSWORDS_EXCEEDED")
                return@launch
            }

            // 2. Proactive defense on 3 consecutive failures: sever USB port & lock biometrics
            if (currentFailed >= 3) {
                Log.e(TAG, "Threshold >= 3 reached. Physically disabling USB port and locking biometrics.")
                strategy.setUsbDataPortEnabled(false)
                strategy.disableBiometrics(true)

                if (SecurityPreferences.isIntruderSelfieEnabled(context)) {
                    PanicActionService.trigger(
                        context,
                        "INTRUDER_SELFIE",
                        PanicActionService.Severity.MEDIUM
                    )
                }
            }
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        handlePasswordSuccess(context)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        handlePasswordSuccess(context)
    }

    private fun handlePasswordSuccess(context: Context) {
        Log.d(TAG, "Lockscreen authentication succeeded. Resetting state.")
        SecurityPreferences.resetFailedAttempts(context)

        val deContext = context.createDeviceProtectedStorageContext()
        deContext.getSharedPreferences("deadman_state", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_authenticated_epoch", System.currentTimeMillis())
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            val strategy = DefenseCoordinator.resolveStrategy(context)
            strategy.disableBiometrics(false)
            strategy.setUsbDataPortEnabled(true)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        if (SecurityPreferences.isMaintenanceMode(context)) {
            Log.i(TAG, "Admin deactivation authorized in maintenance mode.")
            return "Maintenance mode active. Deactivation permitted."
        }

        Log.w(TAG, "Hostile Device Admin deactivation detected. Triggering alert.")
        EventLogger.log(context, SecurityEvent.ADMIN_DEACTIVATION)

        PanicActionService.trigger(context, "UNINSTALL_ATTEMPT", PanicActionService.Severity.HIGH)

        return context.getString(R.string.admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.e(TAG, "CRITICAL: Device Admin has been disabled.")
        EventLogger.log(context, SecurityEvent.ADMIN_DISABLED)
    }
}
