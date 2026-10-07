package com.hamoon.unclecarbon.core

import java.util.concurrent.TimeUnit

/**
 * Pure decision logic for the Dead-Man Wipe Sentinel.
 *
 * The sentinel behaves like a device-inactivity timer: it starts a countdown
 * when the device becomes locked (screen off) and cancels that countdown when
 * the device is successfully unlocked. If the device stays locked past the
 * configured deadline, the standard wipe path runs. There are no manual
 * check-ins and network state never influences the countdown.
 *
 * This object is deliberately framework-free so every state transition is
 * unit-testable without an Android runtime. [TripwireManager] and
 * [com.hamoon.unclecarbon.receivers.TripwireReceiver] supply the persisted state
 * and perform the resulting Android side effects.
 */
object DeadmanSentinelLogic {

    /** Fresh-install default: 7 days expressed in hours. */
    const val DEFAULT_DURATION_HOURS = 168

    /** What the scheduled alarm should do when it fires. */
    enum class AlarmAction {
        /** Stale/obsolete alarm (disabled, already unlocked, or superseded deadline): do nothing. */
        IGNORE,
        /** The deadline has genuinely passed while still locked: execute the standard wipe. */
        WIPE,
        /** The alarm fired early (inexact/idle delivery): re-arm for the remaining time. */
        RESCHEDULE
    }

    /** What boot recovery should do for a countdown that was active before reboot. */
    enum class BootAction {
        /** Nothing to restore (disabled or no active countdown). */
        NONE,
        /** The deadline already elapsed while powered down: execute the standard wipe. */
        WIPE,
        /** The deadline is still in the future: re-arm the alarm for the stored deadline. */
        RESCHEDULE
    }

    /**
     * Absolute wall-clock deadline for a countdown that begins at [nowMs] for the
     * given [durationHours]. Negative/zero durations are treated as expiring
     * immediately at [nowMs].
     */
    fun computeDeadline(nowMs: Long, durationHours: Int): Long =
        nowMs + TimeUnit.HOURS.toMillis(durationHours.coerceAtLeast(0).toLong())

    /**
     * A lock event (screen off) starts a countdown only when the feature is
     * enabled and no countdown is already armed. Returning false for an
     * already-armed state is what makes repeated ACTION_SCREEN_OFF events (and a
     * screen that briefly wakes without an unlock) leave the original deadline
     * untouched.
     */
    fun shouldArmOnLock(enabled: Boolean, alreadyArmed: Boolean): Boolean =
        enabled && !alreadyArmed

    /**
     * Decision for a fired wipe alarm. A wipe happens only when the feature is
     * still enabled, a countdown is still armed, and the current time has reached
     * the persisted deadline. This positively prevents a stale alarm — one left
     * over from a countdown the user already cancelled by unlocking — from
     * wiping the device.
     *
     * @param storedDeadline the deadline currently persisted in device-protected
     *   storage (the single source of truth), or <= 0 if none is armed.
     */
    fun evaluateAlarm(
        enabled: Boolean,
        armed: Boolean,
        storedDeadline: Long,
        nowMs: Long
    ): AlarmAction = when {
        !enabled -> AlarmAction.IGNORE
        !armed -> AlarmAction.IGNORE
        storedDeadline <= 0L -> AlarmAction.IGNORE
        nowMs >= storedDeadline -> AlarmAction.WIPE
        else -> AlarmAction.RESCHEDULE
    }

    /**
     * Decision for boot recovery (read during LOCKED_BOOT_COMPLETED). A reboot
     * must never reset an active countdown: an armed countdown whose deadline has
     * passed wipes, otherwise it is rescheduled for the remaining time. A reboot
     * never *starts* a countdown on its own — that only happens on a lock event —
     * so a disarmed state restores nothing.
     */
    fun evaluateBoot(
        enabled: Boolean,
        armed: Boolean,
        storedDeadline: Long,
        nowMs: Long
    ): BootAction = when {
        !enabled -> BootAction.NONE
        !armed -> BootAction.NONE
        storedDeadline <= 0L -> BootAction.NONE
        nowMs >= storedDeadline -> BootAction.WIPE
        else -> BootAction.RESCHEDULE
    }
}
