package com.hmdd.simplelock.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.hmdd.simplelock.util.LockManager

class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // First-time device-owner provisioning may complete here. Lay down
        // baseline lock-task policies immediately.
        LockManager(context).applyPolicies()
    }
}
