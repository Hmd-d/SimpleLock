package com.hmdd.simplelock.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
     * True when this device is currently pinned via lock task. Used as the
     * "kiosk is active" signal to block boundary edits, brightness lockout
     * holes, and any other escape paths from outside KioskActivity.
     */
    fun isInLockTask(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        else
            @Suppress("DEPRECATION") am.isInLockTaskMode
    }

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

    /**
     * Registers (or clears) KioskHomeAlias as Device Owner's persistent
     * preferred HOME activity. The system itself launches HOME on every
     * boot, sidestepping the Android 10+ background-activity-launch
     * restriction that makes a BOOT_COMPLETED startActivity unreliable.
     * Pair with setKioskHomeAliasEnabled(true) — the alias must be enabled
     * for the system to be able to resolve to it.
     */
    fun setPersistentHome(enabled: Boolean) {
        if (!isDeviceOwner()) return
        runCatching {
            if (enabled) {
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                val alias = ComponentName(
                    context.packageName,
                    "${context.packageName}.KioskHomeAlias"
                )
                dpm.addPersistentPreferredActivity(admin, filter, alias)
            } else {
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            }
        }
    }

    /**
     * Toggles the HOME intent-filter alias for KioskActivity. Enabled only
     * while the kiosk is genuinely pinned, so finishing it on unlock hands
     * HOME back to the user's real system launcher.
     */
    fun setKioskHomeAliasEnabled(enabled: Boolean) {
        val newState = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, "${context.packageName}.KioskHomeAlias"),
            newState,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Sets the system screen brightness via Device Owner's setSystemSetting,
     * which sidesteps the WRITE_SETTINGS user-grant flow entirely (API 28+).
     * Switches brightness mode to MANUAL first so the value actually takes
     * effect (auto-brightness would otherwise immediately overwrite it).
     * Returns true if the change was applied.
     */
    fun setSystemBrightness(value: Int): Boolean {
        if (!isDeviceOwner()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val clamped = value.coerceIn(1, 255)
        return runCatching {
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, "0")
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, clamped.toString())
        }.isSuccess
    }

    /** Reads the current system brightness (0–255), defaulting to mid-scale on failure. */
    fun currentSystemBrightness(): Int = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128).coerceIn(1, 255)

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
