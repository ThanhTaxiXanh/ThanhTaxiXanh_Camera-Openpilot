package vn.thanhtaxixanh.camera.ai

/**
 * Fail-closed Camera2 sensor timestamp admission for diagnostic RAW ONNX.
 *
 * Android Image.timestamp is comparable to SystemClock.elapsedRealtimeNanos
 * ONLY when CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME.
 * UNKNOWN timestamps may be in a different clock domain; callback receipt
 * time must NEVER stand in for the optical capture timestamp.
 *
 * A rejected source image must break any pending temporal inference pair.
 * The preview remains independent and never waits for this validator.
 */
class Camera2SensorFrameGate(
    private val maxAgeMs: Long = VisionSafetyGate.MAX_FRAME_AGE_MS
) {
    init { require(maxAgeMs in 1L..30_000L) }

    enum class Verdict {
        ACCEPT, UNKNOWN_TIMEBASE, INVALID_CLOCK, TOO_OLD, NON_MONOTONIC
    }

    data class Decision(
        val verdict: Verdict,
        val captureElapsedMs: Long? = null,
        val receiptAgeMs: Long? = null
    ) {
        val accepted: Boolean get() = verdict == Verdict.ACCEPT
    }

    private var lastAcceptedSensorNs: Long = -1L
    private val maximumAgeNs = maxAgeMs * 1_000_000L

    @Synchronized fun inspect(
        sensorTimestampNs: Long,
        nowElapsedNs: Long,
        timestampSourceRealtime: Boolean
    ): Decision {
        // A camera using UNKNOWN timestamp source cannot safely be aligned
        // with elapsedRealtimeNanos. Do not create spurious fresh ONNX input.
        if (!timestampSourceRealtime) return Decision(Verdict.UNKNOWN_TIMEBASE)
        if (sensorTimestampNs <= 0L || nowElapsedNs <= 0L ||
            sensorTimestampNs > nowElapsedNs) {
            return Decision(Verdict.INVALID_CLOCK)
        }
        // Latest ImageReader callbacks can arrive out of order or duplicate
        // across session edge cases; a re-used image cannot advance temporal AI.
        if (sensorTimestampNs <= lastAcceptedSensorNs)
            return Decision(Verdict.NON_MONOTONIC)
        val ageNs = nowElapsedNs - sensorTimestampNs
        if (ageNs > maximumAgeNs) return Decision(Verdict.TOO_OLD)
        lastAcceptedSensorNs = sensorTimestampNs
        return Decision(Verdict.ACCEPT,
            sensorTimestampNs / 1_000_000L,
            ageNs / 1_000_000L)
    }

    @Synchronized fun reset() {
        lastAcceptedSensorNs = -1L
    }
}
