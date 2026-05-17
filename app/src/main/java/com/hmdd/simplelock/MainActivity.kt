package com.hmdd.simplelock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hmdd.simplelock.databinding.ActivityMainBinding
import com.hmdd.simplelock.service.GeofenceForegroundService
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val lockManager by lazy { LockManager(this) }

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
            startActivity(Intent(this, MapActivity::class.java))
        }
        binding.btnToggleSystem.setOnClickListener { onToggleClicked() }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val ownerOk = lockManager.isDeviceOwner()
        val on = GeofencePrefs.isEnabled(this)
        val cfg = GeofencePrefs.boundary(this)

        binding.tvOwnerStatus.text = getString(
            if (ownerOk) R.string.status_owner_yes else R.string.status_owner_no
        )
        binding.tvBoundaryStatus.text = cfg?.let {
            getString(R.string.status_boundary_set, it.first, it.second, it.third)
        } ?: getString(R.string.status_boundary_unset)
        binding.btnToggleSystem.text = getString(
            if (on) R.string.btn_toggle_off else R.string.btn_toggle_on
        )
    }

    private fun onToggleClicked() {
        if (GeofencePrefs.isEnabled(this)) {
            // Turn off
            GeofencePrefs.setEnabled(this, false)
            GeofenceForegroundService.stop(this)
            toast(getString(R.string.toast_disabled))
            refreshUi()
            return
        }
        // Turn on — needs a boundary + permissions
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
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
