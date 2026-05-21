package com.hmdd.simplelock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.hmdd.simplelock.databinding.ActivityMainBinding
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

/**
 * Entry-point UI.
 *
 * Two actions:
 *   - "Set Boundary"          → opens MapActivity. Free to change anytime
 *                                (kiosk can't be active while you're here).
 *   - "Verify Location & Lock" → primary action. Pulls a single high-accuracy
 *                                fix; if inside the saved boundary, hands off
 *                                to KioskActivity which engages lock task.
 *                                If outside, shows a toast and stays unlocked.
 *
 * No automatic background work happens anywhere in the app — see project
 * README for the on-demand architecture.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val lockManager by lazy { LockManager(this) }
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private var cancelToken: CancellationTokenSource? = null

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) ensureLocationEnabledThen { verifyAndLock() }
        else toast(getString(R.string.permissions_required))
    }

    /** Result of the system "turn Location on?" dialog; OK → retry the verify. */
    private val locationResolution = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) verifyAndLock()
        else toast(getString(R.string.toast_enable_location))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSetBoundary.setOnClickListener {
            if (lockManager.isInLockTask()) {
                toast(getString(R.string.toast_locked_no_boundary_change)); return@setOnClickListener
            }
            startActivity(Intent(this, MapActivity::class.java))
        }
        binding.btnVerifyLock.setOnClickListener { onVerifyAndLockClicked() }
        binding.btnOpenAlrajhi.setOnClickListener {
            launchExempted(LockManager.ALRAJHI_RETAIL_PACKAGE, getString(R.string.app_alrajhi))
        }
        binding.btnOpenDialer.setOnClickListener {
            launchExempted(LockManager.GOOGLE_DIALER_PACKAGE, getString(R.string.app_google_dialer))
        }
        binding.btnOpenContacts.setOnClickListener {
            launchExempted(LockManager.GOOGLE_CONTACTS_PACKAGE, getString(R.string.app_google_contacts))
        }
        binding.btnOpenMms.setOnClickListener {
            launchExempted(LockManager.AOSP_MMS_PACKAGE, getString(R.string.app_aosp_mms))
        }
        setupBrightnessControl()
    }

    /**
     * Live system-brightness slider. Reads the current value once, then writes
     * back on every change via LockManager.setSystemBrightness() — which uses
     * Device Owner's setSystemSetting and therefore does not prompt the user.
     */
    private fun setupBrightnessControl() {
        val initial = lockManager.currentSystemBrightness()
        binding.seekBarBrightness.progress = initial
        binding.tvBrightnessStatus.text = brightnessLabel(initial)
        binding.seekBarBrightness.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?, progress: Int, fromUser: Boolean
                ) {
                    val clamped = progress.coerceAtLeast(1)
                    binding.tvBrightnessStatus.text = brightnessLabel(clamped)
                    if (fromUser) lockManager.setSystemBrightness(clamped)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            }
        )
    }

    private fun brightnessLabel(value: Int): String =
        getString(R.string.brightness_status, (value * 100) / 255)

    /** Launches a user-exempted (lock-task whitelisted) app by package, or toasts if absent. */
    private fun launchExempted(pkg: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast(getString(R.string.toast_app_missing, label))
            return
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onStop() {
        cancelToken?.cancel()
        cancelToken = null
        super.onStop()
    }

    // ------------------------------------------------------------------
    // UI state
    // ------------------------------------------------------------------

    private fun refreshUi() {
        val ownerOk = lockManager.isDeviceOwner()
        val cfg = GeofencePrefs.boundary(this)
        binding.tvOwnerStatus.text = getString(
            if (ownerOk) R.string.status_owner_yes else R.string.status_owner_no
        )
        binding.tvBoundaryStatus.text = cfg?.let {
            getString(R.string.status_boundary_set, it.first, it.second, it.third)
        } ?: getString(R.string.status_boundary_unset)
        // Always restore a clean idle state on resume.
        setChecking(false)
    }

    private fun setChecking(on: Boolean) {
        binding.tvVerifying.visibility = if (on) View.VISIBLE else View.GONE
        // While the kiosk is pinned (e.g. user pulled the shade and came
        // back), Set Boundary stays disabled regardless of the check state.
        binding.btnSetBoundary.isEnabled = !on && !lockManager.isInLockTask()
        binding.btnVerifyLock.isEnabled = !on
    }

    // ------------------------------------------------------------------
    // Verify & lock
    // ------------------------------------------------------------------

    private fun onVerifyAndLockClicked() {
        if (GeofencePrefs.boundary(this) == null) {
            toast(getString(R.string.toast_no_boundary)); return
        }
        if (!lockManager.isDeviceOwner()) {
            toast(getString(R.string.toast_not_owner)); return
        }
        ensurePermissionsThenVerify()
    }

    private fun ensurePermissionsThenVerify() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ensureLocationEnabledThen { verifyAndLock() }
        else requestPerms.launch(missing.toTypedArray())
    }

    /**
     * Pre-flight: if Location is off, surface the standard Play Services
     * system dialog asking the user to enable it. Avoids the lock-flow
     * equivalent of the kiosk's "no fix → toast → confused user" trap.
     */
    private fun ensureLocationEnabledThen(onReady: () -> Unit) {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 0L
        ).build()
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    runCatching {
                        locationResolution.launch(
                            IntentSenderRequest.Builder(e.resolution).build()
                        )
                    }.onFailure { toast(getString(R.string.toast_enable_location)) }
                } else {
                    toast(getString(R.string.toast_enable_location))
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun verifyAndLock() {
        val boundary = GeofencePrefs.boundary(this) ?: return
        if (!hasFineLocation()) {
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
        if (distance <= boundary.third) {
            // Inside → hand off to kiosk. It starts FGS + lock task itself.
            lockManager.applyPolicies()
            lockManager.setKioskHomeAliasEnabled(true)
            startActivity(Intent(this, KioskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        } else {
            val excess = (distance - boundary.third).toInt()
            toast(getString(R.string.toast_outside_boundary, excess))
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
