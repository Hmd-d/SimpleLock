package com.hmdd.simplelock.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hmdd.simplelock.MainActivity
import com.hmdd.simplelock.R
import com.hmdd.simplelock.SimpleLockApp
import com.hmdd.simplelock.util.GeofenceClientHelper
import com.hmdd.simplelock.util.GeofencePrefs
import com.hmdd.simplelock.util.LockManager

/**
 * Holds the process alive while geofence monitoring is active. Doesn't poll
 * location itself — it just ensures the Play Services geofence registration
 * stays installed and re-applies device-owner policies after configuration
 * changes.
 */
class GeofenceForegroundService : Service() {

    private val helper by lazy { GeofenceClientHelper(this) }
    private val lockManager by lazy { LockManager(this) }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lockManager.applyPolicies()
        val cfg = GeofencePrefs.boundary(this)
        if (cfg == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val (lat, lng, radius) = cfg
        helper.register(lat, lng, radius)
        return START_STICKY
    }

    override fun onDestroy() {
        helper.unregister()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    companion object {
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, GeofenceForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GeofenceForegroundService::class.java))
        }
    }
}
