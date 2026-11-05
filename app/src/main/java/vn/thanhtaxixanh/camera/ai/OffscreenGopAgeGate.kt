package vn.thanhtaxixanh.camera.ai

/**
 * Receipt-age and IDR admission gate for the secondary offscreen H264 codec.
 * A stale P-frame poisons the prediction chain; only a new complete IDR can
 * resume the offscreen decoder. Receipt clocks are not exposure timestamps.
 */
class OffscreenGopAgeGate(private val maxAgeMs: Long = 550L) {
    init { require(maxAgeMs in 100L..5_000L) }

    enum class Age { CURRENT, STALE, INVALID_CLOCK }
    enum class Admission { ACCEPT, AWAIT_IDR, STALE, INVALID_CLOCK }

    private val h264 = H264SecondarySyncGate()

    fun age(capturedAtMs: Long, nowMs: Long): Age = when {
        capturedAtMs < 0L || nowMs < capturedAtMs -> Age.INVALID_CLOCK
        nowMs - capturedAtMs > maxAgeMs -> Age.STALE
        else -> Age.CURRENT
    }

    @Synchronized fun admit(
        annexB: ByteArray, capturedAtMs: Long, nowMs: Long
    ): Admission {
        when (age(capturedAtMs, nowMs)) {
            Age.STALE -> { h264.reset(); return Admission.STALE }
            Age.INVALID_CLOCK -> {
                h264.reset()
                return Admission.INVALID_CLOCK
            }
            Age.CURRENT -> Unit
        }
        return if (h264.accept(annexB)) Admission.ACCEPT
            else Admission.AWAIT_IDR
    }

    @Synchronized fun reset() = h264.reset()
    @Synchronized fun awaitingIdr(): Boolean = h264.awaitingIdr()
}
