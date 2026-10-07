package com.hamoon.unclecarbon.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [SmsSenderAuthorization].
 *
 * These lock in exact full-number E.164 equality for the SMS command channel
 * (which can trigger WIPE): no suffix matching, no fuzzy matching, and fail
 * closed on anything that cannot be canonicalized. Pure JVM logic, no Android
 * framework dependencies.
 *
 * The production formatter is PhoneNumberUtils.formatNumberToE164, which is
 * unavailable on the JVM; [gbFormatter] stands in for it with GB numbering
 * rules only (trunk prefix '0' -> +44) and rejects every other region, so the
 * tests exercise the authorization logic rather than a phone-number library.
 * Numbers use the Ofcom drama range (07700 900xxx) reserved for fiction.
 */
class SmsSenderAuthorizationTest {

    private val gbFormatter: (String, String?) -> String? = { number, region ->
        when {
            number.startsWith("+") -> number
            region == "GB" && number.startsWith("0") && number.length == 11 -> "+44" + number.drop(1)
            else -> null
        }
    }

    /** A formatter that would turn anything into the trusted number. */
    private val promiscuousFormatter: (String, String?) -> String? = { _, _ -> "+447700900123" }

    private val noFormatter: (String, String?) -> String? = { _, _ -> null }

    private fun authorized(
        incoming: String?,
        trusted: String?,
        region: String? = "GB",
        formatter: (String, String?) -> String? = gbFormatter
    ) = SmsSenderAuthorization.isAuthorized(incoming, trusted, region, formatter)

    // --- Authorized: the exact same full number ---

    @Test fun identicalE164_authorized() {
        assertTrue(authorized("+447700900123", "+447700900123"))
    }

    @Test fun ukLocalFormat_vsE164_withGbContext_authorized() {
        assertTrue(authorized("+447700900123", "07700 900123", region = "GB"))
        assertTrue(authorized("07700900123", "+447700900123", region = "GB"))
    }

    @Test fun regionCode_isCaseInsensitive() {
        // TelephonyManager reports lower-case ISO codes.
        assertTrue(authorized("+447700900123", "07700900123", region = "gb"))
    }

    @Test fun formattedRepresentationOfSameNumber_authorized() {
        assertTrue(authorized("+447700900123", "+44 7700 900123"))
        assertTrue(authorized("+447700900123", "+44 (7700) 900-123"))
        assertTrue(authorized("+447700900123", "+44.7700.900.123"))
        assertTrue(authorized("+44 7700 900123", "+44-7700-900-123"))
    }

    @Test fun e164_authorizedWithoutAnyRegionOrFormatter() {
        // Full international numbers need no region context.
        assertTrue(authorized("+447700900123", "+447700900123", region = null, formatter = noFormatter))
        assertTrue(authorized("+447700900123", "+44 7700 900123", region = null, formatter = noFormatter))
    }

    @Test fun nonAsciiDecimalDigits_areCanonicalized() {
        // Persian digits, as a Persian-locale user may type the emergency contact.
        assertTrue(authorized("+447700900123", "+۴۴۷۷۰۰۹۰۰۱۲۳"))
    }

    // --- Rejected: different numbers, however similar ---

    @Test fun differentNumbersSharingTrailingDigits_rejected() {
        assertFalse(authorized("+447700900123", "+1555900123"))
        assertFalse(authorized("+1555900123", "+447700900123"))
    }

    @Test fun differentNumbersSharingLastSevenDigits_rejected() {
        // Both end in 0900123; the old takeLast(7) check accepted this.
        assertFalse(authorized("+447700900123", "+33140900123"))
        assertFalse(authorized("+447700900123", "+12020900123"))
    }

    @Test fun sameNationalNumber_differentCountryCode_rejected() {
        assertFalse(authorized("+447700900123", "+497700900123"))
    }

    @Test fun suffixOrPartialNumber_rejected() {
        assertFalse(authorized("+447700900123", "900123"))
        assertFalse(authorized("+447700900123", "7700900123", region = "GB"))
        assertFalse(authorized("+447700900123", "0900123"))
    }

    @Test fun localFormat_withoutRegion_rejected() {
        // No guessing: a local number with no known country cannot be compared.
        assertFalse(authorized("+447700900123", "07700900123", region = null))
        assertFalse(authorized("+447700900123", "07700900123", region = ""))
        assertFalse(authorized("+447700900123", "07700900123", region = "ZZ"))
    }

    @Test fun localFormat_interpretedInDifferentRegion_rejected() {
        assertFalse(authorized("+447700900123", "07700900123", region = "FR"))
    }

    @Test fun localFormatOnBothSides_withoutRegion_rejected() {
        // Even identical local strings must not match without canonicalization.
        assertFalse(authorized("07700900123", "07700900123", region = null))
    }

