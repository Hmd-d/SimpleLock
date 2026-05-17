# SimpleLock

Geofence-driven kiosk lockdown for Android. When the device crosses **into** a configured boundary, the system pins the device using Android's Device Owner lock-task mode. When it crosses **out**, the pin is released and the device returns to normal.

## Architecture

| Layer | Component | Role |
|---|---|---|
| UI | `MainActivity` | Two buttons: set boundary / toggle system |
| UI | `MapActivity` | Tap-to-pick center + radius slider (50–1050 m) |
| UI | `KioskActivity` | The pinned surface, holds `startLockTask()` |
| Background | `GeofenceForegroundService` | Persistent monitor with sticky notification |
| Background | `GeofenceBroadcastReceiver` | Handles `ENTER` / `EXIT` transitions |
| Background | `BootReceiver` | Re-arms the monitor on reboot |
| Policy | `AppDeviceAdminReceiver` | The component registered as Device Owner |
| Policy | `LockManager` | Wraps `DevicePolicyManager` config |
| Storage | `GeofencePrefs` | SharedPreferences for boundary + on/off |

What stays available inside kiosk (configured via `setLockTaskFeatures`):

- `LOCK_TASK_FEATURE_SYSTEM_INFO` — clock/battery
- `LOCK_TASK_FEATURE_NOTIFICATIONS` — read SMS / alerts
- `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` — power dialog
- `LOCK_TASK_FEATURE_KEYGUARD` — normal lock screen still works
- `LOCK_TASK_FEATURE_HOME` — kiosk acts as HOME, can't be escaped to launcher

The default dialer is added to the lock-task allowlist so **incoming calls remain answerable**. The default SMS app is similarly whitelisted so messages remain readable.

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

## Google Maps API key

`MapActivity` needs a Maps SDK key.

- Local builds: create `app/secrets.properties` with `MAPS_API_KEY=AIza...`
- CI: set the `MAPS_API_KEY` repository secret. The workflow injects it before building.

The build succeeds without a key — the map will just render blank until one is provided.

## Building

Locally:
```bash
gradle wrapper          # one-time, regenerates the wrapper jar
./gradlew :app:assembleDebug
```

Or trigger the **Android APK Build** GitHub Action from the Actions tab — the workflow uploads the APK as a build artifact.

## Permissions requested at runtime

- `ACCESS_FINE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION` (Android 10+)
- `POST_NOTIFICATIONS` (Android 13+)

All declared in the manifest; the user grants them via `MainActivity` when toggling the system on.
