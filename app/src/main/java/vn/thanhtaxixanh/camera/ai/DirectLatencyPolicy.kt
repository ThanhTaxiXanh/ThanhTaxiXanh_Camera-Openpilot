package vn.thanhtaxixanh.camera.ai

/**
 * Explicit preview buffering profiles for independent Direct RTSP/MediaCodec.
 *
 * H264 inter-prediction prevents simply dropping old arbitrary P-frames.
 * Under overload the freshness-guard mode fails the decoder SESSION and
 * reconnects from the next clean IDR instead of fabricating a valid GOP.
 *
 * These are bounded local queues. Neither setting guarantees low
 * camera-to-glass latency, which also depends on A500S's encoder, the
 * network and Surface composition. Smooth is the V1.2.9 default.
 */
enum class DirectLatencyPolicy(
    val buttonTitle: String,
    val description: String,
    val maxQueuedAccessUnits: Int,
    val enqueueWaitMs: Long,
    val maxQueueAgeMs: Long,
    val maxInputWaitMs: Long,
    val requestCodecLowLatency: Boolean
) {
    SMOOTH(
        "DIRECT: MƯỢT · BỘ ĐỆM GỐC",
        "Giữ cấu hình cũ 48 AU và tối đa 900ms khi hàng đợi đầy",
        48, 900L, 1_800L, 1_500L, false
    ),
    FRESHNESS_GUARD(
        "DIRECT: THỬ GIẢM TÍCH LŨY TRỄ",
        "12 AU, nhận tối đa 80ms, AU cũ hơn 450ms sẽ ngắt để kết nối lại từ IDR",
        12, 80L, 450L, 350L, true
    );

    fun next() = entries[(ordinal + 1) % entries.size]

    enum class AgeStatus { FRESH, INVALID_CLOCK, TOO_OLD }

    fun checkAge(receivedAtMs: Long, observedAtMs: Long): AgeStatus {
        if (receivedAtMs < 0L || observedAtMs < receivedAtMs)
            return AgeStatus.INVALID_CLOCK
        return if (observedAtMs - receivedAtMs > maxQueueAgeMs)
            AgeStatus.TOO_OLD else AgeStatus.FRESH
    }

    fun inputWaitExceeded(waitStartMs: Long, nowMs: Long): Boolean =
        nowMs < waitStartMs || nowMs - waitStartMs > maxInputWaitMs
}
