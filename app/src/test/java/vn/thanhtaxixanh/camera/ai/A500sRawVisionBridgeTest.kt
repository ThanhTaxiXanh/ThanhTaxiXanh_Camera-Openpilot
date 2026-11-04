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
            bridge.accept(f, 9_100L))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(f, 9_400L))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(f, 9_700L))
        assertEquals(2, received.size)
        assertEquals(9_700L, received[1].capturedAtMs)
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
            bridge.accept(f, 19_500L))
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(f, 19_400L)) // time reversed
        clock.set(21_000L)
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(f, 20_700L)) // 1300ms gap
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(f, 20_800L))
        assertEquals(listOf(20_800L), received)
        assertEquals(2L, bridge.metrics().discontinuities)
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
