package com.hamoon.unclecarbon.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hamoon.unclecarbon.util.TripwireManager

/**
 * Screen on/off observer. Registered at runtime by [com.hamoon.unclecarbon.services.MonitoringService]
 * for ACTION_SCREEN_OFF and ACTION_USER_PRESENT (these implicit broadcasts cannot
 * be declared in the manifest on modern Android, so they are delivered only while
 * the foreground monitoring service holds this receiver).
 *
 * This is the primary driver of the Dead-Man Wipe Sentinel's inactivity timer:
 *  - ACTION_SCREEN_OFF  -> the device has locked; start the lock countdown.
 *  - ACTION_USER_PRESENT -> the device was unlocked; cancel the countdown.
 *
 * Successful credential unlocks are additionally caught by
 * [AdminReceiver.onPasswordSucceeded], which fires even if this service-hosted
 * receiver is not currently registered.
 */
class ScreenStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen-off observed; arming Dead-Man countdown if applicable.")
                TripwireManager.onDeviceLocked(context)
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "User-present observed; cancelling Dead-Man countdown.")
                TripwireManager.onDeviceUnlocked(context)
            }
        }
    }
}
