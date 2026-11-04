package vn.thanhtaxixanh.camera.ai

/**
 * Non-Android temporal bridge: offscreen A500S YUV frames -> ordered ARGB
 * pairs -> existing single-slot ONNX pipeline. It is a RAW diagnostic path,
 * not a calibrated xADAS/Openpilot camera transform or a safety signal.
 *
 * Each frame is owned by the caller, and is copied to ARGB before the next
 * native image can be released. No YUV image or plane survives this method.
 * Only one offscreen decoder thread should call accept; reset may be called
 * from the UI thread when the owner disables the diagnostic.
 */
class A500sRawVisionBridge(
    private val nowMs: () -> Long,
    private val submit: (OpenpilotInferencePipeline.PairSample) -> Boolean
) {
    enum class Verdict {
        WAITING_PAIR, SUBMITTED, SINK_REJECTED, INVALID_DIMENSIONS,
        STALE, DISCONTINUITY
    }
    data class Metrics(
        val frames: Long,
        val pairs: Long,
        val rejected: Long,
        val discontinuities: Long,
        val lastVerdict: Verdict
    )

    private data class Previous(val pixels: IntArray, val atMs: Long)

    private var previous: Previous? = null
    private var frames = 0L
    private var pairs = 0L
    private var rejected = 0L
    private var discontinuities = 0L
    private var lastVerdict = Verdict.WAITING_PAIR

    // The observed A500S preview resolution is 640x480. Until real camera
    // calibration exists, NEVER resize an arbitrary unknown camera image
    // and pretend that its model geometry is verified.
    companion object {
        const val EXPECTED_WIDTH = 640
        const val EXPECTED_HEIGHT = 480
        const val MAX_GAP_MS = 850L
    }

    @Synchronized fun metrics(): Metrics =
        Metrics(frames, pairs, rejected, discontinuities, lastVerdict)

    @Synchronized fun reset() {
        previous = null
        lastVerdict = Verdict.WAITING_PAIR
    }

    /**
     * capturedAtMs is a local elapsed-realtime timestamp of the access unit
     * receipt. It is NOT physical exposure time or glass-to-glass latency.
     */
    @Synchronized fun accept(
        frame: Yuv420FrameConverter.Frame,
        capturedAtMs: Long
    ): Verdict {
        fun reject(reason: Verdict): Verdict {
            rejected++
            previous = null
            lastVerdict = reason
            return reason
        }
        if (frame.width != EXPECTED_WIDTH || frame.height != EXPECTED_HEIGHT)
            return reject(Verdict.INVALID_DIMENSIONS)
        val now = nowMs()
        if (capturedAtMs < 0L ||
            capturedAtMs > now ||
            now - capturedAtMs > VisionSafetyGate.MAX_FRAME_AGE_MS)
            return reject(Verdict.STALE)
        val old = previous
        if (old != null &&
            (capturedAtMs <= old.atMs || capturedAtMs - old.atMs > MAX_GAP_MS)) {
            discontinuities++
            // Keep the new valid image as the start of a fresh pair; do not
            // bridge across a reconnect, a GOP reset or a timestamp jump.
            previous = Previous(Yuv420FrameConverter.argb(frame), capturedAtMs)
            frames++
            lastVerdict = Verdict.DISCONTINUITY
            return Verdict.DISCONTINUITY
        }
        val argb = Yuv420FrameConverter.argb(frame)
        previous = Previous(argb, capturedAtMs)
        frames++
        if (old == null) {
            lastVerdict = Verdict.WAITING_PAIR
            return lastVerdict
        }
        val accepted = submit(OpenpilotInferencePipeline.PairSample(
            old.pixels, argb, capturedAtMs))
        if (accepted) pairs++ else rejected++
        lastVerdict = if (accepted) Verdict.SUBMITTED else Verdict.SINK_REJECTED
        return lastVerdict
    }
}
