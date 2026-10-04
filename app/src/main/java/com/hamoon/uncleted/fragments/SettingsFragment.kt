package com.hamoon.uncleted.fragments

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.lifecycle.lifecycleScope
import com.hamoon.uncleted.R
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.data.SecurityMonitoringSettings
import com.hamoon.uncleted.util.LocaleManager
import com.hamoon.uncleted.util.SecurityMonitoring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        val themePreference: ListPreference? = findPreference("theme")
        themePreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                applyTheme(newValue as String)
                true
            }

        val languagePreference: ListPreference? = findPreference("language")
        languagePreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                LocaleManager.setLocale(newValue as String)
                true
            }

        val shakePreference: SeekBarPreference? = findPreference("shake_sensitivity")
        shakePreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                SecurityPreferences.setShakeSensitivity(requireContext(), newValue as Int)
                true
            }

        val enabled: SwitchPreferenceCompat? = findPreference("security_monitoring_enabled")
        val newApps: SwitchPreferenceCompat? = findPreference("monitor_new_apps")
        val updates: SwitchPreferenceCompat? = findPreference("monitor_app_updates")
        val internet: SwitchPreferenceCompat? = findPreference("monitor_internet_escalation")
        val signers: SwitchPreferenceCompat? = findPreference("monitor_signing_changes")
        val failures: SwitchPreferenceCompat? = findPreference("monitor_failure_alerts")
        enabled?.isChecked = SecurityMonitoringSettings.enabled(requireContext())
        newApps?.isChecked = SecurityMonitoringSettings.newApps(requireContext())
        updates?.isChecked = SecurityMonitoringSettings.updates(requireContext())
        internet?.isChecked = SecurityMonitoringSettings.internet(requireContext())
        signers?.isChecked = SecurityMonitoringSettings.signers(requireContext())
        failures?.isChecked = SecurityMonitoringSettings.failureAlerts(requireContext())
        (findPreference<ListPreference>("monitor_failure_threshold"))?.value = SecurityMonitoringSettings.failureThreshold(requireContext()).toString()
        findPreference<ListPreference>("monitor_failure_response")?.value = SecurityMonitoringSettings.failureResponse(requireContext())
        (findPreference<EditTextPreference>("monitor_trusted_packages"))?.text = SecurityMonitoringSettings.trusted(requireContext()).sorted().joinToString(",")

        enabled?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, value ->
            if (value as Boolean) {
                pref.isEnabled = false
                viewLifecycleOwnerLiveLaunch {
                    runCatching { SecurityMonitoring.initializePackageBaseline(requireContext()) }.onSuccess {
                        enabled?.isChecked = true
                    }.onFailure {
                        SecurityMonitoringSettings.setEnabled(requireContext(), false)
                        requireContext().getSharedPreferences("security_monitor_status", android.content.Context.MODE_PRIVATE)
                            .edit().putLong("last_error_time", System.currentTimeMillis()).putString("last_error_type", it.javaClass.simpleName).commit()
                        AlertDialog.Builder(requireContext()).setMessage("Application monitoring could not establish its baseline. Check package visibility and storage availability.").setPositiveButton(android.R.string.ok, null).show()
                    }
                    pref.isEnabled = true
                }
                false
            } else { SecurityMonitoringSettings.setEnabled(requireContext(), false); true }
        }
        newApps?.onPreferenceChangeListener = booleanSetting { SecurityMonitoringSettings.setNewApps(requireContext(), it) }
        updates?.onPreferenceChangeListener = booleanSetting { SecurityMonitoringSettings.setUpdates(requireContext(), it) }
        internet?.onPreferenceChangeListener = booleanSetting { SecurityMonitoringSettings.setInternet(requireContext(), it) }
        signers?.onPreferenceChangeListener = booleanSetting { SecurityMonitoringSettings.setSigners(requireContext(), it) }
        failures?.onPreferenceChangeListener = booleanSetting { SecurityMonitoringSettings.setFailureAlerts(requireContext(), it) }
        findPreference<ListPreference>("monitor_failure_response")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, raw ->
            val value = raw as? String ?: return@OnPreferenceChangeListener false
            runCatching { SecurityMonitoringSettings.setFailureResponse(requireContext(), value) }.isSuccess
        }
        findPreference<ListPreference>("monitor_failure_threshold")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, raw ->
            val value = (raw as? String)?.toIntOrNull() ?: return@OnPreferenceChangeListener false
            runCatching { SecurityMonitoringSettings.setFailureThreshold(requireContext(), value) }.isSuccess
        }
        findPreference<EditTextPreference>("monitor_trusted_packages")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, raw ->
            val packages = (raw as? String).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            runCatching { SecurityMonitoringSettings.setTrusted(requireContext(), packages) }.isSuccess
        }
        findPreference<ListPreference>("monitor_notification_severity")?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, raw ->
            val selected = (raw as? String)?.let { runCatching { com.hamoon.uncleted.data.MonitoringSeverity.valueOf(it) }.getOrNull() } ?: return@OnPreferenceChangeListener false
            com.hamoon.uncleted.data.MonitoringSeverity.values().forEach { severity ->
                SecurityPreferences.getInstance(requireContext()).edit()
                    .putBoolean("MONITOR_NOTIFY_${severity.name}", severity.ordinal >= selected.ordinal).apply()
            }
            true
        }
        findPreference<Preference>("monitor_status")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext()).setTitle("Monitoring capabilities")
                .setMessage(SecurityMonitoring.capabilitySummary(requireContext()))
                .setPositiveButton(android.R.string.ok, null).show(); true
        }
        findPreference<Preference>("monitor_event_history")?.setOnPreferenceClickListener {
            viewLifecycleOwnerLiveLaunch {
                val events = withContext(Dispatchers.IO) {
                    (SecurityMonitoring.store(requireContext()).events() +
                        SecurityPreferences.getDeviceProtectedPrefs(requireContext()).let { com.hamoon.uncleted.data.SecurityMonitoringStore(it).events() })
                        .distinctBy { it.id }.sortedByDescending { it.timestamp }.take(30)
                }
                val body = if (events.isEmpty()) "No security monitoring events recorded." else events.joinToString("\n\n") {
                    val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.timestamp))
                    "$date · ${it.severity}\n${it.title}${it.appLabel?.let { label -> " · $label (${it.packageName})" }.orEmpty()}\n${it.explanation}\nSource: ${it.source}\nRecommended action: ${it.recommendedAction ?: "Review the event details."}"
                }
                AlertDialog.Builder(requireContext()).setTitle("Recent monitoring events").setMessage(body).setPositiveButton(android.R.string.ok, null).show()
            }; true
        }
    }

    private fun booleanSetting(save: (Boolean) -> Unit) = Preference.OnPreferenceChangeListener { _, value ->
        if (value is Boolean) { save(value); true } else false
    }

    private fun viewLifecycleOwnerLiveLaunch(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch { block() }
    }

    private fun applyTheme(themeValue: String) {
        val mode = when (themeValue) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark", "amoled" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
