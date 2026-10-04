package com.hamoon.uncleted.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hamoon.uncleted.util.showProtected
import com.hamoon.uncleted.R
import com.hamoon.uncleted.core.LockdownManager
import com.hamoon.uncleted.core.LockdownState
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.databinding.FragmentHardwareSentinelsBinding
import kotlinx.coroutines.launch

class HardwareSentinelsFragment : Fragment() {

    private var _binding: FragmentHardwareSentinelsBinding? = null
    private val binding get() = _binding!!

    private lateinit var timerEntries: Array<String>
    private lateinit var timerValuesMs: Array<String>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHardwareSentinelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        timerEntries = resources.getStringArray(R.array.spectral_timer_entries)
        timerValuesMs = resources.getStringArray(R.array.spectral_timer_values_ms)

        loadSettings()
        setupListeners()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LockdownManager.controller(requireContext()).state.collect { renderProtectionState(it) }
            }
        }
    }

    private fun loadSettings() {
        val context = requireContext()

        binding.switchSpectralSentinel.isChecked = SecurityPreferences.isSpectralSentinelEnabled(context)
        binding.switchSpectralWifiConfirmation.isChecked = SecurityPreferences.isWifiRfConfirmationEnabled(context)

        // Timer window dropdown
        val adapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, timerEntries)
        binding.autoSpectralTimer.setAdapter(adapter)
        val currentMs = SecurityPreferences.getSpectralQuarantineMs(context).toString()
        val timerIndex = timerValuesMs.indexOf(currentMs).takeIf { it >= 0 } ?: 0
        binding.autoSpectralTimer.setText(timerEntries[timerIndex], false)

        // BFU/WIPE action toggle
        val action = SecurityPreferences.getSpectralAction(context)
        binding.toggleSpectralAction.check(
            if (action == "WIPE") R.id.btn_action_wipe else R.id.btn_action_bfu
        )

        binding.switchBasebandSentinel.isChecked = SecurityPreferences.isBasebandSentinelEnabled(context)
        binding.etBasebandTimingAdvance.setText(SecurityPreferences.getTimingAdvanceThreshold(context).toString())

        renderProtectionState(LockdownManager.controller(context).state.value)
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.btnSpectralInfo.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("RF/Network-loss Sentinel")
                .setMessage(
                    "When the device is locked, this sentinel monitors cellular and network " +
                    "connectivity. If all RF signals are lost for longer than the configured " +
                    "timer window (and the device is not in Airplane Mode), the selected action " +
                    "is taken.\n\n" +
                    "BFU: Reboots the device into Before First Unlock state, evicting decryption " +
                    "keys from memory.\n\n" +
                    "WIPE: Performs a Device Owner factory reset, erasing all user data.\n\n" +
                    "\"Wi-Fi RF Confirmation\" uses nearby Wi-Fi radio activity to distinguish a " +
                    "network outage from possible RF isolation. When cellular/network RF disappears, " +
                    "UncleCarbon can temporarily enable Wi-Fi and check whether nearby access points " +
                    "are still visible. Visible access points mean the device is probably not RF-isolated. " +
                    "This is a confirmation heuristic, not proof of a Faraday enclosure."
                )
                .setPositiveButton(android.R.string.ok, null)
                .showProtected(requireActivity())
        }

        // All four RF-loss settings persist IMMEDIATELY (no Save button needed).

        binding.switchSpectralSentinel.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setSpectralSentinelEnabled(context, isChecked)
        }

        binding.switchSpectralWifiConfirmation.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setWifiRfConfirmationEnabled(context, isChecked)
        }

        // BFU/WIPE action — persist the moment the selection changes.
        binding.toggleSpectralAction.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val action = if (checkedId == R.id.btn_action_wipe) "WIPE" else "BFU"
            SecurityPreferences.setSpectralAction(context, action)
        }

        // Quarantine duration — persist the moment a timer window is chosen.
        binding.autoSpectralTimer.setOnItemClickListener { _, _, position, _ ->
            val ms = timerValuesMs.getOrNull(position)?.toLongOrNull() ?: 1800000L
            SecurityPreferences.setSpectralQuarantineMs(context, ms)
        }

        binding.switchBasebandSentinel.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setBasebandSentinelEnabled(context, isChecked)
        }

        binding.switchBlockSafeBoot.setOnClickListener {
            val isChecked = binding.switchBlockSafeBoot.isChecked
            val state = LockdownManager.controller(context).state.value
            renderProtectionState(state)
            if (state.controlsLocked) return@setOnClickListener
            if (!isChecked) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.safe_boot_warning_title)
                    .setMessage(R.string.safe_boot_warning_message)
                    .setPositiveButton(R.string.safe_boot_allow_button) { _, _ ->
                        markProtectionBusy()
                        LockdownManager.setSafeBootBlocked(context.applicationContext, false)
                    }
                    .setNegativeButton(R.string.safe_boot_keep_blocked_button) { _, _ ->
                        renderProtectionState(LockdownManager.controller(context).state.value)
                    }
                    .setOnCancelListener {
                        renderProtectionState(LockdownManager.controller(context).state.value)
                    }
                    .showProtected(requireActivity())
            } else {
                markProtectionBusy()
                LockdownManager.setSafeBootBlocked(context.applicationContext, true)
            }
        }

        // Click listeners keep rendering/read-back from dispatching new commands.
        binding.switchBlockDeveloperFeatures.setOnClickListener {
            val blocked = binding.switchBlockDeveloperFeatures.isChecked
            val state = LockdownManager.controller(context).state.value
            renderProtectionState(state)
            if (state.controlsLocked) return@setOnClickListener
            markProtectionBusy()
            LockdownManager.setDeveloperFeaturesBlocked(context.applicationContext, blocked)
        }

        binding.switchLockdownMode.setOnClickListener {
            val state = LockdownManager.controller(context).state.value
            renderProtectionState(state)
            if (state.controlsLocked) return@setOnClickListener
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lockdown_confirmation_title)
                .setMessage(R.string.lockdown_confirmation_message)
                .setPositiveButton(R.string.lockdown_enable_button) { _, _ ->
                    markProtectionBusy()
                    LockdownManager.activate(context.applicationContext)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showProtected(requireActivity())
        }

        binding.btnSaveHardwareSentinels.setOnClickListener {
            saveConfiguredParameters()
            Toast.makeText(context, "Sentinel parameters saved & armed.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveConfiguredParameters() {
        val context = requireContext()

        // NOTE: the four RF/network-loss settings (sentinel enabled, Wi-Fi RF
        // confirmation, BFU/WIPE action and quarantine duration) persist immediately
        // via their own listeners and are intentionally not handled here. This Save
        // button only covers the unrelated baseband options. Protection actions
        // are verified and persisted by their shared enforcement path.
        val maxTA = binding.etBasebandTimingAdvance.text?.toString()?.toIntOrNull() ?: 30
        SecurityPreferences.setTimingAdvanceThreshold(context, maxTA)
    }

    override fun onResume() {
        super.onResume()
        LockdownManager.refresh(requireContext().applicationContext)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) LockdownManager.refresh(requireContext().applicationContext)
    }

    private fun markProtectionBusy() {
        context?.let { renderProtectionState(LockdownManager.controller(it).state.value.copy(busy = true)) }
    }

    private fun renderProtectionState(state: LockdownState) {
        val views = _binding ?: return
        val context = context ?: return
        views.switchBlockSafeBoot.isChecked = state.protections?.safeBootBlocked == true
        // USB blocking shares the underlying debugging restriction, but does not
        // opt the user into the independent Developer protection setting.
        views.switchBlockDeveloperFeatures.isChecked = state.protections?.developerFeaturesBlocked == true &&
            SecurityPreferences.isDeveloperFeaturesBlocked(context)
        views.switchBlockSafeBoot.isEnabled = !state.controlsLocked
        views.switchBlockDeveloperFeatures.isEnabled = !state.controlsLocked
        views.switchLockdownMode.isChecked = state.enabled
        views.switchLockdownMode.isEnabled = !state.controlsLocked

        fun protectionStatus(applied: Boolean?): String = when {
            state.busy -> getString(R.string.protection_checking)
            applied == null -> getString(if (state.enabled) R.string.protection_unknown_locked else R.string.protection_unknown)
            state.enabled -> getString(if (applied) R.string.protection_locked_on else R.string.protection_locked_failed)
            else -> ""
        }
        views.textSafeBootStatus.text = protectionStatus(state.protections?.safeBootBlocked)
        views.textDeveloperStatus.text = protectionStatus(state.protections?.developerFeaturesBlocked)
        views.textSafeBootStatus.visibility = if (views.textSafeBootStatus.text.isEmpty()) View.GONE else View.VISIBLE
        views.textDeveloperStatus.visibility = if (views.textDeveloperStatus.text.isEmpty()) View.GONE else View.VISIBLE
        views.textLockdownStatus.text = when {
            state.busy -> getString(R.string.protection_checking)
            state.error != null -> getString(
                if (state.enabled) R.string.lockdown_attention else R.string.protection_failed, state.error)
            state.active -> getString(R.string.lockdown_active)
            else -> getString(R.string.lockdown_inactive)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
