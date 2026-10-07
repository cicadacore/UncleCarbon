package com.hamoon.unclecarbon.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.hamoon.unclecarbon.core.strategies.DeviceOwnerStrategy
import com.hamoon.unclecarbon.receivers.AdminReceiver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GrapheneOS Device Owner coordinator. This fork targets a dedicated GrapheneOS
 * install with UncleCarbon provisioned as Device Owner on a locked bootloader
 * (unrooted; no Magisk/KernelSU/APatch/LSPosed). Only the Device Owner strategy
 * is supported. If the app is installed but Device Owner provisioning has not
 * been completed yet, actions requiring privileged APIs will no-op safely until
 * provisioning is completed.
 */
object DefenseCoordinator {

    private const val TAG = "DefenseCoordinator"

    @Volatile
    private var cachedStrategy: DefenseStrategy? = null
    private val mutex = Mutex()

    suspend fun resolveStrategy(context: Context): DefenseStrategy {
        cachedStrategy?.let { return it }

        return mutex.withLock {
            cachedStrategy ?: run {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, AdminReceiver::class.java)

                val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
                val isAdminActive = dpm.isAdminActive(adminComponent)

                if (isDeviceOwner) {
                    Log.i(TAG, "Active Profile: GrapheneOS Device Owner (full capability)")
                } else if (isAdminActive) {
                    Log.w(TAG, "Device Admin active but not Device Owner. Provisioning as Device Owner is required for full functionality.")
                } else {
                    Log.w(TAG, "Device Owner not provisioned. Privileged actions will no-op until provisioning completes.")
                }

                val strategy = DeviceOwnerStrategy(context, dpm, adminComponent)
                cachedStrategy = strategy
                strategy
            }
        }
    }

    fun clearCache() {
        cachedStrategy = null
    }
}
