package com.hamoon.uncleted.services

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Decoy airplane-mode Quick Settings tile. Always presents a confirmation
 * screen before firing; this fork no longer offers a PIN-challenge variant
 * because UncleTed-defined PINs have been removed.
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
        Log.i(TAG, "Fake Airplane Mode tile clicked. Presenting confirmation barrier...")
        val intent = Intent(this, FakeAirplaneConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                7001,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
