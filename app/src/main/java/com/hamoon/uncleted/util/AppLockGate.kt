package com.hamoon.uncleted.util

/** Activity-local authorization; never persisted in a Bundle or on disk. */
internal class AppLockGate {
    enum class State { LOCKED, AUTHENTICATING, ALLOWED, DENIED }
    var state = State.LOCKED
        private set

    fun begin(lockEnabled: Boolean, authenticatorAvailable: Boolean): State {
        if (state == State.DENIED || state == State.AUTHENTICATING) return state
        state = when {
            !lockEnabled -> State.ALLOWED
            !authenticatorAvailable -> State.DENIED
            else -> State.AUTHENTICATING
        }
        return state
    }

    fun authenticationResult(result: BiometricAuthManager.AuthResult) {
        // Late callbacks cannot reopen a denied or backgrounded session.
        if (state == State.AUTHENTICATING) {
            state = if (result == BiometricAuthManager.AuthResult.SUCCESS) State.ALLOWED else State.DENIED
        }
    }

    fun error() { state = State.DENIED }

    fun leaveForeground() {
        // A pending system credential prompt may itself pause/stop the activity.
        // It grants nothing until its success callback arrives.
        if (state == State.ALLOWED) state = State.LOCKED
    }
}
