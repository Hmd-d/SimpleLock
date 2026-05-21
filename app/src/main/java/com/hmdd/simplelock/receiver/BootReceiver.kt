package com.hmdd.simplelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hmdd.simplelock.KioskActivity
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

/**
 * Re-engages the kiosk after a reboot. Without this, lock task does NOT
 * survive a power cycle — Android forgets it, and the device boots into
 * the user's normal launcher, defeating the geofence enforcement.
 *
 * Flow on BOOT_COMPLETED:
 *   1. Read the persisted "kiosk_active" flag set by MainActivity when the
 *      device was originally pinned (and cleared by releaseKiosk()).
 *   2. If the flag is false → no-op. The user released the kiosk normally
 *      before the reboot, so we should boot into their real launcher.
 *   3. Otherwise re-apply Device Owner policies, re-enable the HOME alias,
 *      and launch KioskActivity. KioskActivity.onResume() then calls
 *      startLockTask() exactly as it does in the normal lock flow.
 *
 * No location work runs here — boot persistence is independent of the
 * manual-on-demand location architecture.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!GeofencePrefs.isKioskActive(context)) return

        val lockManager = LockManager(context)
        lockManager.applyPolicies()
        lockManager.setKioskHomeAliasEnabled(true)

        val launch = Intent(context, KioskActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(launch) }
    }
}
