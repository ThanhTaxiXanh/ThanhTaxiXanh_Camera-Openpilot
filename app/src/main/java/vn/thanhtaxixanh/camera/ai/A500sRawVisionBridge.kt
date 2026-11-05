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
    private val submit: (OpenpilotInferencePipeline.PairSample) -> Boolean,
    // Injectable only to make slow YUV->ARGB conversion deterministic in
    // host regression tests. Production always copies actual YUV pixels.
    private val toArgb: (Yuv420FrameConverter.Frame) -> IntArray =
        { Yuv420FrameConverter.argb(it) }
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
        // Offscreen YUV arrives roughly every 300ms. A larger gap would
        // silently pair temporally unrelated images after a decoder pause.
        // This is a raw-input continuity rule, NOT a camera exposure clock.
        const val MAX_GAP_MS = 500L
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
        val wasAlreadyDiscontinuous = old != null &&
            (capturedAtMs <= old.atMs ||
                capturedAtMs - old.atMs > MAX_GAP_MS ||
                now - old.atMs > VisionSafetyGate.MAX_FRAME_AGE_MS)

        // Copying a 640x480 frame can take nontrivial CPU time. Validate
        // again AFTER the copy before enqueuing either member of the pair.
        val argb = toArgb(frame)
        val afterCopyMs = nowMs()
        if (afterCopyMs < now || afterCopyMs < capturedAtMs ||
            afterCopyMs - capturedAtMs >
                VisionSafetyGate.MAX_FRAME_AGE_MS)
            return reject(Verdict.STALE)

        val previousExpiredDuringCopy = old != null &&
            afterCopyMs - old.atMs >
                VisionSafetyGate.MAX_FRAME_AGE_MS
        previous = Previous(argb, capturedAtMs)
        frames++
        if (wasAlreadyDiscontinuous || previousExpiredDuringCopy) {
            discontinuities++
            // Keep only the new, still-fresh frame as pair start. A copied
            // frame must not be paired with an expired previous frame.
            lastVerdict = Verdict.DISCONTINUITY
            return lastVerdict
        }
        if (old == null) {
            lastVerdict = Verdict.WAITING_PAIR
            return lastVerdict
        }
        val accepted = submit(OpenpilotInferencePipeline.PairSample(
            old.pixels, argb, capturedAtMs))
        if (accepted) pairs++ else {
            rejected++
            // If the sink rejects this pair, retaining its current frame
            // would allow a future ONNX pair to cross a failed admission.
            previous = null
        }
        lastVerdict = if (accepted) Verdict.SUBMITTED else Verdict.SINK_REJECTED
        return lastVerdict
    }
}
