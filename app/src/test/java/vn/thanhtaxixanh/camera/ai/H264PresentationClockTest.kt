package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class H264PresentationClockTest {
    @Test fun burstOfDecodedFramesIsPacedByPtsNotCallbackArrival() {
        val clock = H264PresentationClock()
        val first = clock.plan(10_000_000, 1_000_000_000)
        val second = clock.plan(10_033_333, 1_001_000_000)
        val third = clock.plan(10_066_666, 1_002_000_000)
        assertTrue(first.shouldRender && second.shouldRender && third.shouldRender)
        assertEquals(550_000_000L, first.targetNs - 1_000_000_000L)
        assertEquals(33_333_000L, second.targetNs - first.targetNs)
        assertEquals(33_333_000L, third.targetNs - second.targetNs)
    }

    @Test fun moderateLateFrameIsDroppedOnlyAtPresentationStage() {
        val clock = H264PresentationClock()
        clock.plan(0, 1_000_000_000L)
        val late = clock.plan(33_333, 1_000_000_000L + 550_000_000L + 200_000_000L)
        assertFalse(late.shouldRender)
        assertTrue(late.lateByNs >= 150_000_000L)
    }

    @Test fun stalledOrJumpedRtpRebases() {
        val clock = H264PresentationClock()
        clock.plan(0, 1_000_000_000)
        val stalled = clock.plan(33_333, 4_000_000_000)
        assertTrue(stalled.rebased)
        assertTrue(stalled.shouldRender)
        val wrap = clock.plan(1_000, 4_000_001_000)
        assertTrue(wrap.rebased)
    }

    @Test fun irregularDecoderArrivalKeepsPresentationIntervals() {
        val clock = H264PresentationClock()
        val a = clock.plan(2000_000L, 1_000_000_000)
        val b = clock.plan(2040_000L, 1_240_000_000)
        val c = clock.plan(2080_000L, 1_300_000_000)
        assertEquals(40_000_000L, b.targetNs - a.targetNs)
        assertEquals(40_000_000L, c.targetNs - b.targetNs)
    }
}
