package com.hmdd.simplelock.util

import android.content.Context

/**
 * Tiny SharedPreferences-backed store for the configured geofence and the
 * "system on/off" toggle from MainActivity.
 */
object GeofencePrefs {

    private const val PREFS = "simplelock_prefs"
    private const val K_LAT = "geofence_lat"
    private const val K_LNG = "geofence_lng"
    private const val K_RADIUS = "geofence_radius_m"
    private const val K_ENABLED = "system_enabled"
    private const val K_INSIDE = "currently_inside"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveBoundary(ctx: Context, lat: Double, lng: Double, radiusMeters: Float) {
        prefs(ctx).edit()
            .putLong(K_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(K_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .putFloat(K_RADIUS, radiusMeters)
            .apply()
    }

    fun boundary(ctx: Context): Triple<Double, Double, Float>? {
        val p = prefs(ctx)
        if (!p.contains(K_LAT)) return null
        return Triple(
            java.lang.Double.longBitsToDouble(p.getLong(K_LAT, 0L)),
            java.lang.Double.longBitsToDouble(p.getLong(K_LNG, 0L)),
            p.getFloat(K_RADIUS, 100f)
        )
    }

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(K_ENABLED, on).apply()

    fun isInside(ctx: Context): Boolean = prefs(ctx).getBoolean(K_INSIDE, false)
    fun setInside(ctx: Context, inside: Boolean) =
        prefs(ctx).edit().putBoolean(K_INSIDE, inside).apply()
}
