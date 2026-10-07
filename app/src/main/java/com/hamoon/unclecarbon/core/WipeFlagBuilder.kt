package com.hamoon.unclecarbon.core

/**
 * Pure, framework-free composer for Device Owner wipe flags.
 *
 * Kept free of any Android types so the flag-composition rule is independently
 * unit-testable. The production call site passes the real
 * [android.app.admin.DevicePolicyManager] constants; this object only decides
 * which bits are combined, so there is a single place that answers "is the eUICC
 * erase bit included?" without duplicating the production wipe behaviour.
 */
object WipeFlagBuilder {

    /**
     * Returns [baseFlags] with [euiccFlag] OR-ed in when (and only when)
     * [eraseEsim] is true. The mandatory [baseFlags] are always preserved.
     */
    fun build(baseFlags: Int, euiccFlag: Int, eraseEsim: Boolean): Int =
        if (eraseEsim) baseFlags or euiccFlag else baseFlags
}
