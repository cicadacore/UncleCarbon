package com.hamoon.unclecarbon.util

import com.hamoon.unclecarbon.data.SecurityEvent
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.hamoon.unclecarbon.core.DeadmanSentinelLogic
import com.hamoon.unclecarbon.data.SecurityPreferences
import com.hamoon.unclecarbon.receivers.TripwireReceiver

/**
 * Dead-Man Wipe Sentinel scheduler.
 *
 * The sentinel is a device-inactivity timer: the countdown starts when the
 * device locks (screen off) and is cancelled when the device is successfully
 * unlocked. It is NOT a manual check-in timer and network state never resets it.
 *
 *   Device unlocked ──(screen off)──▶ countdown armed ──(unlock)──▶ cancelled
 *                                          │
 *                                          └─(deadline reached while locked)─▶ standard wipe
 *
 * Android wiring:
 *  - [com.hamoon.unclecarbon.receivers.ScreenStateReceiver] delivers
 *    ACTION_SCREEN_OFF -> [onDeviceLocked] and ACTION_USER_PRESENT -> [onDeviceUnlocked].
 *  - [com.hamoon.unclecarbon.receivers.AdminReceiver.onPasswordSucceeded] also calls
 *    [onDeviceUnlocked] as a reliable credential-unlock signal that fires even if
 *    the monitoring service (which hosts the screen receiver) was killed.
 *  - [TripwireReceiver] fires the scheduled AlarmManager deadline and calls
 *    [onAlarmFired], which re-validates the persisted state before wiping.
 *  - [com.hamoon.unclecarbon.receivers.BootCompletedReceiver] calls [restoreAfterBoot]
 *    on LOCKED_BOOT_COMPLETED so an in-flight countdown survives a reboot.
 *
 * All countdown state lives in Device-Protected storage via [SecurityPreferences]
 * so it is readable in the Direct Boot window and survives process death.
 */
object TripwireManager {

    private const val TAG = "TripwireManager"
    private const val ALARM_REQUEST_CODE = 8801

    /**
     * The device has locked (screen turned off). Start a fresh countdown only if
     * the sentinel is enabled and no countdown is already armed. Being a no-op
     * when already armed is what makes repeated ACTION_SCREEN_OFF events — or a
     * screen that briefly wakes without an unlock — leave the original deadline
     * untouched.
     */
    fun onDeviceLocked(context: Context) {
        val enabled = SecurityPreferences.isTripwireEnabled(context)
        val armed = SecurityPreferences.isDeadmanArmed(context)
        if (!DeadmanSentinelLogic.shouldArmOnLock(enabled, armed)) {
            Log.d(TAG, "Screen-off ignored (enabled=$enabled, alreadyArmed=$armed).")
            return
        }

        val now = System.currentTimeMillis()
        val durationHours = SecurityPreferences.getTripwireDuration(context)
        val deadline = DeadmanSentinelLogic.computeDeadline(now, durationHours)

        SecurityPreferences.setDeadmanArmed(context, armed = true, lockStart = now, deadline = deadline)
        setHardwareAlarm(context, deadline)
        Log.i(TAG, "Dead-Man countdown armed on lock for $durationHours h (deadline=$deadline).")
        EventLogger.log(context, SecurityEvent.TRIPWIRE_ARMED)
    }

    /**
     * The device was successfully unlocked. Cancel any armed countdown and clear
     * its persisted deadline so no wipe can result from it. Idempotent: repeated
     * unlock signals after disarming are harmless.
     */
    fun onDeviceUnlocked(context: Context) {
        if (!SecurityPreferences.isDeadmanArmed(context)) {
            return
        }
        cancelHardwareAlarm(context)
        SecurityPreferences.clearDeadmanState(context)
        Log.i(TAG, "Dead-Man countdown cancelled on successful unlock.")
        EventLogger.log(context, SecurityEvent.TRIPWIRE_DISARMED)
    }

    /**
     * Reconcile scheduling when the feature is toggled or its duration changes.
     *
     * Disabling cancels any outstanding alarm and clears the countdown state so
     * nothing can fire. Enabling only clears stale state and any orphaned alarm —
     * it does NOT start a countdown; the countdown begins the next time the device
     * locks. This guarantees re-enabling never resurrects an old disabled-state
     * deadline.
     */
    fun onFeatureReconfigured(context: Context) {
        cancelHardwareAlarm(context)
        SecurityPreferences.clearDeadmanState(context)
        if (SecurityPreferences.isTripwireEnabled(context)) {
            Log.i(TAG, "Dead-Man sentinel enabled; countdown will begin on next device lock.")
        } else {
            Log.i(TAG, "Dead-Man sentinel disabled; countdown cleared.")
            EventLogger.log(context, SecurityEvent.TRIPWIRE_DISARMED)
        }
    }

