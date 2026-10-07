package com.hamoon.unclecarbon.util

import com.hamoon.unclecarbon.data.SecurityEvent
import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.hamoon.unclecarbon.data.SecurityPreferences
import com.hamoon.unclecarbon.services.PanicActionService

object BiometricAuthManager {

    private const val TAG = "BiometricAuthManager"
    private const val MAX_FAILED_BIOMETRIC_ATTEMPTS = 5

    enum class AuthResult {
        SUCCESS, FAILED, CANCELLED, ERROR, UNAVAILABLE, HARDWARE_UNAVAILABLE
    }

    interface AuthCallback {
        fun onAuthResult(result: AuthResult, errorMessage: String? = null)
    }

    // Capability detection and the actual prompt MUST use the same authenticators.
    const val ALLOWED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAuthenticatorAvailable(context: Context): Boolean = try {
        BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    } catch (_: Exception) {
        false
    }

    /** Recreate in onCreate to reconnect AndroidX callbacks after configuration changes. */
    fun createPrompt(activity: FragmentActivity, callback: AuthCallback): BiometricPrompt =
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    try {
                        if (errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                            errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                            handleBiometricLockout(activity)
                        }
                    } catch (_: Exception) {
                        callback.onAuthResult(AuthResult.ERROR)
                        return
                    }
                    val cancelled = errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    callback.onAuthResult(if (cancelled) AuthResult.CANCELLED else AuthResult.ERROR)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val outcome = try {
                        SecurityPreferences.resetFailedBiometricAttempts(activity)
                        AuthResult.SUCCESS
                    } catch (_: Exception) {
                        AuthResult.ERROR
                    }
                    callback.onAuthResult(outcome)
                }

                override fun onAuthenticationFailed() {
                    val outcome = try {
                        handleFailedBiometricAttempt(activity)
                        AuthResult.FAILED
                    } catch (_: Exception) {
                        AuthResult.ERROR
                    }
                    callback.onAuthResult(outcome)
                }
            })

    fun authenticateUser(prompt: BiometricPrompt, title: String, subtitle: String) {
        // The activity catches setup/authenticate exceptions and denies access.
        prompt.authenticate(promptInfo(title, subtitle))
    }

    internal fun promptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

    private fun handleFailedBiometricAttempt(context: Context) {
        SecurityPreferences.incrementFailedBiometricAttempts(context)
        val attempts = SecurityPreferences.getFailedBiometricAttempts(context)

        Log.w(TAG, "Failed biometric attempt #$attempts")
        EventLogger.log(context, SecurityEvent.BIOMETRIC_FAILED)

        if (attempts >= MAX_FAILED_BIOMETRIC_ATTEMPTS) {
            Log.e(TAG, "Maximum biometric attempts exceeded. Triggering security response.")
            PanicActionService.trigger(context, "BIOMETRIC_INTRUSION", PanicActionService.Severity.HIGH)
        }
    }

    private fun handleBiometricLockout(context: Context) {
        Log.e(TAG, "Biometric authentication locked out - potential attack detected")
        EventLogger.log(context, SecurityEvent.BIOMETRIC_LOCKOUT)
        PanicActionService.trigger(context, "BIOMETRIC_LOCKOUT", PanicActionService.Severity.CRITICAL)
    }
}
