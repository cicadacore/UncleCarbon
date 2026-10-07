package com.hamoon.unclecarbon.util

import android.content.Context
import android.content.Intent
import java.io.File

/** Compatibility entry point: raw logcat may contain secrets and is never exported. */
object LogcatManager {
    suspend fun dumpLogcat(context: Context): File? = DiagnosticLogCollector.captureDiagnosticDump(context)
    fun createShareIntent(context: Context, logFile: File): Intent =
        DiagnosticLogCollector.createShareIntent(context, logFile)
}
