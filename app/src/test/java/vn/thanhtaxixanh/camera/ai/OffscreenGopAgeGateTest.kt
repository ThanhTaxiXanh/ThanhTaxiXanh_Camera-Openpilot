package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Host tests with synthetic monotonic clock and H264, not native MediaCodec. */
class OffscreenGopAgeGateTest {
    private val idr = byteArrayOf(0,0,0,1,0x65,0x01)
    private val p = byteArrayOf(0,0,0,1,0x41,0x02)

    @Test fun pFrameCannotStartNewDecoderGop() {
        val g=OffscreenGopAgeGate()
        assertEquals(OffscreenGopAgeGate.Admission.AWAIT_IDR,g.admit(p,1000,1000))
        assertTrue(g.awaitingIdr())
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,1100,1120))
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(p,1200,1250))
    }

    @Test fun stalePFrameRevokesEntireChainUntilIdr() {
        val g=OffscreenGopAgeGate()
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,1000,1010))
        assertEquals(OffscreenGopAgeGate.Admission.STALE,g.admit(p,1200,1751))
        assertTrue(g.awaitingIdr())
        assertEquals(OffscreenGopAgeGate.Admission.AWAIT_IDR,g.admit(p,1800,1810))
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,1900,1920))
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(p,1940,1980))
    }

    @Test fun futureOrNegativeTimestampsInvalidatePrediction() {
        val g=OffscreenGopAgeGate()
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,1000,1100))
        assertEquals(OffscreenGopAgeGate.Admission.INVALID_CLOCK,g.admit(p,1300,1200))
        assertEquals(OffscreenGopAgeGate.Admission.AWAIT_IDR,g.admit(p,1400,1410))
        assertEquals(OffscreenGopAgeGate.Admission.INVALID_CLOCK,g.admit(idr,-1,1420))
    }

    @Test fun exact550msAgeValid551msInvalid() {
        val g=OffscreenGopAgeGate()
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,1000,1550))
        assertEquals(OffscreenGopAgeGate.Age.CURRENT,g.age(1000,1550))
        assertEquals(OffscreenGopAgeGate.Age.STALE,g.age(1000,1551))
        assertEquals(OffscreenGopAgeGate.Admission.STALE,g.admit(p,1000,1551))
    }

    @Test fun queuedPictureCanExpireBeforeWorkerDecode() {
        val g=OffscreenGopAgeGate()
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,10000,10000))
        assertEquals(OffscreenGopAgeGate.Age.STALE,g.age(10000,10600))
        g.reset()
        assertEquals(OffscreenGopAgeGate.Admission.AWAIT_IDR,g.admit(p,10601,10602))
    }

    @Test fun oldQueueHeadRequiresFreshIdrNotDroppedP() {
        val g=OffscreenGopAgeGate()
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,10000,10000))
        assertEquals(OffscreenGopAgeGate.Age.STALE,g.age(10000,10900))
        g.reset()
        assertEquals(OffscreenGopAgeGate.Admission.AWAIT_IDR,g.admit(p,10850,10900))
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,10900,10920))
    }

    @Test fun twelveThousandHealthyPicturesRemainInSameGop() {
        val g=OffscreenGopAgeGate()
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,12000,12010))
        repeat(12000) { n ->
            val t=12000+(n+1)*33L
            assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(p,t,t+75))
        }
        assertFalse(g.awaitingIdr())
    }

    @Test fun emptyInputCannotSynchronizeAndResetReturnsToIdr() {
        val g=OffscreenGopAgeGate()
        assertEquals(OffscreenGopAgeGate.Admission.AWAIT_IDR,g.admit(byteArrayOf(),50,51))
        assertEquals(OffscreenGopAgeGate.Admission.ACCEPT,g.admit(idr,54,55))
        g.reset()
        assertTrue(g.awaitingIdr())
    }

    @Test fun invalidDeadlineConfigurationIsRejected() {
        for (bad in listOf(0L,99L,5001L)) {
            try {
                OffscreenGopAgeGate(bad)
                fail("Invalid freshness limit")
            } catch (_:IllegalArgumentException) { }
        }
    }
}
