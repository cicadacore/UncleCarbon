package com.hamoon.uncleted.services

import com.hamoon.uncleted.data.SecurityEvent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Emergency Quick Settings tile masquerading as Android's Airplane Mode toggle.
 *
 * Behavior: a single tap immediately invokes the standard Device Owner factory
 * reset. There is NO confirmation screen and NO opportunity to cancel — this is
 * an emergency button intended for a user under physical duress who needs to
 * destroy the device's user data as fast as possible while appearing to be
 * toggling airplane mode.
 */
@RequiresApi(Build.VERSION_CODES.N)
class FakeAirplaneTileService : TileService() {

    companion object {
        private const val TAG = "FakeAirplaneTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Airplane mode"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        Log.e(TAG, "!!! EMERGENCY: Fake Airplane tile pressed -> standard factory reset (no confirmation) !!!")
        EventLogger.log(applicationContext, SecurityEvent.AIRPLANE_WIPE)

        // Flip the tile to ACTIVE purely so the UI looks like airplane mode is
        // engaging — this is pure visual camouflage; the real action is the
        // factory reset launched below.
        try {
            qsTile?.let {
                it.state = Tile.STATE_ACTIVE
                it.updateTile()
            }
        } catch (_: Exception) {}

        // Fire the standard Device Owner factory reset off the main thread.
        // Any uncaught exception here must NOT block the attempt from running.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val strategy = DefenseCoordinator.resolveStrategy(applicationContext)
                strategy.executeStandardWipe("FAKE_AIRPLANE_TILE_EMERGENCY")
            } catch (e: Exception) {
                Log.e(TAG, "Emergency factory reset invocation failed", e)
            }
        }
    }
}
