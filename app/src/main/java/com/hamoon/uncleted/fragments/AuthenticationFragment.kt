package com.hamoon.uncleted.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.hamoon.uncleted.R
import com.hamoon.uncleted.core.DefenseCoordinator
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.databinding.FragmentAuthenticationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * GrapheneOS Device Owner authentication settings.
 *
 * The GrapheneOS-native device credential is the source of truth for the
 * lockscreen PIN; UncleTed does not implement any app-level, Duress, Wipe,
 * Honeypot, Decoy, or special PIN of its own. GrapheneOS also provides its
 * own native Duress PIN which should be configured in GrapheneOS Settings
 * directly.
 *
 * This fragment configures only:
 *   - Biometric authentication requirement for opening UncleTed
 *   - Max failed Keyguard attempts before a standard Device Owner factory reset
 */
class AuthenticationFragment : Fragment() {

    private var _binding: FragmentAuthenticationBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthenticationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        val attempts = SecurityPreferences.getFailedAttempts(requireContext())
        binding.tvFailedAttemptsCount.text = "Recent Failed Keyguard Attempts: $attempts"
    }

    private fun loadSettings() {
        val context = requireContext()
        binding.switchBiometricLock.isChecked = SecurityPreferences.isBiometricLockEnabled(context)
        setupFailedAttemptsDropdown(SecurityPreferences.getMaxFailedAttemptsForWipe(context))
    }

    private fun setupFailedAttemptsDropdown(currentCount: Int) {
        val entries = resources.getStringArray(R.array.failed_wipe_attempts_entries)
        val values = resources.getStringArray(R.array.failed_wipe_attempts_values)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, entries)
        binding.autoMaxFailedAttempts.setAdapter(adapter)

        val idx = values.indexOf(currentCount.toString()).takeIf { it != -1 } ?: 2
        binding.autoMaxFailedAttempts.setText(entries[idx], false)
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.switchBiometricLock.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setBiometricLockEnabled(context, isChecked)
        }

        binding.autoMaxFailedAttempts.setOnItemClickListener { _, _, position, _ ->
            val values = resources.getStringArray(R.array.failed_wipe_attempts_values)
            val selectedAttempts = values[position].toInt()
            SecurityPreferences.setMaxFailedAttemptsForWipe(context, selectedAttempts)

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val strategy = DefenseCoordinator.resolveStrategy(context)
                strategy.configureBruteForceThreshold(selectedAttempts)
            }
            Toast.makeText(context, "Max failed attempts threshold: $selectedAttempts", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
