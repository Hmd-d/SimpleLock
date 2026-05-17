package com.hmdd.simplelock

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hmdd.simplelock.databinding.ActivityKioskBinding
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

/**
 * The kiosk surface. While the device is inside the configured boundary, this
 * activity is foreground + pinned via DevicePolicyManager.startLockTask(), so
 * the user cannot reach settings or other apps. The user can still:
 *   - read notifications (LOCK_TASK_FEATURE_NOTIFICATIONS),
 *   - see system info (LOCK_TASK_FEATURE_SYSTEM_INFO),
 *   - receive incoming calls (dialer is whitelisted by LockManager).
 *
 * On geofence EXIT, GeofenceBroadcastReceiver fires ACTION_RELEASE and this
 * activity releases lock task and finishes.
 */
class KioskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskBinding
    private val lockManager by lazy { LockManager(this) }

    private val releaseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RELEASE) releaseAndFinish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        ContextCompat.registerReceiver(
            this,
            releaseReceiver,
            IntentFilter(ACTION_RELEASE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        // Re-apply policies in case Device Owner status was just granted.
        lockManager.applyPolicies()
        if (!isInLockTask()) startLockTask()
        binding.tvKioskMessage.text = getString(R.string.kiosk_message)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(releaseReceiver) }
        super.onDestroy()
    }

    /** Disable back/home/recents inside kiosk. Recents and home are handled by
     *  acting as HOME + lock task; back is suppressed here. */
    @Suppress("OVERRIDE_DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() = Unit

    private fun releaseAndFinish() {
        if (isInLockTask()) runCatching { stopLockTask() }
        GeofencePrefs.setInside(this, false)
        finishAndRemoveTask()
    }

    private fun isInLockTask(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        else
            @Suppress("DEPRECATION") am.isInLockTaskMode
    }

    companion object {
        const val ACTION_RELEASE = "com.hmdd.simplelock.action.RELEASE_KIOSK"
    }
}
