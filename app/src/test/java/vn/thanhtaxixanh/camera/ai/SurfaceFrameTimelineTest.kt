package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class SurfaceFrameTimelineTest {
    @Test fun matchedRenderEventMeasuresOnlyReleaseToRenderCallbackDelay() {
        val t=SurfaceFrameTimeline()
        t.released(9000L,1_000_000_000L)
        t.released(12000L,1_002_000_000L)
        assertEquals(8.0,t.rendered(12000L,1_010_000_000L)!!,0.0001)
        assertEquals(16.0,t.rendered(9000L,1_016_000_000L)!!,0.0001)
        val s=t.snapshot()
        assertEquals(2L,s.matched)
        assertEquals(0L,s.unmatched)
        assertEquals(16.0,s.p95ReleaseToRenderMs!!,0.0001)
        assertEquals(16.0,s.lastReleaseToRenderMs!!,0.0001)
        assertEquals(0,s.pending)
        assertNull(t.rendered(9000L,1_018_000_000L))
        assertEquals(1L,t.snapshot().unmatched)
    }

    @Test fun invalidClockAndNonRenderedFramesCannotBecomeRenderEvidence() {
        val t=SurfaceFrameTimeline()
        t.released(-1L,100L)
        t.released(1L,-1L)
        t.released(2L,1_000_000_000L)
        t.forget(2L) // releaseOutputBuffer(false), not shown
        assertNull(t.rendered(2L,1_003_000_000L))
        t.released(3L,1_000_000_000L)
        assertNull(t.rendered(3L,999_999_999L))
        assertEquals(3L,t.snapshot().invalidClock)
        assertEquals(0L,t.snapshot().matched)
        assertNull(t.snapshot().p95ReleaseToRenderMs)
    }

    @Test fun boundedMemoryAndRollingP95WithOutOfOrderCallbacks() {
        val t=SurfaceFrameTimeline(maxPending=8,sampleWindow=8)
        for(i in 0 until 20) t.released(i.toLong(),1_000_000_000L)
        val s=t.snapshot()
        assertEquals(8,s.pending)
        assertEquals(12L,s.evicted)
        assertNull(t.rendered(1L,1_010_000_000L))
        for(i in 12 until 20) {
            val durationMs=(i-11).toLong()
            val nano=1_000_000_000L+durationMs*1_000_000L
            assertEquals(durationMs.toDouble(),t.rendered(i.toLong(),nano)!!,0.0001)
        }
        val after=t.snapshot()
        assertEquals(8L,after.matched)
        assertEquals(8.0,after.p95ReleaseToRenderMs!!,0.0001)
        assertEquals(0,after.pending)
        t.clear()
        assertNull(t.snapshot().p95ReleaseToRenderMs)
    }
}
