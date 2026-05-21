package com.hmdd.simplelock.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.hmdd.simplelock.receiver.AppDeviceAdminReceiver

/**
 * Wraps DevicePolicyManager interaction.
 *
 * Provisioning the app as Device Owner is a one-time ADB step performed by the
 * developer/owner; see the README for the exact command.
 */
class LockManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val admin: ComponentName =
        ComponentName(context, AppDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * One-shot policy configuration. Idempotent — safe to call on every boot
     * or whenever the user toggles the system on.
     *
     * - Whitelists our package (+ the dialer) for lock task
     * - Enables system info, notifications, global actions and keyguard
     *   so SMS/call alerts and incoming calls remain reachable
     */
    fun applyPolicies() {
        if (!isDeviceOwner()) return

        // Packages allowed to run while pinned. Include the system dialer so
        // incoming calls can be answered, the default SMS app for reading,
        // and the user-exempted apps (Al Rajhi Retail, Google Dialer, Google
        // Contacts, AOSP MMS). distinct() guards against duplicates when
        // the default dialer/SMS app already resolves to one of these.
        val allowed = buildList {
            add(context.packageName)
            telecomDialer()?.let(::add)
            defaultSms()?.let(::add)
            add(ALRAJHI_RETAIL_PACKAGE)
            add(GOOGLE_DIALER_PACKAGE)
            add(GOOGLE_CONTACTS_PACKAGE)
            add(AOSP_MMS_PACKAGE)
        }.distinct().toTypedArray()
        dpm.setLockTaskPackages(admin, allowed)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val features = (
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
                )
            dpm.setLockTaskFeatures(admin, features)
        }
    }

    private fun telecomDialer(): String? = runCatching {
        val tm = context.getSystemService(Context.TELECOM_SERVICE)
            as? android.telecom.TelecomManager
        tm?.defaultDialerPackage
    }.getOrNull()

    private fun defaultSms(): String? = runCatching {
        android.provider.Telephony.Sms.getDefaultSmsPackage(context)
    }.getOrNull()

    companion object {
        const val ALRAJHI_RETAIL_PACKAGE = "com.alrajhiretailapp"
        const val GOOGLE_DIALER_PACKAGE = "com.google.android.dialer"
        const val GOOGLE_CONTACTS_PACKAGE = "com.google.android.contacts"
        const val AOSP_MMS_PACKAGE = "com.android.mms"
    }
}
