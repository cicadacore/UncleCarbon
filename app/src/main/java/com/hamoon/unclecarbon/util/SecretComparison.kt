package com.hamoon.unclecarbon.util

import java.security.MessageDigest

/**
 * Shared helpers for comparing authentication secrets (e.g. the SMS master
 * password) without leaking timing information.
 */
object SecretComparison {

    /**
     * Constant-time equality check for authentication secrets.
     *
     * Compares the UTF-8 byte representations of [a] and [b] using
     * [MessageDigest.isEqual], which does not short-circuit on the first
     * differing byte, so the comparison time does not reveal how many leading
     * characters of a guess were correct.
     *
     * A `null` value represents an absent/unconfigured secret and never
     * matches. The inputs are compared exactly as given: no trimming,
     * case-folding, normalization, or hashing is performed.
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }
}
