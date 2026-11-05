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
