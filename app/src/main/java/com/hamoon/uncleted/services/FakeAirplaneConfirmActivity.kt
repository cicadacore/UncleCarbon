package com.hamoon.uncleted.services

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.databinding.ActivityFakeAirplaneConfirmBinding
import com.hamoon.uncleted.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Decoy Airplane-mode tile confirmation screen.
 *
 * This fork does not provide an app-level PIN challenge or an app-level
 * "immediate silicon wipe" trap action. Supported actions are:
 *   - STANDARD_WIPE: Device Owner standard factory reset
 *   - LOCK:          Lock device via DPM (into BFU on API 24+)
 *   - DURESS:        Silent duress canary / notification only
 */
class FakeAirplaneConfirmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFakeAirplaneConfirmBinding

    companion object {
        private const val TAG = "FakeAirplaneConfirm"

        fun executeTrapProtocol(context: Context) {
            Log.e(TAG, "Fake Airplane tile triggered. Executing configured action.")
            EventLogger.log(context, "TRAP: Decoy Airplane Mode tile action engaged.")

            val action = SecurityPreferences.getFakeAirplaneAction(context)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val strategy = DefenseCoordinator.resolveStrategy(context)

                    when (action) {
                        "STANDARD_WIPE" -> {
                            Log.i(TAG, "Trap: Standard Device Owner factory reset.")
                            strategy.executeStandardWipe("FAKE_AIRPLANE_STANDARD_WIPE")
                        }
                        "LOCK" -> {
                            Log.w(TAG, "Trap: Locking device to BFU via Device Owner.")
                            strategy.evictMemoryKeysAndLock()
                            PanicActionService.trigger(
                                context,
                                "FAKE_AIRPLANE_TILE_ACTIVATED",
                                PanicActionService.Severity.HIGH
                            )
                        }
                        "DURESS" -> {
                            Log.w(TAG, "Trap: Silent duress canary.")
                            PanicActionService.trigger(
                                context,
                                "FAKE_AIRPLANE_TILE_ACTIVATED",
                                PanicActionService.Severity.HIGH
                            )
                        }
                        else -> {
                            strategy.evictMemoryKeysAndLock()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing fake airplane tile trap", e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureWindowFlags()

        try {
            binding = ActivityFakeAirplaneConfirmBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating FakeAirplaneConfirmActivity layout", e)
            finish()
            return
        }

        binding.btnCancel.setOnClickListener {
            Log.i(TAG, "Fake Airplane Mode activation cancelled by user.")
            finish()
        }

        binding.btnConfirm.setOnClickListener {
            executeTrapProtocol(applicationContext)
            finish()
        }
    }

    private fun configureWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }
}
