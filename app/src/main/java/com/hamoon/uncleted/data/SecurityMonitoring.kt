package com.hamoon.uncleted.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

enum class MonitoringSeverity { INFORMATIONAL, LOW, MEDIUM, HIGH, CRITICAL }

data class MonitoredPackage(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val requestedInternet: Boolean,
    val requestedPermissions: Set<String>,
    val internetGranted: Boolean,
    val enabled: Boolean,
    val currentSigner: String,
    val signerLineage: Set<String>
)

data class MonitoringAuditEvent(
    val id: String,
    val type: String,
    val timestamp: Long,
    val severity: MonitoringSeverity,
    val title: String,
    val packageName: String? = null,
    val appLabel: String? = null,
    val explanation: String,
    val source: String,
    val notificationSent: Boolean = false,
    val failureCount: Int? = null,
    val deviceContext: String? = null,
    val recommendedAction: String? = null
)

/** Deterministic policy rules, kept independent of Android so callback semantics can be tested. */
object SecurityMonitoringRules {
    val validFailureThresholds = setOf(1, 3, 5, 10)

    fun validateFailureThreshold(value: Int): Boolean = value in validFailureThresholds

    fun shouldAlertFailure(count: Int, threshold: Int, alreadyAlertedAt: Int, alertsEnabled: Boolean): Boolean =
        alertsEnabled && validateFailureThreshold(threshold) && count >= threshold && alreadyAlertedAt < threshold

    fun acceptsPlatformFailure(platformCount: Int?, lastPlatformCount: Int): Boolean =
        platformCount == null || platformCount <= 0 || platformCount != lastPlatformCount

    fun nextFailureCount(platformCount: Int?, previousCount: Int): Int =
        platformCount?.takeIf { it > 0 } ?: (previousCount + 1)

    fun failureCountAfterSuccess(): Int = 0
    fun passwordCallbacksAvailable(adminActive: Boolean): Boolean = adminActive
    fun isTrustedPackage(packageName: String, trustedPackages: Set<String>): Boolean = packageName in trustedPackages

    fun newlyRequestsInternet(previous: MonitoredPackage?, current: MonitoredPackage): Boolean =
        previous != null && !previous.requestedInternet && current.requestedInternet

    /** Certificate rotation is expected when the old signer remains in Android's signing history. */
    fun unexpectedSignerChange(previous: MonitoredPackage?, current: MonitoredPackage): Boolean =
        previous != null && previous.currentSigner.isNotBlank() && current.currentSigner.isNotBlank() &&
            previous.currentSigner != current.currentSigner && previous.currentSigner !in current.signerLineage

    fun classifyPackageTransition(previous: MonitoredPackage?, current: MonitoredPackage?, kind: String, trusted: Boolean = false): List<PackageChange> {
        if (trusted) return emptyList()
        if (kind == "REMOVED") return if (previous == null) emptyList() else listOf(PackageChange("APP_REMOVED", MonitoringSeverity.INFORMATIONAL, "Application removed", "Android reported package removal."))
        current ?: return emptyList()
        val changes = mutableListOf<PackageChange>()
        if (kind == "ADDED" && previous == null) changes.add(PackageChange("APP_INSTALLED", MonitoringSeverity.LOW, "Application installed", "A new package was installed. This is a change to review, not a malware verdict."))
        if (kind == "UPDATED" && previous != null && previous != current) changes.add(PackageChange("APP_UPDATED", MonitoringSeverity.INFORMATIONAL, "Application updated", "Android reported a package replacement/update with changed metadata."))
        if (newlyRequestsInternet(previous, current)) changes.add(PackageChange("INTERNET_DECLARED", MonitoringSeverity.MEDIUM,
            "Internet permission newly declared", "The updated manifest now requests android.permission.INTERNET. This is a normal permission and does not require a runtime grant."))
        if (unexpectedSignerChange(previous, current)) changes.add(PackageChange("SIGNER_CHANGED", MonitoringSeverity.HIGH,
            "Application signing identity changed", "The previous signer is absent from the installed APK signing lineage; investigate this update."))
        if (kind == "CHANGED" && previous != null && previous.enabled != current.enabled) changes.add(PackageChange("APP_STATE_CHANGED",
            MonitoringSeverity.MEDIUM, if (current.enabled) "Application enabled" else "Application disabled",
            "Android reported an enabled-state transition. This state change alone does not establish malicious behavior."))
        return changes
    }
}

data class PackageChange(val type: String, val severity: MonitoringSeverity, val title: String, val explanation: String)

/** Small bounded audit and package baseline persisted in the private preferences supplied by the caller. */
class SecurityMonitoringStore(private val prefs: SharedPreferences) {
    companion object {
        private const val EVENTS = "security_monitor_events_v1"
        private const val PACKAGES = "security_monitor_packages_v1"
        private const val MAX_EVENTS = 250
        private val GLOBAL_LOCK = Any()
    }

