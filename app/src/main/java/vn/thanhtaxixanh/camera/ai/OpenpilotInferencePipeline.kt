package vn.thanhtaxixanh.camera.ai

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Asynchronous single-slot vision inference. The producer NEVER waits for
 * ONNX or UI. When overloaded, the newest sample replaces the older pending
 * sample; stale model results are dropped before publishing.
 *
 * This is the inference CORE. Source YUV decode and validated A500S camera
 * warp still require an on-device integration. No fabricated warnings.
 */
class OpenpilotInferencePipeline(
    private val engine: VisionInferenceEngine,
    private val mainViewWarp: VirtualCameraWarp.Matrix,
    private val wideViewWarp: VirtualCameraWarp.Matrix,
    private val calibration: VisionSafetyGate.Calibration?,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val nowMs: () -> Long = { System.nanoTime()/1_000_000L },
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
        val status: String
    )
    data class Metrics(
        val received: Long,
        val overwritten: Long,
        val processed: Long,
        val stale: Long,
        val errors: Long
    )

    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val latest = AtomicReference<PairSample?>(null)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TTX-Openpilot-Worker").apply { isDaemon = true }
    }
    private val received=AtomicLong(0)
    private val overwritten=AtomicLong(0)
    private val processed=AtomicLong(0)
    private val stale=AtomicLong(0)
    private val errors=AtomicLong(0)

    init {
        require(sourceWidth in 2..4096 && sourceHeight in 2..4096)
    }

    fun metrics() = Metrics(received.get(), overwritten.get(),
        processed.get(),stale.get(),errors.get())

    /**
     * The caller transfers ownership of IntArrays; it must NOT mutate them.
     * Returns false if disposed or frame dimensions are wrong.
     */
    fun offer(frame: PairSample): Boolean {
        if (closed.get()) return false
        val size=sourceWidth.toLong()*sourceHeight
        if (frame.previousArgb.size.toLong()!=size ||
            frame.currentArgb.size.toLong()!=size) return false
        if (frame.capturedAtMs > nowMs()+100L) return false
        received.incrementAndGet()
        if (latest.getAndSet(frame)!=null) overwritten.incrementAndGet()
        schedule()
        return true
    }

    private fun schedule() {
        if (!running.compareAndSet(false,true)) return
        try {
            executor.execute {
                try {
                    while (!closed.get()) {
                    val sample=latest.getAndSet(null) ?: break
                    val start=nowMs()
                    if (start-sample.capturedAtMs > VisionSafetyGate.MAX_FRAME_AGE_MS ||
                        start<sample.capturedAtMs) {
                        stale.incrementAndGet()
                        continue
                    }
                    try {
                        val main=VirtualCameraWarp.pairToModelInput(
                            sample.previousArgb,sample.currentArgb,
                            sourceWidth,sourceHeight,mainViewWarp)
                        val wide=VirtualCameraWarp.pairToModelInput(
                            sample.previousArgb,sample.currentArgb,
                            sourceWidth,sourceHeight,wideViewWarp)
                        // Fail closed if tensors have invalid normalization,
                        // chroma plane placement or non-finite values.
                        VisionInputContract.requireRawStudioByteTensors(main,wide)
                        val output=engine.run(main,wide)
                        val parsed=OpenpilotVisionDecoder.decode(output)
                        val end=nowMs()
                        if (end-sample.capturedAtMs >
                            VisionSafetyGate.MAX_FRAME_AGE_MS) {
                            stale.incrementAndGet()
                            continue
                        }
                        val lanes=LaneProjection.lanes(
                            parsed,calibration,sourceWidth,sourceHeight)
                        processed.incrementAndGet()
                        if (!closed.get()) onResult(Outcome(sample.capturedAtMs,end,end-start,
                            parsed,lanes,
                            if (calibration?.ready==true)
                                "Model output decoded; projected lanes require on-device validation"
                            else "RAW_ONLY: camera calibration and model axes unverified"))
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                        if (!closed.get()) onResult(Outcome(sample.capturedAtMs,nowMs(),
                            nowMs()-start,null,emptyList(),
                            "Inference error: " + e.javaClass.simpleName +
                                ": " + (e.message ?: "").take(120)))
                    }
                }
                } finally {
                    running.set(false)
                    if (latest.get()!=null && !closed.get()) schedule()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // close() won the race between offer() and executor.execute().
            // Never surface an exception into the decoder/camera callback.
            latest.set(null)
            running.set(false)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false,true)) {
            latest.set(null)
            executor.shutdownNow()
            // Close after the active task exits; a synchronized ONNX runner
            // must never be released from the codec or main UI thread.
            Thread({
                try {
                    // Native ONNX execution may ignore interruption. Never
                    // release its session while an inference call is running.
                    while (!executor.awaitTermination(
                            1L, java.util.concurrent.TimeUnit.SECONDS)) {
                        // Detached daemon thread: no UI/codec thread is blocked.
                    }
                    engine.close()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {}
            }, "TTX-Openpilot-Shutdown").start()
        }
    }
}
