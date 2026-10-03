package com.hamoon.uncleted.crypto

import android.content.Context
import android.os.Build

object CryptoPreferences {

    private const val PREFS_NAME = "hardened_crypto_store"

    private const val KEY_CLEARTEXT_SMS_ALLOWED = "allow_cleartext_sms"

    private const val KEY_STRONGBOX_ENFORCED = "strongbox_enforced"
    private const val KEY_SUICIDE_EXECUTED = "cryptographic_suicide_executed"

    private const val KEY_HARDWARE_MONOTONIC_COUNTER = "hardware_rpmb_monotonic_counter"

    private fun getStorageContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
    }

    // =========================================================================
    // SMS Fallback
    // =========================================================================
    fun isCleartextSmsAllowed(context: Context): Boolean {
        val prefs = getStorageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CLEARTEXT_SMS_ALLOWED, false)
    }

    fun setCleartextSmsAllowed(context: Context, allowed: Boolean) {
        val prefs = getStorageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CLEARTEXT_SMS_ALLOWED, allowed).commit()
    }

    // =========================================================================
    // Hardware Keystore & StrongBox
    // =========================================================================
    fun isStrongBoxEnforced(context: Context): Boolean {
        val prefs = getStorageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_STRONGBOX_ENFORCED, false)
    }

    fun setStrongBoxEnforced(context: Context, enforced: Boolean) {
        val prefs = getStorageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_STRONGBOX_ENFORCED, enforced).commit()
    }

    fun isSuicideExecuted(context: Context): Boolean {
        val prefs = getStorageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SUICIDE_EXECUTED, false)
    }

    fun setSuicideExecuted(context: Context, executed: Boolean) {
        val prefs = getStorageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SUICIDE_EXECUTED, executed).commit()
    }

    // =========================================================================
    // Hardware Monotonic Counter (Anti-Rollback)
    // =========================================================================
    fun getHardwareMonotonicCounter(context: Context): Long {
        val prefs = getStorageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_HARDWARE_MONOTONIC_COUNTER, 1000L)
    }

    fun setHardwareMonotonicCounter(context: Context, counter: Long) {
        val prefs = getStorageContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_HARDWARE_MONOTONIC_COUNTER, counter).commit()
    }
}
