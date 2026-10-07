package com.hamoon.unclecarbon.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hamoon.unclecarbon.sim.SimMonitor

/**
 * Receives the (legacy but still broadcast) `android.intent.action.SIM_STATE_CHANGED`
 * intent and hands the raw state hint to [SimMonitor]. All decision logic
 * lives in [SimMonitor] / [com.hamoon.unclecarbon.sim.SimPresenceEvaluator] so
 * this class never performs an immediate wipe from a raw broadcast — all
 * destructive paths go through the delayed-verification job and the
 * centralised wipe-in-flight latch.
 *
 * The receiver is directBootAware (manifest) because the broadcast can arrive
 * before user unlock on cold boot.
 */
class SimChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimChangeReceiver"
        private const val LEGACY_SIM_STATE_EXTRA = "ss"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.SIM_STATE_CHANGED") return
        val stateHint = intent.getStringExtra(LEGACY_SIM_STATE_EXTRA)
        Log.d(TAG, "SIM_STATE_CHANGED hint received: $stateHint")
        SimMonitor.onSimStateChangedHint(context.applicationContext, stateHint)
    }
}
