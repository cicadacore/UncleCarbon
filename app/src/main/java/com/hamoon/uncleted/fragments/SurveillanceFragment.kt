package com.hamoon.uncleted.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hamoon.uncleted.EvidenceGalleryActivity
import com.hamoon.uncleted.R
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.databinding.FragmentSurveillanceBinding
import com.hamoon.uncleted.services.PanicActionService
import com.hamoon.uncleted.util.StorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Surveillance / Evidence configuration.
 *
 * Removed from this fork (root-only, unavailable on GrapheneOS unrooted):
 *   - Stealth root screencap screenshots
 *   - /dev/input kernel keylogger
 *   - Stealth-media capture using privileged APIs
 */
class SurveillanceFragment : Fragment() {

    private var _binding: FragmentSurveillanceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSurveillanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshEvidenceMetrics()
    }

    private fun loadSettings() {
        val context = requireContext()

        binding.switchRecordVideo.isChecked = SecurityPreferences.isRecordVideoEnabled(context)
        binding.switchAmbientAudio.isChecked = SecurityPreferences.isAmbientAudioEnabled(context)

        setupVideoDurationDropdown(SecurityPreferences.getVideoRecordingDurationSeconds(context))
        setupAudioDurationDropdown(SecurityPreferences.getAudioRecordingDurationSeconds(context))
        binding.switchFrontCamera.isChecked = SecurityPreferences.isFrontCameraCaptureEnabled(context)
        binding.switchBackCamera.isChecked = SecurityPreferences.isBackCameraCaptureEnabled(context)

        val intruderEnabled = SecurityPreferences.isIntruderSelfieEnabled(context)
        binding.switchIntruderSelfie.isChecked = intruderEnabled
        binding.switchSaveSelfieToStorage.isChecked = SecurityPreferences.isSaveSelfieToStorageEnabled(context)
        binding.switchSaveSelfieToStorage.isEnabled = intruderEnabled

        binding.switchSimChange.isChecked = SecurityPreferences.isSimChangeAlertEnabled(context)
        binding.switchWipeOnSimRemoval.isChecked = SecurityPreferences.isWipeOnSimRemovalEnabled(context)
        binding.switchWipeOnSimReplacement.isChecked = SecurityPreferences.isWipeOnSimReplacementEnabled(context)
        binding.switchShakeToPanic.isChecked = SecurityPreferences.isShakeToPanicEnabled(context)
    }

    private fun setupVideoDurationDropdown(currentSeconds: Int) {
        val entries = resources.getStringArray(R.array.video_duration_entries)
        val values = resources.getStringArray(R.array.video_duration_values)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, entries)
        binding.autoVideoDuration.setAdapter(adapter)

        val idx = values.indexOf(currentSeconds.toString()).takeIf { it != -1 } ?: 2
        binding.autoVideoDuration.setText(entries[idx], false)
    }

    private fun setupAudioDurationDropdown(currentSeconds: Int) {
        val entries = resources.getStringArray(R.array.audio_duration_entries)
        val values = resources.getStringArray(R.array.audio_duration_values)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, entries)
        binding.autoAudioDuration.setAdapter(adapter)

        val idx = values.indexOf(currentSeconds.toString()).takeIf { it != -1 } ?: 1
        binding.autoAudioDuration.setText(entries[idx], false)
    }

    private fun refreshEvidenceMetrics() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val (fileCount, totalBytes) = withContext(Dispatchers.IO) {
                var count = 0
                var bytes = 0L

                StorageLayout.migrateLegacyEvidence(context)

                val files = StorageLayout.evidenceDir(context).listFiles()
                files?.forEach { f ->
                    val name = f.name
                    if (name.startsWith("IMG_") || name.startsWith("VID_") ||
                        name.startsWith("AUD_") || name.endsWith(".dng", ignoreCase = true)) {
                        count++
                        bytes += f.length()
                    }
                }

                val cameraDir = StorageLayout.legacyCameraDir(context)
                if (cameraDir.exists()) {
                    cameraDir.listFiles()?.forEach { f ->
                        if (f.name.endsWith(".dng", ignoreCase = true)) {
                            count++
                            bytes += f.length()
                        }
                    }
                }

                Pair(count, bytes)
            }

            if (_binding != null) {
                val formattedSize = when {
                    totalBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", totalBytes.toDouble() / (1024 * 1024))
                    totalBytes >= 1024 -> String.format(Locale.US, "%d KB", totalBytes / 1024)
                    else -> "$totalBytes B"
                }
                binding.tvEvidenceCountSummary.text = "Captured Items: $fileCount files ($formattedSize)"
            }
        }
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.btnOpenEvidenceGallery.setOnClickListener {
            val intent = Intent(context, EvidenceGalleryActivity::class.java)
            startActivity(intent)
        }

        binding.btnPurgeAllEvidence.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("Purge All Evidence Files")
                .setMessage("Zero-fill and permanently delete all recorded surveillance videos, audio files, and photos?")
                .setPositiveButton("Shred All") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val targets = mutableListOf<File>()
                            StorageLayout.migrateLegacyEvidence(context)
                            StorageLayout.evidenceDir(context).listFiles()?.forEach { f ->
                                val name = f.name
                                if (name.startsWith("IMG_") || name.startsWith("VID_") ||
                                    name.startsWith("AUD_") || name.endsWith(".dng", ignoreCase = true)) {
                                    targets.add(f)
                                }
                            }
                            val cameraDir = StorageLayout.legacyCameraDir(context)
                            if (cameraDir.exists()) {
                                cameraDir.listFiles()?.let { targets.addAll(it) }
                            }

                            for (file in targets) {
                                try {
                                    if (file.exists() && file.canWrite()) {
                                        val len = file.length()
                                        val zeroBytes = ByteArray(4096)
                                        RandomAccessFile(file, "rws").use { raf ->
                                            var written = 0L
                                            while (written < len) {
                                                val toWrite = minOf(zeroBytes.size.toLong(), len - written).toInt()
                                                raf.write(zeroBytes, 0, toWrite)
                                                written += toWrite
                                            }
                                            raf.fd.sync()
                                        }
                                    }
                                } catch (_: Exception) {}
                                file.delete()
                            }
                        }
                        refreshEvidenceMetrics()
                        Toast.makeText(context, "All evidence files zeroed and shredded.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.autoVideoDuration.setOnItemClickListener { _, _, position, _ ->
            val values = resources.getStringArray(R.array.video_duration_values)
            val selectedSec = values[position].toInt()
            SecurityPreferences.setVideoRecordingDurationSeconds(context, selectedSec)
            Toast.makeText(context, "Video duration set to $selectedSec seconds.", Toast.LENGTH_SHORT).show()
        }

        binding.autoAudioDuration.setOnItemClickListener { _, _, position, _ ->
            val values = resources.getStringArray(R.array.audio_duration_values)
            val selectedSec = values[position].toInt()
            SecurityPreferences.setAudioRecordingDurationSeconds(context, selectedSec)
            Toast.makeText(context, "Audio duration set to $selectedSec seconds.", Toast.LENGTH_SHORT).show()
        }

        binding.switchFrontCamera.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setFrontCameraCaptureEnabled(context, isChecked)
        }

        binding.switchBackCamera.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setBackCameraCaptureEnabled(context, isChecked)
        }

        binding.switchRecordVideo.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setRecordVideoEnabled(context, isChecked)
        }

        binding.switchAmbientAudio.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setAmbientAudioEnabled(context, isChecked)
        }

        binding.switchIntruderSelfie.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setIntruderSelfieEnabled(context, isChecked)
            binding.switchSaveSelfieToStorage.isEnabled = isChecked
        }

        binding.switchSaveSelfieToStorage.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setSaveSelfieToStorage(context, isChecked)
        }

        binding.switchSimChange.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setSimChangeAlertEnabled(context, isChecked)
        }

        binding.switchWipeOnSimRemoval.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(context)
                    .setTitle("Factory Reset on SIM Removal")
                    .setMessage("If the SIM card is ejected after this is armed, the device will immediately execute a standard Device Owner factory reset. Proceed?")
                    .setPositiveButton("Enable Tripwire") { _, _ ->
                        SecurityPreferences.setWipeOnSimRemovalEnabled(context, true)
                        Toast.makeText(context, "SIM Removal factory reset armed.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        binding.switchWipeOnSimRemoval.isChecked = false
                        SecurityPreferences.setWipeOnSimRemovalEnabled(context, false)
                    }
                    .show()
            } else {
                SecurityPreferences.setWipeOnSimRemovalEnabled(context, false)
            }
        }

        binding.switchWipeOnSimReplacement.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(context)
                    .setTitle("Factory Reset on SIM Replacement")
                    .setMessage("If the SIM card is swapped for a different one, the device will immediately execute a standard Device Owner factory reset. Proceed?")
                    .setPositiveButton("Enable Tripwire") { _, _ ->
                        SecurityPreferences.setWipeOnSimReplacementEnabled(context, true)
                        Toast.makeText(context, "SIM Replacement factory reset armed.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        binding.switchWipeOnSimReplacement.isChecked = false
                        SecurityPreferences.setWipeOnSimReplacementEnabled(context, false)
                    }
                    .show()
            } else {
                SecurityPreferences.setWipeOnSimReplacementEnabled(context, false)
            }
        }

        binding.switchShakeToPanic.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setShakeToPanicEnabled(context, isChecked)
        }

        binding.btnManualLocationAlert.setOnClickListener {
            Toast.makeText(context, "Dispatching location fix alert...", Toast.LENGTH_SHORT).show()
            PanicActionService.trigger(context, "MANUAL_LOCATION", PanicActionService.Severity.LOW)
        }

        binding.btnManualEvidenceBurst.setOnClickListener {
            Toast.makeText(context, "Capturing multi-modal evidence burst...", Toast.LENGTH_SHORT).show()
            PanicActionService.trigger(context, "REMOTE_EVIDENCE", PanicActionService.Severity.HIGH)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
