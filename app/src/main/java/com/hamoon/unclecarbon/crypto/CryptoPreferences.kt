package com.hamoon.unclecarbon.crypto

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File

object CryptoPreferences {

    private const val PREFS_NAME = "hardened_crypto_store"

    private const val KEY_CLEARTEXT_SMS_ALLOWED = "allow_cleartext_sms"

    private const val KEY_STRONGBOX_ENFORCED = "strongbox_enforced"
    private const val KEY_SUICIDE_EXECUTED = "cryptographic_suicide_executed"


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

    /**
     * The old two-copy counter lived entirely in restorable userdata. It was not
     * RPMB and provided no snapshot rollback boundary. Removal is idempotent and
     * never causes key deletion, wipe, quarantine, or a change to token/shard state.
     */
    fun removeObsoleteRollbackState(context: Context) {
        try {
            val storage = getStorageContext(context)
            val prefs = storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains("hardware_rpmb_monotonic_counter")) {
                prefs.edit().remove("hardware_rpmb_monotonic_counter").commit()
            }
            AtomicFile(File(storage.filesDir, "rpmb_rollback_anchor.bin")).delete()
            // Older Android AtomicFile implementations do not know the newer .new suffix.
            File(storage.filesDir, "rpmb_rollback_anchor.bin.new").delete()
        } catch (_: Exception) {
            // Harmless obsolete data can be retried at the next startup.
        }
    }
}
