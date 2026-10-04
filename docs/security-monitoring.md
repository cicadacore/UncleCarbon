# Security monitoring

## Sentry reference

Sentry uses `DeviceAdminReceiver.onPasswordFailed()` for password failure callbacks, checks `DevicePolicyManager.currentFailedPasswordAttempts`, exposes a monitor threshold, and can call its wipe implementation at that threshold. Its application monitor registers package added, replaced, and fully removed broadcasts from its notification listener service. It records packages that did not request `android.permission.INTERNET`, then checks an updated package manifest and notifies if that declaration appears. The original baseline stores only package names; it does not retain an app inventory, certificate identity, or an audit trail for each package change.

UncleCarbon uses its existing `AdminReceiver`, encrypted preference-backed monitoring state, `SecurityMonitoringStore`, and notification infrastructure. Package lifecycle events are received by `PackageSecurityReceiver`; no permanent foreground service is used.

The project keeps its existing Android compatibility strategy: minimum API 28, target API 34. Signing metadata uses `SigningInfo` (API 28+); notification runtime permission checks apply on Android 13+ (API 33+); package visibility restrictions apply on Android 11+ (API 30+).

## Failed device authentication

Android calls the Device Admin password callbacks only when UncleCarbon is active as Device Admin and the system reports the event. The application persists its own callback count and, where the API provides it, Android's current failed-password streak count. That platform counter is not the all-time audit history. A successful authentication resets the streak and the threshold notification latch; it adds a success event while leaving historical failures intact. Duplicate callbacks reporting the same positive platform counter are ignored. If a platform counter is unavailable, the app can count callbacks it receives but cannot prove that it saw every lock-screen failure.

The supported alert thresholds are 1, 3, 5, and 10. The default response records events and sends a security notification at the selected threshold. An optional response activates UncleCarbon's existing non-destructive Lockdown action. Factory reset is not part of this monitor response. Automatic wipe after password failures is disabled by default (`0`); the separate existing destruction setting requires an explicit choice to enable it.

No password, PIN, biometric data, or authentication secret is collected. Password callback availability requires active Device Admin registration. Device Owner status is not required for callback delivery, but some lockdown policies require Device Owner provisioning.

## Application changes

Enable **Monitor installed applications** in Settings to establish a baseline. The app monitors package installed, replaced, removed, and enabled-state broadcasts, then refreshes the affected package metadata. It records package identifier, label, version, install/update times, declared permissions, the relevant Internet declaration/grant status, enabled state, current signing-certificate SHA-256 digest, and the certificate lineage exposed by Android. Trusted package exclusions apply to alerts and event creation for that package.

An Internet escalation means the updated APK manifest newly declares `android.permission.INTERNET`. This is a normal install-time permission, not a runtime permission. A manifest declaration does not imply a new runtime grant or malicious behavior. A new or updated application is likewise a change to review, not a malware verdict.

Signing identity is compared using Android's `SigningInfo` and signing certificate history (API 28+). An old signer retained in the new signing lineage is treated as an observable certificate rotation, not an unexpected identity change. Android cannot establish that an update is trustworthy from a certificate digest alone; signing-key compromise, multiple signers, package-manager visibility, and platform-specific lineage behavior remain limitations. The app raises a high-severity investigation event only when the previous signer is absent from the installed package lineage.

Whole-device baselining needs package visibility. UncleCarbon already declares `QUERY_ALL_PACKAGES` for its existing device inventory/security functions; this monitor reuses that declared capability rather than adding a new visibility permission. Android user/work-profile boundaries and packages not visible to the current user can still limit the baseline. If package metadata cannot be read, Settings reports baseline failure and the monitor is not silently marked available.

## Notifications, events, and privacy

Package metadata, app-monitor settings, and app-change events use UncleCarbon's strict encrypted credential-protected preferences; the monitor refuses to fall back to device-protected storage for this installed-app inventory. Failed-password streak settings and the compact callback audit use Android's private device-protected preferences so they remain available before first unlock. Each event store retains up to 250 entries. The password audit contains counts/timestamps and device-admin context, never authentication secrets. Notifications require the app's notification permission on Android 13+ and an enabled notification channel. When notifications are unavailable, events still record `notificationSent=false`; current permission and last processing error are shown in Settings and Diagnostics. Notification severity can be configured from Low through Critical.

The local event store is best-effort app storage protected by the existing encrypted preferences. It is not hardware-backed, append-only, or tamper-proof against someone controlling the app process or storage keys. Package labels and requested permission names can reveal installed-app details, so only package security metadata is stored and the inventory remains local to the app.

## Safe verification and troubleshooting

Use an emulator or test device with Device Admin enabled. Trigger a failed device credential attempt and verify that only a callback received by Android appears in the local event history; complete a successful unlock to verify that the counter starts a new streak. Do not configure the separate automatic-wipe policy on a device containing data you need.

To exercise package rules safely, use the automated rule tests for manifest transitions and signing lineage. A real package lifecycle test requires installing/replacing a test APK with controlled versions and signer keys. Check Settings and Diagnostics for Device Admin status, application-monitor state, notification access, the last successful monitor time, and the last processing error. Re-establish the baseline after changing what package inventory should be trusted as the starting point.
