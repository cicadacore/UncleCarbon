# Security hardening review

## Audit history and Direct Boot

`SecurityPreferences.logEvent()` now accepts a `SecurityEvent` enum, not arbitrary
text. All callers use fixed messages without phone numbers, addresses, coordinates,
MAC addresses, command text, tokens, notification bodies, email excerpts, or secrets.
No identifier correlation is needed in the audit trail.

History is stored in `security_event_history.xml` in **credential-encrypted (CE)**
SharedPreferences, explicitly resolved from the default package context even when
a caller holds a device-protected context. Android FBE provides its at-rest CE
protection. The log store does not use the operational preferences' DE fallback.
Before first unlock (BFU), messages are discarded, without queuing verbose text or
adding any DE audit state. Reads return an empty list. Logging, cleanup, or CE
access errors cannot interrupt subsequent detector actions. The store rechecks
unlock state on every operation and serializes concurrent history updates.

Both diagnostic export entry points and the live history view use the fixed CE
audit history. Raw logcat collection was removed: arbitrary exception/library
messages and older buffered records cannot be reliably sanitized by regex.
Diagnostic files require unlock and an explicit CE directory. The remaining
environment report describes the OS/build and admin status, not device serials,
SIM identifiers, locations, or credentials. Sensitive source-side logcat messages
were also removed from SMS, email, BLE, zone, geofence, and shared action paths.

## App lock

The main activity and evidence gallery now use the same fail-closed gate:

| Condition | Outcome |
| --- | --- |
| Lock disabled, preference successfully read | Allow |
| Lock enabled, permitted authenticator available | Wait for a successful callback |
| Lock enabled, no permitted authenticator | Close protected activity |
| Failure, cancellation, subsystem error, unreadable settings | Close protected activity |

Both capability detection and `BiometricPrompt` use
`BIOMETRIC_WEAK | DEVICE_CREDENTIAL`. A device credential can therefore satisfy
the capability check when biometrics are unenrolled or unavailable, but still
requires authentication. No app PIN was introduced. Existing biometric failure
counters and lockout security actions remain.

