package com.hamoon.uncleted.util

import android.os.Bundle
import android.app.Dialog
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.hamoon.uncleted.R
import com.hamoon.uncleted.data.SecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared by screens that expose settings, diagnostics, or evidence. */
abstract class AppLockActivity : AppCompatActivity() {
    class LockModel : ViewModel() {
        internal val gate = AppLockGate()
    }

    private lateinit var model: LockModel
    private var prompt: BiometricPrompt? = null
    private var checkJob: Job? = null
    private var foreground = false
    private var contentCreated = false
    private val protectedDialogs = mutableListOf<Dialog>()
    protected open val protectedFragmentContainerId: Int? = null
    protected val isAccessAllowed: Boolean
        get() = foreground && model.gate.state == AppLockGate.State.ALLOWED

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Preserve AndroidX's authentication fragment / credential activity result
        // across rotation, but discard restored protected content before onStart
        // can create its views. Authorization itself is never stored in a Bundle.
        super.onCreate(savedInstanceState)
        protectedFragmentContainerId?.let { containerId ->
            val restored = supportFragmentManager.fragments.filter { it.id == containerId }
            if (restored.isNotEmpty()) {
                supportFragmentManager.beginTransaction().apply {
                    restored.forEach { remove(it) }
                }.commitNow()
            }
        }
        model = ViewModelProvider(this)[LockModel::class.java]
        hideContent()
        try {
            prompt = BiometricAuthManager.createPrompt(this, object : BiometricAuthManager.AuthCallback {
                override fun onAuthResult(result: BiometricAuthManager.AuthResult, errorMessage: String?) {
                    if (isDestroyed || isFinishing || model.gate.state != AppLockGate.State.AUTHENTICATING) return
                    model.gate.authenticationResult(result)
                    if (model.gate.state == AppLockGate.State.DENIED) denyAccess()
                    else if (foreground) showProtectedContent()
                }
            })
        } catch (_: Exception) {
            // Disabled app lock does not depend on the authentication subsystem.
            // An enabled lock will deny when it cannot start/reconnect a prompt.
            if (model.gate.state == AppLockGate.State.AUTHENTICATING) denyAccess()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (isFinishing) return
        when (model.gate.state) {
            AppLockGate.State.DENIED -> denyAccess()
            AppLockGate.State.AUTHENTICATING -> Unit // Includes the system credential screen.
            AppLockGate.State.ALLOWED -> showProtectedContent()
            AppLockGate.State.LOCKED -> {
                checkJob = lifecycleScope.launch {
                    try {
                        val enabled = withContext(Dispatchers.IO) {
                            SecurityPreferences.isBiometricLockEnabled(this@AppLockActivity)
                        }
                        if (!foreground || isFinishing) return@launch
                        val available = !enabled || BiometricAuthManager.isAuthenticatorAvailable(this@AppLockActivity)
                        when (model.gate.begin(enabled, available)) {
                            AppLockGate.State.ALLOWED -> showProtectedContent()
                            AppLockGate.State.AUTHENTICATING -> BiometricAuthManager.authenticateUser(
                                checkNotNull(prompt), getString(R.string.biometric_auth_title),
                                getString(R.string.biometric_auth_subtitle)
                            )
                            else -> denyAccess()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        denyAccess()
                    }
                }
            }
        }
    }

    override fun onPause() {
        foreground = false
        checkJob?.cancel()
        model.gate.leaveForeground()
        hideContent()
        protectedDialogs.toList().forEach { it.cancel() }
        protectedDialogs.clear()
        super.onPause()
    }

    override fun onDestroy() {
        // AndroidX retains an in-flight prompt across a configuration change.
        if (!isChangingConfigurations) {
            model.gate.error()
            try { prompt?.cancelAuthentication() } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    private fun hideContent() {
        findViewById<View>(android.R.id.content)?.visibility = View.INVISIBLE
    }

    internal fun showProtectedDialog(dialog: Dialog) {
        if (!isAccessAllowed || isFinishing) return
        protectedDialogs.removeAll { !it.isShowing }
        protectedDialogs.add(dialog)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun showProtectedContent() {
        if (!isAccessAllowed || isFinishing || isDestroyed) return
        val unlocked = try { SecurityPreferences.isUserUnlocked(this) } catch (_: Exception) { false }
        if (!unlocked) {
            denyAccess()
            return
        }
        if (!contentCreated) {
            onProtectedCreate()
            contentCreated = true
        }
        findViewById<View>(android.R.id.content)?.visibility = View.VISIBLE
    }

    private fun denyAccess() {
        model.gate.error()
        hideContent()
        try { prompt?.cancelAuthentication() } catch (_: Exception) { }
        Toast.makeText(this, R.string.biometric_auth_failed_exit, Toast.LENGTH_SHORT).show()
        finish()
    }

    protected abstract fun onProtectedCreate()
}

/** Dialogs have separate windows and must close when the activity locks. */
fun MaterialAlertDialogBuilder.showProtected(activity: FragmentActivity): AlertDialog =
    create().also { dialog ->
        (activity as? AppLockActivity)?.showProtectedDialog(dialog)
    }
