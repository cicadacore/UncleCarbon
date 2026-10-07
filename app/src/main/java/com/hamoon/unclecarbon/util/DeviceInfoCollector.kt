package com.hamoon.unclecarbon.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log

object DeviceInfoCollector {

    private const val TAG = "DeviceInfoCollector"

    data class DeviceInfo(
        val deviceId: String,
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val securityPatch: String,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val networkOperator: String?,
        val simSerialNumber: String?,
        val imei: String?,
        val installedApps: List<String>
    )

    @SuppressLint("HardwareIds")
    fun collectDeviceInfo(context: Context): DeviceInfo {
        val batteryInfo = getBatteryInfo(context)
        val telephonyInfo = getTelephonyInfo(context)

        return DeviceInfo(
            deviceId = getDeviceId(context),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            securityPatch = Build.VERSION.SECURITY_PATCH,
            batteryLevel = batteryInfo.first,
            isCharging = batteryInfo.second,
            networkOperator = telephonyInfo.first,
            simSerialNumber = telephonyInfo.second,
            imei = telephonyInfo.third,
            installedApps = getKeyInstalledApps(context)
        )
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device ID", e)
            "Unknown"
        }
    }

    private fun getBatteryInfo(context: Context): Pair<Int, Boolean> {
        return try {
            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, iFilter)

            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) {
                (level.toFloat() / scale.toFloat() * 100).toInt()
            } else 0

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            Pair(batteryPct, isCharging)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery info", e)
            Pair(0, false)
        }
    }

    @SuppressLint("HardwareIds")
    private fun getTelephonyInfo(context: Context): Triple<String?, String?, String?> {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            val networkOperator = try {
                telephonyManager.networkOperatorName
            } catch (e: SecurityException) {
                null
            }

            val simSerial = try {
                if (PermissionUtils.hasReadPhoneStatePermission(context)) {
                    telephonyManager.simSerialNumber
                } else null
            } catch (e: SecurityException) {
                null
            }

            val imei = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    PermissionUtils.hasReadPhoneStatePermission(context)) {
                    telephonyManager.imei
                } else null
            } catch (e: SecurityException) {
                null
            }

            Triple(networkOperator, simSerial, imei)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get telephony info", e)
            Triple(null, null, null)
        }
    }

    private fun getKeyInstalledApps(context: Context): List<String> {
        return try {
            val packageManager = context.packageManager
            val installedPackages = packageManager.getInstalledPackages(0)

            // Filter for interesting/security-relevant apps
            val securityApps = listOf(
                "android.settings", "com.android.settings",
                "com.google.android.gms", "com.google.android.googlequicksearchbox",
                "com.whatsapp", "com.facebook.katana", "com.instagram.android",
                "com.android.vending", "com.google.android.apps.authenticator2",
                "com.lastpass.lpandroid", "com.oneplus.security"
            )

            installedPackages
                .map { it.packageName }
                .filter { packageName ->
                    securityApps.any { securityApp -> packageName.contains(securityApp) }
                }
                .take(10) // Limit to prevent huge lists

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed apps", e)
            emptyList()
        }
    }

    fun formatDeviceInfoForEmail(deviceInfo: DeviceInfo): String {
        return """
            DEVICE INFORMATION:
            -------------------
            Device ID: ${deviceInfo.deviceId}
            Manufacturer: ${deviceInfo.manufacturer}
            Model: ${deviceInfo.model}
            Android Version: ${deviceInfo.androidVersion}
            Security Patch: ${deviceInfo.securityPatch}
            
            POWER STATUS:
            -------------
            Battery Level: ${deviceInfo.batteryLevel}%
            Charging: ${if (deviceInfo.isCharging) "Yes" else "No"}
            
            NETWORK INFORMATION:
            -------------------
            Network Operator: ${deviceInfo.networkOperator ?: "Unknown"}
            SIM Serial: ${deviceInfo.simSerialNumber ?: "Unknown/No Permission"}
            IMEI: ${deviceInfo.imei ?: "Unknown/No Permission"}
            
            INSTALLED SECURITY APPS:
            ----------------------
            ${deviceInfo.installedApps.joinToString("\n") { "- $it" }}
        """.trimIndent()
    }
}