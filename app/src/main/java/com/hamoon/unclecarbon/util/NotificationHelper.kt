package com.hamoon.unclecarbon.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hamoon.unclecarbon.MainActivity
import com.hamoon.unclecarbon.R
import com.hamoon.unclecarbon.receivers.WidgetActionReceiver

object NotificationHelper {

    private const val TAG = "NotificationHelper"

    // Channel Identifiers
    const val CHANNEL_MONITORING = "UncleCarbonMonitoringChannel"
    const val CHANNEL_PANIC = "UncleCarbonPanicServiceChannel"
    const val CHANNEL_BROKER = "UncleCarbonEmergencyBrokerChannel"
    const val CHANNEL_SELFIE = "UncleCarbonSelfieSavedChannel"

    // Notification Identifiers
    const val NOTIFICATION_ID_MONITORING = 2001
    const val NOTIFICATION_ID_PANIC = 1001
    const val NOTIFICATION_ID_SELFIE = 3001
    const val NOTIFICATION_ID_BROKER = 9002

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Persistent Monitoring Channel (Low/Silent)
            val monitoringChannel = NotificationChannel(
                CHANNEL_MONITORING,
                "Uncle Carbon Defense Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays live defense suite status, active sentinels, and quick security controls."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // 2. High-Priority Emergency Panic Channel
            val panicChannel = NotificationChannel(
                CHANNEL_PANIC,
                "Uncle Carbon Emergency Protocol",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Active when emergency countermeasures, evidence gathering, or wipes are executing."
                setShowBadge(true)
                enableVibration(true)
                setBypassDnd(true)
            }

            // 3. Full-Screen Broker Channel (CameraX BAL bypass)
            val brokerChannel = NotificationChannel(
                CHANNEL_BROKER,
                "Uncle Carbon Security Broker",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Dispatches emergency activities under background restrictions."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // 4. Evidence & Intruder Capture Channel
            val selfieChannel = NotificationChannel(
                CHANNEL_SELFIE,
                "Uncle Carbon Evidence Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for captured intruder selfies and local surveillance snapshots."
                setShowBadge(true)
            }

            manager.createNotificationChannel(monitoringChannel)
            manager.createNotificationChannel(panicChannel)
            manager.createNotificationChannel(brokerChannel)
            manager.createNotificationChannel(selfieChannel)

            val securityAlerts = NotificationChannel(
                "security_monitor_alerts",
                "Security Monitoring Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for configured failed authentication thresholds and application security changes."
                setShowBadge(true)
            }
            manager.createNotificationChannel(securityAlerts)
        }
    }

    /**
     * Builds the sleek, Material 3 persistent status center notification for MonitoringService.
     */
    fun createMonitoringNotification(
        context: Context,
        profileName: String = "Device Owner",
        sentinelsSummary: String = "Baseline Active"
    ): Notification {
        createNotificationChannels(context)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Quick Control Actions (Safe, non-destructive)
        val lockIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = "ACTION_LOCK"
        }
        val lockPendingIntent = PendingIntent.getBroadcast(
            context,
            101,
            lockIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sirenIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = "ACTION_SIREN"
        }
        val sirenPendingIntent = PendingIntent.getBroadcast(
            context,
            102,
            sirenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val locationIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = "ACTION_LOCATION"
        }
        val locationPendingIntent = PendingIntent.getBroadcast(
            context,
            103,
            locationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val bigText = buildString {
            append("🛡️ Profile: ").append(profileName).append("\n")
            append("⚡ Sentinels: ").append(sentinelsSummary)
        }

        val style = NotificationCompat.BigTextStyle()
            .setBigContentTitle("Uncle Carbon: System Defense Active")
            .setSummaryText("ARMED")
            .bigText(bigText)

        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_shield_check_24)
            .setContentTitle("Uncle Carbon: System Defense Active")
            .setContentText("Status: SECURE | $profileName")
            .setStyle(style)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_dark_primary))
            .addAction(R.drawable.ic_lock_24, "Lock", lockPendingIntent)
            .addAction(R.drawable.ic_alert_24, "Siren", sirenPendingIntent)
            .addAction(R.drawable.ic_info_24, "Locate", locationPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Notification for active emergency and panic execution.
     */
    fun createPanicNotification(context: Context): Notification {
        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_PANIC)
            .setContentTitle("Uncle Carbon Emergency Service")
            .setContentText("Executing defensive security protocol...")
            .setSmallIcon(R.drawable.ic_alert_triangle_24)
            .setColor(ContextCompat.getColor(context, R.color.status_red))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Uses the standard System Defense notification for secondary foreground
     * work so it does not present a separate, misleading Hardware Sentinel
     * status message.
     */
    fun createBasicNotification(context: Context): Notification {
        return createMonitoringNotification(context)
    }

    /**
     * Displays a notification when an intruder photo is captured.
     */
    fun showSelfieSavedNotification(context: Context, imageUri: Uri) {
        createNotificationChannels(context)

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(imageUri, "image/jpeg")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val style = NotificationCompat.BigTextStyle()
            .setBigContentTitle("Intruder Selfie Captured")
            .bigText("An unauthorized access attempt was intercepted. Image saved to Pictures/UncleCarbon.")

        val builder = NotificationCompat.Builder(context, CHANNEL_SELFIE)
            .setSmallIcon(R.drawable.ic_selfie_24)
            .setContentTitle("Intruder Selfie Captured")
            .setContentText("Tap to view the captured photo.")
            .setStyle(style)
            .setColor(ContextCompat.getColor(context, R.color.status_red))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID_SELFIE, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed displaying intruder selfie notification: ${e.message}")
        }
    }
}
