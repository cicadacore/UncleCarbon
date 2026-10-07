# Lockdown Mode implementation and validation

Implemented 4 October 2026. Existing unrelated working-tree changes were preserved.

Paths below use the current `com.hamoon.unclecarbon` package. The feature was
implemented before the package rename from `com.hamoon.uncleted`; the rename did
not change its behaviour. See the [README](../README.md#lockdown-mode) for a summary.

## Existing enforcement paths

The Hardware Sentinels screen previously saved each preference, then called
`DefenseCoordinator.resolveStrategy(context)` and one of:

- `DeviceOwnerStrategy.setSafeBootBlocked(true)` → `applySafeBootPolicy()` →
  `DevicePolicyManager.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)`.
- `DeviceOwnerStrategy.setDeveloperFeaturesBlocked(true)` →
  `reconcileDebuggingFeaturesRestriction()` →
  `DevicePolicyManager.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)`.

This fork uses Device Owner APIs, with no root/Shizuku/ADB enforcement fallback.
Lockdown and the individual switches now share those same strategy methods.
The strategy reports missing Device Owner access and platform failures instead
of silently returning. Each change is read back before its preference is saved.
Already-applied restrictions are checked without repeating the privileged write.

Read-back includes local restrictions and, on Android 14+, global restrictions;
see the official [DevicePolicyManager API reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#getUserRestrictions(android.content.ComponentName)).
Developer protection retains the app's existing
[DISALLOW_DEBUGGING_FEATURES policy](https://developer.android.com/reference/android/os/UserManager#DISALLOW_DEBUGGING_FEATURES),
including its interaction with USB data-port protection.

## Activation, persistence, and lifecycle

The third Material card sits immediately below Developer Interception. Its
switch opens the requested confirmation dialog and stays OFF until confirmation
and successful enforcement. Cancel does not dispatch any protection action.

`LockdownController.activateLockdownMode()` applies Safe Boot first, then
Developer protection, and reads both restrictions back. Both actions are
attempted even if one fails. Only complete success permits saving Lockdown.
A successful individual restriction remains enabled after partial failure;
Lockdown stays inactive and the UI displays the actual failure. A later retry
can complete activation.

`SecurityPreferences.activateLockdown()` synchronously commits the one-way
`BFU_LOCKDOWN_ENABLED` flag and both protection flags together in the existing
device-protected SharedPreferences. The Lockdown flag is authoritative across
credential unlock and has no public OFF setter. Preference getters and setters
prevent stale OFF requests from overriding it. Failed disk commits are reported
and their unconfirmed in-memory Lockdown flag is removed.

The controller's mutex serializes activation, individual settings, and lifecycle
requests. A process-owned coroutine scope lets confirmed work finish across
Fragment recreation. StateFlow renders the latest verified state; click
listeners prevent programmatic rendering from dispatching more commands.
The strategy also guards individual restrictions and USB reconciliation under
a shared lock. Re-enabling USB cannot clear a Lockdown-required debugging block.
The general Save button no longer writes the Safe Boot switch's display state.

Application creation, returning to MainActivity, and the existing direct-boot
receiver reapply and verify active Lockdown. The receiver handles both
`LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` and uses `goAsync()` until enforcement
finishes. No credential-encrypted storage is required before first unlock.

Once latched, all three switches are non-interactive. Successfully verified
protections show **ON — LOCKED BY LOCKDOWN MODE**, and Lockdown shows **ACTIVE**.
If privileges disappear or enforcement fails, the latch remains one-way, but the
screen reports **LOCKDOWN REQUIRES ATTENTION** and displays failed/unverified
protections rather than claiming successful enforcement. Errors are also logged;
failed user actions and enforcement on MainActivity resume show a failure toast.

This is permanent from the normal app interface while the app retains its state
and Device Owner privileges. It does not guarantee survival of uninstall, data
clearing, factory reset, or removal of required privileges.

## Validation performed

- Debug APK assembled using the installed Java 17 toolchain and Gradle 8.4.
- `:app:testDebugUnitTest`: **146 tests passed**, including **18 new Lockdown tests**.
- All files edited for this feature were reviewed; `git diff --check` passed.
- `:app:lintDebug` was run. New text includes Persian translations. Remaining
  lint failures concern existing code/resources (for example MissingPermission
  in `BasebandDowngradeSentinel.kt` and existing missing translations/API checks).
  No lint errors reference the new Lockdown implementation or its new strings.
- `adb devices -l` returned no devices. No on-device UI, reboot, real Device Owner,
  or actual process-death test was executed. The unit tests use a fake platform
  enforcer and store; they do not establish Android hardware behavior or disk
  durability on a device.

Automated cases cover both protections initially OFF; each mixed starting state;
both already ON; order and read-back before commit; failure of either action;
silent platform no-op; missing privileges; persistence failure; retry after
partial success; ignored OFF requests; a new controller reusing saved state;
boot-style repair of either missing restriction; privilege loss and recovery;
idempotent repeated calls; an OFF request racing activation; independent normal
controls; and refresh without opting into Lockdown.

## Device acceptance checks still required

Use a dedicated test device provisioned as Device Owner, with the application's
existing destructive sentinels disabled. Developer protection disables debugging,
so plan to perform final checks on the device rather than relying on an ADB
connection after activation. Use separate clean test installs/devices for the
starting-state permutations; the normal UI intentionally cannot reset Lockdown.

1. Start with both protections OFF. Open Lockdown and Cancel; verify no change.
   Confirm ENABLE LOCKDOWN; verify both become ON/locked and Lockdown ACTIVE.
2. Repeat with Safe Boot only ON, then Developer only ON; verify the missing
   protection is automatically applied. Check Android's real restrictions.
3. Rotate during confirmation (no implicit activation) and during confirmed
   activation; verify no duplicate command effects, crashes, or unlocked controls.
4. Leave and reopen the screen, restart the app, and terminate/restart its
   process. Verify the latch remains and Android policies are verified/reapplied.
5. Reboot, including the period before first unlock; verify both policies and
   then verify the same locked state after unlock.
6. On a non-Device-Owner test installation, attempt activation. Verify the real
   privilege failure is shown and Lockdown remains inactive.
7. On a controlled test setup, revoke privileges or fail one policy operation.
   Verify no false ACTIVE result; an already-saved latch keeps controls locked.
8. Exercise the existing USB protection/re-enable path. Confirm Developer
   protection remains blocked under Lockdown.

## Files changed for this feature

| File | Change |
| --- | --- |
| `app/src/main/java/com/hamoon/unclecarbon/core/LockdownController.kt` | New serialized activation, verification, observable state, and OFF-request guards. |
| `app/src/main/java/com/hamoon/unclecarbon/core/LockdownManager.kt` | New application-scoped controller, dispatch, and failure reporting. |
| `app/src/main/java/com/hamoon/unclecarbon/core/DefenseStrategy.kt` | Shared protection-enforcement contract, including state read-back. |
| `app/src/main/java/com/hamoon/unclecarbon/core/strategies/DeviceOwnerStrategy.kt` | Reuses DPM operations, verifies policies, reports errors, guards Lockdown and USB reconciliation. |
| `app/src/main/java/com/hamoon/unclecarbon/data/SecurityPreferences.kt` | Durable device-protected latch and preference guards. |
| `app/src/main/java/com/hamoon/unclecarbon/data/SecurityEvent.kt` | Fixed audit event for enforcement failure. |
| `app/src/main/java/com/hamoon/unclecarbon/fragments/HardwareSentinelsFragment.kt` | Confirmation, shared actions, live verified/locked state, lifecycle refresh. |
| `app/src/main/java/com/hamoon/unclecarbon/UncleCarbonApplication.kt` | Startup and MainActivity-resume enforcement. |
| `app/src/main/java/com/hamoon/unclecarbon/receivers/BootCompletedReceiver.kt` | Direct-boot and post-unlock enforcement with receiver lifetime handling. |
| `app/src/main/java/com/hamoon/unclecarbon/receivers/AdminReceiver.kt` | Routes baseline protection changes through the shared guarded actions. |
| `app/src/main/res/layout/fragment_hardware_sentinels.xml` | Third Material card and protection status labels. |
| `app/src/main/res/values/strings.xml` | English confirmation, descriptions, and status/error text. |
| `app/src/main/res/values-fa/strings.xml` | Persian equivalents of all new strings. |
| `app/src/test/java/com/hamoon/unclecarbon/core/LockdownControllerTest.kt` | 18 regression tests. |
| `docs/lockdown-mode.md` | Implementation details, validation results, and remaining device checks. |

Build output: `app/build/outputs/apk/debug/app-debug.apk`.
Unit-test report: `app/build/reports/tests/testDebugUnitTest/index.html`.
Lint report: `app/build/reports/lint-results-debug.html`.
