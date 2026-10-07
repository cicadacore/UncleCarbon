package com.hamoon.unclecarbon.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hamoon.unclecarbon.util.showProtected
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.hamoon.unclecarbon.R
import com.hamoon.unclecarbon.crypto.CryptoPreferences
import com.hamoon.unclecarbon.crypto.OneTimeTokenManager
import com.hamoon.unclecarbon.data.SecurityPreferences
import com.hamoon.unclecarbon.databinding.FragmentRemoteSignalingBinding
import com.hamoon.unclecarbon.util.EmailSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemoteSignalingFragment : Fragment() {

    private var _binding: FragmentRemoteSignalingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRemoteSignalingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val context = requireContext()

        updateTokenCountDisplay()

        val cleartextAllowed = CryptoPreferences.isCleartextSmsAllowed(context)
        binding.switchAllowCleartextSms.isChecked = cleartextAllowed
        binding.layoutSmsMasterPassword.isEnabled = cleartextAllowed
        binding.etEmergencyContact.setText(SecurityPreferences.getEmergencyContact(context) ?: "")
        binding.etSmsMasterPassword.setText(SecurityPreferences.getSmsMasterPassword(context) ?: "")
    }

    private fun updateTokenCountDisplay() {
        val remaining = OneTimeTokenManager.getRemainingTokenCount(requireContext())
        binding.tvOtcTokenStatus.text = "Active Single-Use Token in DE Store: $remaining"
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.btnGenerateOtcTokens.setOnClickListener {
            val tokens = OneTimeTokenManager.generateNewTokenBatch(context)
            updateTokenCountDisplay()
            showTokenWalletSheetDialog(tokens)
        }

        binding.btnBurnAllTokens.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("Burn Emergency Token?")
                .setMessage("The active single-use emergency wipe token will be removed from Device-Protected storage.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Burn") { _, _ ->
                    OneTimeTokenManager.clearAllTokens(context)
                    updateTokenCountDisplay()
                    Toast.makeText(context, "Emergency token burned.", Toast.LENGTH_SHORT).show()
                }
                .showProtected(requireActivity())
        }

        binding.switchAllowCleartextSms.setOnCheckedChangeListener { _, isChecked ->
            CryptoPreferences.setCleartextSmsAllowed(context, isChecked)
            binding.layoutSmsMasterPassword.isEnabled = isChecked
        }

        binding.btnSetEmailCredentials.setOnClickListener {
            showEmailCredentialsDialog()
        }

        binding.btnSendTestEmail.setOnClickListener {
            sendTestEmail()
        }

        binding.btnSaveRemoteSignaling.setOnClickListener {
            saveSettings()
            Toast.makeText(context, "Remote signaling parameters saved & armed.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSettings() {
        val context = requireContext()

        val contact = binding.etEmergencyContact.text?.toString()?.trim().orEmpty()
        val masterPassword = binding.etSmsMasterPassword.text?.toString()?.trim().orEmpty()
        SecurityPreferences.setEmergencyContact(context, contact)
        SecurityPreferences.setSmsMasterPassword(context, masterPassword)
    }

    private fun showTokenWalletSheetDialog(tokens: List<String>) {
        val context = requireContext()
        val sheetContent = StringBuilder()
            .append("UNCLE CARBON EMERGENCY WALLET SHEET\n")
            .append("Keep this single-use wipe token in your wallet/passport.\n")
            .append("Texting this line to this phone will request a Device Owner factory reset:\n\n")

        tokens.forEach { token ->
            sheetContent.append("• ").append(token).append("\n")
        }

        val textView = TextView(context).apply {
            text = sheetContent.toString()
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Emergency One-Time Codes")
            .setView(textView)
            .setPositiveButton("Copy All") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("UncleCarbon_OTC_Sheet", sheetContent.toString()))
                Toast.makeText(context, "Wallet sheet copied to clipboard.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .showProtected(requireActivity())
    }

    private fun showEmailCredentialsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_email_credentials, null)
        val etHost = dialogView.findViewById<TextInputEditText>(R.id.et_email_host_dialog)
        val etPort = dialogView.findViewById<TextInputEditText>(R.id.et_email_port_dialog)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.et_email_username_dialog)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.et_email_password_dialog)
        val switchSslTls = dialogView.findViewById<SwitchMaterial>(R.id.switch_enable_ssl_tls_dialog)

        val currentConfig = EmailSender.getEmailConfig(requireContext())
        etHost.setText(currentConfig?.host)
        etPort.setText(currentConfig?.port?.toString())
        etUsername.setText(currentConfig?.username)
        etPassword.setText(currentConfig?.password)
        switchSslTls.isChecked = currentConfig?.enableSslTls ?: true

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_email_credentials_title)
            .setView(dialogView)
            .setPositiveButton("Save") { dialog, _ ->
                val host = etHost.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull()
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString().trim()
                val enableSslTls = switchSslTls.isChecked

                if (host.isNotEmpty() && port != null && username.isNotEmpty() && password.isNotEmpty()) {
                    val config = EmailSender.EmailConfig(host, port, username, password, enableSslTls)
                    EmailSender.setEmailConfig(requireContext(), config)
                    Toast.makeText(requireContext(), "Email credentials saved.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.email_credentials_missing, Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .showProtected(requireActivity())
    }

    private fun sendTestEmail() {
        val recipient = SecurityPreferences.getEmergencyContact(requireContext())
        if (recipient.isNullOrEmpty() || !recipient.contains("@")) {
            Toast.makeText(requireContext(), "Set a valid email in 'Emergency Contact' first.", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(requireContext(), "Sending test email...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val success = EmailSender.sendEmail(
                requireContext(),
                recipient,
                "Uncle Carbon Test Email",
                "This is an authenticated test email from your Uncle Carbon defense suite."
            )
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(requireContext(), getString(R.string.email_sending_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.email_sending_failed, "Check logs for details."), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
