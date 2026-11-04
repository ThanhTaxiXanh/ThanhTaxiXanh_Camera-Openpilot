package vn.thanhtaxixanh.camera.ai

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Brown-Conrady pinhole + radial/tangential distortion reference.
 *
 * This is an OFFLINE DIAGNOSTIC of owner-supplied candidate intrinsics,
 * not the verified A500S optical model. It never supplies a trusted
 * calibration to live HUD, model output, or lead-distance warnings.
 *
 * Camera normalized ray: x points right, y points down; vehicle axes:
 * forward/right/up. Yaw positive right, pitch positive down, matching
 * CameraProjection's independent reference convention.
 */
object LensDistortionProjection {
    data class Intrinsics(
        val width: Int,
        val height: Int,
        val fxPx: Double,
        val fyPx: Double,
        val cxPx: Double,
        val cyPx: Double,
        val k1: Double,
        val k2: Double,
        val p1: Double,
        val p2: Double,
        val k3: Double
    ) {
        val structurallyValid: Boolean get() =
            width in 2..8192 && height in 2..8192 &&
                listOf(fxPx,fyPx,cxPx,cyPx,k1,k2,p1,p2,k3).all { it.isFinite() } &&
                fxPx in width*0.10..width*8.0 &&
                fyPx in height*0.10..height*8.0 &&
                cxPx in -width*0.25..width*1.25 &&
                cyPx in -height*0.25..height*1.25 &&
                abs(k1) <= 2.0 && abs(k2) <= 2.0 &&
                abs(k3) <= 2.0 && abs(p1) <= 0.5 && abs(p2) <= 0.5
    }

    /** Null on invalid candidate, behind-camera or off-screen result. */
    fun project(
        position: CameraProjection.Point3,
        camera: VisionSafetyGate.Calibration,
        lens: Intrinsics
    ): CameraProjection.Pixel? {
        if (!camera.structurallyValid || !lens.structurallyValid ||
            !position.forwardM.isFinite() || !position.rightM.isFinite() ||
            !position.upM.isFinite()) return null
        val pitch = Math.toRadians(camera.pitchDegrees.toDouble())
        val yaw = Math.toRadians(camera.yawDegrees.toDouble())
        val down = camera.cameraHeightMeters.toDouble() - position.upM
        val forwardYaw = cos(yaw) * position.forwardM +
            sin(yaw) * position.rightM
        val lateralYaw = -sin(yaw) * position.forwardM +
            cos(yaw) * position.rightM
        val forwardCamera = cos(pitch) * forwardYaw + sin(pitch) * down
        val downCamera = -sin(pitch) * forwardYaw + cos(pitch) * down
        if (!forwardCamera.isFinite() || forwardCamera <= 0.01) return null

        val x = lateralYaw / forwardCamera
        val y = downCamera / forwardCamera
        if (!x.isFinite() || !y.isFinite()) return null
        val x2 = x*x
        val y2 = y*y
        val r2 = x2+y2
        val radial = 1.0 + lens.k1*r2 + lens.k2*r2*r2 +
            lens.k3*r2*r2*r2
        val xd = x*radial + 2.0*lens.p1*x*y + lens.p2*(r2+2.0*x2)
        val yd = y*radial + lens.p1*(r2+2.0*y2) + 2.0*lens.p2*x*y
        val px = lens.cxPx + lens.fxPx*xd
        val py = lens.cyPx + lens.fyPx*yd
        if (!px.isFinite() || !py.isFinite() ||
            px < 0.0 || px >= lens.width ||
            py < 0.0 || py >= lens.height) return null
        return CameraProjection.Pixel(px.toFloat(),py.toFloat())
    }
}
