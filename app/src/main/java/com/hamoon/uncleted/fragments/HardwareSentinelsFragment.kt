package com.hamoon.uncleted.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hamoon.uncleted.R
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.databinding.FragmentHardwareSentinelsBinding
import kotlinx.coroutines.Dispatchers
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

        binding.switchBlockSafeBoot.isChecked = SecurityPreferences.isSafeBootBlocked(context)
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
                .show()
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
            if (!isChecked) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.safe_boot_warning_title)
                    .setMessage(R.string.safe_boot_warning_message)
                    .setPositiveButton(R.string.safe_boot_allow_button) { _, _ ->
                        SecurityPreferences.setSafeBootBlocked(context, false)
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val strategy = DefenseCoordinator.resolveStrategy(context)
                            strategy.setSafeBootBlocked(false)
                        }
                        Toast.makeText(context, "Safe Boot restriction removed.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.safe_boot_keep_blocked_button) { _, _ ->
                        binding.switchBlockSafeBoot.isChecked = true
                    }
                    .setOnCancelListener {
                        binding.switchBlockSafeBoot.isChecked = true
                    }
                    .show()
            } else {
                SecurityPreferences.setSafeBootBlocked(context, true)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val strategy = DefenseCoordinator.resolveStrategy(context)
                    strategy.setSafeBootBlocked(true)
                }
                Toast.makeText(context, "Safe Boot blocked.", Toast.LENGTH_SHORT).show()
            }
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
        // button only covers the unrelated baseband / safe-boot options.
        val maxTA = binding.etBasebandTimingAdvance.text?.toString()?.toIntOrNull() ?: 30
        SecurityPreferences.setTimingAdvanceThreshold(context, maxTA)

        SecurityPreferences.setSafeBootBlocked(context, binding.switchBlockSafeBoot.isChecked)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