    /**
     * Called by [TripwireReceiver] when the scheduled deadline alarm fires.
     * Re-validates the persisted state so a stale alarm (e.g. one delivered after
     * the user already unlocked) can never wipe the device. Returns true only
     * when a wipe is to be executed by the caller.
     */
    fun resolveAlarmAction(context: Context): DeadmanSentinelLogic.AlarmAction {
        val action = DeadmanSentinelLogic.evaluateAlarm(
            enabled = SecurityPreferences.isTripwireEnabled(context),
            armed = SecurityPreferences.isDeadmanArmed(context),
            storedDeadline = SecurityPreferences.getDeadmanDeadline(context),
            nowMs = System.currentTimeMillis()
        )
        when (action) {
            DeadmanSentinelLogic.AlarmAction.IGNORE ->
                Log.i(TAG, "Dead-Man alarm fired but state is stale/cleared. No wipe.")
            DeadmanSentinelLogic.AlarmAction.RESCHEDULE -> {
                val deadline = SecurityPreferences.getDeadmanDeadline(context)
                Log.w(TAG, "Dead-Man alarm fired early; re-arming for stored deadline=$deadline.")
                setHardwareAlarm(context, deadline)
            }
            DeadmanSentinelLogic.AlarmAction.WIPE ->
                Log.e(TAG, "Dead-Man deadline reached while locked. Wipe will be executed.")
        }
        return action
    }

    /** Clears countdown state after a wipe has been dispatched. */
    fun clearAfterWipe(context: Context) {
        SecurityPreferences.clearDeadmanState(context)
    }

    /**
     * Boot recovery, invoked during LOCKED_BOOT_COMPLETED. An active countdown
     * must survive a reboot: if its deadline already elapsed while powered down
     * the standard wipe runs, otherwise the alarm is re-armed for the remaining
     * time. A reboot never resets or starts a countdown on its own.
     */
    fun restoreAfterBoot(context: Context): DeadmanSentinelLogic.BootAction {
        val action = DeadmanSentinelLogic.evaluateBoot(
            enabled = SecurityPreferences.isTripwireEnabled(context),
            armed = SecurityPreferences.isDeadmanArmed(context),
            storedDeadline = SecurityPreferences.getDeadmanDeadline(context),
            nowMs = System.currentTimeMillis()
        )
        when (action) {
            DeadmanSentinelLogic.BootAction.NONE ->
                Log.d(TAG, "No active Dead-Man countdown to restore after boot.")
            DeadmanSentinelLogic.BootAction.RESCHEDULE -> {
                val deadline = SecurityPreferences.getDeadmanDeadline(context)
                Log.i(TAG, "Restoring Dead-Man countdown after boot; deadline=$deadline.")
                setHardwareAlarm(context, deadline)
            }
            DeadmanSentinelLogic.BootAction.WIPE ->
                Log.e(TAG, "Dead-Man deadline elapsed while powered down. BFU wipe will be executed.")
        }
        return action
    }

    /** Remaining time on the armed countdown, or -1 if the sentinel is not counting down. */
    fun getRemainingTimeMillis(context: Context): Long {
        if (!SecurityPreferences.isTripwireEnabled(context)) return -1L
        if (!SecurityPreferences.isDeadmanArmed(context)) return -1L
        val deadline = SecurityPreferences.getDeadmanDeadline(context)
        if (deadline <= 0L) return -1L
        return deadline - System.currentTimeMillis()
    }

    private fun cancelHardwareAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(getAlarmPendingIntent(context))
        Log.i(TAG, "Dead-Man hardware alarm canceled.")
    }

    private fun setHardwareAlarm(context: Context, triggerAtEpoch: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = getAlarmPendingIntent(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpoch, pendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpoch, pendingIntent)
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpoch, pendingIntent)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm permission missing; scheduling via setAndAllowWhileIdle fallback", e)
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpoch, pendingIntent)
            }
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtEpoch, pendingIntent)
        }
    }

    private fun getAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TripwireReceiver::class.java).apply {
            action = TripwireReceiver.ACTION_TRIPWIRE_EXPIRED
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }
}
