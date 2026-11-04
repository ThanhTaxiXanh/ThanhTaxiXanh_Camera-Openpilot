package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class A500sRawVisionBridgeTest {
    private fun testFrame(w: Int = 640, h: Int = 480) =
        Yuv420FrameConverter.Frame(
            w, h,
            Yuv420FrameConverter.Plane(ByteArray(w*h) { 96.toByte() }, w, 1),
            Yuv420FrameConverter.Plane(ByteArray(w*h/4) { 128.toByte() }, w/2, 1),
            Yuv420FrameConverter.Plane(ByteArray(w*h/4) { 128.toByte() }, w/2, 1)
        )

    @Test fun acceptsOnlyChronologicalBoundedPairs() {
        val clock = AtomicLong(10_000L)
        val received = mutableListOf<OpenpilotInferencePipeline.PairSample>()
        val bridge = A500sRawVisionBridge(
            nowMs = { clock.get() },
            submit = { received.add(it); true })
        val f = testFrame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(f, 9_600L))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(f, 9_700L))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(f, 9_800L))
        assertEquals(2, received.size)
        assertEquals(9_800L, received[1].capturedAtMs)
        assertEquals(640 * 480, received[0].previousArgb.size)
        assertEquals(640 * 480, received[0].currentArgb.size)
        assertEquals(3L, bridge.metrics().frames)
        assertEquals(2L, bridge.metrics().pairs)
    }

    @Test fun rejectsStaleAndWrongDimensionsFailClosed() {
        val clock = AtomicLong(11_000L)
        val bridge = A500sRawVisionBridge(nowMs = { clock.get() },
            submit = { fail("Invalid input must not reach ONNX"); true })
        val good = testFrame()
        assertEquals(A500sRawVisionBridge.Verdict.INVALID_DIMENSIONS,
            bridge.accept(testFrame(4, 4), 10_900L))
        assertEquals(A500sRawVisionBridge.Verdict.STALE,
            bridge.accept(good, 9_000L))
        assertEquals(A500sRawVisionBridge.Verdict.STALE,
            bridge.accept(good, 11_050L))
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(good, 10_900L))
        bridge.reset()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(good, 10_950L))
        assertEquals(3L, bridge.metrics().rejected)
    }

    @Test fun gapOrReconnectionNeverMixesPreviousCameraFrame() {
        val clock = AtomicLong(20_000L)
        val received = mutableListOf<Long>()
        val bridge = A500sRawVisionBridge(nowMs = { clock.get() },
            submit = { received.add(it.capturedAtMs); true })
        val f = testFrame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(f, 19_700L))
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(f, 19_600L)) // time reversed
        clock.set(21_000L)
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(f, 20_700L)) // 1300ms gap
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(f, 20_800L))
        assertEquals(listOf(20_800L), received)
        assertEquals(2L, bridge.metrics().discontinuities)
    }


    @Test fun decoderEpochResetBlocksCrossGopPairEvenWithTinyGap() {
        val now = AtomicLong(10000L)
        val submissions = mutableListOf<Long>()
        val bridge = A500sRawVisionBridge(
            nowMs = { now.get() },
            submit = { submissions.add(it.capturedAtMs); true })
        val frame = testFrame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(frame, 9770L))
        // A missing H264 reference picture is not just a clock gap.
        // Even 40 ms between frames must not cross the damaged GOP.
        bridge.reset()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(frame, 9810L))
        assertTrue(submissions.isEmpty())
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(frame, 9850L))
        assertEquals(listOf(9850L), submissions)
    }

    @Test fun longYuvDeliveryPauseCannotCrossTheTemporalModelWindow() {
        val clock = AtomicLong(10_000L)
        val submitted = mutableListOf<Long>()
        val bridge = A500sRawVisionBridge(
            nowMs = {clock.get()},
            submit = {submitted.add(it.capturedAtMs); true})
        val frame = testFrame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(frame, 9_800L))
        clock.set(10_400L)
        // The new frame is fresh but the 590ms temporal jump must not
        // combine two physically unrelated frames into one ONNX tensor.
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(frame, 10_390L))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(frame, 10_400L))
        assertEquals(listOf(10_400L),submitted)
        assertEquals(1L,bridge.metrics().discontinuities)
    }

    @Test fun previousFrameAgeIsRecheckedWhenSecondFrameArrives() {
        val clock = AtomicLong(10_000L)
        val bridge = A500sRawVisionBridge(
            nowMs = {clock.get()},
            submit = {fail("Old source frame cannot enter ONNX"); true})
        val frame = testFrame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(frame, 9_900L))
        clock.set(10_550L)
        // The pair is only 490ms apart, but now the previous frame
        // would be 650ms old, past the shared freshness deadline.
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(frame, 10_390L))
        assertEquals(0L,bridge.metrics().pairs)
    }

    @Test fun sinkBackpressureNeverClaimsModelWasFed() {
        val clock = AtomicLong(5000L)
        val bridge = A500sRawVisionBridge(nowMs = { clock.get() },
            submit = { false })
        val f = testFrame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(f, 4800L))
        assertEquals(A500sRawVisionBridge.Verdict.SINK_REJECTED,
            bridge.accept(f, 4900L))
        assertEquals(0L, bridge.metrics().pairs)
        assertEquals(1L, bridge.metrics().rejected)
    }
}
