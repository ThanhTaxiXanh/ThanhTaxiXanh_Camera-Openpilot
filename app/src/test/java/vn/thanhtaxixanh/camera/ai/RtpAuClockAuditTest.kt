package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class RtpAuClockAuditTest {
    @Test fun steadyRtp90kHzVideoAndArrivalHaveBoundedPacingError() {
        val clock=RtpAuClockAudit()
        val first=clock.observe(900_000L,10_000L)
        assertTrue(first.acceptable)
        assertEquals(1_000_000L,first.presentationTimeUs)
        for (i in 1..40) {
            val stamp=clock.observe(900_000L+i*3_000L,10_000L+i*33L)
            assertEquals(RtpAuClockAudit.Status.VALID,stamp.status)
            assertEquals(1_000_000L+i*33_333L +
                (i%3).toLong()/2L,stamp.presentationTimeUs!!,2_000L)
        }
        val summary=clock.snapshot()
        assertEquals(41L,summary.observedAccessUnits)
        assertEquals(0L,summary.rejectedAccessUnits)
        assertEquals(0L,summary.timestampWraps)
        assertEquals(33L,summary.lastReceiptStepMs)
        assertEquals(33.333,summary.lastRtpStepMs!!,0.001)
        assertTrue(summary.p95AbsolutePacingErrorMs!! < 1.0)
        assertTrue(summary.currentSessionDriftMs!! > 0.0)
    }

    @Test fun rtp32BitWrapKeepsCodecPtsIncreasingWithoutGiantJump() {
        val clock=RtpAuClockAudit()
        val raw0=0xfffff000L
        val a=clock.observe(raw0,1000L)
        val b=clock.observe((raw0+3000L) and 0xffffffffL,1033L)
        val c=clock.observe((raw0+6000L) and 0xffffffffL,1066L)
        val d=clock.observe((raw0+9000L) and 0xffffffffL,1100L)
        assertEquals(1_000_000L,a.presentationTimeUs)
        assertEquals(1_033_333L,b.presentationTimeUs)
        assertEquals(1_066_666L,c.presentationTimeUs)
        assertEquals(1_100_000L,d.presentationTimeUs)
        assertEquals(1L,clock.snapshot().timestampWraps)
        assertEquals(0L,clock.snapshot().rejectedAccessUnits)
    }

    @Test fun abnormalRtpClockJumpRejectedWithoutPoisoningSubsequentFrames() {
        val clock=RtpAuClockAudit()
        assertTrue(clock.observe(900_000L,1_000L).acceptable)
        val bad=clock.observe(3_000_000L,1_030L)
        assertEquals(RtpAuClockAudit.Status.TIMESTAMP_DISCONTINUITY,bad.status)
        assertNull(bad.presentationTimeUs)
        val next=clock.observe(903_000L,1_033L)
        assertTrue(next.acceptable)
        assertEquals(1_033_333L,next.presentationTimeUs)
        assertEquals(1L,clock.snapshot().rejectedAccessUnits)
    }

    @Test fun backwardsFrameTimestampsAllowSmallBFrameReorderingOnly() {
        val clock=RtpAuClockAudit()
        clock.observe(90_000L,1000L)
        val b=clock.observe(87_000L,1033L)
        assertEquals(RtpAuClockAudit.Status.VALID,b.status)
        assertTrue(b.presentationTimeUs!! < 1_000_000L)
        val invalid=clock.observe(2_000L,1066L)
        assertEquals(RtpAuClockAudit.Status.TIMESTAMP_DISCONTINUITY,invalid.status)
    }

    @Test fun invalidTimestampOrArrivalClockNeverCreatesMediaCodecPts() {
        val clock=RtpAuClockAudit()
        assertEquals(RtpAuClockAudit.Status.INVALID,
            clock.observe(-1L,1_000L).status)
        clock.observe(90_000L,1_000L)
        val regression=clock.observe(93_000L,999L)
        assertEquals(RtpAuClockAudit.Status.RECEIPT_CLOCK_REGRESSION,
            regression.status)
        assertEquals(1_033_333L,clock.observe(93_000L,1_033L).presentationTimeUs)
    }

    @Test fun jitterWindowIsBoundedAndDoesNotTreatDriftAsGlassLatency() {
        val clock=RtpAuClockAudit(sampleWindow=8)
        clock.observe(10_000L,10_000L)
        for(i in 1..100) {
            clock.observe(10_000L+i*3000L,10_000L+i*40L)
        }
        val s=clock.snapshot()
        assertEquals(101L,s.observedAccessUnits)
        assertEquals(33.333-40.0,s.lastPacingErrorMs!!,0.001)
        assertTrue(s.currentSessionDriftMs!! < -660.0)
        assertTrue(s.p95AbsolutePacingErrorMs!! < 7.0)
    }
}
