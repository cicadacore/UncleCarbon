package com.hamoon.uncleted.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LockdownControllerTest {
    private class Store : LockdownStore {
        var enabled = false
        var commits = 0
        var failCommit = false
        var beforeCommit: () -> Unit = {}
        override fun isEnabled() = enabled
        override fun activate() {
            beforeCommit()
            check(!failCommit) { "Disk write failed" }
            commits++
            enabled = true
        }
    }

    private class Enforcer(safe: Boolean = false, developer: Boolean = false) : ProtectionEnforcer {
        var actual = ProtectionState(safe, developer)
        val calls = mutableListOf<String>()
        var available = true
        var failSafe = false
        var failDeveloper = false
        var ignoreDeveloper = false
        var beforeDeveloper: suspend () -> Unit = {}
        var mutations = 0

        override suspend fun setSafeBootBlocked(blocked: Boolean) {
            calls += "safe:$blocked"
            check(available) { "Device Owner privileges unavailable" }
            check(!failSafe) { "Safe Boot denied by Android" }
            if (actual.safeBootBlocked != blocked) mutations++
            actual = actual.copy(safeBootBlocked = blocked)
        }

        override suspend fun setDeveloperFeaturesBlocked(blocked: Boolean) {
            calls += "developer:$blocked"
            beforeDeveloper()
            check(available) { "Device Owner privileges unavailable" }
            check(!failDeveloper) { "Developer restriction denied by Android" }
            if (!ignoreDeveloper) {
                if (actual.developerFeaturesBlocked != blocked) mutations++
                actual = actual.copy(developerFeaturesBlocked = blocked)
            }
        }

        override fun readProtectionState(): ProtectionState {
            calls += "verify"
            check(available) { "Device Owner privileges unavailable" }
            return actual
        }
    }

    @Test fun bothOff_activationAppliesBothInOrderBeforeCommitting() = runBlocking {
        val store = Store()
        val enforcer = Enforcer()
        val controller = LockdownController(store) { enforcer }
        store.beforeCommit = {
            assertEquals(listOf("safe:true", "developer:true", "verify"), enforcer.calls)
            assertEquals(ProtectionState(true, true), enforcer.actual)
            assertFalse(controller.state.value.enabled)
            assertTrue(controller.state.value.busy)
        }
        val result = controller.activateLockdownMode()
        assertTrue(result.active)
        assertTrue(result.controlsLocked)
        assertEquals(2, enforcer.mutations)
        assertEquals(1, store.commits)
    }

    @Test fun onlySafeBootOn_activationEnablesDeveloperProtection() = runBlocking {
        val enforcer = Enforcer(safe = true)
        val result = LockdownController(Store()) { enforcer }.activateLockdownMode()
        assertTrue(result.active)
        assertEquals(ProtectionState(true, true), result.protections)
        assertEquals(1, enforcer.mutations)
    }

    @Test fun onlyDeveloperOn_activationEnablesSafeBootProtection() = runBlocking {
        val enforcer = Enforcer(developer = true)
        val result = LockdownController(Store()) { enforcer }.activateLockdownMode()
        assertTrue(result.active)
        assertEquals(ProtectionState(true, true), result.protections)
        assertEquals(1, enforcer.mutations)
    }

    @Test fun bothAlreadyOn_areVerifiedBeforeLatching() = runBlocking {
        val store = Store()
        val enforcer = Enforcer(true, true)
        assertTrue(LockdownController(store) { enforcer }.activateLockdownMode().active)
        assertEquals(0, enforcer.mutations)
        assertEquals(1, store.commits)
    }

    @Test fun developerFailure_preservesSafeBootButDoesNotActivate() = runBlocking {
        val store = Store()
        val enforcer = Enforcer().apply { failDeveloper = true }
        val result = LockdownController(store) { enforcer }.activateLockdownMode()
        assertFalse(store.enabled)
        assertFalse(result.active)
        assertFalse(result.controlsLocked)
        assertEquals(ProtectionState(true, false), result.protections)
        assertTrue(result.error!!.contains("Developer restriction denied by Android"))
        assertEquals(0, store.commits)
    }

    @Test fun safeBootFailure_stillAttemptsDeveloperProtection() = runBlocking {
        val store = Store()
        val enforcer = Enforcer().apply { failSafe = true }
        val result = LockdownController(store) { enforcer }.activateLockdownMode()
        assertEquals(ProtectionState(false, true), result.protections)
        assertFalse(store.enabled)
        assertTrue(enforcer.calls.contains("developer:true"))
        assertTrue(result.error!!.contains("Safe Boot denied by Android"))
    }

    @Test fun silentPlatformNoOp_failsReadBackAndDoesNotActivate() = runBlocking {
        val store = Store()
        val enforcer = Enforcer().apply { ignoreDeveloper = true }
        val result = LockdownController(store) { enforcer }.activateLockdownMode()
        assertFalse(store.enabled)
        assertFalse(result.active)
        assertTrue(result.error!!.contains("Developer Mode protection is OFF"))
    }

    @Test fun noDeviceOwner_reportsRealFailureAndUnknownState() = runBlocking {
        val store = Store()
        val enforcer = Enforcer().apply { available = false }
        val result = LockdownController(store) { enforcer }.activateLockdownMode()
        assertFalse(store.enabled)
        assertNull(result.protections)
        assertTrue(result.error!!.contains("Device Owner privileges unavailable"))
        assertTrue(enforcer.calls.containsAll(listOf("safe:true", "developer:true")))
    }

    @Test fun failedPersistence_isNotSuccessfulActivation() = runBlocking {
        val store = Store().apply { failCommit = true }
        val result = LockdownController(store) { Enforcer() }.activateLockdownMode()
        assertFalse(result.enabled)
        assertFalse(result.active)
        assertEquals(ProtectionState(true, true), result.protections)
        assertEquals("Disk write failed", result.error)
    }

    @Test fun retryAfterPartialFailure_completesActivation() = runBlocking {
        val store = Store()
        val enforcer = Enforcer().apply { failDeveloper = true }
        val controller = LockdownController(store) { enforcer }
        assertFalse(controller.activateLockdownMode().active)
        enforcer.failDeveloper = false
        assertTrue(controller.activateLockdownMode().active)
        assertEquals(1, store.commits)
        assertEquals(2, enforcer.mutations)
    }

    @Test fun staleOffRequests_cannotOverrideLatchAndRepairDrift() = runBlocking {
        val store = Store()
        val enforcer = Enforcer()
        val controller = LockdownController(store) { enforcer }
        controller.activateLockdownMode()
        enforcer.actual = ProtectionState(false, false)
        assertTrue(controller.setSafeBootBlocked(false).active)
        assertTrue(controller.setDeveloperFeaturesBlocked(false).active)
        assertFalse(enforcer.calls.any { it.endsWith(":false") })
        assertEquals(ProtectionState(true, true), enforcer.actual)
    }

    @Test fun newControllerAfterProcessDeath_reusesDurableLatchAndReenforces() = runBlocking {
        val disk = Store()
        val platform = Enforcer()
        LockdownController(disk) { platform }.activateLockdownMode()
        platform.actual = ProtectionState(false, false)
        val restarted = LockdownController(disk) { platform }
        assertTrue(restarted.state.value.enabled)
        assertTrue(restarted.state.value.controlsLocked)
        assertFalse(restarted.state.value.active) // Await a fresh platform verification.
        assertTrue(restarted.refresh().active)
        assertEquals(ProtectionState(true, true), platform.actual)
        assertEquals(1, disk.commits)
    }

    @Test fun bootWithEitherRestrictionMissing_repairsBothPermutations() = runBlocking {
        for (actual in listOf(ProtectionState(true, false), ProtectionState(false, true))) {
            val store = Store().apply { enabled = true }
            val enforcer = Enforcer(actual.safeBootBlocked, actual.developerFeaturesBlocked)
            assertTrue(LockdownController(store) { enforcer }.refresh().active)
            assertEquals(1, enforcer.mutations)
        }
    }

    @Test fun privilegeLossAfterActivation_keepsControlsLockedButNeverClaimsActive() = runBlocking {
        val store = Store()
        val enforcer = Enforcer()
        val controller = LockdownController(store) { enforcer }
        controller.activateLockdownMode()
        enforcer.available = false
        val result = controller.refresh()
        assertTrue(result.enabled)
        assertTrue(result.controlsLocked)
        assertFalse(result.active)
        assertNull(result.protections)
        assertNotNull(result.error)
        enforcer.available = true
        assertTrue(controller.refresh().active)
    }

    @Test fun repeatedActivationAndBootChecks_doNotRepeatMutationsOrCommit() = runBlocking {
        val store = Store()
        val enforcer = Enforcer()
        val controller = LockdownController(store) { enforcer }
        repeat(3) { controller.activateLockdownMode(); controller.refresh() }
        assertEquals(2, enforcer.mutations)
        assertEquals(1, store.commits)
    }

    @Test fun offRequestDuringActivation_waitsAndCannotUndoEitherProtection() = runBlocking {
        val store = Store()
        val enforcer = Enforcer()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        enforcer.beforeDeveloper = { entered.complete(Unit); resume.await() }
        val controller = LockdownController(store) { enforcer }
        val activation = async { controller.activateLockdownMode() }
        entered.await()
        assertFalse(store.enabled)
        assertTrue(controller.state.value.controlsLocked)
        val disable = async { controller.setSafeBootBlocked(false) }
        resume.complete(Unit)
        assertTrue(activation.await().active)
        assertTrue(disable.await().active)
        assertFalse(enforcer.calls.contains("safe:false"))
    }

    @Test fun ordinaryControlsRemainIndependentBeforeLockdown() = runBlocking {
        val store = Store()
        val enforcer = Enforcer()
        val controller = LockdownController(store) { enforcer }
        controller.setSafeBootBlocked(true)
        assertEquals(ProtectionState(true, false), enforcer.actual)
        controller.setDeveloperFeaturesBlocked(true)
        controller.setSafeBootBlocked(false)
        assertEquals(ProtectionState(false, true), enforcer.actual)
        controller.setDeveloperFeaturesBlocked(false)
        assertEquals(ProtectionState(false, false), enforcer.actual)
        assertFalse(store.enabled)
    }

    @Test fun refreshBeforeOptIn_doesNotEnableLockdownOrEitherProtection() = runBlocking {
        val store = Store()
        val enforcer = Enforcer()
        val result = LockdownController(store) { enforcer }.refresh()
        assertFalse(result.enabled)
        assertFalse(result.controlsLocked)
        assertEquals(ProtectionState(false, false), result.protections)
        assertEquals(listOf("verify"), enforcer.calls)
    }
}
