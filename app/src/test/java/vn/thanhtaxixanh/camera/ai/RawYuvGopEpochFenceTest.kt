package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic cross-thread RAW YUV/ONNX lease tests. These use synthetic
 * pixels, not a real A500S camera, native MediaCodec or ADAS model.
 */
class RawYuvGopEpochFenceTest {
    private val fake = Yuv420FrameConverter.Frame(
        640, 480,
        Yuv420FrameConverter.Plane(byteArrayOf(16), 1, 1),
        Yuv420FrameConverter.Plane(byteArrayOf(128.toByte()), 1, 1),
        Yuv420FrameConverter.Plane(byteArrayOf(128.toByte()), 1, 1)
    )
    private val pixels = IntArray(640*480) { 0xff111111.toInt() }

    @Test fun networkCanSignalGopDamageWithoutWaitingForYuvCopy() {
        val clock = AtomicLong(10_000L)
        val copying = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val submitted = mutableListOf<Long>()
        var count = 0
        val bridge = A500sRawVisionBridge(
            nowMs = {clock.get()},
            submit = {submitted.add(it.capturedAtMs);true},
            toArgb = {
                if (++count == 2) {
                    copying.countDown()
                    assertTrue(releaseCopy.await(5,TimeUnit.SECONDS))
                }
                pixels
            })
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(fake,9_700))
        val verdict = AtomicReference<A500sRawVisionBridge.Verdict?>()
        val worker = Thread { verdict.set(bridge.accept(fake,9_800)) }
        worker.start()
        try {
            assertTrue(copying.await(5,TimeUnit.SECONDS))
            // No monitor acquisition: the network thread cannot wait for ARGB.
            val begin = System.nanoTime()
            bridge.signalDiscontinuity()
            val elapsedMs = (System.nanoTime()-begin)/1_000_000L
            assertTrue("Network-side reset waited for pixel conversion",elapsedMs<200L)
        } finally {
            releaseCopy.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,verdict.get())
        assertTrue(submitted.isEmpty())
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(fake,9_850))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(fake,9_900))
        assertEquals(listOf(9_900L),submitted)
    }

    @Test fun preexistingTemporalPairCannotCrossEpochWithoutUiReset() {
        val clock = AtomicLong(10_000L)
        val submissions = mutableListOf<Long>()
        val bridge = A500sRawVisionBridge(
            nowMs={clock.get()},
            submit={submissions.add(it.capturedAtMs);true},
            toArgb={pixels})
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(fake,9_700))
        bridge.signalDiscontinuity()
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(fake,9_750))
        assertTrue(submissions.isEmpty())
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(fake,9_800))
        assertEquals(listOf(9_800L),submissions)
    }

    @Test fun epochTransitionInsideSinkCannotClaimSubmittedPair() {
        val clock = AtomicLong(10_000L)
        lateinit var bridge: A500sRawVisionBridge
        var calls=0
        var sinkLease: (() -> Boolean)? = null
        bridge=A500sRawVisionBridge(
            nowMs={clock.get()},
            submit={
                calls++
                sinkLease=it.isSourceCurrent
                bridge.signalDiscontinuity()
                true
            },
            toArgb={pixels})
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(fake,9_700))
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(fake,9_800))
        assertFalse(sinkLease!!.invoke())
        assertEquals(1,calls)
        assertEquals(0L,bridge.metrics().pairs)
        assertEquals(1L,bridge.metrics().rejected)
    }

    private class DelayedEngine(
        val entered: CountDownLatch,
        val release: CountDownLatch
    ) : VisionInferenceEngine {
        override fun run(img: FloatArray,bigImg: FloatArray):FloatArray {
            entered.countDown()
            check(release.await(5,TimeUnit.SECONDS)) { "Test engine did not resume" }
            return FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS)
        }
        override fun close() { }
    }

    @Test fun onnxWorkerDropsOutputWhenSourceRevokedDuringInference() {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val sourceValid=AtomicBoolean(true)
        val callbacks=AtomicLong(0)
        val p=OpenpilotInferencePipeline(
            DelayedEngine(entered,release),
            VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(),
            null,512,256,nowMs={10_000L}
        ) {callbacks.incrementAndGet()}
        try {
            // Actual warp worker requires model-scale source pixels;
            // 2x2 would be rejected before the delayed engine starts.
            assertTrue(p.offer(OpenpilotInferencePipeline.PairSample(
                IntArray(512*256),IntArray(512*256),9_950,
                isSourceCurrent={sourceValid.get()})))
            assertTrue("ONNX worker did not start",entered.await(5,TimeUnit.SECONDS))
            sourceValid.set(false)
            release.countDown()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(p.metrics().droppedByEpoch == 0L && System.nanoTime()<deadline)
                Thread.sleep(10)
            assertTrue(p.metrics().droppedByEpoch>0L)
            assertEquals(0L,callbacks.get())
            assertEquals(0L,p.metrics().processed)
        } finally { release.countDown();p.close() }
    }

    @Test fun onnxProducerRejectsAlreadyRevokedGop() {
        val sourceValid=AtomicBoolean(false)
        val callbacks=AtomicLong(0)
        val engine=object:VisionInferenceEngine {
            override fun run(img:FloatArray,bigImg:FloatArray):FloatArray {
                fail("Revoked source cannot enter inference")
                return FloatArray(1576)
            }
            override fun close() {}
        }
        val p=OpenpilotInferencePipeline(
            engine,VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(),null,2,2,
            nowMs={10_000L}
        ) {callbacks.incrementAndGet()}
        try {
            assertFalse(p.offer(OpenpilotInferencePipeline.PairSample(
                IntArray(4),IntArray(4),9_900L,
                isSourceCurrent={sourceValid.get()})))
            assertEquals(0L,p.metrics().received)
            assertEquals(0L,callbacks.get())
        } finally {p.close()}
    }
}
