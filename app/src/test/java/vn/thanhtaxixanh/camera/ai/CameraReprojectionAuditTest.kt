package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class CameraReprojectionAuditTest {
    private val unverified = VisionSafetyGate.Calibration(
        1.5f, 90f, 0f, 0f, "synthetic-reference",
        groundTruthValidated = false,
        modelCoordinateFrameValidated = false
    )

    private fun markers(): List<CameraReprojectionAudit.Marker> {
        var index = 0
        return listOf(4.0, 7.0, 16.0, 32.0).flatMap { forward ->
            listOf(-1.5, 0.0, 1.5).map { right ->
                val point = CameraProjection.Point3(forward, right, 0.0)
                val observed = CameraProjection.project(
                    point, unverified, 640, 480,
                    requireVerifiedCalibration = false)!!
                CameraReprojectionAudit.Marker(
                    "S${index++}", point, observed)
            }
        }
    }

    @Test fun perfectSyntheticGeometryMustNeverAuthorizeAdas() {
        val result = CameraReprojectionAudit.inspect(
            unverified, 640, 480, markers())
        assertEquals(
            CameraReprojectionAudit.Status.GEOMETRY_CONSISTENT_UNVERIFIED,
            result.status)
        assertEquals(12, result.inputMarkers)
        assertEquals(0.0, result.rmsErrorPx!!, 0.000001)
        assertTrue(result.horizontalCoverage!! > 0.20)
        assertTrue(result.verticalCoverage!! > 0.10)
        assertFalse(result.mayEnableAdas)
        assertFalse(unverified.ready)
    }

    @Test fun falsePitchExceedsReprojectionTolerance() {
        val result = CameraReprojectionAudit.inspect(
            unverified.copy(pitchDegrees = 6f),
            640, 480, markers())
        assertEquals(CameraReprojectionAudit.Status.HIGH_REPROJECTION_ERROR,
            result.status)
        assertTrue(result.rmsErrorPx!! > 10.0)
        assertFalse(result.mayEnableAdas)
    }

    @Test fun narrowlyClusteredOrDuplicatedMarkersCannotProveGeometry() {
        val base = markers()
        assertEquals(CameraReprojectionAudit.Status.INSUFFICIENT,
            CameraReprojectionAudit.inspect(
                unverified, 640, 480, base.take(4)).status)
        assertEquals(CameraReprojectionAudit.Status.NARROW_DISTRIBUTION,
            CameraReprojectionAudit.inspect(
                unverified, 640, 480, base.take(9)).status)
        val duplicate = base.toMutableList()
        duplicate[1] = duplicate[0].copy(pixel = duplicate[1].pixel)
        assertEquals(CameraReprojectionAudit.Status.INVALID_INPUT,
            CameraReprojectionAudit.inspect(
                unverified, 640, 480, duplicate).status)
    }

    @Test fun offscreenProjectionOrForgedFiniteValuesFailClosed() {
        val base = markers()
        val narrowFov = unverified.copy(horizontalFovDegrees = 30f)
        assertEquals(CameraReprojectionAudit.Status.OUT_OF_VIEW,
            CameraReprojectionAudit.inspect(
                narrowFov, 640, 480, base).status)
        val forged = base.toMutableList()
        forged[0] = forged[0].copy(pixel =
            CameraProjection.Pixel(Float.NaN, 250f))
        assertEquals(CameraReprojectionAudit.Status.INVALID_INPUT,
            CameraReprojectionAudit.inspect(
                unverified, 640, 480, forged).status)
    }
}
