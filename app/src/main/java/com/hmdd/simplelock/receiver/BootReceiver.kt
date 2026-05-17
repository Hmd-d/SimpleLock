package com.hmdd.simplelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hmdd.simplelock.service.GeofenceForegroundService
import com.hmdd.simplelock.util.GeofencePrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        if (!GeofencePrefs.isEnabled(context)) return
        GeofenceForegroundService.start(context)
    }
}
