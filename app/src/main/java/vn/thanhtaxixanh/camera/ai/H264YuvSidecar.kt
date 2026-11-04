package vn.thanhtaxixanh.camera.ai

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional, independent off-screen H.264 -> YUV_420_888 decoder.
 *
 * The main DirectRtspPlayer preview owns its OWN MediaCodec + Surface. This
 * sidecar never touches that Surface or waits on the preview thread. It reads
 * the already assembled immutable Annex-B access units, decodes on a
 * separate worker, copies image planes before release, and drops its whole
 * prediction chain (waiting for the next IDR) on overload.
 *
 * This is an image-acquisition adapter, NOT validated xADAS inference.
 * Some phones cannot run two concurrent AVC hardware decoders; failure is
 * reported and never stops the preview.
 */
class H264YuvSidecar(
    private val sps: ByteArray?,
    private val pps: ByteArray?,
    private val onFrame: (Yuv420FrameConverter.Frame, Long) -> Unit,
    private val onFault: (String) -> Unit = {},
    private val onDiscontinuity: () -> Unit = {}
) : AutoCloseable {
    data class Metrics(
        val received: Long,
        val skippedToIdr: Long,
        val overflows: Long,
        val decoded: Long,
        val yuvFrames: Long,
        val resets: Long,
        val errors: Long,
        val queueDepth: Int,
        val lastFailure: String
    )

    private data class UnitSample(
        val annexB: ByteArray,
        val capturedElapsedMs: Long,
        val epoch: Long
    )

    private val alive = AtomicBoolean(true)
    private val active = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(false)
    private val gate = H264SecondarySyncGate()
    private val epoch = AtomicLong(0L)
    private val pending = ArrayBlockingQueue<UnitSample>(16)
    private val received = AtomicLong()
    private val skipped = AtomicLong()
    private val overflows = AtomicLong()
    private val decoded = AtomicLong()
    private val delivered = AtomicLong()
    private val resets = AtomicLong()
    private val errors = AtomicLong()
    private val lastFailure = AtomicReference("")
    private val worker = Thread({ runDecoder() }, "TTX-A500S-YUV-Offscreen").apply {
        isDaemon = true
        start()
    }

    fun metrics() = Metrics(received.get(), skipped.get(), overflows.get(),
        decoded.get(), delivered.get(), resets.get(), errors.get(),
        pending.size, lastFailure.get())

    /** Never blocks RTP, the H264 assembler, UI, or the preview feeder. */
    @Synchronized fun offer(annexB: ByteArray, capturedElapsedMs: Long): Boolean {
        if (!alive.get() || annexB.isEmpty() || capturedElapsedMs < 0L) return false
        active.set(true)
        if (!gate.accept(annexB)) {
            skipped.incrementAndGet()
            return false
        }
        if (!pending.offer(UnitSample(annexB, capturedElapsedMs, epoch.get()))) {
            // One missing reference picture poisons the whole GOP. The main
            // Surface decoder is unaffected; only this offscreen codec resets.
            invalidatePredictionChain()
            overflows.incrementAndGet()
            return false
        }
        received.incrementAndGet()
        return true
    }

    /** Called only while holding the sidecar monitor. */
    private fun invalidatePredictionChain() {
        epoch.incrementAndGet()
        pending.clear()
        gate.reset()
        resetRequested.set(true)
        try { onDiscontinuity() } catch (_: Exception) {}
    }

    /** RTP damage, dropped access units or codec errors require a new IDR. */
    @Synchronized fun dropPredictionChain() {
        if (alive.get() && active.get()) invalidatePredictionChain()
    }

    /** Allows the native YUV decoder to be released while video keeps playing. */
    @Synchronized fun pause() {
        if (active.getAndSet(false)) invalidatePredictionChain()
    }

    private fun createDecoder(): MediaCodec {
        val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
        if (sps != null && pps != null) {
            format.setByteBuffer("csd-0",
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
            format.setByteBuffer("csd-1",
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
        }
        val instance = MediaCodec.createDecoderByType("video/avc")
        try {
            // Null Surface is intentional: getOutputImage() is not reliable
            // when MediaCodec has been configured to render to a Surface.
            instance.configure(format, null, null, 0)
            instance.start()
        } catch (error: Exception) {
            instance.release()
            throw error
        }
        return instance
    }

    private fun report(error: Throwable) {
        errors.incrementAndGet()
        val message = "YUV offscreen: " + error.javaClass.simpleName +
            " " + (error.message ?: "").take(90)
        lastFailure.set(message)
        try { onFault(message) } catch (_: Exception) {}
    }

    private fun release(codec: MediaCodec?) {
        if (codec == null) return
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    private fun runDecoder() {
        var codec: MediaCodec? = null
        var decoderSynced = false
        var decoderEpoch = -1L
        var lastCopyAt = 0L
        val clock = OffscreenPresentationClock()
        val timeline = LinkedHashMap<Long, Long>()
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (alive.get()) {
                if (resetRequested.getAndSet(false)) {
                    release(codec)
                    codec = null
                    timeline.clear()
                    clock.reset()
                    decoderSynced = false
                    decoderEpoch = -1L
                    lastCopyAt = 0L
                    resets.incrementAndGet()
                }
                val item = try { pending.poll(100, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { break }
                if (item == null) {
                    try {
                        codec?.let { drain(it, bufferInfo, timeline) { captured, image ->
                            val now = SystemClock.elapsedRealtime()
                            if (active.get() && !resetRequested.get() &&
                                decoderEpoch == epoch.get() &&
                                now - lastCopyAt >= 300L &&
                                now - captured in 0..550L) {
                                lastCopyAt = now
                                deliver(image, captured)
                            }
                        } }
                    } catch (error: Exception) {
                        // An idle-output exception must not silently kill the
                        // decoder worker while the network continues offering AUs.
                        report(error)
                        dropPredictionChain()
                    }
                    continue
                }
                if (!active.get() || resetRequested.get() ||
                    item.epoch != epoch.get()) continue
                if (!decoderSynced) {
                    if (!H264AnnexB.hasIdr(item.annexB)) continue
                    try {
                        codec = createDecoder()
                        decoderSynced = true
                        decoderEpoch = item.epoch
                    } catch (e: Exception) {
                        report(e)
                        dropPredictionChain()
                        // Do not retry continuously without a new IDR.
                        continue
                    }
                }
                val running = codec ?: continue
                if (resetRequested.get() || item.epoch != epoch.get()) continue
                try {
                    var index = running.dequeueInputBuffer(10_000L)
                    if (index < 0) {
                        drain(running, bufferInfo, timeline) { captured, image ->
                            val now = SystemClock.elapsedRealtime()
                            if (active.get() && !resetRequested.get() &&
                            decoderEpoch == epoch.get() &&
                            now - lastCopyAt >= 300L &&
                                now - captured in 0..550L) {
                                lastCopyAt = now
                                deliver(image, captured)
                            }
                        }
                        index = running.dequeueInputBuffer(10_000L)
                    }
                    if (index < 0) {
                        // Never silently skip a dependent P-frame.
                        error("Offscreen input queue full; await next IDR")
                    }
                    val dst = running.getInputBuffer(index)
                        ?: error("Null offscreen input buffer")
                    dst.clear()
                    check(item.annexB.size <= dst.remaining()) {
                        "Offscreen H264 access unit too large"
                    }
                    dst.put(item.annexB)
                    // Offscreen PTS is a local monotonic correlation key; the
                    // preview RTP timestamp and pacing remain unchanged.
                    val ptsUs = clock.next(item.capturedElapsedMs)
                    running.queueInputBuffer(index, 0, item.annexB.size,
                        ptsUs, 0)
                    timeline[ptsUs] = item.capturedElapsedMs
                    while (timeline.size > 48) {
                        val oldest = timeline.keys.first()
                        timeline.remove(oldest)
                    }
                    drain(running, bufferInfo, timeline) { captured, image ->
                        val now = SystemClock.elapsedRealtime()
                        if (active.get() && !resetRequested.get() &&
                            decoderEpoch == epoch.get() &&
                            now - lastCopyAt >= 300L &&
                            now - captured in 0..550L) {
                            lastCopyAt = now
                            deliver(image, captured)
                        }
                    }
                } catch (e: Exception) {
                    report(e)
                    dropPredictionChain()
                }
            }
        } finally {
            release(codec)
        }
    }

    private fun deliver(image: android.media.Image, capturedAtMs: Long) {
        if (image.format != ImageFormat.YUV_420_888) return
        try {
            // Snapshot takes ownership of NO native memory. Image is closed
            // by the drain routine before its codec buffer is released.
            val frame = AndroidYuvImageBridge.snapshot(image)
            delivered.incrementAndGet()
            onFrame(frame, capturedAtMs)
        } catch (e: Exception) { report(e) }
    }

    private inline fun drain(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        capturedTimes: MutableMap<Long, Long>,
        frame: (Long, android.media.Image) -> Unit
    ) {
        repeat(12) {
            val index = codec.dequeueOutputBuffer(info, 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return@repeat
            if (index < 0) return
            try {
                if (info.size > 0) {
                    decoded.incrementAndGet()
                    val captured = capturedTimes.remove(info.presentationTimeUs)
                    if (captured != null) {
                        val image = codec.getOutputImage(index)
                        image?.use { frame(captured, it) }
                    }
                }
            } finally {
                // OFFSCREEN: false means no Surface is involved.
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    override fun close() {
        if (alive.compareAndSet(true, false)) {
            synchronized(this) { invalidatePredictionChain() }
            active.set(false)
            worker.interrupt()
            // Native decoder is only accessed/released by its own worker.
        }
    }
}
