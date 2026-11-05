package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Host-only recorder contract tests using virtual monotonic capture/ImageReader
 * events. Does not validate physical Camera2, Android Surface or native YUV.
 */
class PhoneCameraFieldEvidenceTest {
    private fun recorder(duration: Long = 5_000L) =
        PhoneCameraFieldEvidence(requiredMs=duration)

    private fun healthyTrace(
        clock: PhoneCameraFieldEvidence.Clock = PhoneCameraFieldEvidence.Clock.REALTIME
    ): String {
        val r=recorder()
        r.start(10_000L,PhoneCameraFieldEvidence.Lens.BACK,clock,35)
        r.configured(10_000L)
        for (i in 1..5) {
            val at=10_000L+i*1_000L
            r.capture(at-10)
            r.image(at-5,(at-30)*1_000_000L,at*1_000_000L)
            r.copy(at-1,640,480,true)
            assertEquals(i==5,r.sample(at,true))
        }
        assertFalse(r.isActive())
        return r.report()!!
    }

    @Test fun sixtySecondStyleTraceRemainsOnlyLocalNotHardwareAccepted() {
        val report=healthyTrace()
        assertTrue(report.startsWith("TTXPHONE01\n"))
        assertTrue(report.contains("source=ANDROID_CAMERA2\n"))
        assertTrue(report.contains("camera_preview=REQUIRES_INDEPENDENT_VISUAL_REVIEW\n"))
        assertTrue(report.contains("local_camera2_trace=OBSERVED_LOCAL_ONLY\n"))
        assertTrue(report.contains("criterion_8=CODED_PENDING_INDEPENDENT_DEVICE_REVIEW\n"))
        assertTrue(report.contains("verified_640x480_yuv_copies=5\n"))
        assertTrue(report.contains("peak_sensor_receipt_age_ms=30\n"))
        assertTrue(report.contains("faults=NONE\n"))
        assertFalse(report.contains("PASS_BY_CI"))
    }

    @Test fun unknownSensorClockNeverFabricatesOpticalAge() {
        val report=healthyTrace(PhoneCameraFieldEvidence.Clock.UNKNOWN)
        assertTrue(report.contains("sensor_timestamp_source=UNKNOWN\n"))
        assertTrue(report.contains("peak_sensor_receipt_age_ms=UNKNOWN_TIMEBASE\n"))
        assertTrue(report.contains("local_camera2_trace=OBSERVED_LOCAL_ONLY\n"))
        assertTrue(report.contains("criterion_8=CODED_PENDING_INDEPENDENT_DEVICE_REVIEW\n"))
    }

