# SimpleLock

Manual, geofence-validated kiosk lockdown for Android. The device pins itself using Device Owner lock-task mode when you press **Verify Location & Lock** *inside* a saved boundary, and releases when you press **Check Location to Unlock** *outside* it. There is no automatic background geofencing, no boot receiver, no polling — every lock and unlock is driven by an explicit button press.

This trade-off (manual control vs. automatic detection) yields effectively **0% background battery drain**: when the kiosk is not active, nothing in the app runs.

## Architecture (v1.9.0)

| Layer | Component | Role |
|---|---|---|
| UI | `MainActivity` | **Verify Location & Lock** (primary) + **Set Boundary** + a 2×2 grid of exempted-app shortcuts + a system-brightness slider + a time-based-lock pair (1–168 h slider with primary button + a 1-minute test button) |
| UI | `MapActivity` | Tap-to-pick center + radius slider (50–1050 m), saves to prefs. Refuses to open / save while lock task is active |
| UI | `KioskActivity` | The pinned surface, holds `startLockTask()`, the always-accessible **Check Location to Unlock** button, the 2×2 shortcut grid, the brightness slider, and the time-based-lock guard that refuses release while the timer is still running |
| Receiver | `BootReceiver` | Listens for `BOOT_COMPLETED`. If `GeofencePrefs.isKioskActive` is true, re-applies policies + relaunches `KioskActivity` so the kiosk survives reboot |
| Manifest | `KioskHomeAlias` | Disabled-by-default `<activity-alias>` carrying the HOME intent filter. `LockManager` flips it on when the kiosk takes over and off on release, so unlock hands HOME back to the system launcher |
| Foreground | `KioskNotificationService` | Minimal `specialUse` FGS — hosts the persistent kiosk notification while `KioskActivity` is alive. Notification has no tap intent, so it can't be used to escape lock task |
| Policy | `AppDeviceAdminReceiver` | Component registered as Device Owner |
| Policy | `LockManager` | Wraps `DevicePolicyManager` config — `setLockTaskPackages`, `setLockTaskFeatures`, `KioskHomeAlias` toggle, system-brightness writes, and the `isInLockTask()` check |
| Storage | `GeofencePrefs` | SharedPreferences: saved boundary triple, `kiosk_active` flag (read by `BootReceiver`), `time_lock_until_ms` timestamp (read by `KioskActivity` to block release while a time-lock is running) |

What stays available inside kiosk (configured via `setLockTaskFeatures`):

- `LOCK_TASK_FEATURE_SYSTEM_INFO` — clock/battery
- `LOCK_TASK_FEATURE_NOTIFICATIONS` — read SMS / alerts (and toggle GPS from quick settings)
- `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` — power dialog
- `LOCK_TASK_FEATURE_KEYGUARD` — normal lock screen still works
- `LOCK_TASK_FEATURE_HOME` — kiosk acts as HOME, can't be escaped to launcher

The default dialer is added to the lock-task allowlist so **incoming calls remain answerable**. The default SMS app is similarly whitelisted so messages remain readable. Four additional packages are pinned to the allowlist as user-exempted apps so they keep running inside the kiosk, each with its own shortcut button on the home screen: Al Rajhi Retail (`com.alrajhiretailapp` → **Open Al Rajhi Retail**), Google Dialer (`com.google.android.dialer` → **Open Phone**), Google Contacts (`com.google.android.contacts` → **Open Contacts**) and the AOSP MMS app (`com.android.mms` → **Open Messages**).

## Lock & unlock flows

### Locking
1. Open the app inside the configured boundary.
2. Tap **Verify Location & Lock**.
3. UI shows "Checking GPS…", buttons disable briefly.
4. `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY, …)` returns a fix.
5. If distance to saved center ≤ radius → `KioskActivity` launches, lock task engages, FGS notification appears.
6. If distance > radius → toast `Outside boundary by N m. Cannot lock.`, UI returns to idle.

### Unlocking
1. While in kiosk, tap **Check Location to Unlock** (always anchored to bottom of screen).
2. Same single-fix request as above.
3. If distance > radius → `stopLockTask()`, FGS stops, activity finishes; you return to the system launcher.
4. If still inside → toast + on-screen "Still inside boundary by N m"; kiosk stays.

If GPS is off when you tap the button, the kiosk surfaces the standard Play Services "Allow this app to use your location?" system dialog directly on top of itself — tap **OK** and the unlock check retries automatically. If you dismiss the dialog you can still pull the notification shade (allowed by `LOCK_TASK_FEATURE_NOTIFICATIONS`), toggle Location manually, and retry. The dialog is a system overlay so it works inside lock task without whitelisting `com.android.settings`.

### Time-based lock (v1.9.0+)

A second way to engage the kiosk, independent of the geofence. On the main screen, set the hours (1–168, i.e. up to one week) on the time-lock slider and tap **حجب لمدة محددة** to pin the device for that duration — no boundary check is performed, so the lock engages from any location. The slider label shows a days/hours breakdown for values ≥ 24. The **اختبار: حجب لدقيقة** button always locks for 60 seconds, for easy verification.

