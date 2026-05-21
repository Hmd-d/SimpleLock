package com.hmdd.simplelock

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.hmdd.simplelock.databinding.ActivityMapBinding
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMapBinding
    private var map: GoogleMap? = null
    private var marker: Marker? = null
    private var circle: Circle? = null
    private var pickedRadius: Float = 100f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hard block: never let the boundary be edited while the kiosk is
        // pinned, otherwise a user with notification access could escape
        // lock task by redrawing the geofence.
        if (LockManager(this).isInLockTask()) {
            Toast.makeText(
                this, R.string.toast_locked_no_boundary_change, Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.seekRadius.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                pickedRadius = (50 + p).toFloat() // 50–1050 m
                binding.tvRadius.text = getString(R.string.radius_format, pickedRadius.toInt())
                marker?.position?.let { drawCircle(it) }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
        })

        binding.btnSave.setOnClickListener {
            val m = marker
            if (m == null) {
                Toast.makeText(this, R.string.toast_tap_map_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val saved = GeofencePrefs.saveBoundary(
                this, m.position.latitude, m.position.longitude, pickedRadius
            )
            if (!saved) {
                // Storage layer refused — kiosk became active while the map
                // was open. Bail out without persisting anything.
                Toast.makeText(
                    this, R.string.toast_locked_no_boundary_change, Toast.LENGTH_LONG
                ).show()
                finish()
                return@setOnClickListener
            }
            Toast.makeText(this, R.string.toast_boundary_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.uiSettings.isZoomControlsEnabled = true

        // Restore any previously saved boundary as the starting state.
        GeofencePrefs.boundary(this)?.let { (lat, lng, r) ->
            val p = LatLng(lat, lng)
            placeMarker(p)
            pickedRadius = r
            val progress = (r - 50).toInt().coerceIn(0, 1000)
            binding.seekRadius.progress = progress
            binding.tvRadius.text = getString(R.string.radius_format, r.toInt())
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(p, 16f))
        }

        googleMap.setOnMapClickListener { latLng -> placeMarker(latLng) }
    }

    private fun placeMarker(p: LatLng) {
        val m = map ?: return
        marker?.remove()
        marker = m.addMarker(MarkerOptions().position(p).title(getString(R.string.boundary_center)))
        drawCircle(p)
    }

    private fun drawCircle(center: LatLng) {
        val m = map ?: return
        circle?.remove()
        circle = m.addCircle(
            CircleOptions()
                .center(center)
                .radius(pickedRadius.toDouble())
                .strokeWidth(4f)
                .strokeColor(0xFF1976D2.toInt())
                .fillColor(0x331976D2)
        )
    }
}
