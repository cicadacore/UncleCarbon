package com.hamoon.uncleted.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.hamoon.uncleted.util.SecurityMonitoring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives only platform package lifecycle broadcasts; package names are verified against PackageManager. */
class PackageSecurityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_CHANGED)) return
        val packageName = intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart ?: return
        if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (action != Intent.ACTION_PACKAGE_REMOVED) {
            try { context.packageManager.getPackageInfo(packageName, 0) } catch (_: PackageManager.NameNotFoundException) { return }
        } else if (!replacing) {
            try { context.packageManager.getPackageInfo(packageName, 0); return } catch (_: PackageManager.NameNotFoundException) { }
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { SecurityMonitoring.handlePackageEvent(context.applicationContext, action, packageName, replacing) }
            catch (e: Exception) {
                Log.w("PackageSecurityReceiver", "Could not process package security event (${e.javaClass.simpleName})")
                runCatching { context.getSharedPreferences("security_monitor_status", Context.MODE_PRIVATE)
                    .edit().putLong("last_error_time", System.currentTimeMillis()).putString("last_error_type", e.javaClass.simpleName).apply() }
            } finally { pending.finish() }
        }
    }
}
