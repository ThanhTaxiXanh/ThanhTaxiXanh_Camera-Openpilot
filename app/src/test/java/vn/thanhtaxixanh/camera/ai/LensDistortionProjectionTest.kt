package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class LensDistortionProjectionTest {
    private val camera = VisionSafetyGate.Calibration(
        1.5f,90f,0f,0f,"synthetic-lens-only",
        groundTruthValidated=false,
        modelCoordinateFrameValidated=false)
    private val lens = LensDistortionProjection.Intrinsics(
        640,480,320.0,320.0,320.0,240.0,
        0.0,0.0,0.0,0.0,0.0)

    @Test fun noDistortionMatchesLegacyPinholeReferenceAtKnownPoints() {
        val samples=listOf(
            CameraProjection.Point3(4.0,-1.5,0.0),
            CameraProjection.Point3(7.0,1.0,0.0),
            CameraProjection.Point3(18.0,0.0,0.0),
            CameraProjection.Point3(35.0,1.5,0.0))
        for (m in samples) {
            val pinhole=CameraProjection.project(m,camera,640,480,false)!!
            val lensPixel=LensDistortionProjection.project(m,camera,lens)!!
            assertEquals(pinhole.x,lensPixel.x,0.0001f)
            assertEquals(pinhole.y,lensPixel.y,0.0001f)
        }
        assertFalse(camera.ready)
    }

    @Test fun barrelLensPullsOffAxisPixelTowardOpticalCentre() {
        val point=CameraProjection.Point3(5.0,2.0,0.0)
        val original=LensDistortionProjection.project(point,camera,lens)!!
        val barrel=LensDistortionProjection.project(
            point,camera,lens.copy(k1=-.3))!!
        assertTrue(barrel.x < original.x)
        assertTrue(barrel.y < original.y)
        assertTrue(barrel.x > 320f)
        assertTrue(barrel.y > 240f)
    }

    @Test fun principalPointTangentialAndYawAffectPixelDeterministically() {
        val p=CameraProjection.Point3(10.0,.5,0.0)
        val baseline=LensDistortionProjection.project(p,camera,lens)!!
        val shifted=LensDistortionProjection.project(
            p,camera,lens.copy(cxPx=332.0,cyPx=235.0))!!
        assertEquals(baseline.x+12f,shifted.x,.0001f)
        assertEquals(baseline.y-5f,shifted.y,.0001f)
        val tangential=LensDistortionProjection.project(p,camera,
            lens.copy(p1=.04,p2=-.025))!!
        assertTrue(kotlin.math.abs(tangential.x-baseline.x) > .05f)
        assertTrue(kotlin.math.abs(tangential.y-baseline.y) > .05f)
        val turned=LensDistortionProjection.project(
            p,camera.copy(yawDegrees=3f),lens)!!
        assertTrue(turned.x < baseline.x)
    }

    @Test fun invalidAndOffscreenRaysFailClosed() {
        val p=CameraProjection.Point3(6.0,0.0,0.0)
        assertNull(LensDistortionProjection.project(
            p,camera,lens.copy(fxPx=Double.NaN)))
        assertNull(LensDistortionProjection.project(
            p,camera,lens.copy(fxPx=-50.0)))
        assertNull(LensDistortionProjection.project(
            p,camera,lens.copy(k1=10.0)))
        assertNull(LensDistortionProjection.project(
            p,camera,lens.copy(width=400)))
        assertNull(LensDistortionProjection.project(
            CameraProjection.Point3(-4.0,0.0,0.0),camera,lens))
        assertNull(LensDistortionProjection.project(
            CameraProjection.Point3(4.0,500.0,0.0),camera,lens))
        assertNull(LensDistortionProjection.project(
            CameraProjection.Point3(Double.NaN,0.0,0.0),camera,lens))
    }
}
