package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class CameraLensFixtureTest {
    private val calibration = VisionSafetyGate.Calibration(
        1.5f,90f,0f,0f,"synthetic-distortion",
        false,false
    )
    private val lens = LensDistortionProjection.Intrinsics(
        640,480,320.0,320.0,320.0,240.0,
        -.20,.03,.005,-.003,.005
    )

    private fun fixture(): String {
        val rows=mutableListOf(
            "TTXCAL02",
            "image,640,480",
            "candidate,1.5,90,0,0,synthetic-distortion",
            "lens,320,320,320,240,-0.2,0.03,0.005,-0.003,0.005")
        var i=0
        for (forward in listOf(4.0,7.0,16.0,32.0))
            for (right in listOf(-1.5,0.0,1.5)) {
                val pixel=LensDistortionProjection.project(
                    CameraProjection.Point3(forward,right,0.0),
                    calibration,lens)!!
                rows.add("marker,M${++i},$forward,$right,0.0,${pixel.x},${pixel.y}")
            }
        return rows.joinToString("\n")
    }
    private fun read(data:String) =
        CameraCalibrationFixture.read(ByteArrayInputStream(data.toByteArray()))

    @Test fun version2DistortionFixtureCanBeAuditedButNotAutoCalibrated() {
        val evidence=read(fixture())
        assertNotNull(evidence.lens)
        assertFalse(evidence.candidate.ready)
        val report=evidence.audit()
        assertEquals(CameraReprojectionAudit.Status.GEOMETRY_CONSISTENT_UNVERIFIED,
            report.status)
        assertEquals(12,report.inputMarkers)
        assertEquals(0.0,report.rmsErrorPx!!,0.00001)
        assertFalse(report.mayEnableAdas)
    }

    @Test fun wrongLensIsDetectedAgainstSameIndependentMarkerObservations() {
        val corrupted=fixture().replace(
            "lens,320,320,320,240,-0.2,0.03,0.005,-0.003,0.005",
            "lens,320,320,320,240,0.8,0.03,0.005,-0.003,0.005")
        val report=read(corrupted).audit()
        assertEquals(CameraReprojectionAudit.Status.HIGH_REPROJECTION_ERROR,
            report.status)
        assertTrue(report.rmsErrorPx!! > 4.0)
        assertFalse(report.mayEnableAdas)
    }

    @Test fun v1PinholeFilesRemainSupportedAndBadLensInputsAreRejected() {
        val old=fixture().replaceFirst("TTXCAL02","TTXCAL01")
            .lines().filterNot { it.startsWith("lens,") }.joinToString("\n")
        val legacy=read(old)
        assertNull(legacy.lens)
        assertEquals(12,legacy.markers.size)
        val invalid=fixture().replace(
            "lens,320,320,320,240", "lens,-320,320,320,240")
        try {
            read(invalid)
            fail("Negative focal length is not valid calibration evidence")
        } catch (_: IllegalArgumentException) {}
        val missing=fixture().lines().filterNot {
            it.startsWith("lens,")
        }.joinToString("\n")
        try {
            read(missing)
            fail("TTXCAL02 must not silently fall back to pinhole")
        } catch (_: IllegalArgumentException) {}
    }
}
