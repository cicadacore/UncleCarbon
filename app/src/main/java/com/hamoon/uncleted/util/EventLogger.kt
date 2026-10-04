package com.hamoon.uncleted.util

import android.content.Context
import com.hamoon.uncleted.data.SecurityEvent
import com.hamoon.uncleted.data.SecurityPreferences

object EventLogger {

    fun log(context: Context, event: SecurityEvent) {
        SecurityPreferences.logEvent(context, event)
    }

    fun getLogs(context: Context): List<String> {
        return SecurityPreferences.getLogs(context)
    }

    fun clearLogs(context: Context) {
        SecurityPreferences.clearLogs(context)
    }
}