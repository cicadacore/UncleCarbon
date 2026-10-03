package com.hamoon.uncleted.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.util.Log
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.crypto.CryptoPreferences
import com.hamoon.uncleted.crypto.OneTimeTokenManager
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.services.PanicActionService
import com.hamoon.uncleted.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsCommandReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val fullBodyBuilder = StringBuilder()
        var senderNum: String? = null

        for (sms in messages) {
            fullBodyBuilder.append(sms.messageBody ?: "")
            if (senderNum == null) {
                senderNum = sms.originatingAddress
            }
        }

        val body = fullBodyBuilder.toString().trim()
        if (body.isEmpty()) return

        // =========================================================================
        // ROUTE 1: SINGLE-USE EMERGENCY RECOVERY TOKEN (OTC)
        // =========================================================================
        if (body.startsWith("!UT:OTC-")) {
            try { abortBroadcast() } catch (_: Exception) {}
            purgeSmsFromDatabase(context, body)

            val isValidToken = OneTimeTokenManager.validateAndBurnToken(context, body)
            if (isValidToken) {
                Log.e(TAG, "AUTHENTICATED ONE-TIME RECOVERY TOKEN VERIFIED: Burning token and triggering standard factory reset.")
                EventLogger.log(context, "AUTHENTICATED: Single-use emergency recovery token executed.")

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val strategy = DefenseCoordinator.resolveStrategy(context)
                        strategy.executeStandardWipe("ONE_TIME_EMERGENCY_TOKEN")
                    } finally {
                        pendingResult.finish()
                    }
                }
            } else {
                Log.w(TAG, "REJECTED: Received invalid or previously burned One-Time Emergency Token.")
                EventLogger.log(context, "SECURITY: Rejected invalid/replayed One-Time Emergency Token.")
            }
            return
        }

        // =========================================================================
        // ROUTE 2: CLEARTEXT SMS FALLBACK (UNCLETED [CMD] [PASSWORD])
        // =========================================================================
        if (!CryptoPreferences.isCleartextSmsAllowed(context)) {
            Log.d(TAG, "Cleartext SMS processing is disabled in security settings.")
            return
        }

        val masterPassword = SecurityPreferences.getSmsMasterPassword(context)
        if (masterPassword.isNullOrEmpty()) {
            return
        }

        val parts = body.split(" ")

        if (parts.isNotEmpty() && parts[0].equals("UNCLETED", ignoreCase = true) && parts.size >= 3) {
            val command = parts[1].uppercase()
            val password = parts[2]

            val emergencyContact = SecurityPreferences.getEmergencyContact(context)?.trim()
            val isSenderAuthorized = isSenderWhitelisted(senderNum, emergencyContact)

            if (!isSenderAuthorized) {
                Log.e(TAG, "REJECTED CLEARTEXT SMS: Sender '$senderNum' is NOT authorized in Emergency Contact.")
                EventLogger.log(context, "SECURITY: Cleartext command rejected from unwhitelisted sender: $senderNum")
                return
            }

            if (SecretComparison.constantTimeEquals(password, masterPassword)) {
                try { abortBroadcast() } catch (_: Exception) {}
                purgeSmsFromDatabase(context, body)

                val args = if (parts.size > 3) parts.subList(3, parts.size) else emptyList()
                handleAuthenticatedCommand(context, command, senderNum, args)
            } else {
                Log.w(TAG, "Invalid SMS master password received from $senderNum.")
                EventLogger.log(context, "SECURITY: SMS command attempt from $senderNum with incorrect password.")
            }
        }
    }

    private fun isSenderWhitelisted(incomingNumber: String?, trustedContact: String?): Boolean {
        if (incomingNumber.isNullOrBlank() || trustedContact.isNullOrBlank()) return false
        if (trustedContact.contains("@")) return false

        val normalizedIncoming = PhoneNumberUtils.stripSeparators(incomingNumber)
        val normalizedTrusted = PhoneNumberUtils.stripSeparators(trustedContact)

        @Suppress("DEPRECATION")
        if (PhoneNumberUtils.compare(normalizedIncoming, normalizedTrusted)) {
            return true
        }

        if (normalizedIncoming.length >= 7 && normalizedTrusted.length >= 7) {
            val suffixIncoming = normalizedIncoming.takeLast(7)
            val suffixTrusted = normalizedTrusted.takeLast(7)
            return suffixIncoming == suffixTrusted
        }

        return false
    }

    private fun purgeSmsFromDatabase(context: Context, bodySnippet: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uri = Uri.parse("content://sms")
                context.contentResolver.delete(uri, "body LIKE ?", arrayOf("%$bodySnippet%"))
            } catch (_: Exception) {}
        }
    }

    private fun handleAuthenticatedCommand(context: Context, command: String, sender: String?, args: List<String>) {
        Log.i(TAG, "Authenticated SMS command '$command' received from whitelisted sender $sender.")
        EventLogger.log(context, "Authenticated cleartext SMS command '$command' received from $sender.")

        when (command) {
            "WIPE" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val strategy = DefenseCoordinator.resolveStrategy(context)
                    strategy.executeStandardWipe("REMOTE_CLEARTEXT_WIPE")
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
            "SPEAK" -> {
                val message = args.joinToString(" ")
                if (message.isNotEmpty()) {
                    PanicActionService.pendingTtsMessage = message
                    PanicActionService.trigger(context, "REMOTE_SPEAK", PanicActionService.Severity.MEDIUM)
                }
            }
            else -> {
                Log.w(TAG, "Unknown or unsupported SMS command '$command' from $sender.")
            }
        }
    }
}
