package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class DirectStreamHealthTest {
    private fun advance(
        h: DirectStreamHealth,
        firstMs: Long = 0L,
        count: Int = 360,
        uniquePts: Boolean = true,
        codecOutput: Boolean = true,
        surface: Boolean = true,
        receiveAu: Boolean = true,
        receiveRtp: Boolean = true
    ) {
        repeat(count) { index ->
            val now = firstMs + index*33L
            val pts = if (uniquePts)
                1_000_000L + now*1000L else 1_000_000L
            if (receiveRtp) assertTrue(h.onRtp(now))
            if (receiveAu) assertTrue(h.onAu(pts,now))
            if (codecOutput) assertTrue(h.onDecoded(now))
            if (surface) assertTrue(h.onSurfaceReleased(now))
        }
    }

    @Test fun healthyFrameEventsAndMissingVendorRenderCallbacksDoNotTriggerReset() {
        val h=DirectStreamHealth()
        assertTrue(h.start(0L))
        advance(h)
        val s=h.snapshot(11_847L)
        assertEquals(DirectStreamHealth.Status.FLOWING,s.status)
        assertFalse(s.requiresReconnect)
        assertEquals(0L,s.invalidClocks)
        assertFalse(h.start(12_000L))
    }

    @Test fun networkStillPacketsButNoCompleteAuIsDetected() {
        val h=DirectStreamHealth()
        h.start(0L)
        advance(h,count=300)
        val lastAuMs=299*33L
        for (n in 1..240) h.onRtp(lastAuMs+n*33L)
        val s=h.snapshot(lastAuMs+240*33L)
        assertEquals(DirectStreamHealth.Status.AU_STALLED_WITH_RTP,s.status)
        assertTrue(s.requiresReconnect)
        assertTrue(s.lastRtpAgoMs!! < 100L)
        assertTrue(s.lastAuAgoMs!! > 6000L)
    }

    @Test fun stalledCodecAndStalledSurfaceAreDifferent() {
        val codec=DirectStreamHealth()
        codec.start(0L)
        advance(codec,count=300)
        advance(codec,firstMs=9_900L,count=200,
            codecOutput=false,surface=false)
        assertEquals(DirectStreamHealth.Status.CODEC_OUTPUT_STALLED,
            codec.snapshot(16_467L).status)

        val surface=DirectStreamHealth()
        surface.start(0L)
        advance(surface,count=300)
        advance(surface,firstMs=9_900L,count=200,
            codecOutput=true,surface=false)
        assertEquals(DirectStreamHealth.Status.SURFACE_RELEASE_STALLED,
            surface.snapshot(16_467L).status)
    }

    @Test fun repeatedAUsWithoutPtsAdvanceAreNotMistakenForLiveVideo() {
        val h=DirectStreamHealth()
        h.start(0L)
        advance(h,count=300)
        for (n in 1..120) {
            val now=9_867L+n*33L
            h.onRtp(now)
            h.onAu(10_867_000L,now)
            h.onDecoded(now)
            h.onSurfaceReleased(now)
        }
        val s=h.snapshot(13_827L)
        assertEquals(DirectStreamHealth.Status.SOURCE_PTS_FROZEN,s.status)
        assertTrue(s.nonAdvancingAuCount > 8L)
        assertTrue(s.duplicatePts > 100L)
        assertTrue(s.requiresReconnect)
    }

    @Test fun individualBFramePtsRegressionsDoNotBecomeCameraFreeze() {
        val h=DirectStreamHealth()
        h.start(0L)
        repeat(300) { i ->
            val now=i*33L
            val pts=1_000_000L+(i*3_000L) +
                (if (i%6==2) -6_000L else 0L)
            h.onRtp(now)
            h.onAu(pts,now)
            h.onDecoded(now)
            h.onSurfaceReleased(now)
        }
        val s=h.snapshot(9_867L)
        assertEquals(DirectStreamHealth.Status.FLOWING,s.status)
        assertTrue(s.backwardPts > 0)
        assertFalse(s.requiresReconnect)
    }

    @Test fun sourceRtpSilenceAndStartupAreConservativelySeparated() {
        val h=DirectStreamHealth()
        assertTrue(h.start(0L))
        assertEquals(DirectStreamHealth.Status.STARTING,
            h.snapshot(9_000L).status)
        assertEquals(DirectStreamHealth.Status.RTP_SILENT,
            h.snapshot(13_000L).status)
        assertTrue(h.snapshot(13_000L).requiresReconnect)
    }

    @Test fun clockRegressionAndFutureEventsFailClosed() {
        val h=DirectStreamHealth()
        h.start(1000L)
        assertFalse(h.onAu(-1L,1000L))
        assertFalse(h.onRtp(999L))
        assertTrue(h.onRtp(1000L))
        assertFalse(h.onRtp(900L))
        assertEquals(3L,h.snapshot(1000L).invalidClocks)
        assertEquals(DirectStreamHealth.Status.INVALID_TIMEBASE,
            h.snapshot(999L).status)
        assertFalse(h.snapshot(999L).requiresReconnect)
    }

    @Test fun longSyntheticSessionHasConstantMemoryAndNoFalseFreeze() {
        val h=DirectStreamHealth()
        h.start(0L)
        advance(h,count=36_000)
        val snapshot=h.snapshot(35_999L*33L)
        assertEquals(DirectStreamHealth.Status.FLOWING,snapshot.status)
        assertEquals(0L,snapshot.invalidClocks)
        assertEquals(0L,snapshot.nonAdvancingAuCount)
    }
}
