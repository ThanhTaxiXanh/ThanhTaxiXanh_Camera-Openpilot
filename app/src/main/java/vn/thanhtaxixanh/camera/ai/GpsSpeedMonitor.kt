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

/** Live GPS-only speed; never infer speed from an old or uncertain fix. */
class GpsSpeedMonitor(private val activity: Activity) {
    private val manager: LocationManager =
        activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var active = false
    @Volatile private var value: Float? = null
    @Volatile private var receivedAt = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasSpeed()) return
            val v = location.speed * 3.6f
            if (v.isFinite() && v in 0f..240f) {
                value = v
                receivedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    fun hasPermission(): Boolean = activity.checkSelfPermission(
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (!hasPermission()) return false
        if (active) return true
        return try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 800L, 0f, listener, Looper.getMainLooper())
            active = true
            true
        } catch (_: Exception) { false }
    }

    fun currentSpeed(): Float? = if (active &&
        SystemClock.elapsedRealtime() - receivedAt < 3500L) value else null

    fun stop() {
        if (active) {
            try { manager.removeUpdates(listener) } catch (_: Exception) {}
        }
        active = false
        value = null
        receivedAt = 0L
    }
}
