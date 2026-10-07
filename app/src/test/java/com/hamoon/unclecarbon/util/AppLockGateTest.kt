package com.hamoon.unclecarbon.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockGateTest {
    @Test fun disabledLockAllowsAccessWithoutAnAuthenticator() {
        assertEquals(AppLockGate.State.ALLOWED, AppLockGate().begin(false, false))
    }

    @Test fun enabledLockWaitsForSuccessfulAuthentication() {
        val gate = AppLockGate()
        assertEquals(AppLockGate.State.AUTHENTICATING, gate.begin(true, true))
        gate.authenticationResult(BiometricAuthManager.AuthResult.SUCCESS)
        assertEquals(AppLockGate.State.ALLOWED, gate.state)
    }

    @Test fun failureCancellationUnavailabilityAndSubsystemErrorsDenyAccess() {
        BiometricAuthManager.AuthResult.values().filter { it != BiometricAuthManager.AuthResult.SUCCESS }.forEach {
            val gate = AppLockGate()
            gate.begin(true, true)
            gate.authenticationResult(it)
            assertEquals(it.name, AppLockGate.State.DENIED, gate.state)
            gate.authenticationResult(BiometricAuthManager.AuthResult.SUCCESS)
            assertEquals("late success after $it", AppLockGate.State.DENIED, gate.state)
        }
    }

    @Test fun unavailableAuthenticatorsDenyWithoutAcceptingSuccessCallbacks() {
        val gate = AppLockGate()
        assertEquals(AppLockGate.State.DENIED, gate.begin(true, false))
        gate.authenticationResult(BiometricAuthManager.AuthResult.SUCCESS)
        assertEquals(AppLockGate.State.DENIED, gate.state)
    }

    @Test fun credentialOnlyAvailabilityStillRequiresAuthentication() {
        // The adapter checks WEAK | DEVICE_CREDENTIAL. Availability may mean
        // credential only (no enrolled biometric or temporarily unavailable hardware).
        val gate = AppLockGate()
        assertEquals(AppLockGate.State.AUTHENTICATING, gate.begin(true, true))
        gate.leaveForeground() // Android 9's system credential activity.
        assertEquals(AppLockGate.State.AUTHENTICATING, gate.state)
        gate.authenticationResult(BiometricAuthManager.AuthResult.SUCCESS)
        assertEquals(AppLockGate.State.ALLOWED, gate.state)
    }

    @Test fun returningFromBackgroundRequiresANewAuthentication() {
        val gate = AppLockGate()
        gate.begin(true, true)
        gate.authenticationResult(BiometricAuthManager.AuthResult.SUCCESS)
        gate.leaveForeground()
        assertEquals(AppLockGate.State.LOCKED, gate.state)
        gate.authenticationResult(BiometricAuthManager.AuthResult.SUCCESS)
        assertEquals(AppLockGate.State.LOCKED, gate.state)
        assertEquals(AppLockGate.State.AUTHENTICATING, gate.begin(true, true))
    }

    @Test fun processRecreationDoesNotRestoreAuthorization() {
        assertEquals(AppLockGate.State.LOCKED, AppLockGate().state)
    }

    @Test fun configurationChangeDuringPromptDoesNotAuthorizeOrStartAnotherPrompt() {
        val retained = AppLockGate()
        retained.begin(true, true)
        retained.leaveForeground()
        assertEquals(AppLockGate.State.AUTHENTICATING, retained.state)
        assertEquals(AppLockGate.State.AUTHENTICATING, retained.begin(false, true))
    }

    @Test fun preferenceReadOrPromptSetupErrorIsTerminal() {
        val gate = AppLockGate()
        gate.error()
        assertEquals(AppLockGate.State.DENIED, gate.begin(false, true))
        gate.authenticationResult(BiometricAuthManager.AuthResult.SUCCESS)
        assertEquals(AppLockGate.State.DENIED, gate.state)
    }
}
