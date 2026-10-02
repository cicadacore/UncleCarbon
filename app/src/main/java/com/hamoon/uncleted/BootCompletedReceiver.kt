package com.hamoon.uncleted.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.services.MonitoringService
import com.hamoon.uncleted.services.ZoneWipeService
import com.hamoon.uncleted.util.TripwireManager
import com.hamoon.uncleted.util.WatchdogManager
import java.util.concurrent.atomic.AtomicBoolean

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private val isBfuInitialized = AtomicBoolean(false)
        private val isCeInitialized = AtomicBoolean(false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isPrimaryUser = (Process.myUid() / 100000) == 0
        if (!isPrimaryUser) {
            Log.d(TAG, "Running under secondary user space (UID: ${Process.myUid()}). Skipping core security daemons.")
            return
        }

        val isUnlocked = SecurityPreferences.isUserUnlocked(context)
        Log.d(TAG, "Device boot event received: $action (User unlocked: $isUnlocked)")

        // Prune stale preferences from the removed feature set once per boot.
        try {
            SecurityPreferences.migrateObsoletePreferences(context)
        } catch (e: Exception) {
            Log.w(TAG, "Preference migration failed on boot: ${e.message}")
        }

        // 1. Direct Boot / BFU Phase (once per boot cycle)
        if (!isBfuInitialized.getAndSet(true)) {
            TripwireManager.scheduleFromLastCheckIn(context)

            if (SecurityPreferences.isGeofenceSuicideEnabled(context)) {
                val zoneIntent = Intent(context, ZoneWipeService::class.java)
                try {
                    ContextCompat.startForegroundService(context, zoneIntent)
                    Log.i(TAG, "Started ZoneWipeService on boot.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start ZoneWipeService on boot", e)
                }
            }
        }

        // 2. Credential-Encrypted (CE) Phase (once when unlocked)
        if (isUnlocked && !isCeInitialized.getAndSet(true)) {
            if (SecurityPreferences.isProtectionEnabled(context)) {
                val serviceIntent = Intent(context, MonitoringService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.i(TAG, "Started MonitoringService on post-unlock boot.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed starting MonitoringService on post-unlock boot", e)
                }
            }

            if (SecurityPreferences.isWatchdogModeEnabled(context)) {
                WatchdogManager.scheduleOrCancelWatchdog(context)
                Log.i(TAG, "Rescheduled WatchdogWorker on post-unlock boot.")
            }
        } else if (!isUnlocked) {
            Log.i(TAG, "Device remains Before First Unlock (BFU). Skipping CE-dependent tasks.")
        }
    }
}
