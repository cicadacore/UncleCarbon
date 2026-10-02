package com.hamoon.uncleted.services

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.hamoon.uncleted.core.DefenseCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class LockTileService : TileService() {
    private val tag = "LockTileService"

    override fun onStartListening() {
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        // Use Device Owner evictMemoryKeysAndLock to drop into BFU where supported,
        // falling back to a direct DPM.lockNow() so the tile still works pre-provisioning.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val strategy = DefenseCoordinator.resolveStrategy(applicationContext)
                strategy.evictMemoryKeysAndLock()
            } catch (e: Exception) {
                Log.e(tag, "Fallback: direct DPM lockNow(): ${e.message}", e)
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                try { dpm?.lockNow() } catch (_: Exception) {}
            }
        }
    }
}
