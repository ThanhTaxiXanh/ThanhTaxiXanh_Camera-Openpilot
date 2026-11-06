package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Synthetic local telemetry; NOT physical A500S or optical evidence. */
class FiveMinuteStreamEvidenceTest {
    private fun direct(t:Long,c:Long,reconnecting:Boolean=false) =
        FiveMinuteStreamEvidence.Sample(
            t,FiveMinuteStreamEvidence.Backend.DIRECT,c,
            reconnecting=reconnecting
        )

    @Test fun fiveMinuteUninterruptedDirectCountsOnlyLocalTelemetry() {
        val r=FiveMinuteStreamEvidence()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,1_000)
        for(i in 1..300) r.sample(direct(1_000+i*1_000L,i*20L))
        val report=r.report()!!
        assertTrue(report.contains("local_five_minute_trace=OBSERVED_LOCAL_ONLY"))
        assertTrue(report.contains("field_gate_20=NOT_VERIFIED"))
        assertTrue(report.contains("optical_latency_seconds=NOT_MEASURED"))
        assertFalse(r.isActive())
    }

    @Test fun durationLessThanFiveMinutesCannotPass() {
        val r=FiveMinuteStreamEvidence()
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0)
        for(i in 1..100) r.sample(direct(i*1000L,i*20L))
        r.stop(100_000,"OWNER_STOP")
        assertTrue(r.report()!!.contains("INCOMPLETE_OR_INTERRUPTED"))
        assertTrue(r.report()!!.contains("SESSION_INTERRUPTED"))
    }

    @Test fun reconnectMarksPhysicalContinuityAsUnproven() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,1_000)
        for(i in 1..5) {
            if(i==3) r.reconnect(1_000+i*1_000L)
            r.sample(direct(1_000+i*1_000L,i*30L))
        }
        assertTrue(r.report()!!.contains("RECONNECT_OBSERVED"))
        assertTrue(r.report()!!.contains("INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun ijkReconnectAt37SecondsStillRecordsFull300SecondsButNeverPasses() {
        val r=FiveMinuteStreamEvidence()
        r.start(FiveMinuteStreamEvidence.Backend.IJK,0)
        for (i in 1..300) {
            val now=i*1_000L
            if(i==37) r.reconnect(now,"RTSP_ON_COMPLETION")
            // 37s RTSP ends, next 4 seconds no native player is available.
            val unavailable=i in 37..41
            r.sample(FiveMinuteStreamEvidence.Sample(
                now,FiveMinuteStreamEvidence.Backend.IJK,
                ijkOutputFps=if (unavailable) 0f else 26f,
                ijkPlaying=!unavailable,reconnecting=unavailable))
        }
        assertFalse(r.isActive())
        val report=r.report()!!
        assertTrue(report.contains("elapsed_ms=300000"))
        assertTrue(report.contains("end_reason=DURATION_REACHED"))
        assertTrue(report.contains("event:reconnect,37000,RTSP_ON_COMPLETION"))
        assertTrue(report.contains("event:local_progress_resumed,42000,5000"))
        assertTrue(report.contains("max_local_recovery_gap_ms=5000"))
        assertTrue(report.contains("reconnect_events=1"))
        assertTrue(report.contains("recovered_events=1"))
        assertTrue(report.contains("faults=RECONNECT_OBSERVED"))
        assertTrue(report.contains("local_five_minute_trace=INCOMPLETE_OR_INTERRUPTED"))
        assertTrue(report.contains("field_gate_20=NOT_VERIFIED"))
    }

    @Test fun failedRecoveryNeverReportsResumption() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.IJK,0)
        r.sample(FiveMinuteStreamEvidence.Sample(
            1_000,FiveMinuteStreamEvidence.Backend.IJK,ijkOutputFps=25f,
            ijkPlaying=true))
        r.reconnect(1_500,"RTSP_ON_COMPLETION")
        for(i in 2..5) r.sample(FiveMinuteStreamEvidence.Sample(
            i*1_000L,FiveMinuteStreamEvidence.Backend.IJK,ijkOutputFps=0f,
            ijkPlaying=false,reconnecting=true))
        val report=r.report()!!
        assertTrue(report.contains("unresolved_reconnect=true"))
        assertTrue(report.contains("max_local_recovery_gap_ms=NOT_MEASURED"))
        assertTrue(report.contains("local_five_minute_trace=INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun reasonIsAllowlistedAndCannotLeakRtspUrl() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.IJK,0)
        r.reconnect(500,"rtsp://secret:token@192.168.0.1")
        for(i in 1..5) r.sample(FiveMinuteStreamEvidence.Sample(
            i*1_000L,FiveMinuteStreamEvidence.Backend.IJK,
            ijkOutputFps=25f,ijkPlaying=true))
        val report=r.report()!!
        assertTrue(report.contains("event:reconnect,500,UNCLASSIFIED"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("192.168.0.1"))
    }

    @Test fun counterRegressionCannotBePresentedAsHealthy() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,1_000)
        for(i in 1..5) r.sample(direct(1_000+i*1_000L,
            if (i>=3) ((i-3)*20).toLong() else (i*20).toLong()))
        assertTrue(r.report()!!.contains("SURFACE_COUNTER_RESET"))
    }

    @Test fun missingUiSamplingGapsCannotFakeFiveMinutes() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0)
        r.sample(direct(1_000,20))
        r.sample(direct(5_000,100))
        assertTrue(r.report()!!.contains("SAMPLE_GAP_EXCEEDED"))
    }

    @Test fun frozenDirectSourceIsNotSuccessDespiteFiveMinutes() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0)
        for(i in 1..5) r.sample(direct(i*1_000L,5L))
        assertTrue(r.report()!!.contains("VIDEO_PROGRESS_STALLED"))
    }

    @Test fun validIJKLocalFpsCanBeObservedButCannotClaimOpticalLatency() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.IJK,0)
        for(i in 1..5) r.sample(FiveMinuteStreamEvidence.Sample(
            i*1000L,FiveMinuteStreamEvidence.Backend.IJK,
            ijkOutputFps=22f,ijkPlaying=true))
        assertTrue(r.report()!!.contains("OBSERVED_LOCAL_ONLY"))
        assertTrue(r.report()!!.contains("optical_latency_seconds=NOT_MEASURED"))
    }

    @Test fun ijkZeroFpsFiveSecondsFails() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.IJK,0)
        for(i in 1..5) r.sample(FiveMinuteStreamEvidence.Sample(
            i*1000L,FiveMinuteStreamEvidence.Backend.IJK,
            ijkOutputFps=0f,ijkPlaying=true))
        assertTrue(r.report()!!.contains("VIDEO_PROGRESS_STALLED"))
    }

    @Test fun switchingBackendFailsEvenWithCounters() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0)
        r.sample(direct(1_000,25))
        r.sample(FiveMinuteStreamEvidence.Sample(2_000,
            FiveMinuteStreamEvidence.Backend.IJK,ijkOutputFps=25f,ijkPlaying=true))
        for(i in 3..5) r.sample(direct(i*1_000L,i*25L))
        assertTrue(r.report()!!.contains("BACKEND_CHANGED"))
    }

    @Test fun nonmonotonicSamplesSealAsInvalidClock() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,10_000)
        assertTrue(r.sample(direct(9_000,20)))
        assertTrue(r.report()!!.contains("INVALID_OR_NONMONOTONIC_CLOCK"))
    }

    @Test fun reportsNeverContainAnyRtspUrlOrGpsInput() {
        val r=FiveMinuteStreamEvidence(5_000)
        r.start(FiveMinuteStreamEvidence.Backend.DIRECT,0)
        for(i in 1..5) r.sample(direct(i*1_000L,i*20L))
        val out=r.report()!!
        assertFalse(out.contains("192.168.0.1"))
        assertFalse(out.contains("rtsp://"))
        assertFalse(out.contains("location="))
    }
}
