package com.hmdd.simplelock.util

import android.content.Context
import android.os.Build

/**
 * Persistent state for the geofence system.
 *
 * Stored in *device-protected storage* (`createDeviceProtectedStorageContext`)
 * so BootReceiver can read it during Direct Boot — before the user unlocks
 * the keyguard. This is what makes the fail-safe instant lock on power-up
 * possible without waiting for GPS.
 *
 * Trade-off: device-protected storage is not encrypted with the user's
 * credential. The data here (boundary coords, enabled flag, inside flag) is
 * non-sensitive — the fail-safe guarantee is worth it.
 */
object GeofencePrefs {

    private const val PREFS = "simplelock_prefs"
    private const val K_LAT = "geofence_lat"
    private const val K_LNG = "geofence_lng"
    private const val K_RADIUS = "geofence_radius_m"
    private const val K_ENABLED = "system_enabled"
    private const val K_INSIDE = "currently_inside"

    private fun ctx(c: Context): Context {
        val app = c.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            app.createDeviceProtectedStorageContext()
        else app
    }

    private fun prefs(c: Context) =
        ctx(c).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveBoundary(c: Context, lat: Double, lng: Double, radiusMeters: Float) {
        prefs(c).edit()
            .putLong(K_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(K_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .putFloat(K_RADIUS, radiusMeters)
            .apply()
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

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) =
        prefs(c).edit().putBoolean(K_ENABLED, on).apply()

    fun isInside(c: Context): Boolean = prefs(c).getBoolean(K_INSIDE, false)
    fun setInside(c: Context, inside: Boolean) =
        prefs(c).edit().putBoolean(K_INSIDE, inside).apply()
}
