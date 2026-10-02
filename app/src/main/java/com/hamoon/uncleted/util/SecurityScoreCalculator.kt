package com.hamoon.uncleted.util

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.hamoon.uncleted.R
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.services.PowerButtonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SecurityScoreCalculator {

    data class ChecklistItem(
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val descriptionRes: Int,
        val weight: Int,
        val isMet: () -> Boolean
    )

    data class SecurityLevel(
        val level: Int,
        @StringRes val titleRes: Int,
        @StringRes val descriptionRes: Int,
        @ColorRes val colorRes: Int
    )

    suspend fun getChecklistItems(context: Context): List<ChecklistItem> = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val isDeviceOwner = dpm?.isDeviceOwnerApp(context.packageName) == true
        val isAdmin = PermissionUtils.isDeviceAdminActive(context)
        val isAccessibility = PermissionUtils.isAccessibilityServiceEnabled(context, PowerButtonService::class.java)
        val hasPerms = PermissionUtils.hasCameraPermission(context) &&
                PermissionUtils.hasLocationPermissions(context) &&
                PermissionUtils.hasSmsPermissions(context) &&
                PermissionUtils.hasPostNotificationsPermission(context)
        val hasContact = !SecurityPreferences.getEmergencyContact(context).isNullOrEmpty()
        val isIntruder = SecurityPreferences.isIntruderSelfieEnabled(context)
        val isSim = SecurityPreferences.isSimChangeAlertEnabled(context) ||
                SecurityPreferences.isWipeOnSimRemovalEnabled(context) ||
                SecurityPreferences.isWipeOnSimReplacementEnabled(context)

        listOf(
            ChecklistItem(
                iconRes = R.drawable.ic_key_24,
                titleRes = R.string.check_device_owner_title,
                descriptionRes = R.string.check_device_owner_desc,
                weight = 30,
                isMet = { isDeviceOwner }
            ),
            ChecklistItem(
                iconRes = R.drawable.ic_shield_check_24,
                titleRes = R.string.check_admin_title,
                descriptionRes = R.string.check_admin_desc,
                weight = 20,
                isMet = { isAdmin }
            ),
            ChecklistItem(
                iconRes = R.drawable.ic_accessibility_24,
                titleRes = R.string.check_accessibility_title,
                descriptionRes = R.string.check_accessibility_desc,
                weight = 20,
                isMet = { isAccessibility }
            ),
            ChecklistItem(
                iconRes = R.drawable.ic_smartphone_24,
                titleRes = R.string.check_permissions_title,
                descriptionRes = R.string.check_permissions_desc,
                weight = 15,
                isMet = { hasPerms }
            ),
            ChecklistItem(
                iconRes = R.drawable.ic_contact_24,
                titleRes = R.string.check_contact_title,
                descriptionRes = R.string.check_contact_desc,
                weight = 10,
                isMet = { hasContact }
            ),
            ChecklistItem(
                iconRes = R.drawable.ic_selfie_24,
                titleRes = R.string.check_intruder_title,
                descriptionRes = R.string.check_intruder_desc,
                weight = 5,
                isMet = { isIntruder }
            ),
            ChecklistItem(
                iconRes = R.drawable.ic_sim_card_24,
                titleRes = R.string.check_sim_title,
                descriptionRes = R.string.check_sim_desc,
                weight = 5,
                isMet = { isSim }
            )
        )
    }

    suspend fun calculateSecurityLevel(context: Context): SecurityLevel = withContext(Dispatchers.IO) {
        val items = getChecklistItems(context)
        val maxScore = items.sumOf { it.weight }
        val currentScore = items.filter { it.isMet() }.sumOf { it.weight }
        val percentage = if (maxScore > 0) (currentScore.toFloat() / maxScore.toFloat()) * 100 else 0f

        when {
            !PermissionUtils.isDeviceAdminActive(context) -> SecurityLevel(0, R.string.level_0_title, R.string.level_0_desc, R.color.level_1_poor)
            percentage < 25 -> SecurityLevel(1, R.string.level_1_title, R.string.level_1_desc, R.color.level_1_poor)
            percentage < 50 -> SecurityLevel(2, R.string.level_2_title, R.string.level_2_desc, R.color.level_2_fair)
            percentage < 75 -> SecurityLevel(3, R.string.level_3_title, R.string.level_3_desc, R.color.level_3_good)
            percentage < 100 -> SecurityLevel(4, R.string.level_4_title, R.string.level_4_desc, R.color.level_4_excellent)
            else -> SecurityLevel(5, R.string.level_5_title, R.string.level_5_desc, R.color.level_5_maximum)
        }
    }
}
