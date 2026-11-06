package vn.thanhtaxixanh.camera.ai

/**
 * Pure-JVM, session-local GPS speed freshness gate.
 *
 * Location callbacks can deliver cached fixes. Receipt time is NOT fix time:
 * every speed must be measured against Location.elapsedRealtimeNanos, on the
 * same Android monotonic boot clock as SystemClock.elapsedRealtimeNanos.
 * This class contains no Android dependencies and can be regression-tested.
 *
 * A missing/unreliable newer fix immediately invalidates earlier speed.
 * Duplicate/late fixes must never replace a newer observation. Failure
 * is represented by null, never by a fabricated speed of 0 km/h.
 */
class GpsSpeedTrustGate(
    private val maxFixAgeMs: Long = 3_500L,
    private val maxSpeedAccuracyMps: Float = 5f
) {
    init {
        require(maxFixAgeMs in 500L..30_000L)
        require(maxSpeedAccuracyMps.isFinite() && maxSpeedAccuracyMps > 0f)
    }

    private val maxAgeNs = maxFixAgeMs * 1_000_000L
    private var newestObservedFixNs: Long = -1L
    private var acceptedFixNs: Long = -1L
    private var speedKmh: Float? = null

    /**
     * [speedMps] null means Location.hasSpeed() == false.
     * [speedAccuracyMps] null means the OS did not provide speed accuracy:
     * such samples remain provisional GPS readings, NOT validated ADAS speed.
     */
    @Synchronized fun observe(
        speedMps: Float?,
        fixElapsedNs: Long,
        receivedElapsedNs: Long,
        speedAccuracyMps: Float? = null
    ): Boolean {
        // An older callback is irrelevant even when its payload is malformed.
        // Check ordering BEFORE invalidation: an invalid late cached fix must
        // not erase a newer trusted speed (or falsely extend its lifetime).
        if (fixElapsedNs > 0L && fixElapsedNs <= newestObservedFixNs) return false
        if (fixElapsedNs <= 0L || receivedElapsedNs < 0L ||
            fixElapsedNs > receivedElapsedNs) {
            invalidate()
            return false
        }
        newestObservedFixNs = fixElapsedNs

        val kmh = speedMps?.times(3.6f)
        if (receivedElapsedNs - fixElapsedNs >= maxAgeNs ||
            kmh == null || !kmh.isFinite() || kmh !in 0f..240f ||
            (speedAccuracyMps != null &&
                (!speedAccuracyMps.isFinite() || speedAccuracyMps < 0f ||
                    speedAccuracyMps > maxSpeedAccuracyMps))) {
            invalidate()
            return false
        }

        speedKmh = kmh
        acceptedFixNs = fixElapsedNs
        return true
    }

    /** Returns null if the underlying GPS FIX has expired, even if recently delivered. */
    @Synchronized fun currentKmh(nowElapsedNs: Long): Float? {
        val stamp = acceptedFixNs
        if (stamp <= 0L || nowElapsedNs < stamp ||
            nowElapsedNs - stamp >= maxAgeNs) return null
        return speedKmh
    }

    @Synchronized fun reset() {
        newestObservedFixNs = -1L
        invalidate()
    }

    private fun invalidate() {
        speedKmh = null
        acceptedFixNs = -1L
    }
}
