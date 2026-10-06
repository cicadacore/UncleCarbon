package com.hamoon.uncleted.receivers

import com.hamoon.uncleted.data.SecurityEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.hamoon.uncleted.core.DeadmanSentinelLogic
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.util.EventLogger
import com.hamoon.uncleted.util.TripwireManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TripwireReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TripwireReceiver"
        const val ACTION_TRIPWIRE_EXPIRED = "com.hamoon.uncleted.ACTION_TRIPWIRE_EXPIRED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.e(TAG, "Dead-Man alarm signal received: action=${intent.action}")

        // Re-validate the persisted lock-countdown state before acting. A stale
        // alarm — one left over from a countdown the user already cancelled by
        // unlocking, or from a disabled/cleared sentinel — must never wipe. Any
        // rescheduling of an early-fired alarm is handled inside resolveAlarmAction.
        val action = TripwireManager.resolveAlarmAction(context)
        if (action != DeadmanSentinelLogic.AlarmAction.WIPE) {
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uncleted:tripwire_wakelock")
        wakeLock.acquire(60_000L)

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.e(TAG, "!!! DEAD-MAN LOCK TIMEOUT REACHED !!!")
                EventLogger.log(context, SecurityEvent.TRIPWIRE_EXPIRED)

                val strategy = DefenseCoordinator.resolveStrategy(context)
                strategy.executeStandardWipe("DEADMAN_LOCK_TIMEOUT")
                TripwireManager.clearAfterWipe(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed executing Dead-Man timeout protocol", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}
