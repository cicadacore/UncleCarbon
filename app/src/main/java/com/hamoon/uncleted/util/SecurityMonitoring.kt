package com.hamoon.uncleted.util

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hamoon.uncleted.MainActivity
import com.hamoon.uncleted.R
import com.hamoon.uncleted.data.*
import java.security.MessageDigest

object SecurityMonitoring {
    private const val CHANNEL = "security_monitor_alerts"
    private const val ALERT_ID = 4100

    fun store(context: Context): SecurityMonitoringStore =
        SecurityMonitoringStore(SecurityPreferences.getCredentialProtectedPrefs(context.applicationContext))

    private fun failureEventStore(context: Context) = SecurityMonitoringStore(SecurityPreferences.getDeviceProtectedPrefs(context.applicationContext))

    fun adminCallbackAvailable(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return SecurityMonitoringRules.passwordCallbacksAvailable(dpm.isAdminActive(com.hamoon.uncleted.receivers.AdminReceiver.getComponentName(context)))
    }

    /** Called only from the registered DeviceAdminReceiver. Counter is Android's current streak if exposed. */
    @Synchronized fun failedAuthentication(context: Context, platformCount: Int?): Boolean {
        val prefs = SecurityPreferences.getInstance(context.applicationContext)
        val count = SecurityMonitoringRules.nextFailureCount(platformCount, prefs.getInt("MONITOR_LOCAL_FAILURE_COUNT", 0))
        val lastPlatform = prefs.getInt("MONITOR_LAST_PLATFORM_FAILURE_COUNT", -1)
        if (!SecurityMonitoringRules.acceptsPlatformFailure(platformCount, lastPlatform)) return false
        val editor = prefs.edit().putInt("MONITOR_LOCAL_FAILURE_COUNT", count)
        if (platformCount != null && platformCount > 0) editor.putInt("MONITOR_LAST_PLATFORM_FAILURE_COUNT", platformCount)
        editor.commit()
        val contextInfo = if (platformCount != null) "Android-reported current failure count: $count." else "Android did not expose a current failure count; callback count for this streak: $count."
        val threshold = SecurityMonitoringSettings.failureThreshold(context)
        val alreadyAlertedAt = prefs.getInt("MONITOR_ALERTED_FAILURE_THRESHOLD", 0)
        val response = SecurityMonitoringSettings.failureResponse(context)
        val thresholdReached = count >= threshold && alreadyAlertedAt < threshold
        val notifyRequested = SecurityMonitoringRules.shouldAlertFailure(count, threshold, alreadyAlertedAt,
            SecurityMonitoringSettings.failureAlerts(context) && response != "NOTIFY")
        val lockdownRequested = thresholdReached && response == "LOCKDOWN"
        if (notifyRequested || lockdownRequested) prefs.edit().putInt("MONITOR_ALERTED_FAILURE_THRESHOLD", threshold).commit()
        val streakStarted = prefs.getLong("MONITOR_STREAK_STARTED", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also {
            prefs.edit().putLong("MONITOR_STREAK_STARTED", it).commit()
        }
        val event = MonitoringAuditEvent(
            "keyguard:$count:$streakStarted",
            "KEYGUARD_FAILED", System.currentTimeMillis(), if (notifyRequested) MonitoringSeverity.HIGH else MonitoringSeverity.MEDIUM,
            "Failed device authentication", explanation = "$contextInfo No credential or biometric data is collected.",
            source = "DeviceAdminReceiver.onPasswordFailed", notificationSent = false, failureCount = count,
            deviceContext = "deviceAdminActive=${adminCallbackAvailable(context)};deviceOwner=${context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true};platformCounterAvailable=${platformCount != null}",
            recommendedAction = "If these attempts were unexpected, review access to the device and consider the configured Lockdown response."
        )
        val eventStore = failureEventStore(context)
        val stored = runCatching { eventStore.append(event) }.getOrDefault(false)
        EventLogger.log(context, SecurityEvent.KEYGUARD_FAILED)
        val notified = stored && notifyRequested && notify(context, "Failed device authentication", "$count failed attempts have been reported by Android.")
        if (stored && notifyRequested) runCatching { eventStore.markNotificationStatus(event.id, notified) }
        if (lockdownRequested) com.hamoon.uncleted.core.LockdownManager.activate(context)
        return true
    }

    @Synchronized fun successfulAuthentication(context: Context) {
        val prefs = SecurityPreferences.getInstance(context.applicationContext)
        val prior = prefs.getInt("MONITOR_LOCAL_FAILURE_COUNT", 0)
        prefs.edit().putInt("MONITOR_LOCAL_FAILURE_COUNT", SecurityMonitoringRules.failureCountAfterSuccess())
            .putInt("MONITOR_LAST_PLATFORM_FAILURE_COUNT", SecurityMonitoringRules.failureCountAfterSuccess())
            .putInt("MONITOR_ALERTED_FAILURE_THRESHOLD", SecurityMonitoringRules.failureCountAfterSuccess())
            .putLong("MONITOR_STREAK_STARTED", System.currentTimeMillis()).commit()
        SecurityPreferences.resetFailedAttempts(context)
        if (prior > 0) failureEventStore(context).append(MonitoringAuditEvent(
            "keyguard-success:${System.currentTimeMillis()}", "KEYGUARD_SUCCEEDED", System.currentTimeMillis(),
            MonitoringSeverity.INFORMATIONAL, "Device authentication succeeded", explanation = "Failure streak reset; historical events were retained.",
            source = "DeviceAdminReceiver.onPasswordSucceeded"
        ))
    }

    fun initializePackageBaseline(context: Context) {
        val entries = installedPackages(context).filterNot { it.packageName == context.packageName }
        store(context).replacePackages(entries)
        SecurityMonitoringSettings.setEnabled(context, true)
        SecurityMonitoringSettings.setLastExecution(context, System.currentTimeMillis())
        context.getSharedPreferences("security_monitor_status", Context.MODE_PRIVATE).edit()
            .remove("last_error_time").remove("last_error_type").commit()
    }

    fun handlePackageEvent(context: Context, action: String, packageName: String, replacing: Boolean) {
        if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) return
        if (!SecurityMonitoringSettings.enabled(context)) return
        val store = store(context)
        val old = store.packageSnapshot()[packageName]
        val trusted = SecurityMonitoringRules.isTrustedPackage(packageName, SecurityMonitoringSettings.trusted(context))
        if (action == android.content.Intent.ACTION_PACKAGE_REMOVED && replacing) return
        if (action == android.content.Intent.ACTION_PACKAGE_REMOVED) {
            val changes = if (SecurityMonitoringSettings.updates(context))
                SecurityMonitoringRules.classifyPackageTransition(old, null, "REMOVED", trusted) else emptyList()
            commitPackageChange(context, store, null, packageName, old, changes, action)
            return
        }
        val current = packageSnapshot(context, packageName) ?: return
        if (packageName == context.packageName) return
        val kind = when {
            action == android.content.Intent.ACTION_PACKAGE_CHANGED -> "CHANGED"
            action == android.content.Intent.ACTION_PACKAGE_REPLACED || replacing -> "UPDATED"
            else -> "ADDED"
        }
        val changes = SecurityMonitoringRules.classifyPackageTransition(old, current, kind, trusted).filter { change ->
            val enabled = when (change.type) {
                "APP_INSTALLED" -> SecurityMonitoringSettings.newApps(context)
                "APP_UPDATED" -> SecurityMonitoringSettings.updates(context)
                "INTERNET_DECLARED" -> SecurityMonitoringSettings.internet(context)
                "SIGNER_CHANGED" -> SecurityMonitoringSettings.signers(context)
                else -> true
            }
            enabled
        }
        commitPackageChange(context, store, current, null, current, changes, action)
    }

