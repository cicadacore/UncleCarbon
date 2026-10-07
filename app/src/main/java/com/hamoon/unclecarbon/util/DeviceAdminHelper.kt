package com.hamoon.unclecarbon.util

import com.hamoon.unclecarbon.data.SecurityEvent
import android.content.Context
import android.util.Log
import com.hamoon.unclecarbon.core.DefenseCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DeviceAdminHelper {
    private const val TAG = "DeviceAdminHelper"

    /**
     * Executes the standard Device Owner factory-reset path.
     *
     * The GrapheneOS fork never routes through the removed Level 2/3/4 lethal
     * destruction routines or any root/kernel fallback. If Device Owner is not
     * yet provisioned, the strategy no-ops safely and logs the skip.
     */
    fun wipeDeviceImmediately(context: Context, reason: String = "EMERGENCY_WIPE_INVOCATION") {
        Log.w(TAG, "Invoking standard factory reset through DefenseCoordinator: reason=security trigger")
        EventLogger.log(context, SecurityEvent.WIPE_INVOKED)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val strategy = DefenseCoordinator.resolveStrategy(context)
                Log.i(TAG, "Executing standard wipe via active strategy: ${strategy.profileName}")
                strategy.executeStandardWipe(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Standard wipe invocation failed: ${e.message}", e)
            }
        }
    }
}
