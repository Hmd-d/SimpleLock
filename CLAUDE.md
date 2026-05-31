# Instructions for Claude

Read this before doing any work in this repo.

## Persistent standing rules

1. **Keep `README.md` in sync with the code.** Whenever a change alters any of the following, update `README.md` in the same commit:
   - The component map (which files exist and what role each one plays).
   - The lock or unlock flow (steps, button labels, decision points).
   - The set of declared permissions.
   - The set of foreground services or their `foregroundServiceType`.
   - The battery profile or the list of background activities.
   - Version-to-version upgrade notes (the table at the bottom).

   Trivial changes (typo fixes, comment edits, cosmetic refactors that don't change behavior) do not need a README update. When in doubt, err toward updating it.

2. **Bump `versionCode` and `versionName` in `app/build.gradle.kts` on every user-facing change.** The committed `debug.keystore` guarantees in-place upgrades, but only if `versionCode` is strictly increasing. Add a short comment above the bump explaining what the bump is for.

3. **Never commit secrets.** The Google Maps key lives in `secrets.properties` at the project root (gitignored) and as the `MAPS_API_KEY` GitHub Actions secret. The `local.defaults.properties` file holds only the placeholder fallback and is safe to commit.

4. **Don't reintroduce automated background location work** without an explicit user request. The current architecture (v1.4.0+) is intentionally fully manual — every lock/unlock is driven by a user button press. This is the user's deliberate trade-off for 0% background battery drain.

5. **Verify reachability of the unlock button on every change to `KioskActivity` or its layout.** The user must always be able to tap **Check Location to Unlock** while in kiosk mode. The button must remain visible (anchored to the bottom of the screen), and must not stay disabled across any code path — every location-request callback (success, failure, null, cancellation) must re-enable it.

6. **Preserve all existing user-facing features when adding new ones.** Before editing any file involved in lock, unlock, boundary editing, brightness, the four exempted-app shortcuts (on Main *and* Kiosk), boot persistence, the GPS-off pre-flight dialog, or the HOME-alias toggle, identify which flows the file participates in and confirm they still work after your change. If a refactor would behaviorally alter an existing flow, either keep both paths working or stop and ask. The time-based lock added in v1.9.0 explicitly bypasses the location check, but it must not change the location-based lock or unlock paths.

## Project facts

- **Target device:** Android, minSdk 26, targetSdk 34
- **Language:** Kotlin 2.0, AGP 8.5.2, Gradle 8.9, JDK 17
- **App ID:** `com.hmdd.simplelock`
- **Device Owner component:** `com.hmdd.simplelock/.receiver.AppDeviceAdminReceiver`
- **Architecture:** Manual on-demand. See `README.md` § Architecture.

7. **Reboot must be transparent.** A lock active before a power-cycle must continue to enforce exactly as it did before — the absolute `time_lock_until_ms` timestamp keeps ticking against wall-clock time, and the kiosk must be back on screen as soon as the device finishes booting. The current mechanism (v1.9.2+) registers `KioskHomeAlias` as Device Owner's persistent preferred HOME while pinned, so the *system itself* launches the kiosk on boot — do not rely solely on `BootReceiver.startActivity`, which Android 10+ silently blocks under background-activity-launch rules. Pair every engage path with `LockManager.setPersistentHome(true)` and every release path with `setPersistentHome(false)` (cleared **before** firing the explicit HOME intent so unlock lands on the real launcher).

## Working notes

- The sandbox blocks `api.github.com` but allows the `github.com` git protocol. Use `git push` over HTTPS with the user's PAT embedded in the remote URL; don't try the REST API.
- `keytool` is available in the sandbox. The committed `debug.keystore` was generated with `keytool -genkeypair -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android -validity 36500`. Don't regenerate it.
- The GitHub Actions workflow at `.github/workflows/build.yml` builds a debug APK on every push to `main` and uploads it as the `SimpleLock-debug-apk` artifact.
