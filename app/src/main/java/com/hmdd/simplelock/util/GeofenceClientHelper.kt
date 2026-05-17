package com.hmdd.simplelock.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.hmdd.simplelock.receiver.GeofenceBroadcastReceiver

/**
 * Thin wrapper around GeofencingClient.
 */
class GeofenceClientHelper(private val context: Context) {

    private val client: GeofencingClient = LocationServices.getGeofencingClient(context)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE_EVENT
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun register(lat: Double, lng: Double, radiusMeters: Float) {
        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(lat, lng, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .setLoiteringDelay(5_000)
            .build()

        val request = GeofencingRequest.Builder()
            // Fire ENTER immediately if we are already inside when registering.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(fence)
            .build()

        // Best-effort: remove any previous fence first to avoid duplicates.
        client.removeGeofences(pendingIntent)
        client.addGeofences(request, pendingIntent)
    }

    fun unregister() {
        client.removeGeofences(pendingIntent)
    }

    companion object {
        const val FENCE_ID = "simplelock_boundary"
        const val ACTION_GEOFENCE_EVENT = "com.hmdd.simplelock.GEOFENCE_EVENT"
        private const val REQUEST_CODE = 0x5104
    }
}
