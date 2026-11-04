package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class CameraCalibrationFixtureTest {
    private fun text(): String {
        val calibration = VisionSafetyGate.Calibration(
            1.5f, 90f, 0f, 0f, "fixture-640",
            groundTruthValidated = false,
            modelCoordinateFrameValidated = false)
        val header = listOf(
            "TTXCAL01",
            "image,640,480",
            "candidate,1.5,90,0,0,fixture-640"
        )
        var id = 0
        val rows = listOf(4.0, 7.0, 16.0, 32.0).flatMap { forward ->
            listOf(-1.5, 0.0, 1.5).map { right ->
                val pixel = CameraProjection.project(
                    CameraProjection.Point3(forward,right,0.0),
                    calibration,640,480,false)!!
                "marker,M${++id},$forward,$right,0.0,${pixel.x},${pixel.y}"
            }
        }
        return (header + rows).joinToString("\n")
    }

    @Test fun authorizedLocalCsvCanBeAuditedButNeverSelfCertifies() {
        val input = ByteArrayInputStream(text().toByteArray())
        val evidence = CameraCalibrationFixture.read(input)
        assertEquals(640,evidence.imageWidth)
        assertEquals(480,evidence.imageHeight)
        assertEquals(12,evidence.markers.size)
        assertFalse(evidence.candidate.groundTruthValidated)
        assertFalse(evidence.candidate.modelCoordinateFrameValidated)
        assertFalse(evidence.candidate.ready)
        val report = evidence.audit()
        assertEquals(
            CameraReprojectionAudit.Status.GEOMETRY_CONSISTENT_UNVERIFIED,
            report.status)
        assertFalse(report.mayEnableAdas)
    }

    @Test fun malformedOversizeAndForeignEncodingFailClosed() {
        expectInvalid("TTXFAIL\n" + text().substringAfter("\n"))
        expectInvalid(text() + "\nmarker,missing,1,2")
        expectInvalid(text().replace("image,640,480","image,0,480"))
        expectInvalid(text().replace("fixture-640","untrusted,id"))
        expectInvalid(text().replace("M1,","M☂,"))
        expectInvalid("X".repeat(CameraCalibrationFixture.MAX_FILE_BYTES+1))
    }

    @Test fun insufficientMarkersAndNonPhysicalGeometryFailClosed() {
        val truncated = text().lines().take(3 + 7).joinToString("\n")
        expectInvalid(truncated)
        expectInvalid(text().replace("candidate,1.5,90,0,0",
            "candidate,15.0,90,0,0"))
    }

    private fun expectInvalid(value: String) {
        try {
            CameraCalibrationFixture.read(
                ByteArrayInputStream(value.toByteArray()))
            fail("Invalid CSV must not be parsed into geometry evidence")
        } catch (_: Exception) {}
    }
}
