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
 * If the device shut down while INSIDE, we re-engage kiosk *immediately* on
 * power-up. We trust the persisted flag rather than waiting for GPS — if it
 * turns out we're actually outside, the active polling started below will
 * notice within a few seconds and release.
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

        val wasInside = GeofencePrefs.isInside(context)
        if (wasInside) {
            Log.i(TAG, "Last known state was INSIDE — engaging kiosk instantly")
            val launch = Intent(context, KioskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launch)
        }

        // Credential-encrypted storage and FusedLocationProvider both require
        // post-unlock, so the foreground service only comes up on the second
        // boot signal.
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            GeofenceForegroundService.start(context)
            if (wasInside) {
                // Kick off active polling so an early exit is detected fast.
                GeofenceForegroundService.notifyEnter(context)
            }
        }
    }

    private companion object { const val TAG = "SimpleLockBoot" }
}
