package com.hamoon.unclecarbon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the eSIM/eUICC erase bit is included in the wipe flags only when the
 * user has opted in, and that the mandatory base flags are always preserved.
 *
 * Synthetic bit values stand in for the real DevicePolicyManager.WIPE_* constants
 * so the rule is testable without an Android runtime; production passes the real
 * constants into the same [WipeFlagBuilder.build].
 */
class WipeFlagBuilderTest {

    // Distinct, non-overlapping bits mirroring the shape of the real flags.
    private val wipeExternalStorage = 0b0001
    private val wipeSilently = 0b1000
    private val wipeEuicc = 0b0100
    private val base = wipeExternalStorage or wipeSilently

    @Test fun eraseEsimFalse_omitsEuiccBit() {
        val flags = WipeFlagBuilder.build(base, wipeEuicc, eraseEsim = false)
        assertFalse("EUICC bit must be absent when opt-out", flags and wipeEuicc != 0)
    }

    @Test fun eraseEsimTrue_includesEuiccBit() {
        val flags = WipeFlagBuilder.build(base, wipeEuicc, eraseEsim = true)
        assertTrue("EUICC bit must be present when opt-in", flags and wipeEuicc != 0)
    }

    @Test fun baseFlagsAlwaysPreserved_whenOptOut() {
        val flags = WipeFlagBuilder.build(base, wipeEuicc, eraseEsim = false)
        assertEquals("Mandatory flags must be untouched", base, flags)
    }

    @Test fun baseFlagsAlwaysPreserved_whenOptIn() {
        val flags = WipeFlagBuilder.build(base, wipeEuicc, eraseEsim = true)
        assertTrue(flags and wipeExternalStorage != 0)
        assertTrue(flags and wipeSilently != 0)
        assertEquals(base or wipeEuicc, flags)
    }
}
