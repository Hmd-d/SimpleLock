package com.hmdd.simplelock.util

import android.content.Context

/**
 * Persistent state for the boundary.
 *
 * In the manual architecture there is no "enabled" toggle and no cached
 * inside/outside state — every interaction is a one-shot location check
 * driven by a user button press. So this store has shrunk to just the
 * configured center+radius.
 *
 * Plain default SharedPreferences are fine; we no longer need
 * device-protected storage (no Direct Boot work anymore).
 */
object GeofencePrefs {

    private const val PREFS = "simplelock_prefs"
    private const val K_LAT = "geofence_lat"
    private const val K_LNG = "geofence_lng"
    private const val K_RADIUS = "geofence_radius_m"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Persists a new boundary. Refuses the write while the kiosk is pinned —
     * otherwise a user with notification access could escape lock task by
     * redrawing the geofence to exclude their current location and then
     * unlocking against the freshly-saved boundary. Returns true if the
     * boundary was saved, false if the call was rejected.
     */
    fun saveBoundary(c: Context, lat: Double, lng: Double, radiusMeters: Float): Boolean {
        if (LockManager(c).isInLockTask()) return false
        prefs(c).edit()
            .putLong(K_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(K_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .putFloat(K_RADIUS, radiusMeters)
            .apply()
        return true
    }

    fun boundary(c: Context): Triple<Double, Double, Float>? {
        val p = prefs(c)
        if (!p.contains(K_LAT)) return null
        return Triple(
            java.lang.Double.longBitsToDouble(p.getLong(K_LAT, 0L)),
            java.lang.Double.longBitsToDouble(p.getLong(K_LNG, 0L)),
            p.getFloat(K_RADIUS, 100f)
        )
    }
}
