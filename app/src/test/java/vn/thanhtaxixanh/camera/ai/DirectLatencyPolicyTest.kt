package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class DirectLatencyPolicyTest {
    @Test fun smoothStaysOriginalDefaultAndExperimentalModeIsOptIn() {
        val baseline=DirectLatencyPolicy.SMOOTH
        assertEquals(48,baseline.maxQueuedAccessUnits)
        assertEquals(900L,baseline.enqueueWaitMs)
        assertFalse(baseline.requestCodecLowLatency)
        assertEquals(12,DirectLatencyPolicy.FRESHNESS_GUARD.maxQueuedAccessUnits)
        assertEquals(80L,DirectLatencyPolicy.FRESHNESS_GUARD.enqueueWaitMs)
        assertTrue(DirectLatencyPolicy.FRESHNESS_GUARD.requestCodecLowLatency)
        assertSame(DirectLatencyPolicy.FRESHNESS_GUARD,baseline.next())
        assertSame(baseline,DirectLatencyPolicy.FRESHNESS_GUARD.next())
    }

    @Test fun profileBoundsDoNotBlindlyDropReferencePictures() {
        val strict=DirectLatencyPolicy.FRESHNESS_GUARD
        assertEquals(DirectLatencyPolicy.AgeStatus.FRESH,
            strict.checkAge(1000L,1450L))
        assertEquals(DirectLatencyPolicy.AgeStatus.TOO_OLD,
            strict.checkAge(1000L,1451L))
        assertEquals(DirectLatencyPolicy.AgeStatus.INVALID_CLOCK,
            strict.checkAge(1000L,900L))
        assertEquals(DirectLatencyPolicy.AgeStatus.INVALID_CLOCK,
            strict.checkAge(-1L,1000L))
        assertFalse(strict.inputWaitExceeded(1000L,1350L))
        assertTrue(strict.inputWaitExceeded(1000L,1351L))
        assertTrue(strict.inputWaitExceeded(1000L,999L))
        assertEquals(DirectLatencyPolicy.AgeStatus.FRESH,
            DirectLatencyPolicy.SMOOTH.checkAge(1000L,2800L))
        assertEquals(DirectLatencyPolicy.AgeStatus.TOO_OLD,
            DirectLatencyPolicy.SMOOTH.checkAge(1000L,2801L))
    }
}
