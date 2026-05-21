# SimpleLock

Manual, geofence-validated kiosk lockdown for Android. The device pins itself using Device Owner lock-task mode when you press **Verify Location & Lock** *inside* a saved boundary, and releases when you press **Check Location to Unlock** *outside* it. There is no automatic background geofencing, no boot receiver, no polling — every lock and unlock is driven by an explicit button press.

This trade-off (manual control vs. automatic detection) yields effectively **0% background battery drain**: when the kiosk is not active, nothing in the app runs.

## Architecture (v1.5.0)

| Layer | Component | Role |
|---|---|---|
| UI | `MainActivity` | Three buttons: **Verify Location & Lock** (primary), **Set Boundary**, and **Open Al Rajhi Retail** (shortcut to the exempted app) |
| UI | `MapActivity` | Tap-to-pick center + radius slider (50–1050 m), saves to prefs |
| UI | `KioskActivity` | The pinned surface, holds `startLockTask()` and the always-accessible **Check Location to Unlock** button |
| Foreground | `KioskNotificationService` | Minimal `specialUse` FGS — hosts the persistent kiosk notification while `KioskActivity` is alive. Zero location work, zero callbacks |
| Policy | `AppDeviceAdminReceiver` | Component registered as Device Owner |
| Policy | `LockManager` | Wraps `DevicePolicyManager` config — `setLockTaskPackages` and `setLockTaskFeatures` |
| Storage | `GeofencePrefs` | SharedPreferences for the saved boundary triple (lat, lng, radius) only |

What stays available inside kiosk (configured via `setLockTaskFeatures`):

- `LOCK_TASK_FEATURE_SYSTEM_INFO` — clock/battery
- `LOCK_TASK_FEATURE_NOTIFICATIONS` — read SMS / alerts (and toggle GPS from quick settings)
- `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` — power dialog
- `LOCK_TASK_FEATURE_KEYGUARD` — normal lock screen still works
- `LOCK_TASK_FEATURE_HOME` — kiosk acts as HOME, can't be escaped to launcher

The default dialer is added to the lock-task allowlist so **incoming calls remain answerable**. The default SMS app is similarly whitelisted so messages remain readable. The Al Rajhi Retail app (`com.alrajhiretailapp`) is also whitelisted as a user-exempted app — launch it from the **Open Al Rajhi Retail** button on the main screen and it continues to work even inside the lock-task kiosk.

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

If GPS is off when you tap the button, you'll see `Could not get a location fix. Make sure GPS is enabled.` Pull down the notification shade (allowed by `LOCK_TASK_FEATURE_NOTIFICATIONS`), toggle Location, retry.

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

Explicitly *not* requested: `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`.

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
