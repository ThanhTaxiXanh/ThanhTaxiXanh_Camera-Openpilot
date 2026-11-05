package vn.thanhtaxixanh.camera.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/**
 * Passive GPS speed, fail-closed on cached/stale/inaccurate fixes.
 * NOT a calibrated vehicle speed sensor and never feeds ADAS warnings.
 */
class GpsSpeedMonitor(private val activity: Activity) {
    private val manager: LocationManager =
        activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val trust = GpsSpeedTrustGate()

    @Volatile private var active = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // All timestamps are in the same monotonic boot timebase;
            // Location.time (UTC) cannot safely be mixed with these.
            val accuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond
                else null
            trust.observe(
                speedMps = if (location.hasSpeed()) location.speed else null,
                fixElapsedNs = location.elapsedRealtimeNanos,
                receivedElapsedNs = SystemClock.elapsedRealtimeNanos(),
                speedAccuracyMps = accuracy
            )
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) trust.reset()
        }
    }

    fun hasPermission(): Boolean = activity.checkSelfPermission(
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (!hasPermission()) return false
        if (active) return true
        return try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                trust.reset()
                return false
            }
            trust.reset()
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 800L, 0f, listener, Looper.getMainLooper())
            active = true
            true
        } catch (_: Exception) {
            trust.reset()
            false
        }
    }

    fun currentSpeed(): Float? {
        if (!active || !hasPermission()) return null
        return try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                trust.reset()
                null
            } else trust.currentKmh(SystemClock.elapsedRealtimeNanos())
        } catch (_: Exception) {
            trust.reset()
            null
        }
    }

    fun stop() {
        active = false
        try { manager.removeUpdates(listener) } catch (_: Exception) {}
        trust.reset()
    }
}
