package com.hamoon.uncleted.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verifies the Dead-Man Wipe Sentinel state machine: a device-inactivity timer
 * that arms when the device locks and is cancelled when it is unlocked. These
 * framework-free checks cover the acceptance scenarios that do not require an
 * Android runtime (arming, cancelling, stale-alarm guarding, reboot recovery).
 */
class DeadmanSentinelLogicTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_000_000_000_000L

    // --- Default duration (acceptance: fresh install defaults to 7 Days) ------

    @Test fun defaultDurationIsSevenDays() {
        assertEquals(168, DeadmanSentinelLogic.DEFAULT_DURATION_HOURS)
        assertEquals(7L, TimeUnit.HOURS.toMillis(DeadmanSentinelLogic.DEFAULT_DURATION_HOURS.toLong()) / day)
    }

    @Test fun computeDeadlineAddsDuration() {
        val deadline = DeadmanSentinelLogic.computeDeadline(now, 168)
        assertEquals(now + 7 * day, deadline)
    }

    @Test fun computeDeadlineClampsNegativeDurationToImmediate() {
        assertEquals(now, DeadmanSentinelLogic.computeDeadline(now, -5))
    }

    // --- Arming on lock (acceptance 2, and repeated SCREEN_OFF handling) ------

    @Test fun locksArmWhenEnabledAndNotYetArmed() {
        assertTrue(DeadmanSentinelLogic.shouldArmOnLock(enabled = true, alreadyArmed = false))
    }

    @Test fun repeatedScreenOffDoesNotRearm() {
        // A second ACTION_SCREEN_OFF while already armed must not restart the countdown.
        assertFalse(DeadmanSentinelLogic.shouldArmOnLock(enabled = true, alreadyArmed = true))
    }

    @Test fun disabledFeatureNeverArms() {
        assertFalse(DeadmanSentinelLogic.shouldArmOnLock(enabled = false, alreadyArmed = false))
    }

    // --- Alarm firing guard (acceptance 5, 6, 13, and early-fire reschedule) --

    @Test fun lockedPastDeadlineExecutesWipe() {
        val action = DeadmanSentinelLogic.evaluateAlarm(
            enabled = true, armed = true, storedDeadline = now, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.AlarmAction.WIPE, action)
    }

    @Test fun staleAlarmAfterUnlockDoesNotWipe() {
        // The user unlocked -> state cleared (armed=false). Any late alarm is ignored.
        val action = DeadmanSentinelLogic.evaluateAlarm(
            enabled = true, armed = false, storedDeadline = now - day, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.AlarmAction.IGNORE, action)
    }

    @Test fun alarmIgnoredWhenFeatureDisabled() {
        val action = DeadmanSentinelLogic.evaluateAlarm(
            enabled = false, armed = true, storedDeadline = now - day, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.AlarmAction.IGNORE, action)
    }

    @Test fun earlyAlarmReschedulesInsteadOfWiping() {
        // Idle/inexact delivery can fire before the deadline; re-arm, never wipe early.
        val action = DeadmanSentinelLogic.evaluateAlarm(
            enabled = true, armed = true, storedDeadline = now + day, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.AlarmAction.RESCHEDULE, action)
    }

    @Test fun alarmIgnoredWhenNoDeadlineStored() {
        val action = DeadmanSentinelLogic.evaluateAlarm(
            enabled = true, armed = true, storedDeadline = 0L, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.AlarmAction.IGNORE, action)
    }

    // --- Boot recovery (acceptance 11, 12, 14) --------------------------------

    @Test fun bootRestoresRemainingCountdown() {
        val action = DeadmanSentinelLogic.evaluateBoot(
            enabled = true, armed = true, storedDeadline = now + 2 * day, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.BootAction.RESCHEDULE, action)
    }

    @Test fun bootWipesWhenDeadlineElapsedWhilePoweredDown() {
        // Day 0 locked, deadline Day 7, boots Day 8 -> overdue -> wipe.
        val action = DeadmanSentinelLogic.evaluateBoot(
            enabled = true, armed = true, storedDeadline = now - day, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.BootAction.WIPE, action)
    }

    @Test fun bootDoesNothingWhenNoActiveCountdown() {
        // Not armed before reboot -> a reboot must not start a countdown.
        val action = DeadmanSentinelLogic.evaluateBoot(
            enabled = true, armed = false, storedDeadline = 0L, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.BootAction.NONE, action)
    }

    @Test fun bootDoesNothingWhenDisabled() {
        val action = DeadmanSentinelLogic.evaluateBoot(
            enabled = false, armed = true, storedDeadline = now + day, nowMs = now
        )
        assertEquals(DeadmanSentinelLogic.BootAction.NONE, action)
    }
}
