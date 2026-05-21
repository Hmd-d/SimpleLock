package com.hmdd.simplelock

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.hmdd.simplelock.databinding.ActivityKioskBinding
import com.hmdd.simplelock.service.KioskNotificationService
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

/**
 * The kiosk surface.
 *
 * Always-on UI: the "Check Location to Unlock" button is anchored to the
 * bottom of the screen and is always visible. It is only briefly disabled
 * during an in-flight location request — every code path re-enables it
 * before exiting, so the user can never get stranded with no way to unlock.
 *
 * Unlock flow:
 *   tap → "Checking GPS…" → high-accuracy fix → compute distance →
 *     outside boundary → stopLockTask() + stop FGS + finishAndRemoveTask()
 *     still inside       → toast, button re-enabled, screen stays kiosk
 *     no fix / failure   → toast, button re-enabled, user can retry
 */
class KioskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskBinding
    private val lockManager by lazy { LockManager(this) }
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private var cancelToken: CancellationTokenSource? = null

    /** Latched by releaseKiosk() so any post-finish relaunch bails out instantly. */
    private var released = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Wake screen when kiosk launches; show over the keyguard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        binding.btnCheckUnlock.setOnClickListener { onCheckUnlockClicked() }
    }

    override fun onResume() {
        super.onResume()
        if (released) {
            // Android relaunched us after release (e.g. lingering HOME
            // dispatch). Bail before re-engaging lock task.
            finishAndRemoveTask()
            return
        }
        // Always-on guarantees:
        lockManager.applyPolicies()
        lockManager.setKioskHomeAliasEnabled(true)
        if (!isInLockTask()) startLockTask()
        KioskNotificationService.start(this)
        // Reset transient UI in case we resumed from a phone call etc.
        setChecking(false)
        binding.tvUnlockStatus.text = ""
        binding.tvKioskMessage.text = getString(R.string.kiosk_message)
    }

    override fun onStop() {
        cancelToken?.cancel()
        cancelToken = null
        super.onStop()
    }

    /** Disable back inside kiosk. Home & recents are blocked by lock task. */
    @Suppress("OVERRIDE_DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() = Unit

    // ------------------------------------------------------------------
    // Unlock check
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun onCheckUnlockClicked() {
        val boundary = GeofencePrefs.boundary(this)
        if (boundary == null) {
            // No boundary saved → nothing meaningful to compare against.
            // Release rather than trap the user forever.
            releaseKiosk(); return
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.permissions_required)); return
        }

        cancelToken?.cancel()
        val cts = CancellationTokenSource().also { cancelToken = it }
        setChecking(true)
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (cts.token.isCancellationRequested) return@addOnSuccessListener
                setChecking(false)
                if (loc == null) {
                    toast(getString(R.string.toast_no_fix)); return@addOnSuccessListener
                }
                handleFix(loc, boundary)
            }
            .addOnFailureListener {
                if (cts.token.isCancellationRequested) return@addOnFailureListener
                setChecking(false)
                toast(getString(R.string.toast_location_failed, it.message ?: "?"))
            }
    }

    private fun handleFix(loc: Location, boundary: Triple<Double, Double, Float>) {
        val out = FloatArray(1)
        Location.distanceBetween(
            loc.latitude, loc.longitude,
            boundary.first, boundary.second,
            out
        )
        val distance = out[0]
        if (distance > boundary.third) {
            toast(getString(R.string.toast_unlocking))
            releaseKiosk()
        } else {
            val remaining = (boundary.third - distance).toInt()
            binding.tvUnlockStatus.text = getString(R.string.kiosk_still_inside, remaining)
            toast(getString(R.string.toast_still_inside))
        }
    }

    private fun releaseKiosk() {
        released = true
        if (isInLockTask()) runCatching { stopLockTask() }
        // Disable our HOME alias BEFORE firing the HOME intent so Android
        // resolves it to the user's real launcher, not back to us.
        lockManager.setKioskHomeAliasEnabled(false)
        KioskNotificationService.stop(this)
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        finishAndRemoveTask()
    }

    // ------------------------------------------------------------------
    // UI state — never leaves the unlock button permanently disabled
    // ------------------------------------------------------------------

    private fun setChecking(on: Boolean) {
        binding.btnCheckUnlock.isEnabled = !on
        binding.tvUnlockStatus.text = if (on) getString(R.string.checking_gps) else ""
        binding.tvUnlockStatus.visibility = View.VISIBLE
    }

    private fun isInLockTask(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        else
            @Suppress("DEPRECATION") am.isInLockTaskMode
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
