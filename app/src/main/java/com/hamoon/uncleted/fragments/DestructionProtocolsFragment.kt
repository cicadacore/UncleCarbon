package com.hamoon.uncleted.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hamoon.uncleted.util.showProtected
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.databinding.FragmentDestructionProtocolsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * GrapheneOS Device Owner destruction protocols.
 *
 * Only the supported "Standard Factory Reset" (Device Owner wipeDevice/wipeData)
 * is exposed. Level 2 (JEDEC Silicon Shred), Level 3 (OS Suicide), and Level 4
 * (Nuclear Winter) have been removed from this fork because they require root
 * or raw block-device access that is not available in the target environment.
 */
class DestructionProtocolsFragment : Fragment() {

    private var _binding: FragmentDestructionProtocolsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDestructionProtocolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val context = requireContext()
        binding.switchHardwareWipe.isChecked = SecurityPreferences.isHardwareWipeEnabled(context)
        binding.switchWipeDeviceOnCritical.isChecked = SecurityPreferences.isWipeDeviceEnabled(context)
        binding.switchEraseEsimOnWipe.isChecked = SecurityPreferences.isEraseEsimOnWipeEnabled(context)
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.switchHardwareWipe.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setHardwareWipeEnabled(context, isChecked)
            if (isChecked) {
                Toast.makeText(
                    context,
                    "Rapid-Volume factory reset armed. Press VOL UP, DOWN, UP, DOWN.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.switchWipeDeviceOnCritical.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setWipeDeviceEnabled(context, isChecked)
        }

        // Erase eSIM on wipe: toggling this only changes the flags used IF a
        // future factory-reset wipe occurs. It never deletes an eSIM now, and
        // never affects Lock/BFU actions. Default is OFF.
        binding.switchEraseEsimOnWipe.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setEraseEsimOnWipeEnabled(context, isChecked)
            if (isChecked) {
                Toast.makeText(
                    context,
                    "eSIM/eUICC profiles will be erased during a factory-reset wipe. No eSIM is deleted now.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnExecLevel1.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("STANDARD FACTORY RESET")
                .setMessage(
                    "This will invoke the GrapheneOS Device Owner standard factory reset. " +
                            "All user data is removed and the device reboots.\n\nProceed?"
                )
                .setNegativeButton("Abort", null)
                .setPositiveButton("Confirm Reset") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val strategy = DefenseCoordinator.resolveStrategy(context)
                        strategy.executeStandardWipe("MANUAL_STANDARD_FACTORY_RESET")
                    }
                }
                .showProtected(requireActivity())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
