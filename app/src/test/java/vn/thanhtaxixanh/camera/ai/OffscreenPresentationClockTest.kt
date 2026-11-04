package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Synthetic PTS tests: not a real camera or model parity fixture. */
class OffscreenPresentationClockTest {
    @Test fun separateCodecNeverUsesWrappingRtpTimestampAsCorrelationKey() {
        val clock = OffscreenPresentationClock()
        assertEquals(1_000_000L, clock.next(1000L))
        assertEquals(1_001_000L, clock.next(1001L))
        // RTP may wrap from near 0xFFFFFFFF to 0 but receipt is monotonic.
        assertEquals(1_002_000L, clock.next(1002L))
    }

    @Test fun eachAccessUnitGetsUniquePtsEvenWhenSeveralArriveInOneMillisecond() {
        val clock = OffscreenPresentationClock()
        assertEquals(42_000L, clock.next(42L))
        assertEquals(42_001L, clock.next(42L))
        assertEquals(42_002L, clock.next(42L))
        assertEquals(42_003L, clock.next(41L)) // defensive clock regression
        assertEquals(45_000L, clock.next(45L))
    }

    @Test fun resetsCorrelationTimelineAfterDecoderRestart() {
        val clock = OffscreenPresentationClock()
        assertEquals(900_000L, clock.next(900L))
        assertEquals(900_001L, clock.next(900L))
        clock.reset()
        assertEquals(12_000L, clock.next(12L))
    }

    @Test fun rejectsInvalidClockOrigin() {
        val clock = OffscreenPresentationClock()
        try {
            clock.next(-1L)
            fail("Negative elapsedRealtime should not be admitted")
        } catch (_: IllegalArgumentException) {}
        try {
            clock.next(Long.MAX_VALUE)
            fail("Must not overflow microseconds")
        } catch (_: IllegalArgumentException) {}
    }
}
