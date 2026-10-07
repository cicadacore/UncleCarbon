package com.hamoon.unclecarbon.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import com.hamoon.unclecarbon.services.PanicActionService
import com.hamoon.unclecarbon.util.GeofenceHelper

/**
 * Receives framework proximity-alert broadcasts for the safe-zone feature.
 *
 * Replaces the previous Play Services geofencing-event handling. The
 * proximity-alert [android.app.PendingIntent] created by [GeofenceHelper] is
 * explicit (targets this receiver) and carries our own action + zone id, so the
 * receiver is declared non-exported and ignores any broadcast that is not our
 * proximity alert. Preserves the original semantics: leaving the safe zone
 * triggers the LOW-severity panic action.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Only act on our own proximity-alert broadcasts; reject anything else.
        if (intent.action != GeofenceHelper.ACTION_PROXIMITY_ALERT) {
            Log.w("GeofenceReceiver", "Ignoring unrelated broadcast.")
            return
        }

        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) {
            Log.w("GeofenceReceiver", "Malformed proximity intent: missing entering extra.")
            return
        }

        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)

        if (!entering) {
            Log.w("GeofenceReceiver", "Device has exited a safe zone.")
            PanicActionService.trigger(
                context,
                "GEOFENCE_EXIT",
                PanicActionService.Severity.LOW
            )
        } else {
            Log.i("GeofenceReceiver", "Device has entered a safe zone.")
        }
    }
}
