package com.hamoon.uncleted.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.hamoon.uncleted.R
import com.hamoon.uncleted.crypto.EphemeralKeyDecayEngine
import com.hamoon.uncleted.crypto.OprfClientEngine
import com.hamoon.uncleted.crypto.OprfPreferences
import com.hamoon.uncleted.databinding.FragmentAntiForensicsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Anti-forensics configuration surface for the GrapheneOS fork.
 *
 * Preserved:
 *   - Ephemeral key heartbeat decay (in-memory only; locks via Device Owner)
 *   - OPRF decoupled master key handshake
 *
 * Removed in this fork (require root / raw block devices / kernel SysRq, which
 * are unavailable on an unrooted locked GrapheneOS install):
 *   - 4KB FBE metadata zero-shred against /dev/block/by-name/metadata
 *   - USB kernel trapdoor (UDC bind/unbind on screen-off, SysRq hardware panic)
 *   - /data/adb/post-mount.d early-init USB kill script deployment
 *   - AVB signing / recovery hardening artifact exporter (BootloaderHardeningHelper)
 */
class AntiForensicsFragment : Fragment() {

    private var _binding: FragmentAntiForensicsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAntiForensicsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
        observeEphemeralKeyStatus()
    }

    private fun loadSettings() {
        val context = requireContext()
        binding.switchOprfEnabled.isChecked = OprfPreferences.isOprfEnabled(context)
        binding.etOprfServerUrl.setText(OprfPreferences.getServerUrl(context))
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.btnArmEphemeralKeys.setOnClickListener {
            EphemeralKeyDecayEngine.initialize(context)
            EphemeralKeyDecayEngine.provisionEphemeralSeed(context)
            Toast.makeText(context, "Ephemeral Decay Cycle active. Heartbeat armed.", Toast.LENGTH_SHORT).show()
        }

        binding.btnEvaporateKeys.setOnClickListener {
            EphemeralKeyDecayEngine.purgeEphemeralKey(context)
            Toast.makeText(context, "Ephemeral key evaporated & device lock requested.", Toast.LENGTH_SHORT).show()
        }

        binding.switchOprfEnabled.setOnCheckedChangeListener { _, isChecked ->
            OprfPreferences.setOprfEnabled(context, isChecked)
        }

        binding.btnTestOprfHandshake.setOnClickListener {
            val url = binding.etOprfServerUrl.text?.toString()?.trim().orEmpty()
            OprfPreferences.setServerUrl(context, url)
            Toast.makeText(context, "Dispatching blinded OPRF test query...", Toast.LENGTH_SHORT).show()

            viewLifecycleOwner.lifecycleScope.launch {
                val derived = withContext(Dispatchers.IO) {
                    OprfClientEngine.deriveDecoupledMasterKey(context, "1234")
                }
                if (derived != null) {
                    Toast.makeText(context, "OPRF evaluation succeeded. Master decoupled key derived.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "OPRF handshake failed (Server unreachable or rejected). Key decoupled.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun observeEphemeralKeyStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            EphemeralKeyDecayEngine.decayStatusFlow.collectLatest { status ->
                if (_binding != null) {
                    if (status.isKeyLive) {
                        binding.tvEphemeralStatus.text = "Key State: ARMED & PINNED (Rolling Buffer)"
                        binding.tvEphemeralStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
                    } else {
                        binding.tvEphemeralStatus.text = "Key State: EVAPORATED (Cold BFU)"
                        binding.tvEphemeralStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_yellow))
                    }
                    binding.tvEphemeralHeartbeat.text = "Heartbeat: ${status.missedHeartbeats}/${status.maxAllowedMisses} misses (Entropy pool: ${status.entropyPoolSize} bytes)"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
