package com.hamoon.unclecarbon.core

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.hamoon.unclecarbon.R
import com.hamoon.unclecarbon.data.SecurityEvent
import com.hamoon.unclecarbon.data.SecurityPreferences
import com.hamoon.unclecarbon.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-owned work survives a Fragment recreation; no Activity is retained. */
object LockdownManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var instance: LockdownController? = null

    fun controller(context: Context): LockdownController = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            LockdownController(object : LockdownStore {
                override fun isEnabled() = SecurityPreferences.isLockdownEnabled(app)
                override fun activate() = SecurityPreferences.activateLockdown(app)
            }) { DefenseCoordinator.resolveStrategy(app) }.also { instance = it }
        }
    }

    fun activate(context: Context) = submit(context, reportToUser = true) { it.activateLockdownMode() }
    fun setSafeBootBlocked(context: Context, blocked: Boolean) =
        submit(context, reportToUser = true) { it.setSafeBootBlocked(blocked) }
    fun setDeveloperFeaturesBlocked(context: Context, blocked: Boolean) =
        submit(context, reportToUser = true) { it.setDeveloperFeaturesBlocked(blocked) }
    fun refresh(context: Context) = submit(context) { it.refresh() }

    fun enforceIfEnabled(context: Context, reportToUser: Boolean = false): Job {
        val app = context.applicationContext
        return submit(app, reportToUser) { controller ->
            if (SecurityPreferences.isLockdownEnabled(app)) controller.refresh() else controller.state.value
        }
    }

    private fun submit(
        context: Context,
        reportToUser: Boolean = false,
        action: suspend (LockdownController) -> LockdownState
    ): Job {
        val app = context.applicationContext
        return scope.launch {
            try {
                val result = action(controller(app))
                result.error?.let { error ->
                    Log.e("LockdownManager", error)
                    EventLogger.log(app, SecurityEvent.PROTECTION_ENFORCEMENT_FAILED)
                    if (reportToUser) withContext(Dispatchers.Main) {
                        val message = if (result.enabled) R.string.lockdown_attention else R.string.protection_failed
                        Toast.makeText(app, app.getString(message, error), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("LockdownManager", "Unable to read or enforce protection state", e)
            }
        }
    }
}
