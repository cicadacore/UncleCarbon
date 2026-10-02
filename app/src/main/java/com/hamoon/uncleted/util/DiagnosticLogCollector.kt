package com.hamoon.uncleted.util

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
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

    suspend fun captureDiagnosticDump(context: Context, logScope: String = "ALL"): File? = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputDir = File(context.filesDir, "diagnostics")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val logFile = File(outputDir, "uncleted_bugreport_$timestamp.txt")

        try {
            val env = getEnvironmentDiagnostics(context)

            FileOutputStream(logFile).bufferedWriter().use { writer ->
                writer.write("================================================================\n")
                writer.write("UNCLE TED (GrapheneOS Fork) DIAGNOSTIC REPORT\n")
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
                    writer.write("(No in-memory audit logs recorded)\n")
                } else {
                    for (entry in auditLogs) {
                        writer.write("$entry\n")
                    }
                }
                writer.write("\n")

                writer.write("--- LOGCAT BUFFER DUMP (SCOPE: $logScope, UNPRIVILEGED) ---\n")
                val logcatCmd = arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "*:V")

                try {
                    val process = ProcessBuilder(*logcatCmd).start()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val sanitized = sanitizeLine(line)
                            writer.write("$sanitized\n")
                        }
                    }
                    process.waitFor()
                } catch (pe: Exception) {
                    writer.write("Failed capturing logcat process: ${pe.message}\n")
                }

                writer.write("\n=== END OF REPORT ===\n")
            }

            Log.i(TAG, "Diagnostic bug report captured at: ${logFile.absolutePath}")
            return@withContext logFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed capturing diagnostic dump: ${e.message}", e)
            return@withContext null
        }
    }

    fun createShareIntent(context: Context, logFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Uncle Ted Bug Report (${Build.MODEL})")
            putExtra(Intent.EXTRA_TEXT, "Attached is the diagnostic logcat report for Uncle Ted.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun sanitizeLine(line: String): String {
        return line.replace(Regex("(?i)(pin|password|secret|salt)\\s*=\\s*['\"]?[^'\"\\s]+['\"]?"), "$1=[REDACTED]")
    }
}
