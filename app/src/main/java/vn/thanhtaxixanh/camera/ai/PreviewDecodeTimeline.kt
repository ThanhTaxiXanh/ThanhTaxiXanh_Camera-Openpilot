package vn.thanhtaxixanh.camera.ai

import kotlin.math.ceil

/**
 * Bounded local correlator of H264 AU *receipt* to MediaCodec output callback.
 *
 * This excludes camera exposure, encoder latency, RTP transit to the phone,
 * hardware Surface buffer presentation and display scanout. In particular,
 * low receipt-to-output age does NOT prove low glass-to-glass latency.
 *
 * All access is serialized with short synchronized methods; no native
 * MediaCodec objects, Android clocks or image buffers are retained.
 */
class PreviewDecodeTimeline(
    private val maxPending: Int = 96,
    private val sampleWindow: Int = 128
) {
    init {
        require(maxPending in 8..512)
        require(sampleWindow in 8..512)
    }

    private val submittedAt = LinkedHashMap<Long, Long>()
    private val recent = ArrayDeque<Long>()
    private var decodedMatched = 0L
    private var missingOrEvicted = 0L
    private var invalidClock = 0L
    private var evicted = 0L

    data class Snapshot(
        val matched: Long,
        val unmatched: Long,
        val evicted: Long,
        val invalidClock: Long,
        val pending: Int,
        val lastReceiptToDecoderMs: Long?,
        val p95ReceiptToDecoderMs: Long?,
        val maxReceiptToDecoderMs: Long?
    )

    @Synchronized fun submitted(ptsUs: Long, receiptAtMs: Long) {
        if (ptsUs < 0L || receiptAtMs < 0L) {
            invalidClock++
            return
        }
        if (submittedAt.containsKey(ptsUs)) {
            // PTS reuse invalidates previous correlation, not the decoder.
            evicted++
        }
        submittedAt[ptsUs] = receiptAtMs
        while (submittedAt.size > maxPending) {
            val oldest = submittedAt.keys.first()
            submittedAt.remove(oldest)
            evicted++
        }
    }

    @Synchronized fun forget(ptsUs: Long) {
        submittedAt.remove(ptsUs)
    }

    /**
     * Timestamp must be MediaCodec.BufferInfo.presentationTimeUs, not
     * callback wall time or a guessed frame number.
     */
    @Synchronized fun emitted(
        ptsUs: Long,
        callbackAtMs: Long
    ): Long? {
        val at = submittedAt.remove(ptsUs)
        if (at == null) {
            missingOrEvicted++
            return null
        }
        if (callbackAtMs < at || callbackAtMs < 0L) {
            invalidClock++
            return null
        }
        val age = callbackAtMs - at
        recent.addLast(age)
        if (recent.size > sampleWindow) recent.removeFirst()
        decodedMatched++
        return age
    }

    @Synchronized fun snapshot(): Snapshot {
        if (recent.isEmpty()) return Snapshot(
            decodedMatched,missingOrEvicted,evicted,invalidClock,
            submittedAt.size,null,null,null)
        val sorted = recent.sorted()
        val p95Index = (ceil(.95 * sorted.size).toInt() - 1)
            .coerceIn(0,sorted.lastIndex)
        return Snapshot(
            decodedMatched,missingOrEvicted,evicted,invalidClock,
            submittedAt.size,recent.last(),sorted[p95Index],sorted.last())
    }

    @Synchronized fun clear() {
        submittedAt.clear()
        recent.clear()
    }
}
