# UncleCarbon

**Device Owner security controls for GrapheneOS.**

UncleCarbon is an Android security app designed primarily for
[GrapheneOS](https://grapheneos.org/) on supported Pixel devices. It runs as the
device's **Android Device Owner** and uses documented `DevicePolicyManager`
APIs, plus ordinary app permissions, to add policy enforcement, monitoring and
defensive automation on top of a locked-bootloader GrapheneOS install.

UncleCarbon is a fork of UncleTed by
[Hamoon Soleimani](https://github.com/HamoonSoleimani). It removes the upstream
functionality that depended on root, Magisk/KernelSU/APatch, LSPosed/Xposed
hooks or privileged system-app installation, and keeps only what a Device Owner
app can do on an unmodified, locked GrapheneOS device.

> [!WARNING]
> UncleCarbon can **factory-reset the device**, in several cases from a single
> tap or an automatic trigger with no confirmation. Read
> [Destructive actions and data-loss warning](#destructive-actions-and-data-loss-warning)
> before enabling anything, and test on a device that holds no data you need.

> [!NOTE]
> UncleCarbon is an independent fork and is not affiliated with the GrapheneOS
> project. No endorsement by GrapheneOS or by the UncleTed authors is implied.
> The version number (`10.0.1`) continues from the upstream codebase.

## Contents

- [Why UncleCarbon exists](#why-unclecarbon-exists)
- [Design goals](#design-goals)
- [Privilege model](#privilege-model)
- [Supported deployment model](#supported-deployment-model)
- [GrapheneOS compatibility](#grapheneos-compatibility)
- [Features](#features)
- [Lockdown Mode](#lockdown-mode)
- [Security monitoring](#security-monitoring)
- [Security hardening](#security-hardening)
- [Requirements](#requirements)
- [Building](#building)
- [Device Owner provisioning](#device-owner-provisioning)
- [Permissions](#permissions)
- [Threat model](#threat-model)
- [Destructive actions and data-loss warning](#destructive-actions-and-data-loss-warning)
- [Privacy and networking](#privacy-and-networking)
- [Project structure](#project-structure)
- [Testing](#testing)
- [Relationship to UncleTed](#relationship-to-uncleted)
- [License](#license)

## Why UncleCarbon exists

UncleTed targeted two deployments: a Device Owner app on a locked device, and a
rooted "systemless" install with LSPosed hooks inside `system_server`, a
privileged system app, and direct kernel/block-device access. The second route
needs an unlocked bootloader and disables or weakens much of what makes
GrapheneOS worth running: verified boot, the hardware-backed attestation of a
locked device, and the integrity of the OS image.

UncleCarbon takes the first route only:

- **Locked bootloader, stock GrapheneOS.** Nothing in this repository modifies
  the OS, requires root, or installs into `/system`.
- **Device Owner instead of root.** Privileged behaviour goes through Android's
  Device Policy APIs (`DevicePolicyManager`), which GrapheneOS supports like
  any Android build. Where an API does not exist, the feature was removed rather
  than re-implemented with a bypass.
- **Removed upstream features stay removed.** Root execution helpers, LSPosed
  lock-screen hooks, Xposed init assets, the honeypot/decoy app and decoy-user
  system, the OPRF and post-quantum engines, crypto-shredding of storage,
  root-only USB and memory manipulation, covert canary signaling and the
  Magisk/KernelSU/APatch packaging script are not part of this fork.

## Design goals

- Preserve the GrapheneOS security model: locked bootloader, verified boot, no
  root, no system modification.
- Use documented Android Device Policy and framework APIs; no hidden-API
  privilege escalation (one best-effort reflective Keystore request is noted
  under [Android Keystore and StrongBox](#android-keystore-and-strongbox)).
- Provide defensive automation: lock/reboot, alerting, evidence capture and,
  when explicitly configured, factory reset.
- Detect suspicious environmental and security changes (SIM removal, RF
  isolation, 2G registration, failed unlocks, package changes) and record them
  in a local, fixed-format event history.
- Fail safe: uncertain sensor data must not trigger destructive actions;
  policy changes are read back from Android before they are reported as active.
- Keep destructive behaviour opt-in. Every wipe trigger must be enabled or
  added by the user; the ones that then act without a confirmation dialog are
  listed in the [data-loss warning](#destructive-actions-and-data-loss-warning).

## Privilege model

These four layers are easy to conflate. UncleCarbon's capabilities depend on
which one a feature uses.

| Layer | What it is | What UncleCarbon uses it for |
| --- | --- | --- |
| Ordinary app permissions | Runtime permissions (camera, location, SMS, …) and special access (accessibility, notification listener, usage access) granted by the user. | Evidence capture, location, SMS commands and alerts, volume-key sequence, notification commands. |
| Device Admin | A `DeviceAdminReceiver` the user activates. Limited legacy policy set. | Failed/successful unlock callbacks, `lockNow()`. |
| Device Owner | A Device Admin provisioned with `dpm set-device-owner` before accounts/users exist. Can call the full `DevicePolicyManager` API for the device. | User restrictions (Safe Mode, debugging, USB file transfer), USB data signaling, keyguard feature control, `reboot()`, `wipeDevice()`/`wipeData()`, self-granting `READ_PHONE_STATE`, enabling Wi-Fi for scans. |
| GrapheneOS / Android platform | The OS itself: sandboxing, SELinux, verified boot, file-based encryption, exploit mitigations. | Not controlled by UncleCarbon. |

Device Owner is **not root**. It does not bypass SELinux, cannot read other
apps' data, cannot touch the kernel, Vold or file-based-encryption keys, and
cannot install itself into the system image. Everything UncleCarbon does as
Device Owner is something Android explicitly allows a device policy controller
to do.

## Supported deployment model

The supported configuration is:

1. GrapheneOS on a supported Pixel, bootloader **locked**.
2. UncleCarbon installed as a normal APK.
3. UncleCarbon provisioned as **Device Owner** through ADB.

Installing the APK without Device Owner provisioning gives a partially working
app: alerts, evidence capture and monitoring can run, but every policy control,
reboot and factory reset is skipped (the code logs `WIPE_SKIPPED` or reports
that Device Owner is missing rather than failing silently). Device Admin
activation alone enables only unlock callbacks and screen locking.

The Device Admin component, after the package rename, is:

```text
com.hamoon.unclecarbon/.receivers.AdminReceiver
```

See [Device Owner provisioning](#device-owner-provisioning) for the procedure
and Android's preconditions.

## GrapheneOS compatibility

UncleCarbon does not call private GrapheneOS APIs. It relies on standard
Android Device Policy and framework APIs, which also behave this way on
GrapheneOS. Location uses only `android.location` framework providers (no
Google Play services), so every location feature works without sandboxed
Google Play.

**Provided by UncleCarbon**

- Device Owner policy enforcement: block Safe Mode, block debugging features,
  one-way [Lockdown Mode](#lockdown-mode), USB data/file-transfer restrictions
  after failed unlocks, keyguard biometric disabling after failed unlocks.
- Automated responses: reboot to before-first-unlock (BFU), siren, alerts,
  evidence capture, opt-in factory reset.
- Sentinels: dead-man lock timer, physical SIM removal/replacement, RF-loss,
  2G/baseband alerts, BLE proximity, geographic zones, shake, volume-key
  sequence.
- Remote signaling over SMS and notifications; local security event history.

**Provided natively by GrapheneOS (use the OS settings)**

GrapheneOS ships its own controls in this area, for example an auto-reboot
timer, a duress PIN/password, USB-C port restrictions while locked, a 2G
toggle, and hardened memory allocation with MTE on supported hardware. Check
the [GrapheneOS documentation](https://grapheneos.org/features) for current
behaviour. UncleCarbon does not replace any of these:

- It does not implement its own lock-screen PINs. Use GrapheneOS's duress
  credential for a duress wipe.
- Its baseband sentinel only **observes** telephony state and alerts. Blocking
  2G is done with the OS toggle; UncleCarbon does not change radio settings.
- Its USB response is an additional Device Owner policy, not a replacement for
  GrapheneOS's USB-C port control.
- Its dead-man timer (factory reset after a long locked period) complements,
  rather than replaces, the OS auto-reboot timer (return to BFU).

**Intentionally not attempted**

Modem or radio-firmware control, kernel/Vold/FBE key manipulation, raw block
device discard, lock-screen hooks, system-app installation, decoy users or
decoy apps, process hiding, anti-forensic memory scrubbing of other processes,
post-quantum or OPRF key schemes, and covert network canaries.

## Features

The list below is derived from the current source tree. "DO" means Device Owner
is required for the behaviour described. Features not reachable from the UI are
listed at the end of this section.

### Device Owner security controls

| Capability | Mechanism | DO | Notes |
| --- | --- | --- | --- |
| Block Safe Mode boot | `addUserRestriction(DISALLOW_SAFE_BOOT)` | Yes | **On by default** once provisioned. Prevents booting into Safe Mode, which would stop UncleCarbon's services. |
| Block developer/debugging features | `DISALLOW_DEBUGGING_FEATURES` | Yes | Off by default. Blocks enabling developer options and ADB. Also held while a failed-unlock USB lockdown is active. |
| USB lockdown after 3 failed unlocks | `setUsbDataSignalingEnabled(false)` (Android 12+), `DISALLOW_USB_FILE_TRANSFER`, `DISALLOW_MOUNT_PHYSICAL_MEDIA` | Yes | Automatic. Reverted on the next successful unlock. USB data signaling control needs hardware/HAL support. |
| Disable keyguard biometrics after 3 failed unlocks | `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_BIOMETRICS …)` | Yes | Automatic; reverted on successful unlock. |
| Password policy on provisioning | `setPasswordQuality(NUMERIC_COMPLEX)`, `setPasswordMinimumLength(6)` | Yes | Applied when the admin is enabled as Device Owner; Android will require a compliant screen lock. |
| Platform wipe threshold | `setMaximumFailedPasswordsForWipe()` | Yes | 0 (off, default), 3, 5 or 10 failed attempts. See the warning section. |
| Reboot to BFU | `DevicePolicyManager.reboot()` | Yes | After the reboot, credential-encrypted storage stays locked until the user unlocks. Falls back to `lockNow()` without DO or if the reboot is refused (for example during a call). |
| Keep backups and user creation available | `setBackupServiceEnabled(true)`, clears its own `DISALLOW_ADD_USER` | Yes | Android disables the backup service when a Device Owner is set; UncleCarbon re-enables it so other apps can use Seedvault. UncleCarbon's own data is excluded from backup and device transfer. |
| Grant `READ_PHONE_STATE` to itself | `setPermissionGrantState()` | Yes | Lets the SIM monitor work before first unlock without a permission dialog. |

### Lockdown and defensive actions

| Action | What happens | DO |
| --- | --- | --- |
| Lockdown Mode | One-way latch that enforces and keeps both Safe Mode and debugging blocks on. See [Lockdown Mode](#lockdown-mode). | Yes |
| Lock / BFU | Reboot via `DevicePolicyManager.reboot()`, or screen lock without DO. | Reboot only |
| Factory reset | `wipeDevice()` on Android 14+, `wipeData()` earlier, with `WIPE_EXTERNAL_STORAGE`, `WIPE_SILENTLY` and, by default, `WIPE_EUICC`. | Yes |
| Siren | Default alarm sound at maximum alarm volume plus vibration for 30 s. | No |
| Location alert | One framework location fix sent to the emergency contact. | No |
| Evidence capture | Photos, optional video and audio; see [Surveillance and evidence](#surveillance-and-evidence). | No |

Entry points: the in-app Destruction Protocols screen (with confirmation),
Quick Settings tiles (Lock, Siren, Location, WIPE, and a tile labelled
"Airplane mode"), a home-screen widget (Lock, Siren, Locate, Wipe) and the
persistent monitoring notification (Lock, Siren, Locate).

### Security monitoring

| Capability | Mechanism | DO | Notes |
| --- | --- | --- | --- |
| Failed/successful unlock tracking | `DeviceAdminReceiver.onPasswordFailed/Succeeded`, `getCurrentFailedPasswordAttempts()` | Device Admin | Alert thresholds 1/3/5/10; response is a notification or activating Lockdown Mode. No credential data is collected. |
| Application change monitoring | Package broadcasts, `PackageManager`, `SigningInfo` | No | Baseline of installed packages; alerts on new apps, updates, new `INTERNET` declarations, and signer changes not explained by the signing lineage. Uses `QUERY_ALL_PACKAGES`. |
| Dashboard threat assessment | Local heuristics (recent installs, sideloaded apps, unexpected VPN, failed attempts, unusual hours, usage stats) | No | Runs when the dashboard loads. A HIGH or CRITICAL result triggers the matching panic response (evidence capture and alerts, never a wipe), so false positives have side effects. |

Details: [docs/security-monitoring.md](docs/security-monitoring.md).

### Authentication and app access

- **App lock**: optional `BiometricPrompt` gate (`BIOMETRIC_WEAK |
  DEVICE_CREDENTIAL`) for the main screen and the evidence gallery. It fails
  closed: unavailable authenticators, errors, cancellation and unreadable
  settings all deny access. Protected windows set `FLAG_SECURE`.
- **No app PINs.** The GrapheneOS lock-screen credential is the only device
  credential UncleCarbon relies on. Duress credentials belong to GrapheneOS.
- **Intruder selfie** (opt-in): a front-camera photo after three failed unlock
  attempts, optionally copied to `Pictures/UncleCarbon`.

### Hardware and environmental sentinels

| Sentinel | Mechanism | DO | Notes |
| --- | --- | --- | --- |
| RF-loss ("Faraday") | While the device is locked: no validated internet, cellular positively unavailable (fresh cell info), and optionally a fresh Wi-Fi scan with zero access points. A continuous quarantine timer (30 min to 7 days) then fires. | Reboot and WIPE | Action is BFU reboot (default) or factory reset. Airplane mode cancels. Any uncertainty (missing permission, failed or throttled scan, stale data) holds the timer instead of advancing it. May briefly enable Wi-Fi to scan, then restores it. Heuristic: an empty scan is not proof of a Faraday bag. |
| Baseband / 2G | `TelephonyCallback` service-state and cell-info observation | No | On by default. Alerts when registered on 2G (GSM/GPRS/EDGE…) or when an LTE cell reports strong signal with an implausibly high timing advance. Alerts are HIGH severity, which runs evidence capture and notifies the emergency contact. It cannot identify an IMSI catcher with certainty and does not change radio settings. |
| Shake to panic | Accelerometer threshold | No | Opt-in. HIGH-severity response plus siren. |
| Rapid volume sequence | Accessibility service with key-event filtering (VOL UP, DOWN, UP, DOWN within 3 s gaps) | Yes | Opt-in. Triggers a factory reset. The accessibility service does not read window content. |

Sentinels hosted by the monitoring service (RF-loss, baseband, BLE, shake and
the screen-off hook for the dead-man timer) start only after the first unlock
following a boot; they are not active in the BFU state.

### SIM monitoring

- Detects **physical** SIM presence from `SubscriptionManager` snapshots using
  the `isEmbedded` flag; eSIM profiles are not monitored.
- Removal is confirmed by a delayed verification job (7 s, with a 90 s
  post-boot grace period) so modem resets and airplane-mode toggles do not
  count. A single in-flight latch prevents duplicate wipe requests.
- Replacement detection compares a SHA-256 fingerprint of identity material
  when Android exposes enough of it. Android 10+ restricts ICCID access, so the
  fingerprint is often unavailable; replacement then never triggers a wipe.
- Responses (each opt-in): alert, factory reset on removal, factory reset on
  replacement. Removal handling runs in Direct Boot as well.
- **Caution:** in the current code a verified physical-SIM removal requests a
  factory reset when *either* the SIM alert switch ("Hardware SIM Card Identity
  Sentinel") *or* "Factory Reset on SIM Removal" is enabled. Enabling the alert
  alone is enough to wipe on removal. Replacement is gated correctly: it wipes
  only with "Factory Reset on SIM Replacement" and otherwise alerts.

### Proximity and dead-man controls

| Control | Mechanism | DO | Notes |
| --- | --- | --- | --- |
| BLE proximity sentinel | GATT connection to a configured MAC address, RSSI polled every 1.5 s | Reboot only | Disconnection, a 6 s heartbeat timeout, or N consecutive readings below the RSSI threshold trigger a BFU reboot (screen lock without DO) and a HIGH-severity alert. Any BLE peripheral that keeps a GATT connection works; peripherals that drop idle connections will cause false triggers. |
| Dead-man lock timer | Countdown armed on screen-off, cleared on unlock; exact alarm | Yes | Opt-in. If the device stays locked for the configured period (6 h to 6 months, default 7 days), it is factory-reset. The deadline is persisted in device-protected storage and survives reboots; an overdue deadline found at boot wipes immediately. Network state never affects it. |

The BLE code also maintains a two-share secret (one share sealed with an
Android Keystore key, one read from the peripheral). No current feature uses
the reconstructed secret to protect data, and the UI does not provision it; it
is not a data-protection feature in this build.

### Location and geozone controls

- Point-in-polygon checks of framework location fixes (GPS preferred, network
  provider accepted) against a built-in perimeter (Evin Prison, Tehran) and
  user-defined zones.
- A breach requires three consecutive fresh fixes with accuracy ≤ 30 m inside a
  zone; then UncleCarbon requests a factory reset (DO required).
- Monitoring runs as a location foreground service while the switch labelled
  "Evin Prison Perimeter Sentinel (Auto-Wipe)" is enabled, including in Direct
  Boot after a reboot. That switch also starts evaluation of custom zones:
  custom zones are not monitored while it is off, and the built-in perimeter is
  always included while it is on.

### Remote signaling

| Channel | Behaviour | Authentication |
| --- | --- | --- |
| One-time emergency wipe token | Token of the form `!UC:OTC-W1-XXXX-XXXX-XXXX` (60 bits of randomness), received by SMS or in any notification, triggers a factory reset and is burned. Only one token is active; generating a new one replaces it. | Possession of the token. Only its SHA-256 hash is stored (device-protected storage). Any sender can use it. |
| SMS commands | `UNCLECARBON <COMMAND> <password> [args]` with `WIPE`, `EVIDENCE`, `SIREN`, `LOCK`, `LOCATE`, `AUDIO [seconds]`, `SPEAK [message]`. Disabled unless the cleartext fallback is enabled and a master password is set. | Sender must match the emergency contact exactly after E.164 canonicalization; password compared in constant time. |
| Notification commands | Same syntax found in any posted notification (intended for data-only eSIM plans, e.g. a messenger notification). Supports `WIPE`, `EVIDENCE`, `SIREN`, `LOCK`, `LOCATE`, `AUDIO`. | Password only. There is no sender check on this channel. |
| Outbound alerts | SMS (via `SmsManager`) or email (SMTP via JavaMail) to the emergency contact. | Your SMTP account credentials. |

Limitations: SMS sender IDs can be spoofed on some networks; matching
notifications are dismissed, but the underlying messages stay in the SMS or
messaging app (a non-default SMS app cannot delete SMS); and the password
travels in cleartext.

### Surveillance and evidence

- CameraX front/back photos, optional video (5–120 s) and ambient audio
  (5–600 s), captured in response to panic triggers or remote commands. A
  full-screen-intent "broker" activity is used when Android blocks camera
  access from the background.
- Files are stored in app-private credential-encrypted storage
  (`files/evidence/`) and can be viewed, shared (via a narrowly scoped
  `FileProvider`) or deleted in the app-locked Evidence Locker.
- Attachments are emailed to the emergency contact when SMTP is configured.

### Android Keystore and StrongBox

`StrongBoxSecurityManager` creates an AES-256-GCM key in the Android Keystore,
StrongBox-backed when `FEATURE_STRONGBOX_KEYSTORE` is present and TEE-backed
otherwise, and includes a function to delete it. It also makes a best-effort
reflective call to request key rollback resistance, which may be unavailable
and is not verified. In the current build the key is used only by the dormant BLE
two-share code above, so no user data depends on it. Settings and secrets are
otherwise stored with AndroidX `EncryptedSharedPreferences` (Keystore-wrapped
keys) in credential-encrypted storage, plus the device-protected values listed
in [docs/security-hardening.md](docs/security-hardening.md). UncleCarbon does
not provide app-data rollback protection.

### Diagnostics and security event history

- Security events are appended from a fixed enum of messages (no phone
  numbers, coordinates, tokens or command text) to credential-encrypted
  storage; events that occur before first unlock are discarded. The history is
  viewable in the app.
- The "Bug Reporting & Logs" screen shows Device Owner/Device Admin state and
  monitoring status and can export a text report (build, admin state, audit events) via
  the share sheet. Raw logcat is never collected.

### Present in code but not exposed in the UI

These code paths exist but have no settings entry in the current build, so
they are inactive: periodic watchdog status reports, a circular "safe zone"
exit alert, the secret dialer code, maintenance mode and the trusted-VPN hint.
An app-icon "stealth mode" preference exists but nothing hides the launcher
entry.

## Lockdown Mode

Lockdown Mode is a one-way switch on the "Silicon & Hardware Defense" screen. After
confirmation, `LockdownController` applies `DISALLOW_SAFE_BOOT` and then
`DISALLOW_DEBUGGING_FEATURES`, reads both back from Android (including global
restrictions on Android 14+), and only then commits a latch in device-protected
storage. Partial failures leave successful restrictions in place and report
the error; Lockdown stays inactive until a retry succeeds.

Once latched, neither protection can be turned off from the app. The latch is
re-verified and re-applied at app start, when the main screen resumes, and on
`LOCKED_BOOT_COMPLETED`/`BOOT_COMPLETED`, so no unlock is needed. If Android no
longer reports the restrictions (for example because Device Owner was lost),
the UI shows **LOCKDOWN REQUIRES ATTENTION** instead of claiming success.

Lockdown does not survive a factory reset, clearing the app's data, or removal
of Device Owner. Because debugging stays blocked, ADB (including `adb install`)
is unavailable afterwards; updates must be installed on the device.

Implementation details and device test plan:
[docs/lockdown-mode.md](docs/lockdown-mode.md).

## Security monitoring

The monitoring system has two parts:

- **Failed authentication**: Android reports lock-screen failures and successes
  to the Device Admin receiver. UncleCarbon records counts, raises a
  notification at the selected threshold and can activate Lockdown Mode.
  Automatic wiping is a separate, explicit setting (off by default).
- **Application changes**: with "Monitor installed applications" enabled, a
  baseline of package metadata (version, permissions, enabled state, signing
  certificate digests and lineage) is kept in encrypted credential-protected
  storage and updated from package broadcasts. Events are stored locally (up
  to 250 per store) and can raise notifications by severity.

A changed signer, a new app or a new `INTERNET` declaration is a prompt to
review, not a malware verdict. Full rules, privacy notes and limitations:
[docs/security-monitoring.md](docs/security-monitoring.md).

## Security hardening

Hardening decisions already in the codebase include:

- Fixed-message audit events in credential-encrypted storage only; nothing is
  written before first unlock, and raw logcat is never exported.
- A fail-closed app lock and `FLAG_SECURE` on protected windows.
- Exact, E.164-canonicalized SMS sender matching and constant-time secret
  comparison.
- A `FileProvider` limited to the `evidence/` and `diagnostics/` directories.
- All app data excluded from cloud backup and device transfer.
- Release builds fail instead of falling back to the debug key.
- Native helpers: `PR_SET_DUMPABLE=0`, a request for synchronous MTE tag
  checking, and `android:memtagMode="sync"` in the manifest (effective only on
  MTE-capable hardware).
- An earlier, misleading "anti-rollback" counter was removed rather than kept,
  because restorable userdata cannot prove freshness.

The device-protected (pre-unlock) data that remains, and why, is tabulated in
[docs/security-hardening.md](docs/security-hardening.md).

## Requirements

**Device**

| Item | Value |
| --- | --- |
| OS focus | GrapheneOS on a supported Pixel with a locked bootloader. Other Android builds may work but are not the target. |
| `minSdk` / `targetSdk` / `compileSdk` | 28 (Android 9) / 34 (Android 14) / 34 |
| ABI | `arm64-v8a` only |
| Device Owner | Required for policy controls, reboots and factory resets |
| Camera | Declared as required by the manifest |
| Telephony, Bluetooth LE | Optional; the related sentinels need them |

Several features need newer API levels than `minSdk`: USB data signaling
control (Android 12+ and hardware support), `wipeDevice()` and global
restriction read-back (Android 14+), notification permission (Android 13+).
StrongBox is used when the device reports it (Pixels with a Titan M-series
security chip do) and the code falls back to the TEE otherwise.

The native helper is compiled with `-march=armv8.5-a+memtag
-fsanitize=memtag`. MTE tag checking only exists on MTE-capable SoCs (Pixel 8
and later); behaviour on older arm64 Pixels has not been verified.

**Build host**

- JDK 17 (AGP 8.2 needs JDK 17; the Gradle 8.4 wrapper does not run on JDK 21).
- Android SDK Platform 34.
- Android NDK `30.0.16248370` and CMake `3.22.1` (from `app/build.gradle.kts`).
- Android Gradle Plugin 8.2.0, Kotlin 1.9.20 (downloaded by Gradle).

## Building

Debug build:

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Release build. Release signing is fail-closed: the build aborts unless
`release.keystore` exists in the repository root **and** all three variables
are set. There is no fallback to the debug key, and only the names of missing
variables are printed.

```bash
export UNCLECARBON_KEYSTORE_PASSWORD='…'
export UNCLECARBON_KEY_ALIAS='…'
export UNCLECARBON_KEY_PASSWORD='…'
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

`release.keystore`, `*.jks` and `*.p12` are git-ignored. Keep the release key
safe: Android only installs updates signed with the same key, and a Device
Owner app cannot be uninstalled, so moving to a differently signed build
(including switching between debug and release builds) requires a factory
reset.

## Device Owner provisioning

Understand Device Owner before you start. A Device Owner app cannot be
uninstalled, and UncleCarbon does not currently offer a way to relinquish
Device Owner, so **removing it requires a factory reset**.

1. **Build and install** the APK:

   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

   ADB requires temporarily enabling developer options and USB debugging.

2. **Check the device state.** Android accepts `dpm set-device-owner` from ADB
   only when no Device Owner is set, no accounts are registered on the device
   (including accounts created by apps through Android's account system), and
   no secondary users or work profile exist. If any of these are present,
   factory-reset the device and provision before adding users, profiles or
   accounts.

3. **Provision:**

   ```bash
   adb shell dpm set-device-owner com.hamoon.unclecarbon/.receivers.AdminReceiver
   ```

4. **Verify:**

   ```bash
   adb shell dpm list-owners
   # or: adb shell dumpsys device_policy
   ```

   The "Bug Reporting & Logs" screen also shows "Device Owner: PROVISIONED",
   and the dashboard checklist marks "Device Owner Provisioned".

5. **First launch.** When Android enables the admin receiver as Device Owner,
   UncleCarbon requests a screen lock of at least 6 characters with Android's
   "numeric complex" quality (no repeating or ordered digit sequences; Android
   prompts if the current lock does not comply), blocks Safe Mode, re-enables
   the backup service and grants itself `READ_PHONE_STATE`. Then open the app,
   grant runtime permissions and special access on the System Platform Access
   screen, and configure only the features you need. You may disable USB
   debugging afterwards; the debugging block turns it off, and Lockdown Mode
   keeps it off permanently.

**Upgrading from builds that used `com.hamoon.uncleted`.** The application ID
changed to `com.hamoon.unclecarbon`, so Android treats UncleCarbon as a
different app. It cannot update the old package in place, the old package
stays Device Owner, and settings, tokens and keys do not carry over. Switching
requires a factory reset and fresh provisioning.

## Permissions

**Runtime permissions** (granted by the user on the System Platform Access
screen; `READ_PHONE_STATE` is self-granted by the Device Owner)

| Permission | Purpose |
| --- | --- |
| `CAMERA`, `RECORD_AUDIO` | Evidence photos, video and audio |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` | Location alerts, geozones, cell info and Wi-Fi scans for the RF-loss sentinel |
| `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS` | SMS alerts and commands |
| `READ_PHONE_STATE` | SIM monitor, baseband sentinel |
| `POST_NOTIFICATIONS` | Alerts and foreground-service notifications (Android 13+) |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | BLE proximity sentinel (Android 12+; `BLUETOOTH`/`BLUETOOTH_ADMIN` on ≤ 11) |
| `NEARBY_WIFI_DEVICES` | Wi-Fi scans (Android 13+) |

**Special access** (enabled by the user in system settings)

| Access | Purpose |
| --- | --- |
| Device admin | Implied by Device Owner; unlock callbacks |
| Accessibility service | Volume-key sequence only (`canRequestFilterKeyEvents`; no window content) |
| Notification listener | Notification wipe token and commands; reads notification text on the device |
| Usage access (`PACKAGE_USAGE_STATS`) | Optional dashboard heuristics |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | Optional. No overlays are drawn; it only relaxes background activity-start limits, which Device Owner already does |
| Exact alarms (`SCHEDULE_EXACT_ALARM`; `USE_EXACT_ALARM` is install-time on Android 13+) | Dead-man deadline |
| Full-screen intents (`USE_FULL_SCREEN_INTENT`) | Evidence-capture broker activity |

**Install-time permissions**: `INTERNET` (SMTP only), network and Wi-Fi state
(`ACCESS_/CHANGE_NETWORK_STATE`, `ACCESS_/CHANGE_WIFI_STATE`), `VIBRATE`,
`WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `USE_BIOMETRIC`, `QUERY_ALL_PACKAGES`
(package monitoring), `FOREGROUND_SERVICE` with the camera, location,
microphone, media-playback, data-sync, connected-device and special-use
subtypes, and `WRITE_EXTERNAL_STORAGE` up to Android 9.

**Device Owner privileges** are not permissions; they come from provisioning
and cover the `DevicePolicyManager` calls listed under
[Device Owner security controls](#device-owner-security-controls).

## Threat model

UncleCarbon aims to raise the cost of these scenarios:

- Physical seizure or theft of a locked device: returning it to BFU, blocking
  Safe Mode and debugging, restricting USB after failed unlocks, and
  optionally erasing it on configured triggers (long locked period, SIM
  removal, RF isolation, entering a zone, remote token or command).
- Unlock guessing: alerting, evidence capture, and an optional wipe threshold.
- Transport to an RF-shielded environment, a SIM swap or removal, and moving a
  device into a designated area.
- Unnoticed changes to installed apps (new apps, new `INTERNET` declarations,
  unexpected signer changes).

It does **not** defend against:

- A compromised OS, kernel, firmware, baseband or secure element. GrapheneOS
  remains responsible for sandboxing, verified boot, file-based encryption,
  exploit mitigations and the lock screen.
- An adversary who already has the unlocked device or the user's credential,
  or who coerces the credential (use GrapheneOS's duress credential).
- Attacks that act before a trigger fires, or while UncleCarbon cannot run:
  most sentinels are inactive between boot and first unlock, and triggers
  depend on permissions, sensors and timers working as expected.
- Hardware attacks on the device or its storage. Whether data is recoverable
  after a factory reset depends on Android/GrapheneOS, not on UncleCarbon.
- Spoofed SMS senders, interception of cleartext SMS or email, or anyone who
  learns the command password or the one-time token.
- Remote network attackers in general; UncleCarbon has no network-facing
  service.

Heuristics (RF-loss, baseband, BLE distance, dashboard threats) can produce
false positives and false negatives. Configure destructive responses with that
in mind.

## Destructive actions and data-loss warning

Every UncleCarbon wipe calls the standard Android factory reset through
`DevicePolicyManager`: `wipeDevice()` on Android 14+, `wipeData()` on earlier
versions, with `WIPE_EXTERNAL_STORAGE` and `WIPE_SILENTLY`. eSIM profiles are
also erased (`WIPE_EUICC`) unless "Erase eSIM on Wipe" is turned off. The
device reboots and all user data is removed; this cannot be undone. UncleCarbon
performs no other destructive operation: no raw block-device discard, no key or
partition destruction beyond what Android's reset does. All wipes require
Device Owner.

**Triggers that wipe immediately, without a confirmation dialog**

- The **WIPE** Quick Settings tile and the widget's **WIPE** button.
- The Quick Settings tile labelled **"Airplane mode"**: a decoy for use under
  duress. One tap factory-resets the device.
- The one-time emergency token (SMS or notification) and the `WIPE`
  SMS/notification command.

Tiles and the widget do nothing until you add them to Quick Settings or the
home screen. Do not add them unless you accept the risk of an accidental tap.

**Opt-in automatic triggers**: rapid volume sequence, failed-unlock threshold
(both the platform limit and UncleCarbon's own check), dead-man timer, SIM
removal or replacement (note that the SIM *alert* switch alone also wipes on a
verified removal; see [SIM monitoring](#sim-monitoring)), RF-loss sentinel with
the WIPE action, and geographic zones.

The "Allow Factory Reset on Critical Alerts" switch on the Destruction
Protocols screen does not gate any of the triggers above; in the current code
it is only consulted by a path that no trigger reaches.

**Manual, with confirmation**: "Execute Standard Factory Reset" on the
Destruction Protocols screen.

Test configuration changes on a device without data you need. Keep backups of
anything important outside the device.

## Privacy and networking

- **No telemetry.** There is no analytics, crash reporting, update check,
  advertising SDK, HTTP client or Google Play services dependency.
- **Outbound traffic only when you configure it:**
  - Email alerts go directly to the SMTP server you enter, using JavaMail.
    With the TLS option enabled the connection requests TLS 1.2; server hostname
    verification is not explicitly enabled (JavaMail's default is off), and the
    option can be disabled entirely, so treat email alerts as only as private
    as that setup. Critical-severity emails can include device identifiers
    (Android ID, and IMEI/SIM serial where Android permits — Device Owner apps
    are permitted on Android 10+), battery state, operator and a list of
    installed apps, along with captured media and location.
  - SMS alerts are sent through the carrier in cleartext and include a Google
    Maps link with coordinates (the link is only text; UncleCarbon does not
    contact Google).
  - Tapping the links on the About screen opens the UncleTed author's GitHub
    page or website in a browser.
- **Inbound:** SMS commands and notification text are processed on the device.
  The notification listener reads the text of every posted notification to
  look for tokens and commands; nothing is forwarded.
- **Local storage:** settings and secrets (SMTP password, SMS master password,
  emergency contact) are kept in encrypted credential-encrypted preferences
  (the general settings store falls back to device-protected storage if the
  encrypted store cannot be opened).
  Values needed before first unlock, including the emergency contact and a
  plaintext copy of the SMS master password, are kept in device-protected
  storage; see [docs/security-hardening.md](docs/security-hardening.md).
  Evidence stays in app-private storage unless you share it or enable the
  public intruder-selfie copy.
- **Backups:** `allowBackup="false"` and the data-extraction rules exclude all
  of UncleCarbon's data from cloud backup and device-to-device transfer.

## Project structure

```text
.
├── app/
│   ├── build.gradle.kts          # namespace/applicationId com.hamoon.unclecarbon, signing guard
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/                # native helper (process hardening, MTE, zeroization)
│       │   ├── java/com/hamoon/unclecarbon/
│       │   │   ├── core/           # DefenseCoordinator, Lockdown*, DeadmanSentinelLogic, WipeFlagBuilder
│       │   │   │   └── strategies/ # DeviceOwnerStrategy (all DevicePolicyManager calls)
│       │   │   ├── crypto/         # Keystore/StrongBox helper, one-time token store
│       │   │   ├── data/           # SecurityPreferences, event history, monitoring store
│       │   │   ├── fragments/      # UI screens
│       │   │   ├── proximity/      # BLE proximity sentinel
│       │   │   ├── receivers/      # AdminReceiver, boot, SMS, SIM, package, tripwire, widget
│       │   │   ├── sentinels/      # RF-loss, Wi-Fi environment, baseband
│       │   │   ├── services/       # monitoring, panic actions, zones, tiles, accessibility, notifications
│       │   │   ├── sim/            # physical-SIM state machine
│       │   │   ├── util/           # location, email, camera, app lock, storage layout, helpers
│       │   │   ├── widgets/        # home-screen widget
│       │   │   └── workers/        # WorkManager watchdog worker
│       │   └── res/                # layouts, strings (en, fa), XML configs
│       ├── test/                   # JVM unit tests
│       └── androidTest/            # instrumentation tests
├── docs/
│   ├── lockdown-mode.md
│   ├── security-hardening.md
│   └── security-monitoring.md
├── gradle/                         # wrapper and version catalog
├── build.gradle.kts
└── settings.gradle.kts
```

## Testing

```bash
./gradlew test                    # JVM unit tests (:app:testDebugUnitTest)
./gradlew connectedAndroidTest    # instrumentation tests; needs a device or emulator
./gradlew lintDebug               # Android lint (existing findings are documented in docs/)
```

The unit tests (15 classes, 167 tests) cover the framework-free decision
logic: Lockdown activation and read-back, dead-man timer, debugging-policy
reconciliation, wipe-flag composition, RF-loss decisions and quarantine
timing, the SIM state machine, geozone location logic, SMS sender
authorization, constant-time comparison, app-lock gating, event history and
monitoring rules, and storage layout migration. The instrumentation tests
cover FileProvider path exposure, credential- versus device-protected storage,
and the monitoring store.

None of the automated tests exercise a real Device Owner, a reboot or a
factory reset. Use the device checklists in
[docs/lockdown-mode.md](docs/lockdown-mode.md) and
[docs/security-hardening.md](docs/security-hardening.md) on a dedicated test
device.

## Relationship to UncleTed

- UncleCarbon is a fork of **UncleTed**, the original project by Hamoon
  Soleimani. Credit for the original design and much of the code remains with
  the upstream project.
- UncleCarbon intentionally diverges from upstream. It prioritizes GrapheneOS
  Device Owner compatibility and has removed the root, LSPosed and systemless
  deployment, together with the features that depended on them.
- The Android application ID and package are `com.hamoon.unclecarbon`, so
  UncleCarbon installs separately from UncleTed and does not share its data.
- No endorsement of this fork by the UncleTed authors is implied.

## License

UncleCarbon is distributed under the MIT License inherited from UncleTed; see
[LICENSE](LICENSE). The copyright notice for the original work
(Copyright (c) 2025 Hamoon Soleimani) must be retained in copies and
substantial portions of the software.
