package vn.thanhtaxixanh.camera.ai

/**
 * RTP PTS -> monotonic MediaCodec INPUT deadline, without holding output buffers.
 *
 * An overdue frame is submitted immediately (as soon as an input slot becomes
 * available). It must NOT re-anchor merely because it is late; that would add
 * another buffer delay precisely when the player needs to catch up.
 *
 * The returned deadline only gates a dedicated input-feeder thread, NEVER the
 * RTSP reader or MediaCodec asynchronous callback.
 */
class RtpInputPacer(
    private val cushionNs: Long = 350_000_000L,
    private val backwardDiscontinuityUs: Long = 250_000L,
    private val forwardDiscontinuityUs: Long = 1_500_000L
) {
    data class Plan(
        val deadlineNs: Long,
        val rebased: Boolean,
        val lateByNs: Long
    )

    private var originPtsUs: Long? = null
    private var lastPtsUs: Long? = null
    private var originNs: Long = 0L

    init {
        require(cushionNs in 0L..850_000_000L)
        require(backwardDiscontinuityUs > 0L)
        require(forwardDiscontinuityUs > 0L)
    }

    @Synchronized fun reset() {
        originPtsUs = null
        lastPtsUs = null
        originNs = 0L
    }

    @Synchronized fun plan(ptsUs: Long, nowNs: Long): Plan {
        require(ptsUs >= 0L)
        val prior = lastPtsUs
        val first = originPtsUs
        val discontinuity = first == null || (prior != null &&
            (ptsUs + backwardDiscontinuityUs < prior ||
                ptsUs - prior > forwardDiscontinuityUs))
        if (discontinuity) {
            originPtsUs = ptsUs
            lastPtsUs = ptsUs
            originNs = nowNs + cushionNs
            return Plan(originNs, true, 0L)
        }
        lastPtsUs = ptsUs
        // Relative PTS is bounded by the size of the media session. The sum
        // operates in monotonic System.nanoTime units.
        val deadline = originNs + (ptsUs - originPtsUs!!) * 1000L
        return Plan(deadline, false, (nowNs - deadline).coerceAtLeast(0L))
    }
}
