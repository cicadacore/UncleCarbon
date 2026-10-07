package com.hamoon.unclecarbon.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SecurityEventHistoryTest {
    private class MemoryPrefs {
        val data = mutableMapOf<String, Any?>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putStringSet" -> { data[args!![0] as String] = (args[1] as Set<*>).toSet(); proxy }
                "remove" -> { data.remove(args!![0] as String); proxy }
                "commit" -> true
                "apply" -> null
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "contains" -> data.containsKey(args!![0])
                "getStringSet" -> data[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun bfuDiscardsLegacySensitiveHistoryWithoutOpeningCredentialStorage() {
        val de = MemoryPrefs()
        de.data["event_log"] = setOf("SMS +447700900123 secret=pass; aa:bb:cc:dd:ee:ff; user@example.com")
        de.data["BFU_SIM_PENDING_ABSENT_HINT"] = true
        val history = SecurityEventHistory({ false }, { error("CE accessed before unlock") }, { de.prefs })
        history.append(SecurityEvent.SMS_SENDER_REJECTED)
        assertTrue(history.read().isEmpty())
        assertFalse(de.data.containsKey("event_log"))
        assertEquals(mapOf("BFU_SIM_PENDING_ABSENT_HINT" to true), de.data)
    }

    @Test fun bfuAuditFailureDoesNotInterruptSecurityAction() {
        val history = SecurityEventHistory({ false }, { error("CE accessed") }, { throw IllegalStateException() })
        val calls = mutableListOf<String>()
        fun securityAction() {
            history.append(SecurityEvent.WIPE_REQUESTED)
            calls.add("wipe dispatched")
        }
        securityAction()
        assertEquals(listOf("wipe dispatched"), calls)
        assertTrue(history.read().isEmpty())
        history.clear()
    }

    @Test fun eventsAfterUnlockRemainOnlyInCredentialStorageAndRelockHidesThem() {
        val ce = MemoryPrefs()
        val de = MemoryPrefs()
        var unlocked = false
        val history = SecurityEventHistory({ unlocked }, { ce.prefs }, { de.prefs })
        history.append(SecurityEvent.SMS_AUTH_FAILED)
        unlocked = true
        history.append(SecurityEvent.SIM_REMOVED)
        assertEquals(1, history.read().size)
        assertTrue(history.read().single().endsWith(SecurityEvent.SIM_REMOVED.message))
        assertTrue(de.data.isEmpty())
        unlocked = false
        assertTrue(history.read().isEmpty())
        unlocked = true
        assertEquals(1, history.read().size)
        history.clear()
        assertTrue(history.read().isEmpty())
    }

    @Test fun credentialStorageFailureNeverFallsBackToDeviceStorage() {
        val de = MemoryPrefs()
        val history = SecurityEventHistory({ true }, { throw IllegalStateException("CE unavailable") }, { de.prefs })
        history.append(SecurityEvent.EMAIL_SENT)
        assertTrue(history.read().isEmpty())
        history.clear()
        assertTrue(de.data.isEmpty())
    }

    @Test fun loggingApiCannotAcceptFreeFormSensitiveText() {
        val append = SecurityEventHistory::class.java.declaredMethods.single { it.name == "append" }
        assertArrayEquals(arrayOf(SecurityEvent::class.java), append.parameterTypes)
        val ce = MemoryPrefs()
        val de = MemoryPrefs()
        val history = SecurityEventHistory({ true }, { ce.prefs }, { de.prefs })
        SecurityEvent.values().forEach { history.append(it) }
        assertEquals(SecurityEvent.values().size, history.read().size)
        assertTrue(de.data.isEmpty())
        history.read().forEach { line ->
            assertTrue(SecurityEvent.values().any { line.endsWith(" - ${it.message}") })
        }
    }

    @Test fun unreadableUnlockStateDropsEventsWithoutInterruptingTheCaller() {
        val de = MemoryPrefs()
        val history = SecurityEventHistory({ throw IllegalStateException() }, { error("CE accessed") }, { de.prefs })
        history.append(SecurityEvent.TRIPWIRE_EXPIRED)
        assertTrue(history.read().isEmpty())
    }
}
