package vn.thanhtaxixanh.camera.ai

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecList
import android.os.Build
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
        val lastFailure: String,
        val staleAccessUnits: Long,
        val expiredBacklogResets: Long,
        val staleYuvOutputs: Long,
        val revokedYuvOutputs: Long,
        val unsupportedYuvOutputs: Long,
        val decoderName: String,
        val softwareDecoder: Boolean,
        val softwareCodecAttempts: Long,
        val transientInputWaits: Long,
        val exhaustedInputWaits: Long,
        val nullOutputImages: Long
    )

    private data class UnitSample(
        val annexB: ByteArray,
        val capturedElapsedMs: Long,
        val epoch: Long
    )

    private val alive = AtomicBoolean(true)
    private val active = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(false)
    private val gate = OffscreenGopAgeGate()
    private val outputGate = OffscreenYuvOutputGate()
    private val epoch = AtomicLong(0L)
    private val pending = ArrayBlockingQueue<UnitSample>(16)
    private val received = AtomicLong()
    private val skipped = AtomicLong()
    private val overflows = AtomicLong()
    private val decoded = AtomicLong()
    private val delivered = AtomicLong()
    private val resets = AtomicLong()
    private val errors = AtomicLong()
    private val staleAccessUnits = AtomicLong()
    private val expiredBacklogResets = AtomicLong()
    private val lastFailure = AtomicReference("")
    private val staleYuvOutputs = AtomicLong()
    private val revokedYuvOutputs = AtomicLong()
    private val unsupportedYuvOutputs = AtomicLong()
    private val chosenDecoderName = AtomicReference("NOT_STARTED")
    private val softwareDecoder = AtomicBoolean(false)
    private val softwareCodecAttempts = AtomicLong()
    private val transientInputWaits = AtomicLong()
    private val exhaustedInputWaits = AtomicLong()
    private val nullOutputImages = AtomicLong()
    private val worker = Thread({ runDecoder() }, "TTX-A500S-YUV-Offscreen").apply {
        isDaemon = true
        start()
    }

    fun metrics() = Metrics(received.get(), skipped.get(), overflows.get(),
        decoded.get(), delivered.get(), resets.get(), errors.get(),
        pending.size, lastFailure.get(),
        staleAccessUnits.get(), expiredBacklogResets.get(),
        staleYuvOutputs.get(), revokedYuvOutputs.get(),
        unsupportedYuvOutputs.get(), chosenDecoderName.get(),
        softwareDecoder.get(), softwareCodecAttempts.get(),
        transientInputWaits.get(), exhaustedInputWaits.get(),
        nullOutputImages.get())

    /** Never blocks RTP, the H264 assembler, UI, or the preview feeder. */
    @Synchronized fun offer(annexB: ByteArray, capturedElapsedMs: Long): Boolean {
        if (!alive.get() || annexB.isEmpty() || capturedElapsedMs < 0L) return false
        active.set(true)
        val now=SystemClock.elapsedRealtime()
        // An expired queue head means reference frames are unusable.
        // Never selectively discard compressed P frames to catch up.
        pending.peek()?.let { head ->
            if (gate.age(head.capturedElapsedMs, now) !=
                OffscreenGopAgeGate.Age.CURRENT) {
                expiredBacklogResets.incrementAndGet()
                invalidatePredictionChain()
            }
        }
        when (gate.admit(annexB, capturedElapsedMs, now)) {
            OffscreenGopAgeGate.Admission.ACCEPT -> Unit
            OffscreenGopAgeGate.Admission.AWAIT_IDR -> {
                skipped.incrementAndGet()
                return false
            }
            OffscreenGopAgeGate.Admission.STALE,
            OffscreenGopAgeGate.Admission.INVALID_CLOCK -> {
                staleAccessUnits.incrementAndGet()
                invalidatePredictionChain()
                return false
            }
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
        // Sidecar YUV and Direct Surface codecs MUST start with the same
        // validated H264 SPS picture dimensions, including cropped 4:2:0.
        // Hardcoding 1280x720 for a 640x480 A500S stream can cause vendor
        // decoder reconfigure stalls or oversized buffer allocations.
        val dimensions = H264SpsDimensions.configuredSize(sps)
        val format = MediaFormat.createVideoFormat(
            "video/avc", dimensions.width, dimensions.height)
        if (sps != null && pps != null) {
            format.setByteBuffer("csd-0",
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
            format.setByteBuffer("csd-1",
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
        }
        fun initialize(name: String?): MediaCodec {
            val codec = if (name == null)
                MediaCodec.createDecoderByType("video/avc")
            else MediaCodec.createByCodecName(name)
            try {
                // Only the secondary decoder uses null output Surface,
                // allowing getOutputImage/YUV plane snapshots. The real
                // primary preview Surface is NEVER changed by this method.
                codec.configure(format, null, null, 0)
                codec.start()
                chosenDecoderName.set(codec.name)
                softwareDecoder.set(name != null)
                return codec
            } catch (error: Exception) {
                try { codec.release() } catch (_: Exception) {}
                throw error
            }
        }
        // The real preview already occupies an AVC hardware decoder.
        // Prefer OS-listed software AVC for the OPTIONAL YUV diagnostics,
        // avoiding the observed second-hardware-decoder input starvation
        // when such software codec is present. If unavailable or unable
        // to configure, restore the previous default codec selection.
        val softwareNames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            try {
                OffscreenCodecSelection.softwareAvcCandidates(
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                        .map { info ->
                            OffscreenCodecSelection.Candidate(
                                info.name,
                                !info.isEncoder && info.supportedTypes.any {
                                    it.equals("video/avc", ignoreCase = true)
                                },
                                info.isSoftwareOnly
                            )
                        }
                )
            } catch (_: Exception) { emptyList() }
        else emptyList()
        for (name in softwareNames) {
            softwareCodecAttempts.incrementAndGet()
            try { return initialize(name) }
            catch (_: Exception) {
                // Candidate codec name or vendor configuration may fail.
                // This is not a proof that software decoding is supported.
            }
        }
        return initialize(null)
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
        // The cadence threshold advances only after a valid YUV callback.
        // An invalid image, failed plane copy or revoked epoch must not
        // throttle the next otherwise-healthy offscreen frame.
        var lastCopyAt = -1L
        val clock = OffscreenPresentationClock()
        val timeline = LinkedHashMap<Long, Long>()
        val bufferInfo = MediaCodec.BufferInfo()
        fun acceptDecodedImage(captured: Long, image: android.media.Image) {
            // This is only the secondary decoder's worker; the primary
            // Surface/RTSP thread never waits for native YUV plane copying.
            if (deliver(image, captured, decoderEpoch, lastCopyAt)) {
                lastCopyAt = SystemClock.elapsedRealtime()
            }
        }
        try {
            while (alive.get()) {
                if (resetRequested.getAndSet(false)) {
                    release(codec)
                    codec = null
                    timeline.clear()
                    clock.reset()
                    decoderSynced = false
                    decoderEpoch = -1L
                    lastCopyAt = -1L
                    resets.incrementAndGet()
                }
                val item = try { pending.poll(100, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { break }
                if (item == null) {
                    try {
                        codec?.let { drain(it, bufferInfo, timeline, ::acceptDecodedImage) }
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
                // Queue residence can make an AU stale after admission.
                // Stop the entire offscreen GOP rather than decoding it.
                if (gate.age(item.capturedElapsedMs,
                        SystemClock.elapsedRealtime()) !=
                    OffscreenGopAgeGate.Age.CURRENT) {
                    staleAccessUnits.incrementAndGet()
                    dropPredictionChain()
                    continue
                }
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
                    val waitPlan = OffscreenCodecSelection.inputWaitPlan(
                        softwareDecoder.get())
                    var index = running.dequeueInputBuffer(waitPlan.timeoutUs)
                    var attempts = 0
                    while (index < 0) {
                        attempts++
                        transientInputWaits.incrementAndGet()
                        // The owner-observed software codec could not free
                        // buffers within the old 30ms retry window, causing
                        // repeated full GOP resets. Drain output while
                        // waiting up to a STRICT 160ms software budget.
                        // Hardware remains at the previous 30ms budget.
                        // This is an offscreen-only worker: RTP/Surface
                        // preview must not block on these waits.
                        drain(running, bufferInfo, timeline, ::acceptDecodedImage)
                        if (resetRequested.get() || item.epoch != epoch.get())
                            break
                        // A software decoder under load may have delayed
                        // output, but no stale compressed AU may be queued.
                        if (gate.age(item.capturedElapsedMs,
                                SystemClock.elapsedRealtime()) !=
                            OffscreenGopAgeGate.Age.CURRENT) {
                            staleAccessUnits.incrementAndGet()
                            dropPredictionChain()
                            break
                        }
                        val action = OffscreenCodecSelection.inputBufferUnavailable(
                            attempts,waitPlan.maxAttempts)
                        if (action ==
                            OffscreenCodecSelection.InputWaitVerdict.RESYNC_IDR) {
                            exhaustedInputWaits.incrementAndGet()
                            error("Offscreen input unavailable after " +
                                waitPlan.maxAttempts + " drain/wait attempts " +
                                "(" + waitPlan.timeoutUs + "us each); await next IDR")
                        }
                        index = running.dequeueInputBuffer(waitPlan.timeoutUs)
                    }
                    if (resetRequested.get() || item.epoch != epoch.get())
                        continue
                    val dst = running.getInputBuffer(index)
                        ?: error("Null offscreen input buffer")
                    dst.clear()
                    check(item.annexB.size <= dst.remaining()) {
                        "Offscreen H264 access unit too large"
                    }
                    dst.put(item.annexB)
                    // A codec input wait can cross the deadline. Recheck
                    // before sending any bytes to native MediaCodec.
                    if (gate.age(item.capturedElapsedMs,
                            SystemClock.elapsedRealtime()) !=
                        OffscreenGopAgeGate.Age.CURRENT) {
                        staleAccessUnits.incrementAndGet()
                        dropPredictionChain()
                        continue
                    }
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
                    drain(running, bufferInfo, timeline, ::acceptDecodedImage)
                } catch (e: Exception) {
                    report(e)
                    dropPredictionChain()
                }
            }
        } finally {
            release(codec)
        }
    }

    private fun deliver(
        image: android.media.Image,
        capturedAtMs: Long,
        decoderEpoch: Long,
        lastDeliveredAtMs: Long
    ): Boolean {
        fun inspect(): OffscreenYuvOutputGate.Verdict = outputGate.check(
            expectedEpoch = decoderEpoch,
            currentEpoch = epoch.get(),
            active = alive.get() && active.get(),
            resetPending = resetRequested.get(),
            capturedAtMs = capturedAtMs,
            checkedAtMs = SystemClock.elapsedRealtime(),
            lastDeliveredAtMs = lastDeliveredAtMs
        )
        fun rejected(verdict: OffscreenYuvOutputGate.Verdict): Boolean {
            when (verdict) {
                OffscreenYuvOutputGate.Verdict.STALE_FRAME,
                OffscreenYuvOutputGate.Verdict.INVALID_CLOCK -> {
                    staleYuvOutputs.incrementAndGet()
                    // This offscreen GOP can no longer provide fresh temporal
                    // frames; resync at an IDR without touching preview.
                    dropPredictionChain()
                }
                OffscreenYuvOutputGate.Verdict.EPOCH_REVOKED,
                OffscreenYuvOutputGate.Verdict.RESET_PENDING ->
                    revokedYuvOutputs.incrementAndGet()
                else -> Unit
            }
            return false
        }
        val first = inspect()
        if (first != OffscreenYuvOutputGate.Verdict.DELIVER)
            return rejected(first)
        if (image.format != ImageFormat.YUV_420_888) {
            unsupportedYuvOutputs.incrementAndGet()
            report(IllegalStateException("Decoder output format " + image.format +
                " is not YUV_420_888"))
            dropPredictionChain()
            return false
        }
        try {
            // Native YUV plane copy can take enough time for an RTP damage
            // event to revoke the GOP or for this receipt to expire. Recheck
            // BEFORE passing immutable pixels to any evidence/ONNX consumer.
            val frame = AndroidYuvImageBridge.snapshot(image)
            val afterCopy = inspect()
            if (afterCopy != OffscreenYuvOutputGate.Verdict.DELIVER)
                return rejected(afterCopy)
            onFrame(frame, capturedAtMs)
            // Discontinuity may be raised synchronously from the consumer.
            // Count only a callback that completed within the same GOP.
            if (!alive.get() || !active.get() || resetRequested.get() ||
                decoderEpoch != epoch.get()) {
                revokedYuvOutputs.incrementAndGet()
                return false
            }
            delivered.incrementAndGet()
            return true
        } catch (e: Exception) {
            report(e)
            dropPredictionChain()
            return false
        }
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
                        if (image == null) nullOutputImages.incrementAndGet()
                        else image.use { frame(captured, it) }
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
