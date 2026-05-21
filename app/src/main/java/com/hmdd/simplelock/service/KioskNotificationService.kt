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

/**
 * Minimal foreground service.
 *
 * Exists for one job only: keep the persistent "device locked — kiosk mode"
 * notification visible while [com.hmdd.simplelock.KioskActivity] is alive.
 * Performs ZERO location work, has no callbacks, no polling, no geofence
 * registration. Battery cost ≈ 0 — Android schedules nothing for it.
 *
 * Lifecycle is owned by KioskActivity: started in onResume, stopped in
 * releaseKiosk(). If the activity is killed and recreated, this service
 * stops/starts cleanly with it.
 *
 * Declared as `foregroundServiceType="specialUse"` because the service
 * doesn't do location/sync/media/etc work — its purpose is purely to host
 * the kiosk notification while Device Owner lock-task is engaged.
 */
class KioskNotificationService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

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
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, KioskNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KioskNotificationService::class.java))
        }
    }
}
