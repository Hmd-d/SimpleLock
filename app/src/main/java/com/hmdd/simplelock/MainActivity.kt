package com.hmdd.simplelock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.hmdd.simplelock.databinding.ActivityMainBinding
import com.hmdd.simplelock.service.GeofenceForegroundService
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

/**
 * Entry-point UI plus the in-app verification gate.
 *
 * Whenever this activity resumes and the system is ENABLED:
 *   1. All UI is locked behind a "Verifying location..." state.
 *   2. We pull a single high-accuracy fix via FusedLocationProviderClient.
 *   3. We compute distance to the saved center.
 *   4. Inside  -> launch KioskActivity (lock task engaged there).
 *      Outside -> ensure kiosk is released and present unlocked UI.
 *
 * UI protection rules when system is ENABLED:
 *   - "Set Geofence Boundary" is always disabled.
 *   - "Toggle System" is disabled while we believe we're inside the boundary.
 *     The user can only disable the system while explicitly OUTSIDE.
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
        if (granted.values.all { it }) startMonitoring()
        else toast(getString(R.string.permissions_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnSetBoundary.setOnClickListener {
            if (GeofencePrefs.isEnabled(this)) {
                toast(getString(R.string.toast_disable_first)); return@setOnClickListener
            }
            startActivity(Intent(this, MapActivity::class.java))
        }
        binding.btnToggleSystem.setOnClickListener { onToggleClicked() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        if (GeofencePrefs.isEnabled(this)) verifyLocationAndAct()
    }

    override fun onPause() {
        cancelToken?.cancel()
        cancelToken = null
        super.onPause()
    }

    // ------------------------------------------------------------------
    // UI state
    // ------------------------------------------------------------------

    private fun refreshUi() {
        val ownerOk = lockManager.isDeviceOwner()
        val enabled = GeofencePrefs.isEnabled(this)
        val inside = GeofencePrefs.isInside(this)
        val cfg = GeofencePrefs.boundary(this)

        binding.tvOwnerStatus.text = getString(
            if (ownerOk) R.string.status_owner_yes else R.string.status_owner_no
        )
        binding.tvBoundaryStatus.text = cfg?.let {
            getString(R.string.status_boundary_set, it.first, it.second, it.third)
        } ?: getString(R.string.status_boundary_unset)

        binding.btnToggleSystem.text = getString(
            if (enabled) R.string.btn_toggle_off else R.string.btn_toggle_on
        )

        // UI protection. When enabled: boundary is locked. Toggle off only
        // permitted when system thinks we're outside.
        binding.btnSetBoundary.isEnabled = !enabled
        binding.btnToggleSystem.isEnabled = !enabled || !inside

        binding.tvVerifying.visibility = android.view.View.GONE
    }

    private fun setVerifying(on: Boolean) {
        binding.tvVerifying.visibility =
            if (on) android.view.View.VISIBLE else android.view.View.GONE
        // While verifying, lock everything down — including the toggle —
        // so the user can't slip a tap in between fix and decision.
        if (on) {
            binding.btnSetBoundary.isEnabled = false
            binding.btnToggleSystem.isEnabled = false
        } else {
            refreshUi()
        }
    }

    // ------------------------------------------------------------------
    // Toggle handler
    // ------------------------------------------------------------------

    private fun onToggleClicked() {
        val enabled = GeofencePrefs.isEnabled(this)
        if (enabled) {
            // Per UI protection: only allow off while outside.
            if (GeofencePrefs.isInside(this)) {
                toast(getString(R.string.toast_must_be_outside)); return
            }
            GeofencePrefs.setEnabled(this, false)
            GeofenceForegroundService.stop(this)
            toast(getString(R.string.toast_disabled))
            refreshUi()
            return
        }
        if (GeofencePrefs.boundary(this) == null) {
            toast(getString(R.string.toast_no_boundary)); return
        }
        if (!lockManager.isDeviceOwner()) {
            toast(getString(R.string.toast_not_owner)); return
        }
        ensurePermissionsThenStart()
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            needed += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMonitoring()
        else requestPerms.launch(missing.toTypedArray())
    }

    private fun startMonitoring() {
        GeofencePrefs.setEnabled(this, true)
        lockManager.applyPolicies()
        GeofenceForegroundService.start(this)
        toast(getString(R.string.toast_enabled))
        refreshUi()
        verifyLocationAndAct()
    }

    // ------------------------------------------------------------------
    // Single location check — the heart of the fail-safe
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun verifyLocationAndAct() {
        val boundary = GeofencePrefs.boundary(this) ?: return
        if (!hasFineLocation()) {
            toast(getString(R.string.permissions_required)); return
        }
        cancelToken?.cancel()
        val cts = CancellationTokenSource().also { cancelToken = it }
        setVerifying(true)
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (cts.token.isCancellationRequested) return@addOnSuccessListener
                if (loc == null) {
                    setVerifying(false)
                    toast(getString(R.string.toast_no_fix))
                    return@addOnSuccessListener
                }
                applyVerifiedLocation(loc, boundary)
            }
            .addOnFailureListener {
                if (cts.token.isCancellationRequested) return@addOnFailureListener
                setVerifying(false)
                toast(getString(R.string.toast_location_failed, it.message ?: "?"))
            }
    }

    private fun applyVerifiedLocation(
        loc: Location,
        boundary: Triple<Double, Double, Float>
    ) {
        val out = FloatArray(1)
        Location.distanceBetween(
            loc.latitude, loc.longitude,
            boundary.first, boundary.second,
            out
        )
        val distance = out[0]
        val inside = distance <= boundary.third
        GeofencePrefs.setInside(this, inside)

        if (inside) {
            // Engage kiosk regardless of what state we thought we were in.
            startActivity(Intent(this, KioskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        } else {
            // Make sure any stale kiosk is released.
            val release = Intent(KioskActivity.ACTION_RELEASE).setPackage(packageName)
            sendBroadcast(release)
        }
        setVerifying(false)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
