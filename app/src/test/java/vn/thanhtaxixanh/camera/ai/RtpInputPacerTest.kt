package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtpInputPacerTest {
    @Test fun initiallyBuffersThenTracksRtpFrameRate() {
        val clock = RtpInputPacer()
        val a = clock.plan(90_000_000, 1_000_000_000)
        val b = clock.plan(90_033_333, 1_000_001_000)
        val c = clock.plan(90_066_666, 1_000_003_000)
        assertEquals(350_000_000L, a.deadlineNs - 1_000_000_000L)
        assertEquals(33_333_000L, b.deadlineNs - a.deadlineNs)
        assertEquals(33_333_000L, c.deadlineNs - b.deadlineNs)
        assertFalse(b.rebased)
    }

    @Test fun longDecoderDelayDoesNotRebufferEveryTime() {
        val clock = RtpInputPacer()
        val first = clock.plan(1_000_000, 1_000_000_000)
        val late = clock.plan(1_033_333, 4_000_000_000)
        assertFalse(late.rebased)
        assertEquals(first.deadlineNs + 33_333_000L, late.deadlineNs)
        assertTrue(late.lateByNs > 2_000_000_000L)
    }

    @Test fun timestampDiscontinuityRebases() {
        val clock = RtpInputPacer()
        clock.plan(1_000_000, 1_000_000_000)
        assertTrue(clock.plan(3_000_000, 2_000_000_000).rebased)
        assertTrue(clock.plan(1_000_000, 3_000_000_000).rebased)
    }

    @Test fun consistent30FpsNeverRequiresRebaseOrLateFeed() {
        val clock = RtpInputPacer()
        var rebases = 0
        var overdue = 0
        for (frame in 0..1800) {
            val pts = 10_000_000L + frame * 33_333L
            val now = 1_000_000_000L + frame * 33_333_000L
            val plan = clock.plan(pts, now)
            if (plan.rebased) rebases++
            if (plan.lateByNs > 0) overdue++
        }
        assertEquals(1, rebases)
        assertEquals(0, overdue)
    }

    @Test fun invalidPacingParametersAreRejected() {
        try {
            RtpInputPacer(cushionNs = -1)
            fail("Negative buffer is invalid")
        } catch (_: IllegalArgumentException) {}
    }
}