    fun packageSnapshot(): Map<String, MonitoredPackage> = synchronized(GLOBAL_LOCK) {
        val array = JSONArray(prefs.getString(PACKAGES, "[]"))
        (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).toPackage() }.getOrNull()
        }.associateBy { it.packageName }
    }

    fun replacePackages(packages: Collection<MonitoredPackage>) = synchronized(GLOBAL_LOCK) {
        val array = JSONArray()
        packages.sortedBy { it.packageName }.forEach { array.put(it.toJson()) }
        check(prefs.edit().putString(PACKAGES, array.toString()).commit()) { "Could not persist package baseline" }
    }

    fun updatePackage(pkg: MonitoredPackage) = synchronized(GLOBAL_LOCK) {
        val all = packageSnapshot().toMutableMap()
        all[pkg.packageName] = pkg
        replacePackages(all.values)
    }

    fun updatePackageAndEvents(pkg: MonitoredPackage, events: List<MonitoringAuditEvent>): List<MonitoringAuditEvent> =
        synchronized(GLOBAL_LOCK) { commitPackageAndEvents(pkg, null, events) }

    fun removePackage(packageName: String) = synchronized(GLOBAL_LOCK) {
        replacePackages(packageSnapshot().values.filterNot { it.packageName == packageName })
    }

    fun removePackageAndEvents(packageName: String, events: List<MonitoringAuditEvent>): List<MonitoringAuditEvent> =
        synchronized(GLOBAL_LOCK) { commitPackageAndEvents(null, packageName, events) }

    private fun commitPackageAndEvents(
        pkg: MonitoredPackage?, removedPackageName: String?, auditEvents: List<MonitoringAuditEvent>
    ): List<MonitoringAuditEvent> {
        val packages = packageSnapshot().toMutableMap()
        if (removedPackageName != null) packages.remove(removedPackageName)
        if (pkg != null) packages[pkg.packageName] = pkg
        val packageArray = JSONArray().also { array -> packages.values.sortedBy { it.packageName }.forEach { array.put(it.toJson()) } }
        val oldEvents = JSONArray(prefs.getString(EVENTS, "[]"))
        val existing = (0 until oldEvents.length()).mapNotNull { runCatching { oldEvents.getJSONObject(it) }.getOrNull() }
        val ids = existing.mapTo(mutableSetOf()) { it.optString("id") }
        val added = auditEvents.filter { ids.add(it.id) }
        val nextEvents = JSONArray()
        (existing + added.map { it.toJson() }).takeLast(MAX_EVENTS).forEach(nextEvents::put)
        check(prefs.edit().putString(PACKAGES, packageArray.toString()).putString(EVENTS, nextEvents.toString()).commit()) {
            "Could not transactionally persist package state and audit events"
        }
        return added
    }

    fun append(event: MonitoringAuditEvent): Boolean = synchronized(GLOBAL_LOCK) {
        val old = JSONArray(prefs.getString(EVENTS, "[]"))
        val entries = (0 until old.length()).mapNotNull { runCatching { old.getJSONObject(it) }.getOrNull() }
            .filterNot { it.optString("id") == event.id }
        if (entries.size < old.length() && (0 until old.length()).any { old.optJSONObject(it)?.optString("id") == event.id }) return@synchronized false
        val next = JSONArray()
        (entries + event.toJson()).takeLast(MAX_EVENTS).forEach(next::put)
        check(prefs.edit().putString(EVENTS, next.toString()).commit()) { "Could not persist security event" }
        true
    }

    fun events(): List<MonitoringAuditEvent> = synchronized(GLOBAL_LOCK) {
        val array = JSONArray(prefs.getString(EVENTS, "[]"))
        return (0 until array.length()).mapNotNull { i -> runCatching { array.getJSONObject(i).toEvent() }.getOrNull() }
            .sortedByDescending { it.timestamp }
    }

    fun containsEvent(id: String): Boolean = synchronized(GLOBAL_LOCK) {
        val array = JSONArray(prefs.getString(EVENTS, "[]"))
        return (0 until array.length()).any { array.optJSONObject(it)?.optString("id") == id }
    }

    fun markNotificationStatus(id: String, sent: Boolean) = synchronized(GLOBAL_LOCK) {
        val array = JSONArray(prefs.getString(EVENTS, "[]"))
        val next = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("id") == id) item.put("notified", sent)
            next.put(item)
        }
        check(prefs.edit().putString(EVENTS, next.toString()).commit()) { "Could not persist notification status" }
    }

    private fun MonitoredPackage.toJson() = JSONObject().apply {
        put("package", packageName); put("label", label); put("version", versionName); put("code", versionCode)
        put("first", firstInstallTime); put("updated", lastUpdateTime); put("internet", requestedInternet)
        put("permissions", JSONArray(requestedPermissions.sorted()))
        put("granted", internetGranted); put("enabled", enabled); put("signer", currentSigner)
        put("lineage", JSONArray(signerLineage.sorted()))
    }
    private fun JSONObject.toPackage() = MonitoredPackage(
        getString("package"), optString("label"), optString("version"), optLong("code"), optLong("first"),
        optLong("updated"), optBoolean("internet"), getJSONArray("permissions").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
        optBoolean("granted"), optBoolean("enabled"),
        optString("signer"), getJSONArray("lineage").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    )
    private fun MonitoringAuditEvent.toJson() = JSONObject().apply {
        put("id", id); put("type", type); put("time", timestamp); put("severity", severity.name)
        put("title", title); put("package", packageName); put("label", appLabel); put("explanation", explanation)
        put("source", source); put("notified", notificationSent)
        if (failureCount != null) put("failureCount", failureCount)
        put("deviceContext", deviceContext)
        put("recommendation", recommendedAction)
    }
    private fun JSONObject.toEvent() = MonitoringAuditEvent(
        getString("id"), getString("type"), getLong("time"), MonitoringSeverity.valueOf(getString("severity")),
        getString("title"), optString("package").takeIf(String::isNotEmpty), optString("label").takeIf(String::isNotEmpty),
        getString("explanation"), getString("source"), optBoolean("notified"),
        if (has("failureCount")) optInt("failureCount") else null, optString("deviceContext").takeIf(String::isNotEmpty),
        optString("recommendation").takeIf(String::isNotEmpty)
    )
}

