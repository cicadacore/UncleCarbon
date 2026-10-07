package com.hamoon.unclecarbon.services

import com.hamoon.unclecarbon.data.SecurityEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.hamoon.unclecarbon.core.DefenseCoordinator
import com.hamoon.unclecarbon.data.SecurityPreferences
import com.hamoon.unclecarbon.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rapid Volume Sequence AccessibilityService.
 *
 * Monitors VOL UP -> VOL DOWN -> VOL UP -> VOL DOWN pressed quickly to trigger
 * the standard Device Owner factory reset. The service requests the hardware
 * key filtering capability both in `res/xml/accessibility_service_config.xml`
 * (via `canRequestFilterKeyEvents="true"` and `accessibilityFlags` that include
 * `flagRequestFilterKeyEvents`) and here, by unioning the required flag into
 * the live `AccessibilityServiceInfo` without overwriting defaults with zeros.
 *
 * Enabling the service is a runtime prerequisite: if the user has not enabled
 * Accessibility access for this app, the UI explains that. This is NOT a
 * GrapheneOS-specific restriction, and the volume sequence must not fall back
 * to any root/kernel path.
 */
class PowerButtonService : AccessibilityService() {

    private val tag = "PowerButtonService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Hardware Wipe Sequence: [VOL_UP, VOL_DOWN, VOL_UP, VOL_DOWN]
    private val wipeSequence = listOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN
    )
    private var sequenceIndex = 0
    private var lastPressTime = 0L
    // Maximum time allowed between consecutive required presses. Widened from
    // 2s to 3s so the sequence can be completed deliberately under stress
    // without needing extremely fast presses, while still staying tight enough
    // that accidental activation remains unlikely.
    private val sequenceTimeoutMs = 3000L

    // Guards against duplicate wipe calls from repeated key events during the
    // tiny window between sequence detection and the Device Owner wipe actually
    // tearing down the service.
    private val wipeTriggered = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(tag, "Accessibility Service connected.")

        // Merge the hardware key filtering flag into the live service info without
        // wiping other flags/eventTypes to zero. The config XML already declares
        // canRequestFilterKeyEvents and the required accessibilityFlags; this is a
        // defensive runtime union in case the system has reset the live info.
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            Log.i(tag, "AccessibilityServiceInfo.flags=${info.flags} (FILTER_KEY_EVENTS requested)")
        } catch (e: Exception) {
            Log.e(tag, "Failed updating AccessibilityServiceInfo flags", e)
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "Uncle Carbon Service: ACTIVE", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!SecurityPreferences.isHardwareWipeEnabled(this)) {
            return super.onKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_UP) {
            return super.onKeyEvent(event)
        }

        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }

        val now = System.currentTimeMillis()
        if (now - lastPressTime > sequenceTimeoutMs) {
            sequenceIndex = 0
        }

        if (keyCode == wipeSequence[sequenceIndex]) {
            sequenceIndex++
            lastPressTime = now

            if (sequenceIndex == wipeSequence.size) {
                sequenceIndex = 0
                if (wipeTriggered.compareAndSet(false, true)) {
                    Log.e(tag, "Rapid volume sequence matched. Initiating standard factory reset.")
                    EventLogger.log(this, SecurityEvent.VOLUME_WIPE)

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "EMERGENCY FACTORY RESET TRIGGERED", Toast.LENGTH_LONG).show()
                    }

                    triggerStandardFactoryReset()
                } else {
                    Log.d(tag, "Standard factory reset already in flight; ignoring repeated match.")
                }
                return true
            }
        } else {
            // If this key is the start of a new attempt, remember it; otherwise reset.
            sequenceIndex = if (keyCode == wipeSequence[0]) 1 else 0
            lastPressTime = now
        }

        return super.onKeyEvent(event)
    }

    /**
     * Routes to the single standard Device Owner factory-reset path. No
     * StrongBox suicide preprocessing, no raw storage destruction, no root
     * commands. Exactly one wipe request per completed sequence.
     */
    private fun triggerStandardFactoryReset() {
        serviceScope.launch {
            try {
                val strategy = DefenseCoordinator.resolveStrategy(applicationContext)
                strategy.executeStandardWipe("RAPID_VOLUME_SEQUENCE")
            } catch (e: Exception) {
                Log.e(tag, "Standard factory reset invocation failed: ${e.message}", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
