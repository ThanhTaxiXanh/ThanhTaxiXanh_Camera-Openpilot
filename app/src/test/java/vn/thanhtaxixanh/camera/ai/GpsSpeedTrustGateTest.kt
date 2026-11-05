package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Virtual monotonic boot clock and synthetic GPS fixes; no physical GPS claim.
 */
class GpsSpeedTrustGateTest {
    private val second = 1_000_000_000L
    private fun g()=GpsSpeedTrustGate()
    @Test fun acceptsRecentFixAndConvertsToKmh() {
        val x=g()
        assertTrue(x.observe(10f,10*second,10*second+100_000_000L,0.2f))
        assertEquals(36f,x.currentKmh(11*second)!!,0.001f)
    }
    @Test fun cachedFixCannotBecomeFreshByLateDelivery() {
        val x=g()
        assertFalse(x.observe(12f,10*second,20*second,0.1f))
        assertNull(x.currentKmh(20*second))
    }
    @Test fun expiresUsingFixTimestampNotListenerTimestamp() {
        val x=g()
        assertTrue(x.observe(10f,10*second,12*second,0.2f))
        assertNotNull(x.currentKmh(13*second))
        assertNull(x.currentKmh(13*second+500_000_000L))
    }
    @Test fun explicitMissingSpeedRevokesFormerReading() {
        val x=g()
        x.observe(10f,10*second,10*second,0.1f)
        assertFalse(x.observe(null,11*second,11*second))
        assertNull(x.currentKmh(11*second))
    }
    @Test fun rejectsExcessiveOrInvalidSpeedAccuracy() {
        val x=g()
        assertFalse(x.observe(10f,10*second,10*second,7f))
        assertFalse(x.observe(10f,11*second,11*second,Float.NaN))
        assertFalse(x.observe(10f,12*second,12*second,-2f))
        assertNull(x.currentKmh(12*second))
    }
    @Test fun duplicateAndOutOfOrderFixCannotReplaceNewerReading() {
        val x=g()
        assertTrue(x.observe(10f,10*second,10*second))
        assertFalse(x.observe(4f,10*second,11*second))
        assertFalse(x.observe(4f,9*second,11*second))
        assertEquals(36f,x.currentKmh(11*second)!!,0.001f)
    }
    @Test fun futureFixIsInvalidAndNeverFabricatesSpeed() {
        val x=g()
        assertFalse(x.observe(10f,11*second,10*second))
        assertNull(x.currentKmh(10*second))
        assertTrue(x.observe(5f,12*second,12*second))
    }
    @Test fun rejectsNonfiniteNegativeAndOutOfRangeSpeed() {
        val x=g()
        assertFalse(x.observe(Float.NaN,10*second,10*second))
        assertFalse(x.observe(Float.POSITIVE_INFINITY,11*second,11*second))
        assertFalse(x.observe(-1f,12*second,12*second))
        assertFalse(x.observe(67f,13*second,13*second))
        assertNull(x.currentKmh(13*second))
    }
    @Test fun resetRevokesSpeedButNewSessionCanAcquireFreshFix() {
        val x=g()
        assertTrue(x.observe(10f,10*second,10*second))
        x.reset()
        assertNull(x.currentKmh(10*second))
        assertTrue(x.observe(7f,11*second,11*second,0.3f))
        assertEquals(25.2f,x.currentKmh(11*second)!!,0.001f)
    }
    @Test fun rejectsZeroOrInvalidMonotonicTimestamp() {
        val x=g()
        assertFalse(x.observe(2f,0L,1L))
        assertFalse(x.observe(2f,-1L,1L))
        assertFalse(x.observe(2f,1L,-1L))
        assertNull(x.currentKmh(1L))
    }
    @Test fun optionalSpeedAccuracyRemainsProvisionalNotAdasEvidence() {
        val x=g()
        assertTrue(x.observe(4f,10*second,10*second,null))
        assertEquals(14.4f,x.currentKmh(11*second)!!,0.001f)
    }
}