While the timer is running, tapping **Check Location to Unlock** is refused with a remaining-minutes toast and the kiosk message shows the countdown. When the timer expires, the next unlock tap falls through to the standard location-based flow (release if you're outside the saved boundary). The active-timer timestamp is in `GeofencePrefs` so it survives reboot — `BootReceiver` re-pins the kiosk, and the kiosk continues refusing release until the timer passes.

There is no scheduled job or alarm — the timestamp is only read on demand whenever the user taps a button or `KioskActivity.onResume` runs.

## Provisioning the app as Device Owner

Device Owner provisioning is only possible on a **factory-reset device with no accounts added**. On any current account-attached device this command will fail with `Not allowed to set the device owner because there are already several users on the device`.

1. Wipe the device (factory reset). Don't sign into anything.
2. Skip every account-setup screen.
3. Enable Developer Options and turn on USB debugging.
4. Connect via ADB and install the debug APK:
   ```bash
   adb install -r app-debug.apk
   ```
5. **Grant Device Owner status:**

```bash
adb shell dpm set-device-owner com.hmdd.simplelock/.receiver.AppDeviceAdminReceiver
```

Expected output: `Success: Device owner set to package ComponentInfo{...}`.

> ⚠️ Once set, Device Owner cannot be removed except by factory reset (or, programmatically, by the app itself via `clearDeviceOwnerApp`).

## Permissions

Trimmed for the manual architecture — no background or boot permissions are requested.

- `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` — requested only at the moment you tap a verification button
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — for the kiosk notification
- `POST_NOTIFICATIONS` — Android 13+
- `INTERNET` + `ACCESS_NETWORK_STATE` — Google Maps
- `WRITE_SETTINGS` — declared for the brightness slider; the actual write goes through Device Owner's `setSystemSetting()` so no user grant flow is involved
- `RECEIVE_BOOT_COMPLETED` — fires `BootReceiver` once on boot to re-pin the kiosk when it was active at power-off. The receiver does no polling, no location work, and no scheduling — it launches the kiosk activity and exits.

Explicitly *not* requested: `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `WAKE_LOCK`.

## Battery profile

| State | Cost |
|---|---|
| App installed, not in kiosk | 0 — nothing runs |
| Pressing a verify button | ~3–15 s of GPS at high accuracy, once per press |
| In kiosk, screen off | Negligible (FGS posts a notification, performs no work) |
| In kiosk, screen on | Normal foreground-app cost; no extra location work between button presses |

## Google Maps API key

`MapActivity` needs a Maps SDK key.

- Local builds: create `secrets.properties` at the project root with `MAPS_API_KEY=AIza...`
- CI: set the `MAPS_API_KEY` repository secret. The workflow injects it before building.

The build succeeds without a key — the map will just render blank until one is provided.

## Building

Locally:
```bash
gradle wrapper          # one-time, regenerates the wrapper jar
./gradlew :app:assembleDebug
```

Or trigger the **Android APK Build** GitHub Action from the Actions tab — the workflow uploads the APK as a build artifact.

The committed `debug.keystore` (password `android`) means every CI build signs identically, so `adb install -r` will update your installed app in place rather than requiring an uninstall.

## Upgrading from older versions

| From → To | Notes |
|---|---|
| 1.0.0 → 1.1.0 | Different debug key — must `adb uninstall` first, then install once. Future upgrades are in place. |
| 1.1.0 → 1.2.0 | In-place. Adds active polling + 5 s geofence responsiveness. |
| 1.2.0 → 1.3.0 | In-place. Adds adaptive polling tiers, drops `FLAG_KEEP_SCREEN_ON`. |
| 1.3.0 → 1.4.0 | In-place, **but saved boundary is lost.** v1.3.0 stored prefs in device-protected storage; v1.4.0 reverted to credential-protected. Re-tap **Set Boundary** after upgrading. |
| 1.4.0 → 1.5.0 | In-place. Adds the Al Rajhi Retail shortcut + lock-task exemption. |
| 1.5.0 → 1.5.1 | In-place. Adds Google Dialer and Google Contacts to the lock-task exemption list. |
| 1.5.1 → 1.5.2 | In-place. Adds the AOSP MMS app (`com.android.mms`) to the lock-task exemption list. |
| 1.5.2 → 1.6.0 | In-place. Adds home-screen shortcut buttons for Google Dialer, Google Contacts, and AOSP MMS (rearranged as a 2×2 grid with Al Rajhi). |
| 1.6.0 → 1.6.1 | In-place. Fixes a HOME-intent trap where outside-boundary unlock silently re-engaged the kiosk; release now disables the HOME alias and explicitly hands off to the system launcher. |
| 1.6.1 → 1.6.2 | In-place. If Location is off, the kiosk now pops the Play Services system dialog directly so the user can re-enable GPS in place instead of being stranded. |
| 1.6.2 → 1.7.0 | In-place. Adds a system-brightness slider on the main screen driven by Device Owner's `setSystemSetting` (no WRITE_SETTINGS prompt). Requires Android 9+ for the brightness write path. |
| 1.7.0 → 1.7.1 | In-place. Security: closes a lock-task escape where a user could redraw the geofence while pinned (kiosk notification → MainActivity → MapActivity → save → unlock). The kiosk notification no longer has a tap intent, and **Set Boundary** / `MapActivity` / `GeofencePrefs.saveBoundary` all refuse to mutate the boundary during lock task. |
| 1.7.1 → 1.8.0 | In-place. Two regressions fixed: (1) the four exempted-app shortcuts now live directly on the kiosk surface so they remain reachable once pinned (without reopening the boundary-edit attack), and (2) a `BOOT_COMPLETED` receiver re-engages the kiosk after reboot using a persisted `kiosk_active` flag. |
| 1.8.0 → 1.8.1 | In-place. Adds the brightness slider to `KioskActivity` so screen brightness can be adjusted while pinned. |
| 1.8.1 → 1.9.0 | In-place. Adds the time-based lock (1–12 h slider + 1-minute test button on `MainActivity`; `KioskActivity` refuses unlock while the timer is running and shows a countdown). The location-based lock and unlock paths are unchanged. |
| 1.9.0 → 1.9.1 | In-place. Raises the time-lock ceiling from 12 h to 168 h (7 days) and adds a days/hours breakdown to the slider label. |
