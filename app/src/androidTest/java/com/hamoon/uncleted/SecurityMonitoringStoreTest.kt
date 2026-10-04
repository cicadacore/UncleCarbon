package com.hamoon.uncleted

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hamoon.uncleted.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityMonitoringStoreTest {
    @Test fun baselineAndAuditSurviveRepositoryRecreationAndDuplicateEventsAreIgnored() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("security-monitor-store-test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first = SecurityMonitoringStore(prefs)
        val pkg = MonitoredPackage("org.example.app", "Example", "2", 2, 1, 2, true,
            setOf("android.permission.INTERNET"), true, true, "digest", setOf("digest"))
        val event = MonitoringAuditEvent("id-1", "APP_UPDATED", 5, MonitoringSeverity.INFORMATIONAL,
            "Application updated", pkg.packageName, pkg.label, "Test event", "test")
        assertEquals(listOf(event), first.updatePackageAndEvents(pkg, listOf(event)))
        assertTrue(first.updatePackageAndEvents(pkg, listOf(event)).isEmpty())
        first.markNotificationStatus(event.id, true)

        val recreated = SecurityMonitoringStore(prefs)
        assertEquals(pkg, recreated.packageSnapshot().getValue(pkg.packageName))
        assertEquals(listOf(event.copy(notificationSent = true)), recreated.events())
        prefs.edit().clear().commit()
    }
}
