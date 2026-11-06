package vn.thanhtaxixanh.camera.ai

import java.util.concurrent.atomic.AtomicLong

/**
 * Research-only ARGB temporal source from an IJK Surface PixelCopy.
 *
 * PixelCopy may scale/composite an already-decoded frame. This is NOT native
 * A500S YUV, camera exposure time, verified model geometry or a safety HUD.
 * The supplied IntArray is exclusively owned and never mutated afterward.
 */
class IjkArgbVisionBridge(
    private val nowMs: () -> Long,
    private val submit: (OpenpilotInferencePipeline.PairSample) -> Boolean
) {
    enum class Verdict { WAITING_PAIR, SUBMITTED, INVALID, STALE, DISCONTINUITY, SINK_REJECTED }
    data class Metrics(
        val frames: Long, val pairs: Long, val rejected: Long,
        val discontinuities: Long, val lastVerdict: Verdict
    )
    private data class Previous(val pixels: IntArray, val requestMs: Long, val epoch: Long)

    private val sourceEpoch = AtomicLong()
    private var previous: Previous? = null
    private var frames = 0L
    private var pairs = 0L
    private var rejected = 0L
    private var discontinuities = 0L
    private var lastVerdict = Verdict.WAITING_PAIR

    fun signalDiscontinuity() { sourceEpoch.incrementAndGet() }

    @Synchronized fun reset() {
        signalDiscontinuity()
        previous = null
        lastVerdict = Verdict.WAITING_PAIR
    }

    @Synchronized fun metrics(): Metrics =
        Metrics(frames, pairs, rejected, discontinuities, lastVerdict)

    /**
     * atRequestMs comes from SystemClock.elapsedRealtime() when PixelCopy
     * was requested, never an RTP PTS or optical sensor exposure timestamp.
     */
    @Synchronized fun accept(argb: IntArray, atRequestMs: Long): Verdict {
        fun refuse(status: Verdict): Verdict {
            rejected++
            previous = null
            lastVerdict = status
            return status
        }
        if (argb.size != 640 * 480) return refuse(Verdict.INVALID)
        val startEpoch = sourceEpoch.get()
        val now = nowMs()
        if (atRequestMs < 0L || now < atRequestMs ||
            now - atRequestMs > VisionSafetyGate.MAX_FRAME_AGE_MS)
            return refuse(Verdict.STALE)
        val old = previous
        if (startEpoch != sourceEpoch.get()) {
            discontinuities++
            return refuse(Verdict.DISCONTINUITY)
        }
        previous = Previous(argb, atRequestMs, startEpoch)
        frames++
        if (old == null) {
            lastVerdict = Verdict.WAITING_PAIR
            return lastVerdict
        }
        if (old.epoch != startEpoch || atRequestMs <= old.requestMs ||
            atRequestMs - old.requestMs > A500sRawVisionBridge.MAX_GAP_MS ||
            now - old.requestMs > VisionSafetyGate.MAX_FRAME_AGE_MS) {
            discontinuities++
            lastVerdict = Verdict.DISCONTINUITY
            return lastVerdict
        }
        val accepted = submit(OpenpilotInferencePipeline.PairSample(
            old.pixels, argb, atRequestMs,
            isSourceCurrent = { sourceEpoch.get() == startEpoch }))
        if (sourceEpoch.get() != startEpoch) {
            discontinuities++
            return refuse(Verdict.DISCONTINUITY)
        }
        if (accepted) pairs++ else {
            rejected++
            previous = null
        }
        lastVerdict = if (accepted) Verdict.SUBMITTED else Verdict.SINK_REJECTED
        return lastVerdict
    }
}
