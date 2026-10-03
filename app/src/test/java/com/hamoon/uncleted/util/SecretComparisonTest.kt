package com.hamoon.uncleted.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [SecretComparison.constantTimeEquals].
 *
 * These lock in the equality semantics of the constant-time authentication
 * comparison so a future change cannot silently alter which secrets are
 * accepted. Pure JVM logic, no Android framework dependencies.
 */
class SecretComparisonTest {

    @Test fun equalStrings_returnTrue() {
        assertTrue(SecretComparison.constantTimeEquals("hunter2", "hunter2"))
    }

    @Test fun unequalStrings_returnFalse() {
        assertFalse(SecretComparison.constantTimeEquals("hunter2", "hunter3"))
    }

    @Test fun differentLengths_returnFalse() {
        assertFalse(SecretComparison.constantTimeEquals("short", "shorter"))
    }

    @Test fun emptyStrings_areEqual() {
        assertTrue(SecretComparison.constantTimeEquals("", ""))
    }

    @Test fun emptyVsNonEmpty_returnFalse() {
        assertFalse(SecretComparison.constantTimeEquals("", "x"))
        assertFalse(SecretComparison.constantTimeEquals("x", ""))
    }

    @Test fun nulls_neverMatch() {
        assertFalse(SecretComparison.constantTimeEquals(null, "x"))
        assertFalse(SecretComparison.constantTimeEquals("x", null))
        assertFalse(SecretComparison.constantTimeEquals(null, null))
        // A null secret must not match an empty provided value either.
        assertFalse(SecretComparison.constantTimeEquals(null, ""))
        assertFalse(SecretComparison.constantTimeEquals("", null))
    }

    @Test fun nonAsciiUtf8_equalValuesMatch() {
        // Multi-byte UTF-8: emoji, accented Latin, Cyrillic, CJK.
        val secret = "pÅss–wörd🔐Пароль密码"
        assertTrue(SecretComparison.constantTimeEquals(secret, secret))
        assertTrue(SecretComparison.constantTimeEquals(secret, "pÅss–wörd🔐Пароль密码"))
    }

    @Test fun nonAsciiUtf8_differentValuesDoNotMatch() {
        assertFalse(SecretComparison.constantTimeEquals("Пароль", "пароль")) // case differs
        assertFalse(SecretComparison.constantTimeEquals("密码", "密碼"))        // second glyph differs
    }

    @Test fun caseSensitive_noNormalization() {
        assertFalse(SecretComparison.constantTimeEquals("Secret", "secret"))
    }

    @Test fun whitespace_notTrimmed() {
        assertFalse(SecretComparison.constantTimeEquals(" secret", "secret"))
        assertFalse(SecretComparison.constantTimeEquals("secret ", "secret"))
    }
}
