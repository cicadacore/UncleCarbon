package com.hamoon.uncleted

import android.app.Activity
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.core.LockdownManager
import com.hamoon.uncleted.util.LocaleManager
import com.hamoon.uncleted.util.NativeSecurityBridge

class UncleTedApplication : Application() {

    companion object {
        private const val TAG = "UncleTedApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // Retire the historical implicit five-attempt wipe default on existing installs.
        // Wiping remains available only when an explicit user setting is persisted.
        if (!SecurityPreferences.hasExplicitMaxFailedAttemptsForWipe(this)) {
            runCatching {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                val admin = ComponentName(this, com.hamoon.uncleted.receivers.AdminReceiver::class.java)
                if (dpm?.isAdminActive(admin) == true) dpm.setMaximumFailedPasswordsForWipe(admin, 0)
            }.onFailure { Log.w(TAG, "Unable to clear legacy implicit password-wipe policy") }
        }

        LockdownManager.enforceIfEnabled(this)

        // 1. Enforce Native Hardware MTE Tagging & Anti-Debugging Memory Flags
        try {
            val hardened = NativeSecurityBridge.enforceSecurityBaselines()
            Log.i(TAG, "Native runtime memory defenses armed (Success: $hardened).")
        } catch (e: Exception) {
            Log.e(TAG, "Critical failure arming native runtime memory defenses", e)
        }

        // 1a. Prune preferences for features removed from this GrapheneOS fork.
        try {
            SecurityPreferences.migrateObsoletePreferences(this)
        } catch (e: Exception) {
            Log.w(TAG, "Preference migration encountered an error: ${e.message}")
        }

        // 2. Safe Direct Boot Guard: Do NOT access CE storage in BFU mode
        if (SecurityPreferences.isUserUnlocked(this)) {
            try {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                val languageValue = sharedPreferences.getString("language", "system") ?: "system"
                LocaleManager.setLocale(languageValue)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load language preferences: ${e.message}")
            }
        } else {
            Log.i(TAG, "Device is in BFU state. Deferring CE SharedPreferences access until unlock.")
        }

        // 3. Register Activity Lifecycle Callbacks safely with dynamic unlock validation
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Dynamically evaluate unlocked state on each activity creation to prevent stale BFU closure capture
                if (SecurityPreferences.isUserUnlocked(activity)) {
                    try {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@UncleTedApplication)
                        val languageValue = prefs.getString("language", "system") ?: "system"
                        LocaleManager.setLocale(languageValue)

                        val themeValue = prefs.getString("theme", "system")
                        applyNightModeForActivity(themeValue)
                        if (themeValue == "amoled") {
                            when (activity) {
                                is MainActivity -> {
                                    activity.setTheme(R.style.Theme_UncleTed_Amoled)
                                }
                                is CameraPermissionBrokerActivity -> {
                                    activity.setTheme(R.style.Theme_UncleTed_Amoled_Transparent)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    LockdownManager.enforceIfEnabled(this@UncleTedApplication, reportToUser = true)
                }
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyNightModeForActivity(themeValue: String?) {
        when (themeValue) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark", "amoled" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
