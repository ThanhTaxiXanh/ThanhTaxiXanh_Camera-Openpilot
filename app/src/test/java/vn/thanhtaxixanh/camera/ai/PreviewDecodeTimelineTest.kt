package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class PreviewDecodeTimelineTest {
    @Test fun matchesPtsEvenWhenMediaCodecOutputsFramesOutOfOrder() {
        val samples=PreviewDecodeTimeline()
        samples.submitted(9000L,1_000L)
        samples.submitted(13000L,1_010L)
        assertEquals(40L,samples.emitted(13000L,1_050L))
        assertEquals(100L,samples.emitted(9000L,1_100L))
        val s=samples.snapshot()
        assertEquals(2L,s.matched)
        assertEquals(0L,s.unmatched)
        assertEquals(100L,s.lastReceiptToDecoderMs)
        assertEquals(100L,s.p95ReceiptToDecoderMs)
        assertEquals(100L,s.maxReceiptToDecoderMs)
        assertEquals(0,s.pending)
        assertNull(samples.emitted(9000L,1_120L))
        assertEquals(1L,samples.snapshot().unmatched)
    }

    @Test fun boundedCorrelationNeverRetainsUnlimitedCompressedFrameState() {
        val timeline=PreviewDecodeTimeline(maxPending=8,sampleWindow=8)
        repeat(20) { i -> timeline.submitted(i.toLong(),i.toLong()) }
        val state=timeline.snapshot()
        assertEquals(8,state.pending)
        assertEquals(12L,state.evicted)
        assertNull(timeline.emitted(0L,50L))
        assertEquals(0L,timeline.emitted(19L,19L))
        assertEquals(1L,timeline.snapshot().unmatched)
    }

    @Test fun invalidClocksOrCodecPtsCannotBecomeLatencyEvidence() {
        val timeline=PreviewDecodeTimeline()
        timeline.submitted(-1L,100L)
        timeline.submitted(1L,-1L)
        timeline.submitted(2L,100L)
        assertNull(timeline.emitted(2L,99L))
        assertEquals(3L,timeline.snapshot().invalidClock)
        assertEquals(0L,timeline.snapshot().matched)
        assertNull(timeline.snapshot().lastReceiptToDecoderMs)
    }

    @Test fun p95UsesFiniteBoundedRollingWindowAndResetClearsHistory() {
        val t=PreviewDecodeTimeline(maxPending=16,sampleWindow=8)
        repeat(10) { i ->
            t.submitted(i.toLong(),100L+i)
            assertEquals(i.toLong()*10L,
                t.emitted(i.toLong(),100L+i+i*10L))
        }
        val s=t.snapshot()
        assertEquals(10L,s.matched)
        assertEquals(90L,s.p95ReceiptToDecoderMs)
        assertEquals(90L,s.maxReceiptToDecoderMs)
        t.clear()
        assertNull(t.snapshot().p95ReceiptToDecoderMs)
        assertEquals(0,t.snapshot().pending)
    }
}
