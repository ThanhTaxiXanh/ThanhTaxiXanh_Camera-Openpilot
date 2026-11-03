package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class CameraProjectionTest {
    private val sample = VisionSafetyGate.Calibration(
        cameraHeightMeters = 1.5f,
        horizontalFovDegrees = 90f,
        pitchDegrees = 0f,
        yawDegrees = 0f,
        datasetId = "test-verified-fixture",
        groundTruthValidated = true,
        modelCoordinateFrameValidated = true
    )
    @Test fun roadInFrontProjectsBeneathImageCenter() {
        val p = CameraProjection.project(
            CameraProjection.Point3(15.0, 0.0, 0.0), sample, 640, 480)
        assertNotNull(p)
        assertEquals(320f,p!!.x,0.0001f)
        assertTrue(p.y > 240f)
    }
    @Test fun rightHandWorldIsRightHandScreen() {
        val right = CameraProjection.project(
            CameraProjection.Point3(15.0, 2.0, 0.0),sample,640,480)!!
        val left = CameraProjection.project(
            CameraProjection.Point3(15.0,-2.0, 0.0),sample,640,480)!!
        assertTrue(right.x > 320f)
        assertTrue(left.x < 320f)
    }
    @Test fun zeroDistanceBehindCameraOrUnverifiedCalibrationCannotProject() {
        assertNull(CameraProjection.project(
            CameraProjection.Point3(-1.0,0.0,0.0),sample,640,480))
        assertNull(CameraProjection.project(
            CameraProjection.Point3(15.0,0.0,0.0),
            sample.copy(groundTruthValidated=false),640,480))
    }
    @Test fun positiveDownwardPitchCentersGroundNearOpticalAxis() {
        val pitched=sample.copy(pitchDegrees=10f)
        val x=1.5/kotlin.math.tan(Math.toRadians(10.0))
        val p=CameraProjection.project(
            CameraProjection.Point3(x,0.0,0.0),pitched,640,480)!!
        assertEquals(240f,p.y,0.001f)
    }
    @Test fun doesNotProduceNaNsOrOffscreenPositions() {
        assertNull(CameraProjection.project(
            CameraProjection.Point3(Double.NaN,1.0,0.0),sample,640,480))
        assertNull(CameraProjection.project(
            CameraProjection.Point3(1.0,100.0,0.0),sample,640,480))
    }
    @Test fun lanesStayHiddenWhenCoordinateConventionIsUnverified() {
        val data=FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS)
        val snapshot=OpenpilotVisionDecoder.decode(data)
        assertTrue(LaneProjection.lanes(snapshot,
            sample.copy(modelCoordinateFrameValidated=false),
            640,480).isEmpty())
    }
}
