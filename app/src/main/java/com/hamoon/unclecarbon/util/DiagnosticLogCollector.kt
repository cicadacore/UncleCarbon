package com.hamoon.unclecarbon.util

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.hamoon.unclecarbon.data.SecurityPreferences
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogCollector {

    private const val TAG = "DiagnosticLogCollector"

    data class DeviceEnvironment(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val buildFingerprint: String,
        val isDeviceOwner: Boolean,
        val isDeviceAdmin: Boolean
    )

    suspend fun getEnvironmentDiagnostics(context: Context): DeviceEnvironment = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val isDeviceOwner = dpm?.isDeviceOwnerApp(context.packageName) == true
        val isAdmin = PermissionUtils.isDeviceAdminActive(context)

        DeviceEnvironment(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT,
            isDeviceOwner = isDeviceOwner,
            isDeviceAdmin = isAdmin
        )
    }

    suspend fun captureDiagnosticDump(context: Context): File? = withContext(Dispatchers.IO) {
        if (!SecurityPreferences.isUserUnlocked(context)) return@withContext null
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(StorageLayout.diagnosticsDir(context), "unclecarbon_bugreport_$timestamp.txt")

            val env = getEnvironmentDiagnostics(context)

            FileOutputStream(logFile).bufferedWriter().use { writer ->
                writer.write("================================================================\n")
                writer.write("UNCLECARBON DIAGNOSTIC REPORT\n")
                writer.write("Generated: ${Date()}\n")
                writer.write("================================================================\n\n")

                writer.write("--- DEVICE PLATFORM METRICS ---\n")
                writer.write("Brand/Model: ${env.manufacturer} ${env.model}\n")
                writer.write("Android OS: ${env.androidVersion} (API ${env.apiLevel})\n")
                writer.write("Build Fingerprint: ${env.buildFingerprint}\n")
                writer.write("Device Owner Provisioned: ${env.isDeviceOwner}\n")
                writer.write("Device Admin Active: ${env.isDeviceAdmin}\n")
                writer.write("Host Process PID: ${Process.myPid()}\n\n")

                writer.write("--- IN-APP EVENT AUDIT LOGS ---\n")
                val auditLogs = EventLogger.getLogs(context)
                if (auditLogs.isEmpty()) {
                    writer.write("(No post-unlock audit events recorded)\n")
                } else {
                    for (entry in auditLogs) {
                        writer.write("$entry\n")
                    }
                }
                writer.write("\n")

                // A regex cannot reliably remove arbitrary secrets from platform,
                // library, exception or historical BFU logcat messages. Export only
                // fixed audit messages from CE; never collect raw logcat buffers.
                writer.write("Raw logcat is excluded for privacy.\n")

                writer.write("\n=== END OF REPORT ===\n")
            }

            Log.i(TAG, "Diagnostic bug report captured at: ${logFile.absolutePath}")
            return@withContext logFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed capturing diagnostic dump.")
            return@withContext null
        }
    }

    fun createShareIntent(context: Context, logFile: File): Intent {
        check(SecurityPreferences.isUserUnlocked(context)) { "Unlock required to export diagnostics" }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "UncleCarbon Bug Report (${Build.MODEL})")
            putExtra(Intent.EXTRA_TEXT, "Attached is the diagnostic audit report for UncleCarbon.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

}
