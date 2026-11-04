package vn.thanhtaxixanh.camera.ai

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Asynchronous single-slot diagnostic pipeline. H264/YUV preview threads
 * never wait on model execution; overload replaces the older queued frame.
 *
 * The independent offscreen A500S MediaCodec provides *receipt* timestamps,
 * not optical-exposure timestamps. All model, calibration and metric HUD
 * outputs remain unverified; this pipeline must not manufacture warnings.
 */
class OpenpilotInferencePipeline(
    private val engine: VisionInferenceEngine,
    private val mainViewWarp: VirtualCameraWarp.Matrix,
    private val wideViewWarp: VirtualCameraWarp.Matrix,
    private val calibration: VisionSafetyGate.Calibration?,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onResult: (Outcome) -> Unit
) : Closeable {

    data class PairSample(
        val previousArgb: IntArray,
        val currentArgb: IntArray,
        val capturedAtMs: Long
    )
    data class Outcome(
        val capturedAtMs: Long,
        val completedAtMs: Long,
        val runtimeMs: Long,
        val model: OpenpilotVisionDecoder.Snapshot?,
        val projectedLanes: List<LaneProjection.ProjectedLane>,
        val status: String,
        val timing: VisionPipelineTiming.Trace? = null
    )
    data class Metrics(
        val received: Long,
        val overwritten: Long,
        val processed: Long,
        val stale: Long,
        val errors: Long,
        val invalidations: Long,
        val droppedByEpoch: Long,
        val lastTiming: VisionPipelineTiming.Trace?
    )
    private data class QueuedPair(
        val pair: PairSample,
        val epoch: Long,
        val offeredAtMs: Long
    )

    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val epoch = AtomicLong(0L)
    private val latest = AtomicReference<QueuedPair?>(null)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TTX-Openpilot-Worker").apply { isDaemon = true }
    }
    private val received = AtomicLong()
    private val overwritten = AtomicLong()
    private val processed = AtomicLong()
    private val stale = AtomicLong()
    private val errors = AtomicLong()
    private val invalidations = AtomicLong()
    private val droppedByEpoch = AtomicLong()
    private val lastTiming = AtomicReference<VisionPipelineTiming.Trace?>(null)

    init {
        require(sourceWidth in 2..4096 && sourceHeight in 2..4096)
    }

    fun metrics() = Metrics(
        received.get(), overwritten.get(), processed.get(),
        stale.get(), errors.get(), invalidations.get(), droppedByEpoch.get(),
        lastTiming.get()
    )

    /**
     * Invalidates any queued or in-flight inference from a prior decoder GOP,
     * network reconnect, YUV error, or a camera-source transition.
     * Nonblocking even when ONNX Runtime is currently executing.
     */
    fun invalidate() {
        epoch.incrementAndGet()
        invalidations.incrementAndGet()
        latest.set(null)
        lastTiming.set(null)
    }

    /**
     * Caller transfers ownership of both ARGB arrays; do not mutate them
     * once offered. Returns false if closed or malformed. The pixel copying
     * was already completed by the YUV bridge before this method is called.
     */
    fun offer(frame: PairSample): Boolean {
        if (closed.get()) return false
        val expected = sourceWidth.toLong() * sourceHeight
        if (frame.previousArgb.size.toLong() != expected ||
            frame.currentArgb.size.toLong() != expected) return false
        val offeredAt = nowMs()
        if (frame.capturedAtMs < 0L || frame.capturedAtMs > offeredAt)
            return false
        val stamp = QueuedPair(frame, epoch.get(), offeredAt)
        received.incrementAndGet()
        if (latest.getAndSet(stamp) != null) overwritten.incrementAndGet()
        if (closed.get() || stamp.epoch != epoch.get()) {
            latest.compareAndSet(stamp, null)
            return false
        }
        schedule()
        return true
    }

    private fun validEpoch(value: Long): Boolean =
        !closed.get() && epoch.get() == value

    private fun notifyOutcome(outcome: Outcome, stamp: Long) {
        if (!validEpoch(stamp)) {
            droppedByEpoch.incrementAndGet()
            return
        }
        // A failing UI callback must not be mistaken for an ONNX failure
        // or result in a second fabricated inference callback.
        try { onResult(outcome) } catch (_: Exception) {
            errors.incrementAndGet()
        }
    }

    private fun schedule() {
        if (!running.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    while (!closed.get()) {
                        val entry = latest.getAndSet(null) ?: break
                        if (!validEpoch(entry.epoch)) {
                            droppedByEpoch.incrementAndGet()
                            continue
                        }
                        val pair = entry.pair
                        val start = nowMs()
                        if (start < entry.offeredAtMs ||
                            start < pair.capturedAtMs ||
                            start - pair.capturedAtMs >
                                VisionSafetyGate.MAX_FRAME_AGE_MS) {
                            stale.incrementAndGet()
                            continue
                        }
                        try {
                            val main = VirtualCameraWarp.pairToModelInput(
                                pair.previousArgb, pair.currentArgb,
                                sourceWidth, sourceHeight, mainViewWarp)
                            val wide = VirtualCameraWarp.pairToModelInput(
                                pair.previousArgb, pair.currentArgb,
                                sourceWidth, sourceHeight, wideViewWarp)
                            VisionInputContract.requireRawStudioByteTensors(main, wide)
                            if (!validEpoch(entry.epoch)) {
                                droppedByEpoch.incrementAndGet()
                                continue
                            }
                            val modelStart = nowMs()
                            if (modelStart < start ||
                                modelStart - pair.capturedAtMs >
                                    VisionSafetyGate.MAX_FRAME_AGE_MS) {
                                stale.incrementAndGet()
                                continue
                            }
                            val output = engine.run(main, wide)
                            val modelEnd = nowMs()
                            if (!validEpoch(entry.epoch)) {
                                droppedByEpoch.incrementAndGet()
                                continue
                            }
                            val decoded = OpenpilotVisionDecoder.decode(output)
                            val lanes = LaneProjection.lanes(
                                decoded, calibration, sourceWidth, sourceHeight)
                            val completed = nowMs()
                            val timing = VisionPipelineTiming.inspect(
                                receiptMs = pair.capturedAtMs,
                                offeredMs = entry.offeredAtMs,
                                workerStartMs = start,
                                modelStartMs = modelStart,
                                modelCompletedMs = modelEnd,
                                publishedAtMs = completed
                            )
                            if (!validEpoch(entry.epoch)) {
                                droppedByEpoch.incrementAndGet()
                                continue
                            }
                            lastTiming.set(timing)
                            if (!timing.publishable) {
                                stale.incrementAndGet()
                                continue
                            }
                            processed.incrementAndGet()
                            notifyOutcome(Outcome(
                                capturedAtMs = pair.capturedAtMs,
                                completedAtMs = completed,
                                runtimeMs = completed - start,
                                model = decoded,
                                projectedLanes = lanes,
                                status = if (calibration?.ready == true)
                                    "Model output decoded; projected lanes require on-device validation"
                                else
                                    "RAW_ONLY: camera calibration and model axes unverified",
                                timing = timing
                            ), entry.epoch)
                        } catch (failure: Exception) {
                            errors.incrementAndGet()
                            if (!validEpoch(entry.epoch)) {
                                droppedByEpoch.incrementAndGet()
                                continue
                            }
                            val failureAt = nowMs()
                            if (failureAt < start ||
                                failureAt - pair.capturedAtMs >
                                    VisionSafetyGate.MAX_FRAME_AGE_MS) {
                                stale.incrementAndGet()
                                continue
                            }
                            notifyOutcome(Outcome(
                                pair.capturedAtMs, failureAt,
                                failureAt - start, null, emptyList(),
                                "Inference error: " + failure.javaClass.simpleName +
                                    ": " + (failure.message ?: "").take(120)
                            ), entry.epoch)
                        }
                    }
                } finally {
                    running.set(false)
                    if (latest.get() != null && !closed.get()) schedule()
                }
            }
        } catch (_: RejectedExecutionException) {
            // close() may beat executor.execute() after offer(). Never throw
            // on the camera/decoder callback thread in this race.
            latest.set(null)
            running.set(false)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            epoch.incrementAndGet()
            latest.set(null)
            executor.shutdownNow()
            // ONNX may ignore interruption. Avoid closing its native session
            // while run() is executing, and never block UI/codec threads.
            Thread({
                try {
                    while (!executor.awaitTermination(1L, TimeUnit.SECONDS)) {
                        // Wait only on this detached cleanup worker.
                    }
                    engine.close()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {}
            }, "TTX-Openpilot-Shutdown").apply {
                isDaemon = true
                start()
            }
        }
    }
}