    @Test fun sensorClockInvalidAndStaleAreCountedButNotUsedAsOpticalProof() {
        val r=recorder()
        r.start(1000L,PhoneCameraFieldEvidence.Lens.FRONT,
            PhoneCameraFieldEvidence.Clock.REALTIME,29)
        r.configured(1000L)
        r.capture(2000)
        r.image(2000L,0L,2000_000_000L)
        r.image(2010L,3_000_000_000L,2_000_000_000L)
        r.image(2020L,1_000_000L,2_020_000_000L)
        r.copy(2030L,640,480,true)
        val m=r.metrics(2040L)
        assertEquals(2L,m.invalidSensorClocks)
        assertEquals(1L,m.staleSensorFrames)
        assertEquals(2019L,m.peakSensorAgeMs)
        r.stop(2500L)
        assertTrue(r.report()!!.contains("local_camera2_trace=INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun badCroppedYuvCopyCannotClaimVerifiedFrame() {
        val r=recorder()
        r.start(1000,PhoneCameraFieldEvidence.Lens.BACK,
            PhoneCameraFieldEvidence.Clock.REALTIME,35)
        r.configured(1000)
        r.image(1200,1_190_000_000L,1_200_000_000L)
        r.copy(1210,320,240,true)
        r.copy(1220,640,480,false)
        r.copy(1230,640,480,true)
        assertEquals(1L,r.metrics(1300).copiedYuvFrames)
        assertEquals(2L,r.metrics(1300).badYuvCopies)
        r.stop(1500)
        assertTrue(r.report()!!.contains("bad_yuv_copies=2\n"))
    }

    @Test fun repeatingRequestAndYuvMayStallEvenIfSurfaceIsValid() {
        val r=recorder(10_000)
        r.start(1000,PhoneCameraFieldEvidence.Lens.BACK,
            PhoneCameraFieldEvidence.Clock.REALTIME,35)
        r.configured(1000)
        r.capture(1900);r.image(1900,1_800_000_000L,1_900_000_000L)
        r.copy(1900,640,480,true)
        for(i in 1..10) r.sample(1000L+i*1000L,true)
        val report=r.report()!!
        assertTrue(report.contains("CAPTURE_PROGRESS_STALLED"))
        assertTrue(report.contains("YUV_PROGRESS_STALLED"))
        assertTrue(report.contains("local_camera2_trace=INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun surfaceLossOrLongTickGapMarksTraceIncomplete() {
        val r=recorder()
        r.start(1000,PhoneCameraFieldEvidence.Lens.BACK,
            PhoneCameraFieldEvidence.Clock.UNKNOWN,35)
        r.configured(1000)
        r.capture(1900);r.image(1900,0L,0L)
        r.sample(4000,false)
        r.sample(6000,true)
        val report=r.report()!!
        assertTrue(report.contains("SURFACE_INVALID"))
        assertTrue(report.contains("TICK_GAP"))
        assertTrue(report.contains("local_camera2_trace=INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun ownerStopsEarlyAndDataCannotBeReopened() {
        val r=recorder()
        r.start(1000,PhoneCameraFieldEvidence.Lens.BACK,
            PhoneCameraFieldEvidence.Clock.REALTIME,35)
        r.configured(1000)
        r.capture(1400)
        r.stop(1450,"ACTIVITY_PAUSED")
        val report=r.report()!!
        assertTrue(report.contains("end_reason=ACTIVITY_PAUSED"))
        assertTrue(report.contains("INSUFFICIENT_YUV_CALLBACKS"))
        assertTrue(report.contains("local_camera2_trace=INCOMPLETE_OR_INTERRUPTED"))
        r.capture(1500)
        r.copy(1500,640,480,true)
        assertEquals(report,r.report())
    }

    @Test fun invalidClockAndRowCapCannotBecomeSuccess() {
        val invalid=recorder()
        invalid.start(1000,PhoneCameraFieldEvidence.Lens.OTHER,
            PhoneCameraFieldEvidence.Clock.REALTIME,35)
        assertTrue(invalid.sample(1000,true))
        assertTrue(invalid.report()!!.contains("TICK_CLOCK_INVALID"))
        val cap=PhoneCameraFieldEvidence(requiredMs=60_000,maxRows=8)
        cap.start(1000,PhoneCameraFieldEvidence.Lens.BACK,
            PhoneCameraFieldEvidence.Clock.REALTIME,35)
        cap.configured(1000)
        for(i in 1..9) {
            cap.capture(1000+i*1000L)
            cap.image(1000+i*1000L,(1000+i*1000L)*1_000_000L,
                (1000+i*1000L)*1_000_000L)
            cap.copy(1000+i*1000L,640,480,true)
            cap.sample(1000+i*1000L,true)
        }
        assertTrue(cap.report()!!.contains("ROW_LIMIT"))
        assertTrue(cap.report()!!.contains("local_camera2_trace=INCOMPLETE_OR_INTERRUPTED"))
    }

    @Test fun ignoredOldCameraCallbacksCannotAlterSealedReport() {
        val r=recorder()
        r.start(1000,PhoneCameraFieldEvidence.Lens.BACK,
            PhoneCameraFieldEvidence.Clock.UNKNOWN,35)
        r.stop(1200,"CAMERA_CLOSED")
        val report=r.report()!!
        r.configured(1300)
        r.capture(1300)
        r.image(1300,0,0)
        r.copy(1300,640,480,true)
        assertEquals(report,r.report())
        assertEquals(0L,r.metrics(1400).captureCallbacks)
    }

    @Test fun invalidDurationsAndSampleBoundsRejected() {
        for(bad in listOf(0L,4999L,300001L)){
            try { PhoneCameraFieldEvidence(requiredMs=bad);fail("bad duration") }
            catch (_: IllegalArgumentException) {}
        }
        try { PhoneCameraFieldEvidence(maxRows=7);fail("bad rows") }
        catch (_: IllegalArgumentException) {}
        try { PhoneCameraFieldEvidence(maxTickGapMs=400);fail("bad gap") }
        catch (_: IllegalArgumentException) {}
    }
}
