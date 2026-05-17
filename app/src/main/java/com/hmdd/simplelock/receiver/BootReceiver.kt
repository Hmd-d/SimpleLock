package com.hmdd.simplelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hmdd.simplelock.KioskActivity
import com.hmdd.simplelock.service.GeofenceForegroundService
import com.hmdd.simplelock.util.GeofencePrefs

/**
 * Fail-safe lockdown on boot.
 *
 * Receives both LOCKED_BOOT_COMPLETED (Direct Boot, fires before the user
 * unlocks the keyguard) and BOOT_COMPLETED (after unlock).
 *
 * The bet: if the device shut down while the user was INSIDE the boundary,
 * we must re-engage kiosk *immediately* on power-up — before there is any
 * window for the user to open the app and disable the system. We trust the
 * persisted `isInside` flag rather than waiting for a GPS fix. If it later
 * turns out we're actually outside, the Play Services geofence will fire an
 * EXIT and release the kiosk; in the meantime, the user is safely contained.
 *
 * The receiver is marked `directBootAware="true"` in the manifest and the
 * SharedPreferences live in device-protected storage so this all works
 * pre-unlock.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (!GeofencePrefs.isEnabled(context)) {
            Log.i(TAG, "System disabled — nothing to do on $action")
            return
        }

        // Fail-safe: lock NOW based on last known state, don't wait for GPS.
        if (GeofencePrefs.isInside(context)) {
            Log.i(TAG, "Last known state was INSIDE — engaging kiosk instantly")
            val launch = Intent(context, KioskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launch)
        }

        // After the user unlocks, re-arm the monitor. Foreground services
        // and most Play Services calls require credential-encrypted storage,
        // so this only runs on BOOT_COMPLETED, not LOCKED_BOOT_COMPLETED.
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            GeofenceForegroundService.start(context)
        }
    }

    private companion object { const val TAG = "SimpleLockBoot" }
}
