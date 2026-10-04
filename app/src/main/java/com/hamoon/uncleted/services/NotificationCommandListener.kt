package com.hamoon.uncleted.services

import com.hamoon.uncleted.data.SecurityEvent
import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.crypto.CryptoPreferences
import com.hamoon.uncleted.crypto.OneTimeTokenManager
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.util.EventLogger
import com.hamoon.uncleted.util.SecretComparison
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationCommandListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        val combinedMessage = "$title $text $bigText $subText".trim()
        if (combinedMessage.isEmpty()) return

        // 1. Single-Use Emergency Recovery Token (OTC)
        if (combinedMessage.contains("!UT:OTC-")) {
            val token = extractToken(combinedMessage)
            if (token.isNotEmpty()) {
                cancelNotification(sbn.key)
                val isValid = OneTimeTokenManager.validateAndBurnToken(applicationContext, token)
                if (isValid) {
                    Log.e(TAG, "AUTHENTICATED OTC TOKEN RECEIVED VIA NOTIFICATION: Burning and triggering standard factory reset.")
                    EventLogger.log(applicationContext, SecurityEvent.NOTIFICATION_TOKEN_ACCEPTED)
                    CoroutineScope(Dispatchers.IO).launch {
                        val strategy = DefenseCoordinator.resolveStrategy(applicationContext)
                        strategy.executeStandardWipe("NOTIFICATION_OTC_WIPE")
                    }
                } else {
                    Log.w(TAG, "Rejected invalid or burned OTC token received in notification.")
                }
                return
            }
        }

        // 2. Permissive Cleartext Fallback (eSIM Data-Only Burner Fallback)
        if (CryptoPreferences.isCleartextSmsAllowed(applicationContext) && combinedMessage.contains("UNCLETED", ignoreCase = true)) {
            val masterPassword = SecurityPreferences.getSmsMasterPassword(applicationContext)
            if (!masterPassword.isNullOrEmpty()) {
                val parts = combinedMessage.split("\\s+".toRegex())
                val uncleTedIndex = parts.indexOfFirst { it.equals("UNCLETED", ignoreCase = true) }
                if (uncleTedIndex != -1 && parts.size >= uncleTedIndex + 3) {
                    val command = parts[uncleTedIndex + 1].uppercase()
                    val password = parts[uncleTedIndex + 2]

                    if (SecretComparison.constantTimeEquals(password, masterPassword)) {
                        cancelNotification(sbn.key)
                        val args = if (parts.size > uncleTedIndex + 3) parts.subList(uncleTedIndex + 3, parts.size) else emptyList()
                        handleAuthenticatedNotificationCommand(applicationContext, command, args)
                    }
                }
            }
        }
    }

    private fun extractToken(text: String): String {
        val regex = Regex("!UT:OTC-[A-Z0-9-]+")
        return regex.find(text)?.value ?: ""
    }


    private fun handleAuthenticatedNotificationCommand(context: Context, command: String, args: List<String>) {
        EventLogger.log(context, SecurityEvent.NOTIFICATION_AUTHENTICATED)
        when (command) {
            "WIPE" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val strategy = DefenseCoordinator.resolveStrategy(context)
                    strategy.executeStandardWipe("NOTIFICATION_CLEARTEXT_WIPE")
                }
            }
            "EVIDENCE" -> {
                PanicActionService.trigger(context, "REMOTE_EVIDENCE", PanicActionService.Severity.HIGH)
            }
            "SIREN" -> {
                PanicActionService.trigger(context, "REMOTE_SIREN", PanicActionService.Severity.HIGH)
            }
            "LOCK" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        DefenseCoordinator.resolveStrategy(context).evictMemoryKeysAndLock()
                    } catch (_: Exception) {}
                }
            }
            "LOCATE" -> {
                PanicActionService.trigger(context, "MANUAL_LOCATION", PanicActionService.Severity.LOW)
            }
            "AUDIO" -> {
                val duration = args.firstOrNull()?.toIntOrNull() ?: 60
                PanicActionService.pendingAudioDuration = duration
                PanicActionService.trigger(context, "REMOTE_AUDIO_RECORD", PanicActionService.Severity.HIGH)
            }
        }
    }
}
