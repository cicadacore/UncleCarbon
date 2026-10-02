package com.hamoon.uncleted.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Screen on/off observer. Previously tied to the raw kernel USB trapdoor
 * (/sys/class/udc) and the ZRAM memory-scrub routines, both of which have been
 * removed from this GrapheneOS fork because they require root or privileged
 * kernel access. Device Owner USB signaling is driven by AdminReceiver's
 * password-success/failure callbacks instead.
 */
class ScreenStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_SCREEN_OFF -> Log.d(TAG, "Screen-off event observed.")
            Intent.ACTION_USER_PRESENT -> Log.d(TAG, "User-present event observed.")
        }
    }
}
