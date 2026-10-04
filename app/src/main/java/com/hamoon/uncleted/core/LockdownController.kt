package com.hamoon.uncleted.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Actual restrictions read back from Android, never inferred from preferences. */
data class ProtectionState(val safeBootBlocked: Boolean, val developerFeaturesBlocked: Boolean)

interface ProtectionEnforcer {
    suspend fun setSafeBootBlocked(blocked: Boolean)
    suspend fun setDeveloperFeaturesBlocked(blocked: Boolean)
    fun readProtectionState(): ProtectionState
}

interface LockdownStore {
    fun isEnabled(): Boolean
    /** One-way, durable commit. Must throw if persistence fails. */
    fun activate()
}

data class LockdownState(
    val enabled: Boolean = false,
    val protections: ProtectionState? = null,
    val busy: Boolean = true,
    val error: String? = null
) {
    val controlsLocked: Boolean get() = enabled || busy
    val active: Boolean get() = enabled && !busy && error == null &&
        protections == ProtectionState(true, true)
}

/** Serializes UI, startup and boot requests; a saved latch always wins over OFF requests. */
class LockdownController(
    private val store: LockdownStore,
    private val resolveEnforcer: suspend () -> ProtectionEnforcer
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LockdownState(enabled = store.isEnabled()))
    val state = mutableState.asStateFlow()

    suspend fun activateLockdownMode() = update { enforcer ->
        enforceBoth(enforcer)
        // Both actions and a fresh read-back must succeed before the one-way commit.
        if (!store.isEnabled()) store.activate()
    }

    suspend fun refresh() = update { enforcer ->
        if (store.isEnabled()) enforceBoth(enforcer)
    }

    suspend fun setSafeBootBlocked(blocked: Boolean) = update { enforcer ->
        if (store.isEnabled()) enforceBoth(enforcer) else enforcer.setSafeBootBlocked(blocked)
    }

    suspend fun setDeveloperFeaturesBlocked(blocked: Boolean) = update { enforcer ->
        if (store.isEnabled()) enforceBoth(enforcer) else enforcer.setDeveloperFeaturesBlocked(blocked)
    }

    private suspend fun enforceBoth(enforcer: ProtectionEnforcer) {
        val failures = mutableListOf<String>()
        // Attempt the second protection even if the first fails. Keep successful
        // restrictions in place; rolling them back would weaken device protection.
        attempt("Safe Boot protection", failures) { enforcer.setSafeBootBlocked(true) }
        attempt("Developer Mode protection", failures) { enforcer.setDeveloperFeaturesBlocked(true) }
        attempt("Protection verification", failures) {
            val actual = enforcer.readProtectionState()
            check(actual.safeBootBlocked) { "Android reports Safe Boot protection is OFF." }
            check(actual.developerFeaturesBlocked) { "Android reports Developer Mode protection is OFF." }
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }

    private suspend fun attempt(label: String, failures: MutableList<String>, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "$label: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun update(action: suspend (ProtectionEnforcer) -> Unit): LockdownState = mutex.withLock {
        mutableState.value = mutableState.value.copy(enabled = store.isEnabled(), busy = true, error = null)
        var enforcer: ProtectionEnforcer? = null
        var error: String? = null
        try {
            enforcer = resolveEnforcer()
            action(enforcer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        val actual = try {
            enforcer?.readProtectionState()
        } catch (e: Exception) {
            if (error == null) error = e.message ?: e.javaClass.simpleName
            null
        }
        LockdownState(store.isEnabled(), actual, busy = false, error = error).also {
            mutableState.value = it
        }
    }
}
