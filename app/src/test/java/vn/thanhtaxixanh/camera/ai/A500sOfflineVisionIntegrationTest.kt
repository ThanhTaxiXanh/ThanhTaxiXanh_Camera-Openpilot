package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Synthetic host end-to-end: decoder-owned 640x480 YUV420 planes ->
 * stride-aware ARGB -> temporal A500S bridge -> 512x256 warp ->
 * 12-channel FLOAT32 model inputs -> fake engine -> 1,576-head decoder.
 *
 * No genuine camera image, real ONNX model, xADAS golden coordinates or
 * hardware/preview performance claim is present in these tests.
 */
class A500sOfflineVisionIntegrationTest {
    private fun grayFrame(y: Int): Yuv420FrameConverter.Frame {
        val w = A500sRawVisionBridge.EXPECTED_WIDTH
        val h = A500sRawVisionBridge.EXPECTED_HEIGHT
        return Yuv420FrameConverter.Frame(
            w, h,
            Yuv420FrameConverter.Plane(ByteArray(w*h) { y.toByte() },w,1),
            Yuv420FrameConverter.Plane(ByteArray(w*h/4) { 128.toByte() },w/2,1),
            Yuv420FrameConverter.Plane(ByteArray(w*h/4) { 128.toByte() },w/2,1))
    }

    private val center = VirtualCameraWarp.Matrix(doubleArrayOf(
        1.0,0.0,64.0, 0.0,1.0,112.0, 0.0,0.0,1.0))
    private val wide = VirtualCameraWarp.Matrix(doubleArrayOf(
        1.2,0.0,13.0, 0.0,1.2,86.0, 0.0,0.0,1.0))

