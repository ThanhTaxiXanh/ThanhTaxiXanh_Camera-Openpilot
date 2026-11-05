package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic producer/preprocessor clock tests. The converter is
 * injected to simulate CPU delays; these are not A500S hardware tests.
 */
class RawYuvIngressFreshnessTest {
    private val pixels = IntArray(640 * 480) { 0xff606060.toInt() }
    // No actual YUV pixel read happens in these specific timing tests.
    // Real pixel/stride conversion is covered by Yuv420FrameConverterTest.
    private fun frame() = Yuv420FrameConverter.Frame(
        640, 480,
        Yuv420FrameConverter.Plane(byteArrayOf(16), 1, 1),
        Yuv420FrameConverter.Plane(byteArrayOf(128.toByte()), 1, 1),
        Yuv420FrameConverter.Plane(byteArrayOf(128.toByte()), 1, 1)
    )

    @Test fun slowFirstYuvCopyCannotSeedNewOnnxPair() {
        val clock = AtomicLong(10_000)
        val calls = mutableListOf<Long>()
        var copyCount = 0
        val bridge = A500sRawVisionBridge(
            nowMs = { clock.get() },
            submit = { calls.add(it.capturedAtMs); true },
            toArgb = {
                if (copyCount++ == 0) clock.addAndGet(400)
                pixels
            }
        )
        val input = frame()
        assertEquals(A500sRawVisionBridge.Verdict.STALE,
            bridge.accept(input, 9_750))
        // The rejected image can never become temporal state.
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(input, 10_200))
        assertTrue(calls.isEmpty())
        assertEquals(1L, bridge.metrics().rejected)
    }

    @Test fun lateSecondCopyRejectsItsNowExpiredImageBeforeSink() {
        val clock = AtomicLong(10_000)
        val calls = mutableListOf<Long>()
        var copied = 0
        val bridge = A500sRawVisionBridge(
            nowMs = { clock.get() },
            submit = { calls.add(it.capturedAtMs); true },
            toArgb = {
                if (++copied == 2) clock.addAndGet(500)
                pixels
            }
        )
        val input = frame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(input, 9_850))
        assertEquals(A500sRawVisionBridge.Verdict.STALE,
            bridge.accept(input, 9_900))
        assertEquals(0L, bridge.metrics().pairs)
        assertTrue(calls.isEmpty())
    }

    @Test fun previouslyFreshImageExpiringDuringCopyBreaksPair() {
        val clock = AtomicLong(10_000)
        val calls = mutableListOf<Long>()
        var copied = 0
        val bridge = A500sRawVisionBridge(
            nowMs = { clock.get() },
            submit = { calls.add(it.capturedAtMs); true },
            toArgb = {
                if (++copied == 2) clock.addAndGet(440)
                pixels
            }
        )
        val input = frame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(input, 9_850))
        // At end of copy now=10440. Previous age 590ms is invalid,
        // while the current image at 9900 is still fresh (540ms).
        assertEquals(A500sRawVisionBridge.Verdict.DISCONTINUITY,
            bridge.accept(input, 9_900))
        assertTrue(calls.isEmpty())
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(input, 9_910))
        assertEquals(listOf(9_910L), calls)
    }

    @Test fun clockRegressingDuringPixelCopyFailsClosed() {
        val clock = AtomicLong(10_000)
        val bridge = A500sRawVisionBridge(
            nowMs = { clock.get() },
            submit = { fail("Regression must not reach ONNX"); true },
            toArgb = {
                clock.set(9_999)
                pixels
            }
        )
        assertEquals(A500sRawVisionBridge.Verdict.STALE,
            bridge.accept(frame(), 9_900))
    }

    @Test fun rejectedSinkInvalidatesPreviousFrameBeforeNextPair() {
        val clock = AtomicLong(10_000)
        var accepting = false
        val calls = mutableListOf<Long>()
        val bridge = A500sRawVisionBridge(
            nowMs = { clock.get() },
            submit = {
                calls.add(it.capturedAtMs)
                accepting
            },
            toArgb = { pixels }
        )
        val input = frame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(input, 9_700))
        assertEquals(A500sRawVisionBridge.Verdict.SINK_REJECTED,
            bridge.accept(input, 9_800))
        accepting = true
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(input, 9_850))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(input, 9_900))
        assertEquals(listOf(9_800L, 9_900L), calls)
        assertEquals(1L, bridge.metrics().pairs)
    }

    @Test fun healthyFreshFramesStillSubmitWithOriginalReceiptTimestamp() {
        val clock = AtomicLong(10_000)
        val outputs = mutableListOf<Long>()
        val bridge = A500sRawVisionBridge(
            nowMs = { clock.get() },
            submit = { outputs.add(it.capturedAtMs); true },
            toArgb = { pixels }
        )
        val input = frame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(input, 9_710))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(input, 9_810))
        assertEquals(A500sRawVisionBridge.Verdict.SUBMITTED,
            bridge.accept(input, 9_910))
        assertEquals(listOf(9_810L, 9_910L), outputs)
    }

    @Test fun sourceAgeBoundaryAt550MsAfterConversionIsInclusive() {
        val clock = AtomicLong(10_000)
        val bridge = A500sRawVisionBridge(
            nowMs = { clock.get() },
            submit = { true },
            toArgb = {
                clock.set(10_250)
                pixels
            }
        )
        val input = frame()
        assertEquals(A500sRawVisionBridge.Verdict.WAITING_PAIR,
            bridge.accept(input, 9_700))
        clock.set(10_251)
        assertEquals(A500sRawVisionBridge.Verdict.STALE,
            bridge.accept(input, 9_700))
    }

    private class NeverRunEngine : VisionInferenceEngine {
        var calls = 0
        override fun run(img: FloatArray, bigImg: FloatArray): FloatArray {
            calls++
            return FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS)
        }
        override fun close() {}
    }

    @Test fun onnxQueueRejectsOldReceiptBeforeCountingReceived() {
        val clock = AtomicLong(10_000)
        val engine = NeverRunEngine()
        val pipeline = OpenpilotInferencePipeline(
            engine, VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(), null, 2, 2,
            nowMs = { clock.get() }
        ) {}
        try {
            val image = IntArray(4)
            assertFalse(pipeline.offer(
                OpenpilotInferencePipeline.PairSample(image, image, 9_449)))
            assertEquals(0L, pipeline.metrics().received)
            assertEquals(1L, pipeline.metrics().stale)
            assertEquals(0, engine.calls)
        } finally { pipeline.close() }
    }

    @Test fun onnxQueueRejectsFutureAndInvalidSourceTime() {
        val clock = AtomicLong(10_000)
        val engine = NeverRunEngine()
        val pipeline = OpenpilotInferencePipeline(
            engine, VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(), null, 2, 2,
            nowMs = { clock.get() }
        ) {}
        try {
            val image = IntArray(4)
            assertFalse(pipeline.offer(
                OpenpilotInferencePipeline.PairSample(image, image, 10_001)))
            assertFalse(pipeline.offer(
                OpenpilotInferencePipeline.PairSample(image, image, -1)))
            assertEquals(2L, pipeline.metrics().stale)
            assertEquals(0L, pipeline.metrics().received)
        } finally { pipeline.close() }
    }
}
