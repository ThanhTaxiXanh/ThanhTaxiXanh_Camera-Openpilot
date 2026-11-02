package vn.thanhtaxixanh.camera.ai

/**
 * RTP presentation timestamp -> monotonic display deadline.
 *
 * This clock does not wait/sleep. The caller schedules MediaCodec output-buffer
 * release on its codec handler; decoder state is preserved even when a LATE
 * decoded picture is suppressed on the presentation surface.
 *
 * Uses a 550 ms presentation cushion because the A500S 300 ms path was seen
 * to stutter while the VLC 800 ms path remained smooth. It is a starting
 * point for measurement, not a guarantee of exactly 800 ms end-to-end delay.
 */
class H264PresentationClock(
    private val cushionNs: Long = 550_000_000L,
    private val lateDropNs: Long = 150_000_000L,
    private val resetLagNs: Long = 850_000_000L
) {
    data class Plan(
        val targetNs: Long,
        val shouldRender: Boolean,
        val rebased: Boolean,
        val lateByNs: Long
    )
    private var anchorPtsUs: Long? = null
    private var anchorNs = 0L
    private var lastPtsUs: Long? = null

    init {
        require(cushionNs in 0..850_000_000L)
        require(lateDropNs > 0)
        require(resetLagNs > lateDropNs)
    }

    @Synchronized
    fun reset() {
        anchorPtsUs = null
        lastPtsUs = null
        anchorNs = 0L
    }

    @Synchronized
    fun plan(presentationTimeUs: Long, nowNs: Long): Plan {
        require(presentationTimeUs >= 0) { "Negative video timestamp" }
        var reset = anchorPtsUs == null
        val previous = lastPtsUs
        val first = anchorPtsUs
        if (first != null) {
            val span = presentationTimeUs - first
            // Timestamp can jump at RTP wrap or stream restart; never leave
            // the picture stuck against an old timeline.
            if (span < 0 || span > 3_600_000_000L ||
                (previous != null && presentationTimeUs + 250_000L < previous))
                reset = true
        }

        if (reset) {
            anchorPtsUs = presentationTimeUs
            anchorNs = nowNs + cushionNs
            lastPtsUs = presentationTimeUs
            return Plan(anchorNs, true, true, 0L)
        }

        var target = anchorNs + (presentationTimeUs - anchorPtsUs!!) * 1000L
        var late = nowNs - target

        // If the producer suffered a prolonged outage, re-anchor to the
        // resumed stream instead of discarding future video indefinitely.
        // Similarly handle unreasonable forward jumps in RTP time.
        if (late > resetLagNs || target - nowNs > 950_000_000L) {
            anchorPtsUs = presentationTimeUs
            anchorNs = nowNs + cushionNs
            target = anchorNs
            late = 0L
            reset = true
        }
        lastPtsUs = presentationTimeUs
        return Plan(target, late <= lateDropNs, reset, late.coerceAtLeast(0L))
    }
}
