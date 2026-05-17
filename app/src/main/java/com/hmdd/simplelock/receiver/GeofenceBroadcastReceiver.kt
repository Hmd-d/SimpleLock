package com.hmdd.simplelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.hmdd.simplelock.KioskActivity
import com.hmdd.simplelock.util.GeofencePrefs

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofence error code=${event.errorCode}")
            return
        }

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> enter(context)
            Geofence.GEOFENCE_TRANSITION_EXIT -> exit(context)
            else -> Unit
        }
    }

    private fun enter(context: Context) {
        Log.i(TAG, "ENTER → engaging kiosk")
        GeofencePrefs.setInside(context, true)
        // Launching the kiosk activity into a new task brings the lock-task
        // surface foreground; KioskActivity will call startLockTask() itself.
        val launch = Intent(context, KioskActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launch)
    }

    private fun exit(context: Context) {
        Log.i(TAG, "EXIT → releasing kiosk")
        GeofencePrefs.setInside(context, false)
        // Tell the (possibly running) kiosk activity to release lock task.
        val release = Intent(KioskActivity.ACTION_RELEASE).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(release)
    }

    companion object { private const val TAG = "SimpleLockFence" }
}
