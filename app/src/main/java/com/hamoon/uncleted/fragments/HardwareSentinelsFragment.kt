package com.hamoon.uncleted.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

/**
 * GrapheneOS-compatible hardware sentinel configuration.
 *
 * Removed features (not available on GrapheneOS, locked bootloader, unrooted):
 *   - PMIC Battery Micro-Telemetry (SELinux blocks SysFS probing)
 *   - UncleTed 2G user restriction (GrapheneOS has native OS-level 2G control)
 *   - Faraday blackout receiver (relied on radio isolation we no longer perform)
 *   - Raw USB Gadget / UDC Tripwire (requires /sys/class/udc kernel access)
 *
 * Preserved features:
 *   - Baseband IMSI-catcher / downgrade sentinel (telephony callback observation)
 *   - Spectral collapse sentinel (ambient RF sensors)
 *   - Device Owner Safe Boot restriction (DISALLOW_SAFE_BOOT)
 */
class HardwareSentinelsFragment : Fragment() {

    private var _binding: FragmentHardwareSentinelsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHardwareSentinelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val context = requireContext()

        binding.switchSpectralSentinel.isChecked = SecurityPreferences.isSpectralSentinelEnabled(context)
        binding.switchSpectralMotionRequired.isChecked = SecurityPreferences.isSpectralMotionRequired(context)
        binding.etSpectralQuarantineMs.setText(SecurityPreferences.getSpectralQuarantineMs(context).toString())

        binding.switchBasebandSentinel.isChecked = SecurityPreferences.isBasebandSentinelEnabled(context)
        binding.etBasebandTimingAdvance.setText(SecurityPreferences.getTimingAdvanceThreshold(context).toString())

        binding.switchBlockSafeBoot.isChecked = SecurityPreferences.isSafeBootBlocked(context)
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.switchSpectralSentinel.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setSpectralSentinelEnabled(context, isChecked)
        }

        binding.switchSpectralMotionRequired.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setSpectralMotionRequired(context, isChecked)
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

        val spectralMs = binding.etSpectralQuarantineMs.text?.toString()?.toLongOrNull() ?: 15000L
        SecurityPreferences.setSpectralQuarantineMs(context, spectralMs)

        val maxTA = binding.etBasebandTimingAdvance.text?.toString()?.toIntOrNull() ?: 30
        SecurityPreferences.setTimingAdvanceThreshold(context, maxTA)

        SecurityPreferences.setSafeBootBlocked(context, binding.switchBlockSafeBoot.isChecked)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
