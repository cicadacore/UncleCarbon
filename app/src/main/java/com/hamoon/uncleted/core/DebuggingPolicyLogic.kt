package com.hamoon.uncleted.core

/**
 * Pure decision logic for the effective `DISALLOW_DEBUGGING_FEATURES` Device
 * Owner restriction.
 *
 * Two independent controls can require debugging features to stay blocked:
 *  - the user's "Developer Interception" policy, and
 *  - an active USB data-port lockdown (the project severs USB and blocks
 *    debugging after repeated failed unlocks).
 *
 * The restriction must remain set while EITHER requires it, so neither control
 * can silently clear the other's policy. Framework-free so the rule is
 * unit-testable without an Android runtime.
 */
object DebuggingPolicyLogic {

    fun shouldBlockDebugging(
        developerFeaturesBlocked: Boolean,
        usbDataPortDisabled: Boolean
    ): Boolean = developerFeaturesBlocked || usbDataPortDisabled
}
