package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class VisionPipelineTimingTest {
    @Test fun separatesLocalReceiptQueuePreprocessingModelAndPostprocessing() {
        val trace = VisionPipelineTiming.inspect(
            receiptMs = 1_000L,
            offeredMs = 1_090L,
            workerStartMs = 1_110L,
            modelStartMs = 1_160L,
            modelCompletedMs = 1_240L,
            publishedAtMs = 1_250L
        )
        assertEquals(
            VisionPipelineTiming.Status.FRESH_RECEIPT_TO_RESULT, trace.status)
        assertTrue(trace.publishable)
        assertEquals(20L, trace.queueDelayMs)
        assertEquals(50L, trace.preprocessingMs)
        assertEquals(80L, trace.inferenceMs)
        assertEquals(10L, trace.postprocessingMs)
        assertEquals(250L, trace.sourceToResultMs)
        assertTrue(trace.note.contains("NOT optical exposure"))
    }

    @Test fun exactDeadlineIsIncludedButLaterCompletedResultIsDropped() {
        val within = VisionPipelineTiming.inspect(
            1_000, 1_000, 1_020, 1_050, 1_540, 1_550)
        assertTrue(within.publishable)
        val late = VisionPipelineTiming.inspect(
            1_000, 1_000, 1_020, 1_050, 1_540, 1_551)
        assertEquals(VisionPipelineTiming.Status.TOO_OLD, late.status)
        assertEquals(551L, late.sourceToResultMs)
        assertFalse(late.publishable)
    }

    @Test fun invalidClockAndNegativeOrderingNeverReportCredibleLatency() {
        val inputs = listOf(
            listOf(-1L, 100L, 110L, 115L, 120L, 125L),
            listOf(101L, 100L, 110L, 115L, 120L, 125L),
            listOf(100L, 101L, 99L, 115L, 120L, 125L),
            listOf(100L, 101L, 110L, 109L, 120L, 125L),
            listOf(100L, 101L, 110L, 115L, 114L, 125L),
            listOf(100L, 101L, 110L, 115L, 120L, 119L)
        )
        inputs.forEach { x ->
            val trace = VisionPipelineTiming.inspect(
                x[0], x[1], x[2], x[3], x[4], x[5])
            assertEquals(VisionPipelineTiming.Status.INVALID_CLOCK,trace.status)
            assertFalse(trace.publishable)
            assertNull(trace.sourceToResultMs)
        }
    }

    @Test fun timingIsNotARtspOrOpticalExposureMeasurement() {
        val trace = VisionPipelineTiming.inspect(
            30_000, 30_030, 30_040, 30_060, 30_110, 30_120)
        assertTrue(trace.note.contains("screen latency"))
        assertEquals(120L, trace.sourceToResultMs)
    }
}
