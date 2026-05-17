package com.hmdd.simplelock.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hmdd.simplelock.KioskActivity
import com.hmdd.simplelock.MainActivity
import com.hmdd.simplelock.R
import com.hmdd.simplelock.SimpleLockApp
import com.hmdd.simplelock.util.GeofenceClientHelper
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

/**
 * Background brain.
 *
 * Two modes:
 *
 *   PASSIVE (default, while unlocked / outside)
 *     - Holds the registered Play Services geofence.
 *     - Almost zero battery — Play Services batches transitions for us.
 *
 *   ACTIVE (while locked / inside)
 *     - Adds an aggressive FusedLocationProvider stream at PRIORITY_HIGH_ACCURACY
 *       with 5s interval / 2s fastest. Every fix recomputes distance to the
 *       saved center. As soon as we are confidently outside the radius we
 *       release the kiosk and drop back to passive — without waiting for the
 *       batched geofence EXIT.
 *
 * Entry-points (call from outside via the companion-object helpers):
 *   start(ctx)         -> service comes up in PASSIVE mode
 *   notifyEnter(ctx)   -> service flips to ACTIVE mode
 *   notifyExit(ctx)    -> service flips back to PASSIVE mode
 *   stop(ctx)          -> tears down both modes
 */
class GeofenceForegroundService : Service() {

    private val helper by lazy { GeofenceClientHelper(this) }
    private val lockManager by lazy { LockManager(this) }
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    @Volatile private var activePolling = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            evaluateForExit(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always keep policies fresh (Device Owner config may have changed).
        lockManager.applyPolicies()

        when (intent?.action) {
            ACTION_ENTER -> startActivePolling()
            ACTION_EXIT -> stopActivePolling()
            else -> registerPassiveGeofence()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopActivePolling()
        helper.unregister()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // PASSIVE mode
    // ------------------------------------------------------------------

    private fun registerPassiveGeofence() {
        val cfg = GeofencePrefs.boundary(this) ?: run {
            stopSelf()
            return
        }
        helper.register(cfg.first, cfg.second, cfg.third)
    }

    // ------------------------------------------------------------------
    // ACTIVE mode
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startActivePolling() {
        if (activePolling) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start active polling: fine-location permission missing")
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        activePolling = true
        Log.i(TAG, "Active polling ON (5s/2s @ HIGH_ACCURACY)")
    }

    private fun stopActivePolling() {
        if (!activePolling) return
        fused.removeLocationUpdates(locationCallback)
        activePolling = false
        Log.i(TAG, "Active polling OFF")
    }

    /**
     * Called for every fresh location while active. We use distance *minus
     * the reading's own accuracy* to avoid releasing on a single noisy fix
     * — i.e. only release when we are confidently outside the radius.
     */
    private fun evaluateForExit(loc: Location) {
        val boundary = GeofencePrefs.boundary(this) ?: return
        val out = FloatArray(1)
        Location.distanceBetween(
            loc.latitude, loc.longitude,
            boundary.first, boundary.second,
            out
        )
        val confidentDistance = out[0] - loc.accuracy
        if (confidentDistance > boundary.third) {
            Log.i(TAG, "Active polling detected EXIT (d=${out[0]}, acc=${loc.accuracy}, r=${boundary.third})")
            handleActiveExit()
        }
    }

    private fun handleActiveExit() {
        // Update state, release kiosk, drop back to passive. The passive
        // geofence will eventually also fire EXIT but by then we're a no-op.
        GeofencePrefs.setInside(this, false)
        sendBroadcast(Intent(KioskActivity.ACTION_RELEASE).setPackage(packageName))
        stopActivePolling()
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun startForegroundCompat() {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, SimpleLockApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "SimpleLockSvc"
        private const val NOTIF_ID = 1001

        const val ACTION_ENTER = "com.hmdd.simplelock.svc.ACTION_ENTER"
        const val ACTION_EXIT = "com.hmdd.simplelock.svc.ACTION_EXIT"

        fun start(ctx: Context) = send(ctx, null)
        fun notifyEnter(ctx: Context) = send(ctx, ACTION_ENTER)
        fun notifyExit(ctx: Context) = send(ctx, ACTION_EXIT)

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GeofenceForegroundService::class.java))
        }

        private fun send(ctx: Context, action: String?) {
            val i = Intent(ctx, GeofenceForegroundService::class.java).apply {
                if (action != null) this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
        }
    }
}
