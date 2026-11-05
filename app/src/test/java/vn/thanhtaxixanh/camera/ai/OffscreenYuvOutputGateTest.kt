package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side epoch/age decision tests. Native MediaCodec Image copy is not
 * exercised and these are NOT real A500S YUV frames or optical evidence.
 */
class OffscreenYuvOutputGateTest {
    private val guard = OffscreenYuvOutputGate()

    private fun check(
        expected: Long = 11L,
        current: Long = 11L,
        active: Boolean = true,
        reset: Boolean = false,
        captured: Long = 9_700L,
        now: Long = 10_000L,
        delivered: Long = -1L
    ): OffscreenYuvOutputGate.Verdict = guard.check(
        expected,current,active,reset,captured,now,delivered)

    @Test fun freshOutputDeliveredOnlyInSameActiveGop() {
        assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,check())
        assertEquals(OffscreenYuvOutputGate.Verdict.INACTIVE,
            check(active=false))
        assertEquals(OffscreenYuvOutputGate.Verdict.EPOCH_REVOKED,
            check(current=12L))
        assertEquals(OffscreenYuvOutputGate.Verdict.RESET_PENDING,
            check(reset=true))
    }

    @Test fun gopRevocationDuringNativePixelCopyDropsOldFrameImmediately() {
        val source=AtomicLong(20L)
        val copying=CountDownLatch(1)
        val continueCopy=CountDownLatch(1)
        val result=java.util.concurrent.atomic.AtomicReference<OffscreenYuvOutputGate.Verdict>()
        val worker=Thread {
            val first=guard.check(20L,source.get(),true,false,9_700L,10_000L,-1L)
            assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,first)
            copying.countDown()
            check(continueCopy.await(5,TimeUnit.SECONDS))
            result.set(guard.check(20L,source.get(),true,false,9_700L,10_150L,-1L))
        }
        worker.start()
        try {
            assertTrue(copying.await(5,TimeUnit.SECONDS))
            source.incrementAndGet() // nonblocking RTP/decoder reset
        } finally {
            continueCopy.countDown()
            worker.join(5_000L)
        }
        assertFalse(worker.isAlive)
        assertEquals(OffscreenYuvOutputGate.Verdict.EPOCH_REVOKED,result.get())
        assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,
            guard.check(21L,source.get(),true,false,10_050L,10_160L,-1L))
    }

    @Test fun frameExpiringDuringCopyCannotReachTemporalBridge() {
        assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,
            check(captured=9_600L,now=10_000L))
        assertEquals(OffscreenYuvOutputGate.Verdict.STALE_FRAME,
            check(captured=9_600L,now=10_151L))
        assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,
            check(captured=9_600L,now=10_150L))
    }

    @Test fun failedOrRevokedCopyDoesNotThrottleNextGoodImage() {
        // If a native copy fails or its epoch is revoked, the caller MUST
        // leave lastDeliveredAtMs unchanged (-1), not commit the pre-copy time.
        assertEquals(OffscreenYuvOutputGate.Verdict.EPOCH_REVOKED,
            check(expected=1,current=2,captured=9_800,now=10_000))
        assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,
            check(expected=2,current=2,captured=9_850,now=10_100))
        assertEquals(OffscreenYuvOutputGate.Verdict.THROTTLED,
            check(expected=2,current=2,captured=10_200,now=10_220,
                delivered=10_100))
        assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,
            check(expected=2,current=2,captured=10_300,now=10_400,
                delivered=10_100))
    }

    @Test fun invalidClockIsRejectedBeforeFreshnessCalculation() {
        assertEquals(OffscreenYuvOutputGate.Verdict.INVALID_CLOCK,
            check(captured=-1L))
        assertEquals(OffscreenYuvOutputGate.Verdict.INVALID_CLOCK,
            check(captured=10_001L))
        assertEquals(OffscreenYuvOutputGate.Verdict.INVALID_CLOCK,
            check(delivered=10_100L))
    }

    @Test fun inactiveOrPendingResetBeatsTimestampAndThrottle() {
        assertEquals(OffscreenYuvOutputGate.Verdict.INACTIVE,
            check(active=false,captured=-1L,delivered=10_050L))
        assertEquals(OffscreenYuvOutputGate.Verdict.RESET_PENDING,
            check(reset=true,captured=-1L))
        assertEquals(OffscreenYuvOutputGate.Verdict.EPOCH_REVOKED,
            check(expected=10,current=11,reset=true))
    }

    @Test fun minSpacingAndAgeAreIndependentlyConfigurable() {
        val gate=OffscreenYuvOutputGate(maxFrameAgeMs=750L,
            minDeliverySpacingMs=0L)
        assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,
            gate.check(1,1,true,false,9_250,10_000,9_999))
        assertEquals(OffscreenYuvOutputGate.Verdict.STALE_FRAME,
            gate.check(1,1,true,false,9_249,10_000,9_999))
        for (maxAge in listOf(-1L,0L,99L,5_001L)) {
            try { OffscreenYuvOutputGate(maxAge);fail("Accepted invalid age $maxAge") }
            catch (_: IllegalArgumentException) {}
        }
        try { OffscreenYuvOutputGate(minDeliverySpacingMs=5_001L)
            fail("Accepted invalid output cadence")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun longVirtualPlaybackAndResetDoNotReopenOldEpoch() {
        val gate=OffscreenYuvOutputGate()
        var epoch=1L
        var last=-1L
        repeat(10_000) { i ->
            val now=20_000L+i*350L
            if(i>0 && i%103==0) {epoch++;last=-1L}
            val verdict=gate.check(epoch,epoch,true,false,now-100L,now,last)
            assertEquals(OffscreenYuvOutputGate.Verdict.DELIVER,verdict)
            last=now
            if(epoch>1L)
                assertEquals(OffscreenYuvOutputGate.Verdict.EPOCH_REVOKED,
                    gate.check(epoch-1,epoch,true,false,now-100L,now,last))
        }
    }
}
