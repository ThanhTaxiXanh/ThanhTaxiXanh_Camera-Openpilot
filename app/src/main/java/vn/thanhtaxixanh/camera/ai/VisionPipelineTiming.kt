package vn.thanhtaxixanh.camera.ai

/**
 * Pure-JVM measurement of the TTX RAW-only inference timeline.
 *
 * capturedAtMs is the LOCAL Android receipt timestamp of a compressed
 * A500S H264 access unit. It is not the camera exposure timestamp; therefore
 * sourceToResultMs must NEVER be reported as glass-to-glass preview latency.
 *
 * All timestamps are sampled from the SAME elapsed-realtime clock. Clock
 * regressions fail closed instead of yielding negative/credible latencies.
 */
object VisionPipelineTiming {
    enum class Status { FRESH_RECEIPT_TO_RESULT, TOO_OLD, INVALID_CLOCK }

    data class Trace(
        val status: Status,
        val queueDelayMs: Long?,
        val preprocessingMs: Long?,
        val inferenceMs: Long?,
        val sourceToResultMs: Long?,
        val note: String = "Local H264 receipt to ONNX result; NOT optical exposure or screen latency"
    ) {
        val publishable: Boolean get() = status == Status.FRESH_RECEIPT_TO_RESULT
    }

    fun inspect(
        receiptMs: Long,
        offeredMs: Long,
        workerStartMs: Long,
        modelStartMs: Long,
        completedMs: Long,
        maxAgeMs: Long = VisionSafetyGate.MAX_FRAME_AGE_MS
    ): Trace {
        if (maxAgeMs < 0L || receiptMs < 0L ||
            offeredMs < receiptMs || workerStartMs < offeredMs ||
            modelStartMs < workerStartMs || completedMs < modelStartMs) {
            return Trace(Status.INVALID_CLOCK, null, null, null, null)
        }
        val age = completedMs - receiptMs
        val result = if (age <= maxAgeMs)
            Status.FRESH_RECEIPT_TO_RESULT else Status.TOO_OLD
        return Trace(result,
            workerStartMs - offeredMs,
            modelStartMs - workerStartMs,
            completedMs - modelStartMs,
            age)
    }
}
