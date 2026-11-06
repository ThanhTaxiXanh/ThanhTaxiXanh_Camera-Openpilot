package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Synthetic ARGB arrays; not a verified A500S pixel or ONNX field result. */
class IjkArgbVisionBridgeTest {
    private val pixel = IntArray(640 * 480) { 0xff233445.toInt() }

    @Test fun twoFreshPixelCopyRequestsFeedOneTemporalPair() {
        var now = 1_010L
        var pairs = 0
        val b = IjkArgbVisionBridge(nowMs = { now }) {
            assertEquals(1_350L, it.capturedAtMs)
            assertEquals(640 * 480, it.currentArgb.size)
            pairs++
            true
        }
        assertEquals(IjkArgbVisionBridge.Verdict.WAITING_PAIR,
            b.accept(pixel, 1_000L))
        now = 1_370L
        assertEquals(IjkArgbVisionBridge.Verdict.SUBMITTED,
            b.accept(pixel.copyOf(), 1_350L))
        assertEquals(1, pairs)
        assertEquals(1L, b.metrics().pairs)
    }

    @Test fun gapBeyond500MsCannotReusePriorFrame() {
        var now = 10L
        val b = IjkArgbVisionBridge(nowMs = { now }) { fail("must not offer"); true }
        b.accept(pixel, 0L)
        now = 610L
        assertEquals(IjkArgbVisionBridge.Verdict.DISCONTINUITY,
            b.accept(pixel, 600L))
        assertEquals(1L, b.metrics().discontinuities)
    }

    @Test fun duplicateTimestampCannotFormTemporalPair() {
        var now = 10L
        val b = IjkArgbVisionBridge(nowMs = { now }) { fail("must not offer"); true }
        b.accept(pixel, 0L)
        assertEquals(IjkArgbVisionBridge.Verdict.DISCONTINUITY,
            b.accept(pixel, 0L))
    }

    @Test fun staleCopyIsDiscarded() {
        val b = IjkArgbVisionBridge(nowMs = { 1_000L }) { fail("must not offer"); true }
        assertEquals(IjkArgbVisionBridge.Verdict.STALE,
            b.accept(pixel, 400L))
        assertEquals(1L, b.metrics().rejected)
    }

    @Test fun wrongDimensionsAreNeverResizedToInventNativeFrame() {
        val b = IjkArgbVisionBridge(nowMs = { 10L }) { fail("must not offer"); true }
        assertEquals(IjkArgbVisionBridge.Verdict.INVALID,
            b.accept(IntArray(100), 0L))
    }

    @Test fun reconnectInvalidatesOldTemporalPartner() {
        var now = 10L
        val b = IjkArgbVisionBridge(nowMs = { now }) { fail("must not offer"); true }
        b.accept(pixel, 0L)
        b.reset()
        now = 410L
        assertEquals(IjkArgbVisionBridge.Verdict.WAITING_PAIR,
            b.accept(pixel, 400L))
    }

    @Test fun sourceInvalidationDuringSubmissionSuppressesSuccess() {
        var now = 10L
        lateinit var b: IjkArgbVisionBridge
        b = IjkArgbVisionBridge(nowMs = { now }) {
            b.signalDiscontinuity()
            true
        }
        b.accept(pixel, 0L)
        now = 360L
        assertEquals(IjkArgbVisionBridge.Verdict.DISCONTINUITY,
            b.accept(pixel.copyOf(), 350L))
        assertEquals(0L, b.metrics().pairs)
    }

    @Test fun sinkFailureBreaksPairContinuity() {
        var now = 10L
        val b = IjkArgbVisionBridge(nowMs = { now }) { false }
        b.accept(pixel, 0L)
        now = 360L
        assertEquals(IjkArgbVisionBridge.Verdict.SINK_REJECTED,
            b.accept(pixel, 350L))
        now = 710L
        assertEquals(IjkArgbVisionBridge.Verdict.WAITING_PAIR,
            b.accept(pixel, 700L))
    }
}
