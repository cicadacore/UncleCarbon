package com.hamoon.uncleted.receivers

import com.hamoon.uncleted.data.SecurityEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
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
                EventLogger.log(context, SecurityEvent.SMS_TOKEN_ACCEPTED)

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
                EventLogger.log(context, SecurityEvent.SMS_TOKEN_REJECTED)
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
            val isSenderAuthorized = isSenderWhitelisted(context, senderNum, emergencyContact)

            if (!isSenderAuthorized) {
                Log.e(TAG, "SMS command rejected: sender not authorized.")
                EventLogger.log(context, SecurityEvent.SMS_SENDER_REJECTED)
                return
            }

            if (SecretComparison.constantTimeEquals(password, masterPassword)) {
                try { abortBroadcast() } catch (_: Exception) {}
                purgeSmsFromDatabase(context, body)

                val args = if (parts.size > 3) parts.subList(3, parts.size) else emptyList()
                handleAuthenticatedCommand(context, command, args)
            } else {
                Log.w(TAG, "SMS command authentication failed.")
                EventLogger.log(context, SecurityEvent.SMS_AUTH_FAILED)
            }
        }
    }

    /**
     * Exact sender authorization: both numbers are canonicalized to full E.164
     * and must be identical. Anything that cannot be canonicalized (alphanumeric
     * sender IDs, email contacts, local numbers with no known region, ...) is
     * rejected. See [SmsSenderAuthorization].
     */
    private fun isSenderWhitelisted(context: Context, incomingNumber: String?, trustedContact: String?): Boolean {
        return SmsSenderAuthorization.isAuthorized(
            incomingNumber,
            trustedContact,
            resolveHomeRegionIso(context)
        ) { number, region -> PhoneNumberUtils.formatNumberToE164(number, region) }
    }

    /**
     * Region used to interpret numbers written without a '+' country code: the
     * SIM's home country (the context in which the user typed a local number),
     * falling back to the system locale's country. The serving network's
     * country is intentionally not used, as it is wrong while roaming. Returns
     * null when no region is known, so local-format numbers fail closed.
     */
    private fun resolveHomeRegionIso(context: Context): String? {
        val simCountry = try {
            context.getSystemService(TelephonyManager::class.java)?.simCountryIso
        } catch (_: Exception) {
            null
        }
        SmsSenderAuthorization.normalizeRegionIso(simCountry)?.let { return it }

        val localeCountry = try {
            Resources.getSystem().configuration.locales[0]?.country
        } catch (_: Exception) {
            null
        }
        return SmsSenderAuthorization.normalizeRegionIso(localeCountry)
    }

    private fun purgeSmsFromDatabase(context: Context, bodySnippet: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uri = Uri.parse("content://sms")
                context.contentResolver.delete(uri, "body LIKE ?", arrayOf("%$bodySnippet%"))
            } catch (_: Exception) {}
        }
    }

    private fun handleAuthenticatedCommand(context: Context, command: String, args: List<String>) {
        Log.i(TAG, "SMS command authenticated.")
        EventLogger.log(context, SecurityEvent.SMS_AUTHENTICATED)

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
                Log.w(TAG, "Unsupported SMS command rejected.")
            }
        }
    }
}