    // --- Rejected: malformed / non-phone inputs ---

    @Test fun malformedSender_rejected() {
        assertFalse(authorized("++447700900123", "+447700900123"))
        assertFalse(authorized("44+7700900123", "+447700900123"))
        assertFalse(authorized("+", "+447700900123"))
        assertFalse(authorized("+0447700900123", "+447700900123"))
        assertFalse(authorized("+447700900123#", "+447700900123"))
        assertFalse(authorized("*447700900123", "+447700900123"))
        assertFalse(authorized("+447700900123,1", "+447700900123"))
        assertFalse(authorized("+44770090012345678901", "+447700900123"))
    }

    @Test fun alphanumericSenderId_rejected() {
        assertFalse(authorized("UNCLECARBON", "+447700900123"))
        assertFalse(authorized("Vodafone", "+447700900123"))
        assertFalse(authorized("+44 7700 9OO123", "+447700900123")) // letter O, not zero
    }

    @Test fun alphanumericSenderId_neverReachesFormatter() {
        // A phone-number library may convert letters to keypad digits; the
        // screen must reject alphanumeric IDs before any formatter runs.
        assertFalse(authorized("UNCLECARBON", "+447700900123", formatter = promiscuousFormatter))
        assertFalse(authorized("1-800-UNCLECARBON", "+447700900123", formatter = promiscuousFormatter))
    }

    @Test fun identicalAlphanumericValues_rejected() {
        assertFalse(authorized("UNCLECARBON", "UNCLECARBON"))
    }

    @Test fun emailEmergencyContact_rejected() {
        assertFalse(authorized("+447700900123", "owner@example.com"))
        assertFalse(authorized("+447700900123", "+447700900123@sms.example.com"))
        assertFalse(authorized("owner@example.com", "owner@example.com"))
    }

    @Test fun emailEmergencyContact_rejectedEvenByPromiscuousFormatter() {
        assertFalse(authorized("+447700900123", "owner@example.com", formatter = promiscuousFormatter))
    }

    @Test fun nullOrBlank_rejected() {
        assertFalse(authorized(null, "+447700900123"))
        assertFalse(authorized("+447700900123", null))
        assertFalse(authorized(null, null))
        assertFalse(authorized("", "+447700900123"))
        assertFalse(authorized("+447700900123", ""))
        assertFalse(authorized("   ", "+447700900123"))
        assertFalse(authorized("+447700900123", "   "))
        assertFalse(authorized("", ""))
    }

    // --- Formatter misbehaviour fails closed ---

    @Test fun formatterThrowing_fallsBackToStrictE164Only() {
        val throwing: (String, String?) -> String? = { _, _ -> throw IllegalStateException("boom") }
        assertTrue(authorized("+447700900123", "+44 7700 900123", formatter = throwing))
        assertFalse(authorized("+447700900123", "07700900123", formatter = throwing))
    }

    @Test fun formatterReturningNonE164_isIgnored() {
        val garbage: (String, String?) -> String? = { number, _ -> number.filter { it.isDigit() } }
        assertFalse(authorized("+447700900123", "07700900123", formatter = garbage))
        // Explicit international numbers still compare exactly.
        assertTrue(authorized("+447700900123", "+447700900123", formatter = garbage))
    }

    // --- Canonicalization details ---

    @Test fun toCanonicalE164_examples() {
        assertEquals("+447700900123", SmsSenderAuthorization.toCanonicalE164("+44 7700 900123", null, noFormatter))
        assertEquals("+447700900123", SmsSenderAuthorization.toCanonicalE164("07700 900123", "GB", gbFormatter))
        assertNull(SmsSenderAuthorization.toCanonicalE164("07700 900123", null, gbFormatter))
        assertNull(SmsSenderAuthorization.toCanonicalE164("UNCLECARBON", "GB", promiscuousFormatter))
        assertNull(SmsSenderAuthorization.toCanonicalE164("+123456", null, noFormatter)) // too short for E.164
    }

    @Test fun normalizeRegionIso_examples() {
        assertEquals("GB", SmsSenderAuthorization.normalizeRegionIso("gb"))
        assertEquals("GB", SmsSenderAuthorization.normalizeRegionIso(" GB "))
        assertNull(SmsSenderAuthorization.normalizeRegionIso(null))
        assertNull(SmsSenderAuthorization.normalizeRegionIso(""))
        assertNull(SmsSenderAuthorization.normalizeRegionIso("GBR"))
        assertNull(SmsSenderAuthorization.normalizeRegionIso("4G"))
        assertNull(SmsSenderAuthorization.normalizeRegionIso("zz"))
    }
}
