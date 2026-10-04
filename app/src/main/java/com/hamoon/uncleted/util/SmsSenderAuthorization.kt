package com.hamoon.uncleted.util

/**
 * Pure, framework-free sender authorization for cleartext SMS remote commands.
 *
 * The SMS command channel can trigger destructive actions (including WIPE), so
 * a sender is authorized ONLY when the incoming originating address and the
 * configured emergency contact both canonicalize to a full E.164 number and
 * those two canonical strings are exactly equal. There is deliberately no
 * suffix, national-significant-number, area-code or other fuzzy matching, and
 * every input that cannot be canonicalized fails closed.
 *
 * Region-aware parsing of locally formatted numbers (e.g. a UK `07…` number)
 * is delegated to an injected formatter so this object stays Android-free and
 * unit-testable. In production that formatter is
 * `android.telephony.PhoneNumberUtils.formatNumberToE164`.
 */
object SmsSenderAuthorization {

    /** Strict E.164: '+', a non-zero leading digit, 7..15 digits in total. */
    private val E164_PATTERN = Regex("^\\+[1-9][0-9]{6,14}$")

    /** Visual separators commonly typed or delivered inside a phone number. */
    private const val SEPARATORS = " \t-.()/ "

    /** Upper bound on digits accepted before formatting (E.164 max 15 + IDD prefix). */
    private const val MAX_DIALABLE_DIGITS = 20

    /**
     * Returns true only if [incomingAddress] and [trustedContact] are the same
     * full phone number after canonicalization to E.164.
     *
     * @param regionIso ISO 3166-1 alpha-2 region used to interpret numbers
     *   written without a '+' country code; `null` means unknown, in which case
     *   such numbers cannot be canonicalized and authorization fails.
     * @param formatter converts a dialable number to E.164 for a region, or
     *   returns `null` if it is not a valid number there.
     */
    fun isAuthorized(
        incomingAddress: String?,
        trustedContact: String?,
        regionIso: String?,
        formatter: (dialableNumber: String, regionIso: String?) -> String?
    ): Boolean {
        // An email-valued emergency contact can never authenticate an SMS sender.
        if (trustedContact != null && trustedContact.contains('@')) return false

        val canonicalIncoming = toCanonicalE164(incomingAddress, regionIso, formatter) ?: return false
        val canonicalTrusted = toCanonicalE164(trustedContact, regionIso, formatter) ?: return false
        return canonicalIncoming == canonicalTrusted
    }

    /**
     * Canonicalizes [raw] to E.164, or returns `null` if that cannot be done
     * safely (blank, alphanumeric sender ID, stray symbols, unknown region for
     * a local-format number, or a result that is not strict E.164).
     */
    fun toCanonicalE164(
        raw: String?,
        regionIso: String?,
        formatter: (dialableNumber: String, regionIso: String?) -> String?
    ): String? {
        // Screen BEFORE handing anything to the formatter: libphonenumber maps
        // letters to keypad digits (vanity numbers), which must never let an
        // alphanumeric sender ID become an authenticating phone number.
        val dialable = toDialableOrNull(raw) ?: return null

        val formatted = try {
            formatter(dialable, normalizeRegionIso(regionIso))
        } catch (_: Exception) {
            null
        }
        if (formatted != null && E164_PATTERN.matches(formatted)) return formatted

        // A number already written in full international form is exact without
        // any region context, even if the formatter's metadata rejects it.
        return if (E164_PATTERN.matches(dialable)) dialable else null
    }

    /**
     * Returns an upper-case ISO 3166-1 alpha-2 region code, or `null` if
     * [regionIso] is not one (blank, wrong length, non-letters, or the
     * libphonenumber "unknown region" code `ZZ`).
     */
    fun normalizeRegionIso(regionIso: String?): String? {
        val region = regionIso?.trim()?.uppercase() ?: return null
        if (region.length != 2 || !region.all { it in 'A'..'Z' } || region == "ZZ") return null
        return region
    }

    /**
     * Strips visual separators, returning an optional leading '+' followed by
     * ASCII digits. Any other character (letters, '@', '*', '#', ',', ';', a
     * non-leading '+', ...) makes the whole value unusable and yields `null`.
     * Non-ASCII decimal digits (e.g. Persian/Arabic-Indic) are mapped to ASCII.
     */
    internal fun toDialableOrNull(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null

        val out = StringBuilder(trimmed.length)
        for ((index, ch) in trimmed.withIndex()) {
            when {
                ch == '+' && index == 0 -> out.append('+')
                ch in SEPARATORS -> Unit
                else -> {
                    val digit = Character.digit(ch, 10)
                    if (digit < 0) return null
                    out.append('0' + digit)
                }
            }
        }

        val digitCount = if (out.startsWith('+')) out.length - 1 else out.length
        if (digitCount == 0 || digitCount > MAX_DIALABLE_DIGITS) return null
        return out.toString()
    }
}
