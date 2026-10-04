package com.hamoon.uncleted.util

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

object LogcatManager {

    private const val TAG = "LogcatManager"

    /**
     * Dumps relevant application logcat entries, hardware info, and SELinux status into a file.
     * Sanitizes sensitive information (PINs, passwords) before writing.
     */
    suspend fun dumpLogcat(context: Context): File? = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val logFile = File(StorageLayout.diagnosticsDir(context), "uncleted_diagnostic_$timestamp.txt")

        try {
            FileOutputStream(logFile).bufferedWriter().use { writer ->
                // Header: Diagnostics Metadata
                writer.write("====================================================\n")
                writer.write("UNCLE TED DIAGNOSTIC DUMP (v10.0.1)\n")
                writer.write("Date: ${Date()}\n")
                writer.write("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
                writer.write("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                writer.write("Build Fingerprint: ${Build.FINGERPRINT}\n")

                writer.write("Target Environment: GrapheneOS Device Owner (locked bootloader, unrooted)\n")
                writer.write("Process PID: ${Process.myPid()}\n")
                writer.write("====================================================\n\n")

                val logcatCmd = arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "*:V")

                try {
                    val process = ProcessBuilder(*logcatCmd).start()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val sanitized = sanitizeLogLine(line)
                            writer.write(sanitized)
                            writer.write("\n")
                        }
                    }
                    process.waitFor()
                } catch (procEx: Exception) {
                    writer.write("Failed executing logcat process: ${procEx.message}\n")
                }

                // Step 2: Append Uncle Ted In-Memory Security Audit Logs
                writer.write("\n====================================================\n")
                writer.write("SECURITY AUDIT EVENT LOGS (DE STORAGE)\n")
                writer.write("====================================================\n")
                val eventLogs = EventLogger.getLogs(context)
                if (eventLogs.isEmpty()) {
                    writer.write("(No security audit events recorded yet)\n")
                } else {
                    for (entry in eventLogs) {
                        writer.write(entry)
                        writer.write("\n")
                    }
                }
            }
            Log.i(TAG, "Diagnostic logs written to ${logFile.absolutePath}")
            return@withContext logFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed dumping logcat", e)
            return@withContext null
        }
    }

    /**
     * Creates an Android Share Sheet intent so the user can send the log file.
     */
    fun createShareIntent(context: Context, logFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Uncle Ted Diagnostic Log (${Build.MODEL})")
            putExtra(Intent.EXTRA_TEXT, "Attached is the diagnostic logcat dump for Uncle Ted defense suite.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun sanitizeLogLine(line: String): String {
        return line.replace(Regex("(?i)(pin|password|secret|key|salt)\\s*=\\s*['\"]?[^'\"\\s]+['\"]?"), "$1=[REDACTED]")
    }
}
