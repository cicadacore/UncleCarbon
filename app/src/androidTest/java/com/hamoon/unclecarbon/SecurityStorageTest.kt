package com.hamoon.unclecarbon

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hamoon.unclecarbon.crypto.CryptoPreferences
import com.hamoon.unclecarbon.data.SecurityEvent
import com.hamoon.unclecarbon.data.SecurityEventHistory
import com.hamoon.unclecarbon.data.SecurityPreferences
import com.hamoon.unclecarbon.util.BiometricAuthManager
import com.hamoon.unclecarbon.util.StorageLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecurityStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun deviceProtectedCallerResolvesCredentialHistoryAndDiagnostics() {
        val de = context.createDeviceProtectedStorageContext()
        val ce = SecurityPreferences.requireCredentialStorageContext(de)
        assertFalse(ce.isDeviceProtectedStorage)
        assertSame(context.applicationContext, ce)
        assertEquals(context.filesDir.canonicalFile, ce.filesDir.canonicalFile)
        assertEquals(context.filesDir.canonicalFile, StorageLayout.diagnosticsDir(de).parentFile!!.canonicalFile)
        val name = "audit_test_${UUID.randomUUID()}"
        val cePrefs = ce.getSharedPreferences(name, Context.MODE_PRIVATE)
        val dePrefs = de.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            var unlocked = false
            dePrefs.edit().putStringSet("event_log", setOf("+447700900123 user@example.com aa:bb:cc:dd:ee:ff")).commit()
            dePrefs.edit().putBoolean("BFU_TRIPWIRE_ENABLED", true).commit()
            val history = SecurityEventHistory({ unlocked }, { cePrefs }, { dePrefs })
            history.append(SecurityEvent.TRIPWIRE_EXPIRED)
            assertTrue(history.read().isEmpty())
            assertFalse(dePrefs.contains("event_log"))
            assertTrue(dePrefs.getBoolean("BFU_TRIPWIRE_ENABLED", false))
            unlocked = true
            history.append(SecurityEvent.SMS_AUTH_FAILED)
            assertTrue(cePrefs.edit().commit()) // Flush before checking persisted state.
            assertTrue(cePrefs.getStringSet("event_log", emptySet())!!.single().endsWith(SecurityEvent.SMS_AUTH_FAILED.message))
            assertFalse(dePrefs.contains("event_log"))
            unlocked = false
            assertTrue(history.read().isEmpty())
        } finally {
            ce.deleteSharedPreferences(name)
            de.deleteSharedPreferences(name)
        }
    }

    @Test fun credentialContextCanOpenEncryptedPreferencesFromADeviceProtectedCaller() {
        val de = context.createDeviceProtectedStorageContext()
        val ce = SecurityPreferences.requireCredentialStorageContext(de)
        val name = "encrypted_context_test_${UUID.randomUUID()}"
        val keyAlias = "encrypted_context_key_${UUID.randomUUID()}"
        try {
            val masterKey = MasterKey.Builder(ce, keyAlias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                ce,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            assertTrue(prefs.edit().putBoolean("first_install", true).commit())
            assertTrue(prefs.getBoolean("first_install", false))
        } finally {
            ce.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(keyAlias)) deleteEntry(keyAlias)
            }
        }
    }

    @Test fun firstInstallAppLockPreferenceReadsAsDisabledFromADeviceProtectedCaller() {
        // MainActivity holds a regular activity context, but this also covers the
        // direct-boot-derived context that exposed the null application-context
        // bug in the field. A missing preference is the explicit first-install
        // disabled state; it must not be interpreted as an authentication error.
        assertFalse(SecurityPreferences.isBiometricLockEnabled(
            context.createDeviceProtectedStorageContext()
        ))
    }

    @Test fun obsoleteCounterRemovalPreservesSecurityFlagsAndShardData() {
        val de = context.createDeviceProtectedStorageContext()
        val name = "rollback_cleanup_test_${UUID.randomUUID()}"
        val root = File(de.filesDir, name).apply { mkdirs() }
        val prefs = de.getSharedPreferences(name, Context.MODE_PRIVATE)
        val testContext = object : ContextWrapper(de) {
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        try {
            prefs.edit().putLong("hardware_rpmb_monotonic_counter", Long.MAX_VALUE)
                .putBoolean("cryptographic_suicide_executed", true)
                .putBoolean("allow_cleartext_sms", false).commit()
            listOf("", ".bak", ".new").forEach {
                File(root, "rpmb_rollback_anchor.bin$it").writeText("obsolete")
            }
            val shard = File(root, "sealed-shard-test.bin").apply { writeText("preserve") }
            repeat(2) { CryptoPreferences.removeObsoleteRollbackState(testContext) }
            assertFalse(prefs.contains("hardware_rpmb_monotonic_counter"))
            assertTrue(CryptoPreferences.isSuicideExecuted(testContext))
            assertFalse(CryptoPreferences.isCleartextSmsAllowed(testContext))
            assertEquals("preserve", shard.readText())
            assertEquals(listOf(shard.name), root.listFiles()!!.map { it.name })
        } finally {
            root.listFiles()?.forEach { it.delete() }
            root.delete()
            de.deleteSharedPreferences(name)
        }
    }

    @Test fun promptUsesTheSameCombinedAuthenticatorMaskAsCapabilityDetection() {
        val expected = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(expected, BiometricAuthManager.ALLOWED_AUTHENTICATORS)
        val prompt = BiometricAuthManager.promptInfo("App authentication", "Confirm identity")
        assertEquals(expected, prompt.allowedAuthenticators)
    }
}
