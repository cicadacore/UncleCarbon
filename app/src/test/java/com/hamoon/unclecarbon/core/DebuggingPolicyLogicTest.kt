package com.hamoon.unclecarbon.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that Developer Interception and the USB data-port lockdown cannot
 * clear each other's DISALLOW_DEBUGGING_FEATURES restriction.
 */
class DebuggingPolicyLogicTest {

    @Test fun bothOff_debuggingAllowed() {
        assertFalse(DebuggingPolicyLogic.shouldBlockDebugging(developerFeaturesBlocked = false, usbDataPortDisabled = false))
    }

    @Test fun developerOnly_blocks() {
        assertTrue(DebuggingPolicyLogic.shouldBlockDebugging(developerFeaturesBlocked = true, usbDataPortDisabled = false))
    }

    @Test fun usbLockdownOnly_blocks() {
        assertTrue(DebuggingPolicyLogic.shouldBlockDebugging(developerFeaturesBlocked = false, usbDataPortDisabled = true))
    }

    @Test fun bothOn_blocks() {
        assertTrue(DebuggingPolicyLogic.shouldBlockDebugging(developerFeaturesBlocked = true, usbDataPortDisabled = true))
    }

    /**
     * Regression for the required USB combination case 5: Developer Interception
     * ON, then USB is disabled and later re-enabled. Re-enabling USB (usb flag
     * back to false) must leave debugging blocked because Developer Interception
     * still requires it.
     */
    @Test fun developerOn_usbDisableThenReEnable_staysBlocked() {
        // Developer ON + USB disabled
        assertTrue(DebuggingPolicyLogic.shouldBlockDebugging(developerFeaturesBlocked = true, usbDataPortDisabled = true))
        // USB re-enabled while Developer still ON
        assertTrue(DebuggingPolicyLogic.shouldBlockDebugging(developerFeaturesBlocked = true, usbDataPortDisabled = false))
    }

    /**
     * Disabling Developer Interception while a USB lockdown is still active must
     * NOT clear the restriction.
     */
    @Test fun developerDisabled_whileUsbStillLockedDown_staysBlocked() {
        assertTrue(DebuggingPolicyLogic.shouldBlockDebugging(developerFeaturesBlocked = false, usbDataPortDisabled = true))
    }
}
