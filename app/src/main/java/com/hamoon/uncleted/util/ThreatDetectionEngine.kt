package com.hamoon.uncleted.util

import com.hamoon.uncleted.data.SecurityEvent
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.services.PanicActionService
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.abs

object ThreatDetectionEngine {

    private const val TAG = "ThreatDetectionEngine"
    private const val ANALYSIS_WINDOW_HOURS = 24
    private const val SUSPICIOUS_APP_THRESHOLD = 10 // New apps installed in short time
    private const val NETWORK_ANOMALY_THRESHOLD = 50 // MB of unexpected data transfer

    data class ThreatAssessment(
        val threatLevel: ThreatLevel,
        val threats: List<DetectedThreat>,
        val recommendations: List<String>,
        val confidence: Float
    )

    enum class ThreatLevel {
        MINIMAL, LOW, MEDIUM, HIGH, CRITICAL
    }

    data class DetectedThreat(
        val type: ThreatType,
        val description: String,
        val severity: PanicActionService.Severity,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class ThreatType {
        SUSPICIOUS_APP_ACTIVITY,
        NETWORK_ANOMALY,
        RAPID_SETTING_CHANGES,
        UNAUTHORIZED_ACCESS_ATTEMPTS,
        PRIVILEGE_ESCALATION,
        DATA_EXFILTRATION,
        SOCIAL_ENGINEERING,
        HARDWARE_TAMPERING
    }

    suspend fun performThreatAnalysis(context: Context): ThreatAssessment = withContext(Dispatchers.IO) {
        val threats = mutableListOf<DetectedThreat>()
        var confidence = 0.0f

        // Parallel threat detection
        val analysisJobs = listOf(
            async { analyzeAppInstallations(context) },
            async { analyzeNetworkActivity(context) },
            async { analyzeSystemChanges(context) },
            async { analyzeAccessPatterns(context) },
            async { analyzeDataFlow(context) }
        )

        analysisJobs.awaitAll().forEach { detectedThreats ->
            threats.addAll(detectedThreats)
        }

        // Calculate confidence based on threat correlation
        confidence = calculateThreatConfidence(threats)

        // Determine overall threat level
        val threatLevel = determineThreatLevel(threats)

        // Generate recommendations
        val recommendations = generateRecommendations(threats, threatLevel)

        // Log analysis results
        logThreatAnalysis(context, threatLevel, threats.size, confidence)

        ThreatAssessment(threatLevel, threats, recommendations, confidence)
    }

    private suspend fun analyzeAppInstallations(context: Context): List<DetectedThreat> = withContext(Dispatchers.IO) {
        val threats = mutableListOf<DetectedThreat>()
        val packageManager = context.packageManager

        try {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val recentInstalls = mutableListOf<ApplicationInfo>()
            val currentTime = System.currentTimeMillis()

            // Check for recently installed apps
            for (app in installedApps) {
                try {
                    val packageInfo = packageManager.getPackageInfo(app.packageName, 0)
                    val installTime = packageInfo.firstInstallTime

                    // Apps installed in the last 24 hours
                    if (currentTime - installTime < ANALYSIS_WINDOW_HOURS * 60 * 60 * 1000) {
                        recentInstalls.add(app)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking install time for ${app.packageName}: ${e.message}")
                }
            }

            // Analyze for suspicious patterns
            if (recentInstalls.size > SUSPICIOUS_APP_THRESHOLD) {
                threats.add(DetectedThreat(
                    ThreatType.SUSPICIOUS_APP_ACTIVITY,
                    "Unusual number of app installations detected: ${recentInstalls.size} in 24 hours",
                    PanicActionService.Severity.MEDIUM
                ))
            }

            // Check for known malicious app patterns
            recentInstalls.forEach { app ->
                if (isSuspiciousApp(context, app)) {
                    threats.add(DetectedThreat(
                        ThreatType.SUSPICIOUS_APP_ACTIVITY,
                        "Potentially suspicious sideloaded app detected: ${app.packageName}",
                        PanicActionService.Severity.HIGH
                    ))
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing app installations", e)
        }

        threats
    }

    private suspend fun analyzeNetworkActivity(context: Context): List<DetectedThreat> = withContext(Dispatchers.IO) {
        val threats = mutableListOf<DetectedThreat>()

        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork

            if (activeNetwork != null) {
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

                // Check for VPN usage only if it's not expected by the user.
                if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    // This now checks the setting you configured in the UI.
                    val expectedVpn = SecurityPreferences.isTrustedVpnEnabled(context)
                    if (!expectedVpn) {
                        threats.add(DetectedThreat(
                            ThreatType.NETWORK_ANOMALY,
                            "An unexpected VPN connection is active",
                            PanicActionService.Severity.HIGH
                        ))
                    }
                }

                // Analyze data usage patterns (simplified)
                analyzeDataUsagePatterns(context)?.let { threat ->
                    threats.add(threat)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing network activity", e)
        }

        threats
    }

    private suspend fun analyzeSystemChanges(context: Context): List<DetectedThreat> = withContext(Dispatchers.IO) {
        val threats = mutableListOf<DetectedThreat>()

        // Check for rapid system setting changes
        val lastSystemCheck = SecurityPreferences.getLastSystemCheck(context)
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSystemCheck < 60 * 60 * 1000) { // Within last hour
            val settingChanges = SecurityPreferences.getSystemChangeCount(context)
            if (settingChanges > 10) { // More than 10 changes in an hour
                threats.add(DetectedThreat(
                    ThreatType.RAPID_SETTING_CHANGES,
                    "Rapid system configuration changes detected: $settingChanges changes in 1 hour",
                    PanicActionService.Severity.MEDIUM
                ))
            }
        }

        SecurityPreferences.setLastSystemCheck(context, currentTime)

        threats
    }

    private suspend fun analyzeAccessPatterns(context: Context): List<DetectedThreat> = withContext(Dispatchers.IO) {
        val threats = mutableListOf<DetectedThreat>()

        // Analyze failed authentication attempts
        val failedAttempts = SecurityPreferences.getFailedAttempts(context)
        val failedBiometricAttempts = SecurityPreferences.getFailedBiometricAttempts(context)

        if (failedAttempts > 5 || failedBiometricAttempts > 3) {
            threats.add(DetectedThreat(
                ThreatType.UNAUTHORIZED_ACCESS_ATTEMPTS,
                "Multiple failed authentication attempts detected",
                PanicActionService.Severity.HIGH
            ))
        }

        // This check is disabled because the learning mechanism is not yet implemented,
        // preventing constant false positives.
        /*
        // TODO: Re-enable this check once the BehavioralAnalysisEngine can learn and store usual access hours.
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val usualAccessPatterns = SecurityPreferences.getUsualAccessPattern(context)

        if (usualAccessPatterns.isNotEmpty() && !usualAccessPatterns.contains(currentHour)) {
            threats.add(DetectedThreat(
                ThreatType.UNAUTHORIZED_ACCESS_ATTEMPTS,
                "Device accessed at unusual time: ${currentHour}:00",
                PanicActionService.Severity.LOW
            ))
        }
        */

        threats
    }

    private suspend fun analyzeDataFlow(context: Context): List<DetectedThreat> = withContext(Dispatchers.IO) {
        val threats = mutableListOf<DetectedThreat>()

        if (PermissionUtils.isUsageAccessGranted(context)) {
            try {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val endTime = System.currentTimeMillis()
                val startTime = endTime - (ANALYSIS_WINDOW_HOURS * 60 * 60 * 1000)

                val usageStats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime
                )

                // Analyze for data-intensive apps that might be exfiltrating data
                usageStats.forEach { stat ->
                    if (stat.totalTimeInForeground > 0) {
                        val packageName = stat.packageName

                        // ### FIX: Whitelist the app's own package name ###
                        // The app performs legitimate background tasks (like sending emails with evidence)
                        // which could otherwise be falsely flagged as data exfiltration.
                        if (packageName == context.packageName) {
                            return@forEach // Skips analysis for our own app
                        }

                        // Check if this is a new or suspicious app with high data usage
                        if (isSuspiciousDataUsage(stat)) {
                            threats.add(DetectedThreat(
                                ThreatType.DATA_EXFILTRATION,
                                "An app showed unusually high foreground activity: $packageName",
                                PanicActionService.Severity.MEDIUM
                            ))
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing data flow", e)
            }
        }

        threats
    }

    /**
     * ### SOLUTION PART 1: Smarter Suspicious App Logic ###
     * This function is now more intelligent. It only flags an app if it both:
     * 1. Contains a suspicious keyword in its package name.
     * 2. Is NOT a system app AND was NOT installed from the Google Play Store (i.e., it was sideloaded).
     * This drastically reduces false positives from legitimate applications.
     */
    private fun isSuspiciousApp(context: Context, app: ApplicationInfo): Boolean {
        // Rule 1: Ignore system apps.
        if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
            return false
        }

        // Rule 2: Ignore apps installed from the official Play Store.
        val packageManager = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(app.packageName)
        }
        if (installer == "com.android.vending") {
            return false
        }

        // Rule 3: Check for suspicious keywords in the package name (now applied only to sideloaded/unknown apps).
        val suspiciousPatterns = listOf(
            "spy", "monitor", "track", "hidden", "stealth", "remote", "control",
            "keylog", "screen", "recorder", "parent", "employee", "surveillance"
        )

        val appName = app.packageName.lowercase()
        return suspiciousPatterns.any { pattern -> appName.contains(pattern) }
    }


    private fun analyzeDataUsagePatterns(context: Context): DetectedThreat? {
        // This would integrate with network monitoring APIs in a real implementation
        // For now, we'll simulate based on stored patterns
        val expectedDataUsage = SecurityPreferences.getExpectedDataUsage(context)
        val actualDataUsage = getCurrentDataUsage(context)

        if (expectedDataUsage > 0 && actualDataUsage > expectedDataUsage * 2) {
            return DetectedThreat(
                ThreatType.DATA_EXFILTRATION,
                "Data usage significantly higher than normal: ${actualDataUsage}MB vs expected ${expectedDataUsage}MB",
                PanicActionService.Severity.MEDIUM
            )
        }

        return null
    }

    /**
     * ### SOLUTION PART 2: Fixed and Relaxed Usage Analysis ###
     * The original logic was flawed because `expectedUsage` was always 0, flagging any app
     * used for over 30 minutes.
     * This is now fixed to check for a much more reasonable threshold: any single app
     * running in the foreground for more than 4 hours in a 24-hour window. This is a much
     * better indicator of anomalous activity and won't flag normal daily usage.
     */
    private fun isSuspiciousDataUsage(stat: UsageStats): Boolean {
        val fourHoursInMillis = 4 * 60 * 60 * 1000L
        return stat.totalTimeInForeground > fourHoursInMillis
    }

    private fun getCurrentDataUsage(context: Context): Long {
        // Simplified implementation - would use TrafficStats or NetworkStatsManager in production
        return SecurityPreferences.getCurrentDataUsage(context)
    }

    private fun calculateThreatConfidence(threats: List<DetectedThreat>): Float {
        if (threats.isEmpty()) return 1.0f

        val severityWeights = mapOf(
            PanicActionService.Severity.LOW to 0.2f,
            PanicActionService.Severity.MEDIUM to 0.5f,
            PanicActionService.Severity.HIGH to 0.8f,
            PanicActionService.Severity.CRITICAL to 1.0f
        )

        val totalWeight = threats.sumOf { severityWeights[it.severity]?.toDouble() ?: 0.0 }
        return (totalWeight / threats.size).toFloat().coerceIn(0.0f, 1.0f)
    }

    /**
     * ### SOLUTION PART 3: Relaxed Threat Level Calculation ###
     * The thresholds for escalating the overall threat level have been slightly increased
     * to require more evidence, making the engine less "jumpy".
     */
    private fun determineThreatLevel(threats: List<DetectedThreat>): ThreatLevel {
        val criticalCount = threats.count { it.severity == PanicActionService.Severity.CRITICAL }
        val highCount = threats.count { it.severity == PanicActionService.Severity.HIGH }
        val mediumCount = threats.count { it.severity == PanicActionService.Severity.MEDIUM }

        return when {
            criticalCount > 0 -> ThreatLevel.CRITICAL
            highCount >= 2 || (highCount >= 1 && mediumCount >= 2) -> ThreatLevel.HIGH
            highCount >= 1 || mediumCount >= 2 -> ThreatLevel.MEDIUM
            mediumCount >= 1 || threats.size >= 4 -> ThreatLevel.LOW
            else -> ThreatLevel.MINIMAL
        }
    }

    private fun generateRecommendations(threats: List<DetectedThreat>, threatLevel: ThreatLevel): List<String> {
        val recommendations = mutableListOf<String>()

        when (threatLevel) {
            ThreatLevel.CRITICAL -> {
                recommendations.add("IMMEDIATE ACTION REQUIRED: Consider triggering emergency protocols")
                recommendations.add("Review all recent system changes and app installations")
                recommendations.add("Consider enabling maximum security mode")
            }
            ThreatLevel.HIGH -> {
                recommendations.add("Increase monitoring frequency")
                recommendations.add("Review and verify all recent activities")
                recommendations.add("Consider restricting app installations")
            }
            ThreatLevel.MEDIUM -> {
                recommendations.add("Monitor system more closely")
                recommendations.add("Review security settings")
            }
            ThreatLevel.LOW -> {
                recommendations.add("Continue normal monitoring")
                recommendations.add("Review recent activities")
            }
            ThreatLevel.MINIMAL -> {
                recommendations.add("System appears secure")
            }
        }

        // Add specific recommendations based on threat types
        threats.forEach { threat ->
            when (threat.type) {
                ThreatType.SUSPICIOUS_APP_ACTIVITY -> recommendations.add("Review recently installed applications")
                ThreatType.NETWORK_ANOMALY -> recommendations.add("Check network connections and VPN settings")
                ThreatType.UNAUTHORIZED_ACCESS_ATTEMPTS -> recommendations.add("Consider changing authentication credentials")
                ThreatType.DATA_EXFILTRATION -> recommendations.add("Review app permissions and data access")
                else -> {}
            }
        }

        return recommendations.distinct()
    }

    private fun logThreatAnalysis(context: Context, threatLevel: ThreatLevel, threatCount: Int, confidence: Float) {
        val message = "Threat Analysis: Level=$threatLevel, Threats=$threatCount, Confidence=${(confidence * 100).toInt()}%"
        EventLogger.log(context, SecurityEvent.THREAT_ANALYSIS)
        Log.i(TAG, message)

        // Trigger alerts for high-level threats
        if (threatLevel == ThreatLevel.CRITICAL || threatLevel == ThreatLevel.HIGH) {
            PanicActionService.trigger(context, "THREAT_DETECTED",
                when(threatLevel) {
                    ThreatLevel.CRITICAL -> PanicActionService.Severity.CRITICAL
                    ThreatLevel.HIGH -> PanicActionService.Severity.HIGH
                    else -> PanicActionService.Severity.MEDIUM
                }
            )
        }
    }
}

// Additional SecurityPreferences extensions for threat detection
private fun SecurityPreferences.getLastSystemCheck(context: Context): Long =
    getInstance(context).getLong("LAST_SYSTEM_CHECK", 0L)

private fun SecurityPreferences.setLastSystemCheck(context: Context, timestamp: Long) =
    getInstance(context).edit().putLong("LAST_SYSTEM_CHECK", timestamp).apply()

private fun SecurityPreferences.getSystemChangeCount(context: Context): Int =
    getInstance(context).getInt("SYSTEM_CHANGE_COUNT", 0)

private fun SecurityPreferences.getUsualAccessPattern(context: Context): Set<Int> =
    getInstance(context).getStringSet("USUAL_ACCESS_PATTERN", setOf())?.map { it.toInt() }?.toSet() ?: setOf()

private fun SecurityPreferences.getExpectedDataUsage(context: Context): Long =
    getInstance(context).getLong("EXPECTED_DATA_USAGE", 0L)

private fun SecurityPreferences.getCurrentDataUsage(context: Context): Long =
    getInstance(context).getLong("CURRENT_DATA_USAGE", 0L)

private fun SecurityPreferences.getExpectedAppUsage(context: Context, packageName: String): Long =
    getInstance(context).getLong("EXPECTED_USAGE_$packageName", 0L)

private fun SecurityPreferences.getFailedBiometricAttempts(context: Context): Int =
    getInstance(context).getInt("FAILED_BIOMETRIC_ATTEMPTS", 0)
