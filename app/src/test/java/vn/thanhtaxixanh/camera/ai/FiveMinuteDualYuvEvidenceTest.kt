package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * TTXLIVE01 dual-stream evidence contract. All samples are synthetic,
 * not real A500S hardware, ImageReader, ONNX model or optical latency.
 */
class FiveMinuteDualYuvEvidenceTest {
    private fun recorder() = FiveMinuteStreamEvidence(requiredDurationMs=5_000L)

    private fun direct(
        elapsed: Long, rendered: Long, yuvCount: Long,
        yuvOn: Boolean = true,
        resets: Long? = 0L, errors: Long? = 0L,
        pairs: Long? = null, onnx: Long? = null
    ) = FiveMinuteStreamEvidence.Sample(
        atElapsedMs = elapsed,
        backend = FiveMinuteStreamEvidence.Backend.DIRECT,
        releasedFrames = rendered,
        yuvEnabled = yuvOn,
        yuvFrames = yuvCount,
        sidecarResets = resets, sidecarErrors = errors,
        rawPairs = pairs, onnxResults = onnx
    )

    @Test fun simultaneousPreviewAndYuvCanProduceLocalOnlyTrace() {
        val r=recorder()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0L,
            requireRawYuv=true)
        for (i in 1..5) assertEquals(i==5,
            r.sample(direct(i*1000L,i*25L,i*3L,
                pairs=(i-1).toLong(),onnx=(i-2).coerceAtLeast(0).toLong())))
        val report=r.report()!!
        assertTrue(report.contains("a500s_yuv_sidecar_required=true"))
        assertTrue(report.contains("local_yuv_sidecar_trace=OBSERVED_LOCAL_ONLY"))
        assertTrue(report.contains("local_five_minute_trace=OBSERVED_LOCAL_ONLY"))
        assertTrue(report.contains("yuv_callback_delta=12"))
        assertTrue(report.contains("yuv_progress_samples=4"))
        assertTrue(report.contains("sidecar_reset_delta=0"))
        assertTrue(report.contains("sidecar_error_delta=0"))
        assertTrue(report.contains("last_raw_pairs=4"))
        assertTrue(report.contains("last_onnx_results=3"))
        assertTrue(report.contains("field_gate_20=NOT_VERIFIED"))
        assertTrue(report.contains("onnx_model_ground_truth=NOT_VERIFIED"))
        assertTrue(report.contains("optical_latency_seconds=NOT_MEASURED"))
    }

    @Test fun perfectSurfaceWithStalledYuvCannotLookHealthy() {
        val r=recorder()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0,
            requireRawYuv=true)
        for(i in 1..5) r.sample(direct(i*1_000L,i*30L,8L))
        val report=r.report()!!
        assertTrue(report.contains("YUV_PROGRESS_STALLED"))
        assertTrue(report.contains("INSUFFICIENT_YUV_PROGRESS"))
        assertTrue(report.contains("local_yuv_sidecar_trace=INCOMPLETE_OR_INTERRUPTED"))
        assertTrue(report.contains("local_five_minute_trace=INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun disabledYuvFailsEvenWhenCountersStillMove() {
        val r=recorder()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0,
            requireRawYuv=true)
        for(i in 1..5) r.sample(direct(i*1000L,i*20L,i*4L,
            yuvOn = i < 3))
        assertTrue(r.report()!!.contains("YUV_TAP_DISABLED"))
        assertTrue(r.report()!!.contains(
            "local_yuv_sidecar_trace=INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun priorDecoderResetsAreNotMistakenForNewTraceErrors() {
        val r=recorder()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0,
            requireRawYuv=true)
        for(i in 1..5) r.sample(direct(i*1000L,i*20L,i*4L,
            resets=9L,errors=2L))
        val report=r.report()!!
        assertTrue(report.contains("sidecar_reset_delta=0"))
        assertTrue(report.contains("sidecar_error_delta=0"))
        assertTrue(report.contains("faults=NONE"))
    }

    @Test fun newDecoderResetAndErrorAreFailClosedDuringTest() {
        val r=recorder()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0,
            requireRawYuv=true)
        for(i in 1..5) r.sample(direct(i*1000L,i*24L,i*3L,
            resets=if(i<3) 4 else 5,
            errors=if(i<4) 0 else 1))
        val report=r.report()!!
        assertTrue(report.contains("sidecar_reset_delta=1"))
        assertTrue(report.contains("sidecar_error_delta=1"))
        assertTrue(report.contains("YUV_DECODER_RESET_OBSERVED"))
        assertTrue(report.contains("YUV_DECODER_ERRORS"))
        assertTrue(report.contains(
            "local_yuv_sidecar_trace=INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun missingSidecarMetricsAndAbsentYuvFrameCountAreNotIgnored() {
        val r=recorder()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0,
            requireRawYuv=true)
        for(i in 1..5) r.sample(direct(i*1000L,i*30L,i*3L,
            resets=null,errors=null))
        assertTrue(r.report()!!.contains("YUV_SIDECAR_METRICS_MISSING"))
        val missing=recorder()
        missing.start(FiveMinuteStreamEvidence.Backend.DIRECT,0,
            requireRawYuv=true)
        for(i in 1..5) missing.sample(FiveMinuteStreamEvidence.Sample(
            i*1000L,FiveMinuteStreamEvidence.Backend.DIRECT,
            releasedFrames=i*30L, yuvEnabled=true,
            sidecarResets=0L,sidecarErrors=0L))
        assertTrue(missing.report()!!.contains("MISSING_YUV_CALLBACK_COUNT"))
    }

    @Test fun yuvCounterRegressionIsVisibleAndCannotPass() {
        val r=recorder()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0,
            requireRawYuv=true)
        for(i in 1..5) r.sample(direct(i*1000L,i*30L,
            if(i==3) 0 else i*5L))
        assertTrue(r.report()!!.contains("YUV_CALLBACK_COUNTER_RESET"))
    }

    @Test fun yuvIsDirectOnlyAndLegacyPreviewTraceDoesNotClaimSidecar() {
        val r=recorder()
        try {
            r.start(FiveMinuteStreamEvidence.Backend.IJK,0,
                requireRawYuv=true)
            fail("IJK must not masquerade as a dual MediaCodec trace")
        } catch (_: IllegalArgumentException) { }
        r.start(FiveMinuteStreamEvidence.Backend.IJK,0)
        for(i in 1..5) r.sample(FiveMinuteStreamEvidence.Sample(
            i*1000L,FiveMinuteStreamEvidence.Backend.IJK,
            ijkOutputFps=21f,ijkPlaying=true))
        val report=r.report()!!
        assertTrue(report.contains("local_yuv_sidecar_trace=NOT_ARMED"))
        assertTrue(report.contains("yuv_callback_delta=NOT_MEASURED"))
    }

    @Test fun traceCannotUpgradeA500sOrMetricModelWithFakeResults() {
        val r=recorder()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0,
            requireRawYuv=true)
        for(i in 1..5) r.sample(direct(i*1000L,i*30L,i*4L,
            pairs=i*20L,onnx=i*20L))
        val report=r.report()!!
        assertTrue(report.contains("local_five_minute_trace=OBSERVED_LOCAL_ONLY"))
        assertTrue(report.contains("onnx_model_ground_truth=NOT_VERIFIED"))
        assertTrue(report.contains("field_gate_20=NOT_VERIFIED"))
        assertTrue(report.contains("independent_hardware_review=REQUIRED"))
    }
}
