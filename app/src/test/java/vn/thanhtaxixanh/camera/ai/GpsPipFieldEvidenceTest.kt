package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** All observations are synthetic; these tests do NOT execute Android PiP/GNSS. */
class GpsPipFieldEvidenceTest {
    private fun snapshot(
        at: Long, fresh: Boolean = true,
        pip: Boolean = false, permission: Boolean = true
    ) = GpsPipFieldEvidence.Snapshot(
        at, gpsRequested = true, gpsPermission = permission,
        gpsProviderEnabled = true, freshSpeedPresent = fresh,
        inPip = pip, videoSurfaceValid = true,
        playback = GpsPipFieldEvidence.Playback.IJK,
        unitsImperial = false, brandVisible = true
    )

    @Test fun requestTrueDoesNotClaimPipEntry() {
        val e=GpsPipFieldEvidence()
        e.start(100)
        e.event(101,GpsPipFieldEvidence.Event.PIP_REQUESTED)
        e.event(102,GpsPipFieldEvidence.Event.PIP_REQUEST_RETURNED_TRUE)
        e.stop(105)
        val t=e.report()!!
        assertTrue(t.contains("pip_requests=1"))
        assertTrue(t.contains("pip_confirmed_entry_callbacks=0"))
        assertTrue(t.contains("criterion_19=NOT_VERIFIED"))
    }

    @Test fun onlyPlatformCallbackConfirmsEntryAndExit() {
        val e=GpsPipFieldEvidence()
        e.start(100)
        e.event(101,GpsPipFieldEvidence.Event.PIP_REQUESTED)
        e.event(103,GpsPipFieldEvidence.Event.PIP_ENTERED_CALLBACK)
        e.sample(snapshot(104,pip=true))
        e.event(110,GpsPipFieldEvidence.Event.PIP_EXITED_CALLBACK)
        e.stop(111)
        assertTrue(e.report()!!.contains("pip_confirmed_entry_callbacks=1"))
        assertTrue(e.report()!!.contains("pip_confirmed_exit_callbacks=1"))
        assertTrue(e.report()!!.contains("independent_phone_review=REQUIRED"))
    }

    @Test fun rejectedPipRequestNeverImpliesSuccess() {
        val e=GpsPipFieldEvidence()
        e.start(1)
        e.event(2,GpsPipFieldEvidence.Event.PIP_REQUESTED)
        e.event(3,GpsPipFieldEvidence.Event.PIP_REQUEST_REJECTED)
        e.stop(4)
        assertTrue(e.report()!!.contains("pip_confirmed_entry_callbacks=0"))
    }

    @Test fun permissionDeniedRevokesGpsPresenceEvidence() {
        val e=GpsPipFieldEvidence()
        e.start(1)
        e.event(2,GpsPipFieldEvidence.Event.GPS_PERMISSION_DENIED)
        e.sample(snapshot(3,permission=false))
        e.stop(5)
        assertTrue(e.report()!!.contains("gps_permission_denials=1"))
        assertTrue(e.report()!!.contains("fresh_gps_presence_samples=0"))
    }

    @Test fun freshGpsSamplesNeverClaimGpsAccuracyOrLocation() {
        val e=GpsPipFieldEvidence()
        e.start(1)
        e.sample(snapshot(2))
        e.sample(snapshot(3,fresh=false))
        e.stop(4)
        val text=e.report()!!
        assertTrue(text.contains("fresh_gps_presence_samples=1"))
        assertTrue(text.contains("no_location_coordinates=true"))
        assertFalse(text.contains("longitude="))
        assertFalse(text.contains("latitude="))
        assertTrue(text.contains("gate_A_19=CODED_PENDING_INDEPENDENT_DEVICE_REVIEW"))
    }

    @Test fun settingsCanBeObservedAcrossReturnWithoutFakePip() {
        val e=GpsPipFieldEvidence()
        e.start(1)
        e.event(2,GpsPipFieldEvidence.Event.SETTINGS_RESUMED)
        e.sample(snapshot(3).copy(unitsImperial=true,brandVisible=false))
        e.stop(4)
        assertTrue(e.report()!!.contains("IJK,mph_ft,false"))
    }

    @Test fun oldOrFutureRelativeClockIsRejectedAndSealed() {
        val e=GpsPipFieldEvidence()
        e.start(100)
        assertFalse(e.sample(snapshot(99)))
        assertFalse(e.isActive())
        assertTrue(e.report()!!.contains("INVALID_MONOTONIC_CLOCK"))
    }

    @Test fun reversedEventTimestampInvalidatesTrace() {
        val e=GpsPipFieldEvidence()
        e.start(1)
        e.event(5,GpsPipFieldEvidence.Event.SETTINGS_RESUMED)
        assertFalse(e.event(4,GpsPipFieldEvidence.Event.PIP_ENTERED_CALLBACK))
        assertTrue(e.report()!!.contains("INVALID_CLOCK"))
        assertTrue(e.report()!!.contains("pip_confirmed_entry_callbacks=0"))
    }

    @Test fun rowBoundPreventsUnboundedRecording() {
        val e=GpsPipFieldEvidence(8)
        e.start(0)
        for(i in 1..12) e.sample(snapshot(i.toLong()))
        assertFalse(e.isActive())
        assertTrue(e.report()!!.contains("ROW_LIMIT_REACHED"))
        assertTrue(e.report()!!.contains("criterion_19=NOT_VERIFIED"))
    }

    @Test fun allEventsUseFixedEnumsAndHaveNoRawLocationFields() {
        val e=GpsPipFieldEvidence()
        e.start(0)
        var at=1L
        for(t in GpsPipFieldEvidence.Event.entries) e.event(at++,t)
        e.stop(at)
        val text=e.report()!!
        assertFalse(text.contains("rtsp://"))
        assertFalse(text.contains("192.168.0.1"))
        assertFalse(text.contains("gps.latitude"))
        assertTrue(text.contains("no_video_or_camera_credentials=true"))
    }

    @Test fun cannotStartTwiceOrAcceptAfterStop() {
        val e=GpsPipFieldEvidence()
        e.start(0)
        try {
            e.start(1)
            fail("second start must fail")
        } catch (_:IllegalArgumentException) {}
        e.stop(2)
        assertFalse(e.sample(snapshot(3)))
    }

    @Test fun stopClockRegressionIsReported() {
        val e=GpsPipFieldEvidence()
        e.start(10)
        e.sample(snapshot(20))
        e.stop(19)
        assertTrue(e.report()!!.contains("STOP_CLOCK_REVERSED"))
    }
}
