package vn.thanhtaxixanh.camera.ai

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pinhole projection independent of Android APIs.
 *
 * World/car axes: +forward in meters, +right in meters, +up in meters.
 * Camera: yaw positive to the right; pitch positive downward.
 * Pixel origin is upper-left. This is a geometrical reference only:
 * the sign/convention of a specific model's raw (y,z) must be verified.
 */
object CameraProjection {
    data class Point3(val forwardM: Double, val rightM: Double, val upM: Double)
    data class Pixel(val x: Float, val y: Float)

    fun project(
        position: Point3,
        calibration: VisionSafetyGate.Calibration,
        imageWidth: Int,
        imageHeight: Int,
        requireVerifiedCalibration: Boolean = true
    ): Pixel? {
        if (imageWidth < 2 || imageHeight < 2) return null
        if (!calibration.structurallyValid) return null
        if (requireVerifiedCalibration && !calibration.ready) return null
        if (!position.forwardM.isFinite() || !position.rightM.isFinite() ||
            !position.upM.isFinite()) return null
        val pitch = Math.toRadians(calibration.pitchDegrees.toDouble())
        val yaw = Math.toRadians(calibration.yawDegrees.toDouble())
        val x = position.forwardM
        val y = position.rightM
        val up = position.upM
        val cameraDown = calibration.cameraHeightMeters.toDouble() - up

        val forwardYaw = cos(yaw) * x + sin(yaw) * y
        val lateralYaw = -sin(yaw) * x + cos(yaw) * y
        val forwardCamera = cos(pitch) * forwardYaw + sin(pitch) * cameraDown
        val downCamera = -sin(pitch) * forwardYaw + cos(pitch) * cameraDown
        if (forwardCamera <= 0.01 || !forwardCamera.isFinite()) return null

        val fx = imageWidth.toDouble() / 2.0 /
            tan(Math.toRadians(calibration.horizontalFovDegrees.toDouble()) / 2.0)
        val px = imageWidth / 2.0 + fx * lateralYaw / forwardCamera
        val py = imageHeight / 2.0 + fx * downCamera / forwardCamera

        if (!px.isFinite() || !py.isFinite()) return null
        if (px !in 0.0..imageWidth.toDouble() ||
            py !in 0.0..imageHeight.toDouble()) return null
        return Pixel(px.toFloat(), py.toFloat())
    }
}

/**
 * Converts Openpilot xADAS-style lane points into a projected overlay only
 * after both model coordinate semantics and real-world calibration pass.
 * It never invents a lane and never estimates a lead distance from pixels.
 */
object LaneProjection {
    data class ProjectedLane(
        val index: Int,
        val confidence: Float,
        val pixels: List<CameraProjection.Pixel>
    )

    fun lanes(
        model: OpenpilotVisionDecoder.Snapshot,
        calibration: VisionSafetyGate.Calibration?,
        imageWidth: Int,
        imageHeight: Int
    ): List<ProjectedLane> {
        if (calibration?.ready != true) return emptyList()
        return model.lanes.filter {
            it.existProb >= VisionSafetyGate.MIN_LANE_PROBABILITY
        }.mapNotNull { lane ->
            val points = lane.points.mapNotNull { pt ->
                CameraProjection.project(
                    CameraProjection.Point3(
                        forwardM = pt.distanceFromCameraM.toDouble(),
                        rightM = pt.lateralRaw.toDouble(),
                        upM = pt.verticalRaw.toDouble()
                    ),
                    calibration, imageWidth, imageHeight
                )
            }
            if (points.size < 4) null
            else ProjectedLane(lane.index, lane.existProb, points)
        }
    }
}