    @Test fun syntheticA500sYuvPairTraversesAllRawTensorStagesWithoutAdas() {
        val clock = AtomicLong(5_000L)
        val result = AtomicReference<OpenpilotInferencePipeline.Outcome>()
        val inputs = AtomicReference<Pair<Float,Float>>()
        val finished = CountDownLatch(1)
        val engine = object : VisionInferenceEngine {
            override fun run(img: FloatArray,bigImg: FloatArray): FloatArray {
                VisionInputContract.requireRawStudioByteTensors(img,bigImg)
                val plane=SupercomboV027Contract.WIDTH*SupercomboV027Contract.HEIGHT
                inputs.set(Pair(img[0],img[6*plane]))
                assertEquals(SupercomboV027Contract.INPUT_ELEMENTS,bigImg.size)
                clock.addAndGet(35L) // logical model runtime, not wall time
                return FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS)
            }
            override fun close() {}
        }
        val pipeline = OpenpilotInferencePipeline(
            engine,center,wide,null,640,480,nowMs={clock.get()}) {
                result.set(it)
                finished.countDown()
            }
        val bridge = A500sRawVisionBridge(
            nowMs = {clock.get()}, submit={pair->pipeline.offer(pair)})
        try {
            assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
                bridge.accept(grayFrame(96),4_900L))
            assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
                bridge.accept(grayFrame(160),4_960L))
            assertTrue(finished.await(8,TimeUnit.SECONDS))
            val values=inputs.get()
            assertNotNull(values)
            assertEquals(96f,values!!.first,1.0f)
            assertEquals(160f,values.second,1.0f)
            val outcome=result.get()
            assertNotNull(outcome)
            assertTrue(outcome.status.startsWith("RAW_ONLY"))
            assertEquals(4,outcome.model!!.lanes.size)
            assertTrue(outcome.projectedLanes.isEmpty())
            assertNotNull(outcome.timing)
            assertEquals(75L,outcome.timing!!.sourceToResultMs)
            assertEquals(35L,outcome.timing!!.inferenceMs)
            assertEquals(1L,pipeline.metrics().processed)
            assertEquals(1L,bridge.metrics().pairs)
            assertFalse(VisionSafetyGate.assess(
                input=null,calibration=null,nowElapsedMs=5_035L,
                speedMetersPerSecond=null).mayWarn)
        } finally {
            pipeline.close()
        }
    }

    @Test fun damagedGopEpochBlocksAnAlreadyRunningModelOutcome() {
        val clock=AtomicLong(8_000L)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val newest=CountDownLatch(1)
        val calls=AtomicInteger()
        val outcomes=CopyOnWriteArrayList<Long>()
        val engine=object:VisionInferenceEngine {
            override fun run(img:FloatArray,bigImg:FloatArray):FloatArray {
                if (calls.incrementAndGet()==1) {
                    entered.countDown()
                    check(release.await(8,TimeUnit.SECONDS))
                }
                return FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS)
            }
            override fun close() {}
        }
        val pipeline=OpenpilotInferencePipeline(
            engine,VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(),null,512,256,
            nowMs={clock.get()}) {
                outcomes.add(it.capturedAtMs)
                newest.countDown()
            }
        val pixels=IntArray(512*256) { 0xff808080.toInt() }
        try {
            assertTrue(pipeline.offer(OpenpilotInferencePipeline.PairSample(
                pixels,pixels,7_900L)))
            assertTrue(entered.await(8,TimeUnit.SECONDS))
            // This is the same nonblocking invalidation fired on RTP loss,
            // sidecar decoder discontinuity, and RTSP reconnect.
            pipeline.invalidate()
            release.countDown()
            assertTrue(pipeline.offer(OpenpilotInferencePipeline.PairSample(
                pixels,pixels,7_980L)))
            assertTrue(newest.await(8,TimeUnit.SECONDS))
            assertEquals(listOf(7_980L),outcomes.toList())
            assertEquals(2,calls.get())
            assertEquals(1L,pipeline.metrics().invalidations)
            assertTrue(pipeline.metrics().droppedByEpoch>=1L)
        } finally {
            release.countDown()
            pipeline.close()
        }
    }

    @Test fun verySlowModelResultIsDiscardedInsteadOfPublished() {
        val clock=AtomicLong(12_000L)
        val entered=CountDownLatch(1)
        val published=CopyOnWriteArrayList<OpenpilotInferencePipeline.Outcome>()
        val engine=object:VisionInferenceEngine {
            override fun run(img:FloatArray,bigImg:FloatArray):FloatArray {
                clock.addAndGet(600L)
                entered.countDown()
                return FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS)
            }
            override fun close() {}
        }
        val pipeline=OpenpilotInferencePipeline(
            engine,VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(),null,512,256,
            nowMs={clock.get()}) { published.add(it) }
        val pixels=IntArray(512*256) { 0xff101010.toInt() }
        try {
            assertTrue(pipeline.offer(OpenpilotInferencePipeline.PairSample(
                pixels,pixels,11_950L)))
            assertTrue(entered.await(8,TimeUnit.SECONDS))
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
            while (pipeline.metrics().stale==0L && System.nanoTime()<until)
                Thread.sleep(5)
            assertEquals(0L,pipeline.metrics().processed)
            assertTrue(pipeline.metrics().stale>=1L)
            assertEquals(
                VisionPipelineTiming.Status.TOO_OLD,
                pipeline.metrics().lastTiming!!.status)
            assertTrue(published.isEmpty())
        } finally {
            pipeline.close()
        }
    }

    @Test fun latestPairWinsWhenAnOnnxWorkerIsBackpressured() {
        val clock=AtomicLong(15_000L)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val finished=CountDownLatch(2)
        val resultTimes=CopyOnWriteArrayList<Long>()
        val runs=AtomicInteger()
        val engine=object:VisionInferenceEngine {
            override fun run(img:FloatArray,bigImg:FloatArray):FloatArray {
                if (runs.incrementAndGet()==1) {
                    entered.countDown()
                    check(release.await(8,TimeUnit.SECONDS))
                }
                return FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS)
            }
            override fun close() {}
        }
        val pipeline=OpenpilotInferencePipeline(
            engine,VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(),null,512,256,
            nowMs={clock.get()}) {
                resultTimes.add(it.capturedAtMs)
                finished.countDown()
            }
        val px=IntArray(512*256) { 0xffaaaaaa.toInt() }
        try {
            assertTrue(pipeline.offer(OpenpilotInferencePipeline.PairSample(
                px,px,14_900L)))
            assertTrue(entered.await(8,TimeUnit.SECONDS))
            assertTrue(pipeline.offer(OpenpilotInferencePipeline.PairSample(
                px,px,14_930L)))
            assertTrue(pipeline.offer(OpenpilotInferencePipeline.PairSample(
                px,px,14_960L)))
            release.countDown()
            assertTrue(finished.await(8,TimeUnit.SECONDS))
            assertEquals(listOf(14_900L,14_960L),resultTimes.toList())
            assertEquals(2,runs.get())
            assertEquals(2L,pipeline.metrics().processed)
            assertEquals(1L,pipeline.metrics().overwritten)
        } finally {
            release.countDown()
            pipeline.close()
        }
    }
}
