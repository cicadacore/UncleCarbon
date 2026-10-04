package com.hamoon.uncleted.core

interface DefenseStrategy : ProtectionEnforcer {
    val profileName: String
    val isHardwareSecured: Boolean
    val isDeviceOwnerProvisioned: Boolean

    suspend fun executeStandardWipe(reason: String)
    suspend fun setUsbDataPortEnabled(enabled: Boolean)
    suspend fun configureBruteForceThreshold(maxFailedAttempts: Int)
    suspend fun evictMemoryKeysAndLock()
    suspend fun disableBiometrics(disable: Boolean)
}
