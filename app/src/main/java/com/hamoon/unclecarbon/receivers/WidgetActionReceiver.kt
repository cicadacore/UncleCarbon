package com.hamoon.unclecarbon.receivers

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.hamoon.unclecarbon.core.DefenseCoordinator
import com.hamoon.unclecarbon.services.PanicActionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WidgetActionReceiver : BroadcastReceiver() {

    private val tag = "WidgetActionReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(tag, "Received action: $action")

        when (action) {
            "ACTION_LOCATION" -> {
                Toast.makeText(context, "Sending Location Alert...", Toast.LENGTH_SHORT).show()
                PanicActionService.trigger(context, "MANUAL_LOCATION", PanicActionService.Severity.LOW)
            }
            "ACTION_SIREN" -> {
                Toast.makeText(context, "Activating Siren...", Toast.LENGTH_SHORT).show()
                PanicActionService.trigger(context, "MANUAL_SIREN", PanicActionService.Severity.HIGH)
            }
            "ACTION_WIPE" -> {
                Toast.makeText(context, "Initiating standard factory reset...", Toast.LENGTH_LONG).show()
                PanicActionService.trigger(context, "MANUAL_WIPE", PanicActionService.Severity.CRITICAL)
            }
            "ACTION_LOCK" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        DefenseCoordinator.resolveStrategy(context).evictMemoryKeysAndLock()
                    } catch (e: Exception) {
                        Log.e(tag, "Lock failed, falling back to DPM.lockNow(): ${e.message}", e)
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        try { dpm?.lockNow() } catch (_: Exception) {}
                    }
                }
            }
        }
    }
}
