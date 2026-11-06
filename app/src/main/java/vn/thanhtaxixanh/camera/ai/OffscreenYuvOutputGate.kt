package vn.thanhtaxixanh.camera.ai

/**
 * Two-phase freshness and GOP lease check for offscreen MediaCodec YUV output.
 *
 * The network/RTP thread may revoke an H264 prediction chain while the
 * sidecar worker is copying native Image planes. Validate both BEFORE the
 * copy and AFTER the copy, just before exposing the pixels to the RAW/ONNX
 * callback. This guard never holds a network or Surface preview lock.
 *
 * Times are local elapsedRealtime receipt/copy clocks; NEVER exposure time.
 */
class OffscreenYuvOutputGate(
    private val maxFrameAgeMs: Long = 550L,
    private val minDeliverySpacingMs: Long = 300L
) {
    enum class Verdict {
        DELIVER, INACTIVE, EPOCH_REVOKED, RESET_PENDING,
        INVALID_CLOCK, STALE_FRAME, THROTTLED
    }

    init {
        require(maxFrameAgeMs in 100L..5_000L)
        require(minDeliverySpacingMs in 0L..5_000L)
    }

    fun check(
        expectedEpoch: Long,
        currentEpoch: Long,
        active: Boolean,
        resetPending: Boolean,
        capturedAtMs: Long,
        checkedAtMs: Long,
        lastDeliveredAtMs: Long
    ): Verdict = when {
        !active -> Verdict.INACTIVE
        currentEpoch != expectedEpoch -> Verdict.EPOCH_REVOKED
        resetPending -> Verdict.RESET_PENDING
        capturedAtMs < 0L || checkedAtMs < capturedAtMs ||
            (lastDeliveredAtMs >= 0L && checkedAtMs < lastDeliveredAtMs) ->
            Verdict.INVALID_CLOCK
        checkedAtMs - capturedAtMs > maxFrameAgeMs ->
            Verdict.STALE_FRAME
        lastDeliveredAtMs >= 0L &&
            checkedAtMs - lastDeliveredAtMs < minDeliverySpacingMs ->
            Verdict.THROTTLED
        else -> Verdict.DELIVER
    }
}

/**
 * Decoded output age is NOT the same as H264 prediction-chain integrity.
 * A late decoded Image must never reach the RAW/ONNX temporal consumer,
 * but discarding that output does not make previously queued reference
 * pictures invalid. Restarting the native decoder on EVERY stale Image
 * causes an IDR-dependent reset loop (observed on owner A500S).
 *
 * Invalid physical/monotonic clocks still demand sidecar resync. RTP/AU
 * corruption and stale compressed input remain governed by their own
 * existing fail-closed H264 epoch and GOP gates.
 */
object OffscreenYuvRejectionPolicy {
    enum class Action {
        DROP_ONLY,
        DROP_AND_INVALIDATE_TEMPORAL,
        RESET_SECONDARY_GOP
    }

    fun action(verdict:OffscreenYuvOutputGate.Verdict):Action = when(verdict) {
        OffscreenYuvOutputGate.Verdict.STALE_FRAME ->
            Action.DROP_AND_INVALIDATE_TEMPORAL
        OffscreenYuvOutputGate.Verdict.INVALID_CLOCK ->
            Action.RESET_SECONDARY_GOP
        else -> Action.DROP_ONLY
    }
}
