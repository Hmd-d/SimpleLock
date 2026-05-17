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
 * Two top-level modes:
 *
 *   PASSIVE (default, while unlocked / outside the boundary)
 *     - Holds the registered Play Services geofence.
 *     - Near-zero battery cost — Play Services batches transitions for us.
 *
 *   ACTIVE (while locked / inside the boundary)
 *     - Adds a FusedLocationProvider stream at PRIORITY_HIGH_ACCURACY.
 *     - Two sub-tiers, picked from each new fix's distance-to-edge:
 *         FAST: interval 5s / min 2s — used in the outer ring near the edge
 *               (≤ NEAR_BUFFER_M from boundary). Sub-10s exit detection.
 *         SLOW: interval 30s / min 15s — used while well inside the boundary,
 *               where exits are physically impossible in <30s. Saves battery
 *               for the long stretches where the user is just sitting inside.
 *     - When confidently outside (distance - reading.accuracy > radius), the
 *       service releases the kiosk and drops back to PASSIVE.
 *
 * Mode transitions are driven by callers via the companion helpers:
 *   start(ctx)         -> service comes up in PASSIVE
 *   notifyEnter(ctx)   -> service flips to ACTIVE (initial tier = FAST)
 *   notifyExit(ctx)    -> service flips back to PASSIVE
 *   stop(ctx)          -> tears everything down
 */
class GeofenceForegroundService : Service() {

    private enum class PollTier { OFF, FAST, SLOW }

    private val helper by lazy { GeofenceClientHelper(this) }
    private val lockManager by lazy { LockManager(this) }
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    @Volatile private var currentTier: PollTier = PollTier.OFF

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            evaluateAndAdapt(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Come up foreground in PASSIVE-flavoured notification.
        postNotification(active = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always keep policies fresh.
        lockManager.applyPolicies()

        when (intent?.action) {
            ACTION_ENTER -> {
                postNotification(active = true)
                // Start aggressive; the first fix will adapt down to SLOW if
                // we're actually well inside.
                setPollTier(PollTier.FAST)
            }
            ACTION_EXIT -> {
                setPollTier(PollTier.OFF)
                postNotification(active = false)
            }
            else -> registerPassiveGeofence()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        setPollTier(PollTier.OFF)
        helper.unregister()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // PASSIVE
    // ------------------------------------------------------------------

    private fun registerPassiveGeofence() {
        val cfg = GeofencePrefs.boundary(this) ?: run {
            stopSelf()
            return
        }
        helper.register(cfg.first, cfg.second, cfg.third)
    }

    // ------------------------------------------------------------------
    // ACTIVE tiers
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun setPollTier(tier: PollTier) {
        if (tier == currentTier) return

        // Stopping is unconditional.
        if (tier == PollTier.OFF) {
            if (currentTier != PollTier.OFF) {
                fused.removeLocationUpdates(locationCallback)
            }
            currentTier = PollTier.OFF
            Log.i(TAG, "Polling -> OFF")
            return
        }

        // Switching tier requires permission to actually subscribe.
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot poll: fine-location permission missing")
            return
        }

        val (interval, fastest) = when (tier) {
            PollTier.FAST -> 5_000L to 2_000L
            PollTier.SLOW -> 30_000L to 15_000L
            else -> error("unreachable")
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(fastest)
            .setWaitForAccurateLocation(false)
            .build()

        // Atomically swap streams: drop old, install new with the same callback.
        if (currentTier != PollTier.OFF) {
            fused.removeLocationUpdates(locationCallback)
        }
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        currentTier = tier
        Log.i(TAG, "Polling -> $tier (interval=${interval}ms, fastest=${fastest}ms)")
    }

    /**
     * Per-fix decision. Either pre-empt exit, or adapt the polling tier based
     * on how close to the boundary edge we are right now.
     */
    private fun evaluateAndAdapt(loc: Location) {
        val boundary = GeofencePrefs.boundary(this) ?: return
        val out = FloatArray(1)
        Location.distanceBetween(
            loc.latitude, loc.longitude,
            boundary.first, boundary.second,
            out
        )
        val distanceToCenter = out[0]
        val distanceToEdge = boundary.third - distanceToCenter
        // Subtract reading accuracy to avoid releasing on a single noisy fix.
        val confidentDistance = distanceToCenter - loc.accuracy

        if (confidentDistance > boundary.third) {
            Log.i(
                TAG,
                "EXIT detected by active polling " +
                    "(d=$distanceToCenter, acc=${loc.accuracy}, r=${boundary.third})"
            )
            handleActiveExit()
            return
        }

        // Still inside. Switch tier based on proximity to the edge.
        val nextTier = if (distanceToEdge < NEAR_BUFFER_M) PollTier.FAST else PollTier.SLOW
        setPollTier(nextTier)
    }

    private fun handleActiveExit() {
        GeofencePrefs.setInside(this, false)
        sendBroadcast(Intent(KioskActivity.ACTION_RELEASE).setPackage(packageName))
        setPollTier(PollTier.OFF)
        postNotification(active = false)
    }

    // ------------------------------------------------------------------
    // Foreground notification (re-posted whenever mode changes)
    // ------------------------------------------------------------------

    private fun postNotification(active: Boolean) {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = getString(
            if (active) R.string.notif_text_active else R.string.notif_text
        )
        val notif: Notification = NotificationCompat.Builder(this, SimpleLockApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        // Safe to call startForeground multiple times on an already-foreground
        // service — it just updates the notification.
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

        /** Distance-to-edge threshold (m) below which we use FAST polling. */
        private const val NEAR_BUFFER_M = 30f

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