Authorization is not serialized. Pausing hides protected content, revokes an
existing grant, and closes protected dialogs; returning requires another check.
The gate retains a pending prompt across configuration changes and allows the
system credential activity to run while access remains denied. AndroidX callbacks
are rebound in `onCreate`, following its [prompt lifecycle guidance](https://developer.android.com/reference/androidx/biometric/BiometricPrompt).
Restored content fragments are removed before their views can be created; the
authentication fragment and credential result state are preserved. Fresh process
instances start locked. Denial is terminal and ignores later success callbacks.
Protected windows disallow screenshots/task snapshots.

App-lock preferences use strict encrypted CE access. A cryptographic/storage
failure cannot become a default `false` in DE. A legacy DE `true` value still
requires authentication until an explicit preference change successfully retires
that copy.

## AntiRollbackManager decision and migration

**Removed.** Its only callers were master-key access and successful encryption in
`StrongBoxSecurityManager`. The check compared `hardware_rpmb_monotonic_counter`
in DE SharedPreferences with the DE file `rpmb_rollback_anchor.bin`. Both belong
to restorable userdata. Restoring them together evades the check; independent
write failure or file loss could instead cause a spurious lockdown. There was no
independent freshness boundary worth retaining as a consistency detector.

The obsolete counter and AtomicFile artifacts (`.bin`, `.bak`, `.new`) are removed
idempotently by the existing startup/boot preference migration. Missing, corrupt,
or stale values never cause a wipe, quarantine, key replacement, or lockdown.
Cleanup failures leave inert data for a later retry. No key aliases, ciphertexts,
token hashes, Shard A values, or Shard B logic are changed. No direct boot receiver,
SIM/Spectral decision state machine, tripwire schedule, or Device Owner wipe
implementation is removed.

Existing legacy DE event history is **deleted, not imported**. Recognized DE
diagnostic reports are also deleted from the dedicated directory or legacy files
root. Existing operational state remains. Deletion is a logical filesystem
deletion, not a claim of secure erasure of historical flash blocks or snapshots.

**No verified app-data snapshot rollback protection exists.** AES-GCM protects
Shard A ciphertext integrity and Keystore isolates its wrapping key; neither
proves freshness. Shard B remains volatile and required for reconstruction, but
is not a monotonic counter. The optional hidden-API `setRollbackResistant` request
was retained without changing existing key behavior; support is not guaranteed
or attested, and even a successful request does not bind app-data versions.
One-time-token hashes remain subject to userdata snapshot restoration; this
change does not claim to fix that separate limitation.

## Operational DE state intentionally retained

| Existing state | Why it remains available before unlock |
| --- | --- |
| Protection/maintenance flags, wipe preferences and thresholds, USB/admin policy posture, authentication failure counts | Select and enforce the configured security action during Direct Boot. |
| SIM baseline fingerprint, physical-SIM presence, pending verification hints, in-flight wipe latch | Continue removal/replacement detection across restarts. The existing fingerprint is not a new keyed audit identifier. |
| Spectral enablement, RF confirmation policy, thresholds, action, quarantine status and elapsed/boot references | Continue RF isolation confirmation and quarantine decisions without CE. |
| Tripwire enablement, duration and last check-in | Recreate the deadline and detect expiry during downtime. |
| BLE target MAC, RSSI/breach thresholds and encrypted Shard A | Reconnect to the configured peripheral and enforce proximity separation. The operational MAC is still identifying; it is no longer logged. |
| Wipe-zone coordinates, names and geometry | Evaluate configured geographic triggers before unlock. Exact geometry remains sensitive operational configuration, never audit text. |
| Emergency phone/email contact and existing plaintext BFU SMS password; cleartext-command opt-in | Existing remote-command authorization and alert delivery in BFU. These pre-existing sensitive values were not moved or duplicated by this change; replacing their storage needs a separate compatible credential migration. |
| Active one-time-token hashes; key-deletion and StrongBox status flags | Validate/burn recovery tokens and deny key access after key deletion. No plaintext tokens are added. |
| Camera/recording configuration and tile action selectors | Preserve existing BFU emergency responses. |

The operational `SecurityPreferences.getInstance()` DE fallback remains for
existing non-audit callers. Consequently this is not a claim that *all* app data
in DE is minimized or all existing secrets are removed. The new history and
app-lock paths never use that fallback, and this change adds no plaintext DE secrets.

## Validation

- Added 15 JVM tests in `SecurityEventHistoryTest` and `AppLockGateTest`: BFU
  discard/read isolation, sensitive legacy-log removal, preservation of detector
  state, continued dispatch after logging failure, CE persistence and relock,
  CE-failure behavior, the fixed event API, all authentication outcomes,
  credential-only availability, background reauthentication, recreation and stale callbacks.
- Added three Android instrumentation tests in `SecurityStorageTest`: real CE/DE
  context separation and preference storage (including a simulated locked state),
  idempotent obsolete-state cleanup preserving security data, and prompt-mask parity.
- `testDebugUnitTest`: 128 tests, zero failures/errors; 127 passed and one existing
  symlink test skipped because the host does not support its symlink operation.
- `assembleDebug` and `assembleDebugAndroidTest`: passed with the installed Java 17 runtime.
- `lintDebug`: fails with **166 existing errors**. A separate clean `HEAD` build
  reports the same error signatures. No lint baseline or suppression was added.
  Current warnings include synchronous SharedPreferences deletion, deliberately
  used to remove old sensitive records before returning, and existing UI/dependency warnings.
- No emulator/device was connected (`adb devices -l` was empty). Instrumentation
  tests were compiled, not executed. Physical reboot/BFU storage inspection and
  real biometric/device-credential lifecycle behavior still require a test device.

On-device follow-up: verify a cold boot discards history while SIM/Spectral/tripwire
decisions continue, then unlock and confirm CE events/export. Exercise biometric
success/failure/cancel, credential-only enrollment, unavailable hardware, missing
credentials, rotation during a prompt, Home/return with a dialog open, and process
recreation. Use a dedicated fixture for destructive-action checks.

## Changed files

Paths below are relative to `app/src/main/java/com/hamoon/unclecarbon/` unless shown otherwise.
(The changes predate the package rename from `com.hamoon.uncleted`.)

| File(s) | Security change |
| --- | --- |
| `data/SecurityPreferences.kt` | Replaces DE history access; strict CE app-lock preferences and legacy lock-state preservation; hooks cleanup into existing migration. |
| `data/SecurityEventHistory.kt`, `data/SecurityEvent.kt`, `util/EventLogger.kt` | CE-only best-effort history and fixed-message logging API. |
| `MainActivity.kt`, `util/AppLockActivity.kt`, `util/AppLockGate.kt` | Removes unavailable-biometric success path; lifecycle-aware gate and protected dialog handling. |
| `util/BiometricAuthManager.kt` | Matches capability/prompt masks; explicit failure/cancellation/error results; removes shadowed preference extensions. |
| `EvidenceGalleryActivity.kt` | Applies app lock to gallery returns/recreation and dismisses media/confirmation windows when paused. |
| `fragments/AuthenticationFragment.kt` | Does not silently accept a failed app-lock preference write. |
| `fragments/DestructionProtocolsFragment.kt`, `fragments/HardwareSentinelsFragment.kt`, `fragments/PermissionsFragment.kt`, `fragments/ProximityTripwireFragment.kt`, `fragments/RemoteSignalingFragment.kt`, `fragments/SurveillanceFragment.kt` | Keeps separate settings/token/credential dialog windows behind the app-lock lifecycle. |
| `util/DiagnosticLogCollector.kt`, `util/LogcatManager.kt`, `fragments/DiagnosticsFragment.kt`, `app/src/main/res/layout/fragment_diagnostics.xml` | Exports/views CE audit history instead of potentially sensitive raw logcat; labels match available diagnostics. |
| `util/StorageLayout.kt` | Requires CE and unlock for diagnostics; deletes recognized legacy DE reports. |
| `crypto/AntiRollbackManager.kt` (deleted), `crypto/CryptoPreferences.kt` | Removes misleading two-copy rollback mechanism and safely cleans its obsolete state. |
| `crypto/StrongBoxSecurityManager.kt` | Removes redundant counter dependencies; documents limits of GCM/Keystore/optional rollback request; uses fixed audit messages. |
| `receivers/SmsCommandReceiver.kt`, `services/NotificationCommandListener.kt` | Removes sender and command interpolation from audit/logcat paths. |
| `util/AdvancedEmailSender.kt`, `util/EmailSender.kt`, `workers/WatchdogWorker.kt` | Removes email/SMS recipient data, email excerpts and potentially identifying delivery exceptions from logs; email success is recorded only after delivery. |
| `proximity/BleProximitySentinel.kt`, `util/GeofenceHelper.kt`, `receivers/GeofenceBroadcastReceiver.kt`, `services/ZoneWipeService.kt` | Removes MAC, coordinates and zone names from audit/logcat messages. |
| `services/PanicActionService.kt`, `core/strategies/DeviceOwnerStrategy.kt`, `util/DeviceAdminHelper.kt` | Removes raw alert text and free-form trigger reasons from logs without changing action dispatch. |
| `proximity/ProximityShardingEngine.kt`, `receivers/AdminReceiver.kt`, `receivers/TripwireReceiver.kt`, `sentinels/AdvancedBasebandSentinel.kt`, `sentinels/BasebandDowngradeSentinel.kt`, `sentinels/SpectralSentinel.kt`, `services/FakeAirplaneTileService.kt`, `services/PowerButtonService.kt`, `sim/SimMonitor.kt`, `util/ThreatDetectionEngine.kt`, `util/TripwireManager.kt` | Converts remaining audit callers to the fixed schema; detector/wipe logic is unchanged. |
| `app/src/test/.../data/SecurityEventHistoryTest.kt`, `app/src/test/.../util/AppLockGateTest.kt`, `app/src/androidTest/.../SecurityStorageTest.kt` | Regression coverage described above. |
| `README.md`, `docs/security-hardening.md` | Removes false hardware-counter claims and documents migration, retained DE data, validation and limits. |
