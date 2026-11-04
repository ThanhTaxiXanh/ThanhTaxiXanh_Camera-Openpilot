package vn.thanhtaxixanh.camera.ai

/**
 * MediaCodec PTS for the *offscreen diagnostic decoder* only.
 *
 * The 90 kHz RTP timestamp is a 32-bit counter and eventually wraps. Using
 * that unwrapped-looking value as a codec output correlation key makes a
 * long-running secondary session ambiguous across the wrap. This decoder
 * does not present audio/video and does not pace the preview, so a unique
 * monotonic PTS based on local access-unit receipt time is sufficient for
 * matching each output Image back to its frame age.
 *
 * The resulting PTS is NOT camera exposure time and MUST NOT be interpreted
 * as glass-to-glass latency. Preview retains its original RTP PTS.
 */
class OffscreenPresentationClock {
    private var previousUs = -1L

    fun next(receivedElapsedMs: Long): Long {
        require(receivedElapsedMs >= 0L &&
            receivedElapsedMs <= (Long.MAX_VALUE - 1000L) / 1000L)
        val candidate = receivedElapsedMs * 1000L
        val next = if (previousUs < 0L) candidate
            else maxOf(candidate, previousUs + 1L)
        previousUs = next
        return next
    }

    fun reset() { previousUs = -1L }
}