object SecurityMonitoringSettings {
    private fun prefs(context: Context): SharedPreferences = SecurityPreferences.getCredentialProtectedPrefs(context.applicationContext)
    private fun operationalPrefs(context: Context): SharedPreferences = SecurityPreferences.getInstance(context.applicationContext)
    private fun setOperational(context: Context, key: String, value: Any) {
        fun save(target: SharedPreferences) {
            val editor = target.edit()
            when (value) {
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
            }
            check(editor.commit()) { "Could not persist monitoring setting" }
        }
        save(SecurityPreferences.getDeviceProtectedPrefs(context))
        if (SecurityPreferences.isUserUnlocked(context)) save(operationalPrefs(context))
    }
    fun failureThreshold(context: Context): Int = operationalPrefs(context).getInt("MONITOR_FAILURE_THRESHOLD", 3)
        .takeIf(SecurityMonitoringRules::validateFailureThreshold) ?: 3
    fun setFailureThreshold(context: Context, value: Int) {
        require(SecurityMonitoringRules.validateFailureThreshold(value)) { "Unsupported failure threshold" }
        setOperational(context, "MONITOR_FAILURE_THRESHOLD", value)
    }
    fun failureAlerts(context: Context) = operationalPrefs(context).getBoolean("MONITOR_FAILURE_ALERTS", true)
    fun setFailureAlerts(context: Context, value: Boolean) = setOperational(context, "MONITOR_FAILURE_ALERTS", value)
    fun failureResponse(context: Context): String = operationalPrefs(context).getString("MONITOR_FAILURE_RESPONSE", "ALERT")
        ?.takeIf { it in setOf("NOTIFY", "ALERT", "LOCKDOWN") } ?: "ALERT"
    fun setFailureResponse(context: Context, value: String) {
        require(value in setOf("NOTIFY", "ALERT", "LOCKDOWN"))
        setOperational(context, "MONITOR_FAILURE_RESPONSE", value)
    }
    fun newApps(context: Context) = prefs(context).getBoolean("MONITOR_NEW_APPS", true)
    fun setNewApps(context: Context, value: Boolean) = check(prefs(context).edit().putBoolean("MONITOR_NEW_APPS", value).commit())
    fun updates(context: Context) = prefs(context).getBoolean("MONITOR_APP_UPDATES", true)
    fun setUpdates(context: Context, value: Boolean) = check(prefs(context).edit().putBoolean("MONITOR_APP_UPDATES", value).commit())
    fun internet(context: Context) = prefs(context).getBoolean("MONITOR_INTERNET_ESCALATION", true)
    fun setInternet(context: Context, value: Boolean) = check(prefs(context).edit().putBoolean("MONITOR_INTERNET_ESCALATION", value).commit())
    fun signers(context: Context) = prefs(context).getBoolean("MONITOR_SIGNING_CHANGES", true)
    fun setSigners(context: Context, value: Boolean) = check(prefs(context).edit().putBoolean("MONITOR_SIGNING_CHANGES", value).commit())
    fun trusted(context: Context): Set<String> = prefs(context).getStringSet("MONITOR_TRUSTED_PACKAGES", emptySet()).orEmpty()
    fun setTrusted(context: Context, value: Set<String>) {
        require(value.size <= 100 && value.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) })
        check(prefs(context).edit().putStringSet("MONITOR_TRUSTED_PACKAGES", value.toSet()).commit())
    }
    fun enabled(context: Context) = prefs(context).getBoolean("MONITOR_SECURITY_ENABLED", false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("MONITOR_SECURITY_ENABLED", value).commit()
    fun lastExecution(context: Context) = prefs(context).getLong("MONITOR_LAST_EXECUTION", 0L)
    fun setLastExecution(context: Context, value: Long) = check(prefs(context).edit().putLong("MONITOR_LAST_EXECUTION", value).commit())
}