    fun capabilitySummary(context: Context): String = buildString {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        append("Device Admin: ").append(if (adminCallbackAvailable(context)) "active; password callbacks available" else "inactive; password callbacks unavailable")
        append("\nDevice Owner: ").append(dpm?.isDeviceOwnerApp(context.packageName) == true)
        val prefs = SecurityPreferences.getInstance(context.applicationContext)
        append("\nCurrent callback failure streak: ").append(prefs.getInt("MONITOR_LOCAL_FAILURE_COUNT", 0))
        append("\nLast recorded failed authentication: ").append(failureEventStore(context).events().firstOrNull { it.type == "KEYGUARD_FAILED" }
            ?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.timestamp)) } ?: "none")
        append("\nPassword threshold: ").append(SecurityMonitoringSettings.failureThreshold(context))
        append("\nApplication monitoring: ").append(if (SecurityMonitoringSettings.enabled(context)) "enabled" else "disabled; establish a baseline in Settings")
        append("\nPackage visibility: QUERY_ALL_PACKAGES is declared for whole-device package monitoring; work profile and platform boundaries may still limit results")
        append("\nNotification permission: ").append(if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "granted" else "not granted")
        append("\nNotifications enabled: ").append(NotificationManagerCompat.from(context).areNotificationsEnabled())
        append("\nLast package monitor execution: ").append(SecurityMonitoringSettings.lastExecution(context).let { if (it == 0L) "not recorded" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) })
        val errorPrefs = context.getSharedPreferences("security_monitor_status", Context.MODE_PRIVATE)
        append("\nLast monitoring error: ").append(errorPrefs.getString("last_error_type", null)?.let {
            val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(errorPrefs.getLong("last_error_time", 0L)))
            "$it at $time"
        } ?: "none recorded")
    }

    private fun installedPackages(context: Context): List<MonitoredPackage> =
        context.packageManager.getInstalledPackages(packageFlags()).mapNotNull { snapshot(context, it) }

    private fun packageSnapshot(context: Context, name: String): MonitoredPackage? = try {
        snapshot(context, context.packageManager.getPackageInfo(name, packageFlags()))
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun packageFlags(): Int = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES

    @Suppress("DEPRECATION")
    private fun snapshot(context: Context, info: PackageInfo): MonitoredPackage? {
        val name = info.packageName
        val pm = context.packageManager
        val requested = info.requestedPermissions.orEmpty()
        val internetIndex = requested.indexOf(Manifest.permission.INTERNET)
        val granted = internetIndex >= 0 &&
            (info.requestedPermissionsFlags?.getOrNull(internetIndex)?.and(PackageInfo.REQUESTED_PERMISSION_GRANTED) ?: 0) != 0
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo else null
        val current = signers?.apkContentsSigners.orEmpty().map(::digest).sorted().joinToString(",")
        val lineage = signers?.signingCertificateHistory.orEmpty().map(::digest).toSet()
        return MonitoredPackage(name, runCatching { pm.getApplicationLabel(info.applicationInfo).toString() }.getOrDefault(name),
            info.versionName.orEmpty(), if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.firstInstallTime, info.lastUpdateTime, internetIndex >= 0, requested.toSet(), granted,
            info.applicationInfo.enabled, current, lineage)
    }

    private fun digest(signature: android.content.pm.Signature): String = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun commitPackageChange(
        context: Context,
        store: SecurityMonitoringStore,
        current: MonitoredPackage?,
        removedPackageName: String?,
        eventPackage: MonitoredPackage?,
        changes: List<PackageChange>,
        source: String
    ) {
        val now = System.currentTimeMillis()
        val candidates = if (eventPackage == null) emptyList() else changes.map { change ->
            val suffix = if (change.type == "APP_STATE_CHANGED") eventPackage.enabled.toString() else ""
            MonitoringAuditEvent("${change.type}:${eventPackage.packageName}:${eventPackage.versionCode}:${eventPackage.lastUpdateTime}:$suffix",
                change.type, now, change.severity, change.title, eventPackage.packageName, eventPackage.label,
                change.explanation, source, false, recommendedAction = recommendationFor(change.type))
        }
        val inserted = if (current != null) store.updatePackageAndEvents(current, candidates)
            else store.removePackageAndEvents(requireNotNull(removedPackageName), candidates)
        SecurityMonitoringSettings.setLastExecution(context, now)
        inserted.forEach { event ->
            val notifyRequested = severityNotificationEnabled(context, event.severity)
            val notified = notifyRequested && notify(context, event.title,
                "${event.appLabel.orEmpty()} (${event.packageName.orEmpty()}): ${event.explanation}", event.id)
            if (notifyRequested) store.markNotificationStatus(event.id, notified)
        }
    }

    private fun recommendationFor(type: String): String = when (type) {
        "APP_INSTALLED" -> "Review the application publisher and whether you intended to install it."
        "APP_UPDATED" -> "Review the publisher and release details if you did not expect this update."
        "APP_REMOVED" -> "Confirm that the removal was expected."
        "INTERNET_DECLARED" -> "Review the application update and publisher; the declaration alone is not a threat verdict."
        "SIGNER_CHANGED" -> "Verify the publisher and signing lineage before trusting this updated application."
        "APP_STATE_CHANGED" -> "Confirm that the application state change was expected."
        else -> "Review the event in the application security history."
    }

    private fun severityNotificationEnabled(context: Context, severity: MonitoringSeverity): Boolean =
        SecurityPreferences.getCredentialProtectedPrefs(context).getBoolean("MONITOR_NOTIFY_${severity.name}", severity != MonitoringSeverity.INFORMATIONAL)

    private fun notify(context: Context, title: String, message: String, notificationKey: String = title): Boolean {
        NotificationHelper.createNotificationChannels(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val intent = android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = android.app.PendingIntent.getActivity(context, title.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_alert_24).setContentTitle(title)
            .setContentText(message.take(220)).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(pending)
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_STATUS).build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(ALERT_ID + (notificationKey.hashCode() and 0x7ff), notification)
            true
        }.getOrDefault(false)
    }
}
