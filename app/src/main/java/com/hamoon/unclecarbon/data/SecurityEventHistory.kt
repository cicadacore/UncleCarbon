package com.hamoon.unclecarbon.data

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Best-effort audit storage. Never a prerequisite for a detector or security action. */
internal class SecurityEventHistory(
    private val isUnlocked: () -> Boolean,
    private val credentialPrefs: () -> SharedPreferences,
    private val devicePrefs: () -> SharedPreferences
) {
    companion object {
        const val FILE_NAME = "security_event_history"
        const val LEGACY_KEY = "event_log"
        private const val MAX_ENTRIES = 150
    }

    // All instances share a lock: multiple receivers may log concurrently.
    fun append(event: SecurityEvent) = synchronized(SecurityEventHistory::class.java) {
        discardLegacyDeviceHistory()
        try {
            if (!isUnlocked()) return@synchronized
            val prefs = credentialPrefs()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val entries = prefs.getStringSet(LEGACY_KEY, emptySet()).orEmpty() + "$stamp - ${event.message}"
            prefs.edit().putStringSet(LEGACY_KEY, entries.sortedDescending().take(MAX_ENTRIES).toSet()).apply()
        } catch (_: Exception) {
            // CE unavailable or storage failure: drop the event, never fall back to DE/logcat.
        }
    }

    fun read(): List<String> = synchronized(SecurityEventHistory::class.java) {
        discardLegacyDeviceHistory()
        try {
            if (!isUnlocked()) emptyList()
            else credentialPrefs().getStringSet(LEGACY_KEY, emptySet()).orEmpty().sortedDescending()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear() = synchronized(SecurityEventHistory::class.java) {
        discardLegacyDeviceHistory()
        try {
            if (isUnlocked()) credentialPrefs().edit().remove(LEGACY_KEY).commit()
        } catch (_: Exception) { }
    }

    fun discardLegacyDeviceHistory() {
        try {
            val prefs = devicePrefs()
            // Do not copy potentially identifying legacy audit text into the new store.
            if (prefs.contains(LEGACY_KEY)) prefs.edit().remove(LEGACY_KEY).commit()
        } catch (_: Exception) {
            // Retry on the next read/write/startup, without interrupting BFU actions.
        }
    }
}
