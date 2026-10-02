package com.hamoon.uncleted.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TripwireReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TripwireReceiver"
        const val ACTION_TRIPWIRE_EXPIRED = "com.hamoon.uncleted.ACTION_TRIPWIRE_EXPIRED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.e(TAG, "Tripwire alarm signal received: action=${intent.action}")

        if (!SecurityPreferences.isTripwireEnabled(context)) {
            Log.d(TAG, "Tripwire received alarm but feature is currently disabled. Discarding.")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uncleted:tripwire_wakelock")
        wakeLock.acquire(60_000L)

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.e(TAG, "!!! AUTONOMOUS OFFLINE DEAD-MAN TRIPWIRE EXPIRED !!!")
                EventLogger.log(context, "CRITICAL: Offline tripwire duration exceeded. Standard factory reset.")

                val strategy = DefenseCoordinator.resolveStrategy(context)
                strategy.executeStandardWipe("AUTONOMOUS_TRIPWIRE_OFFLINE_LIMIT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed executing tripwire expiration protocol", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}
