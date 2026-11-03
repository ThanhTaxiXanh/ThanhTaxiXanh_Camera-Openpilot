package vn.thanhtaxixanh.camera.ai

import kotlin.math.abs
import kotlin.math.max

/**
 * Enforces the "never invent lanes, distance or warnings" policy.
 *
 * The raw xADAS/Openpilot output parser yields 3D model coordinates.
 * Converting those into physical lanes and longitudinal distances requires
 * a validated extrinsic camera calibration AND a tested model-specific
 * coordinate convention. We intentionally do not infer calibration from
 * user-entered height/pitch or an unverified ONNX file.
 *
 * Everything here is platform independent and unit tested on GitHub.
 */
object VisionSafetyGate {
    const val MAX_FRAME_AGE_MS = 550L
    const val MIN_LANE_PROBABILITY = 0.75f
    const val MIN_LEAD_PROBABILITY = 0.80f

    data class Calibration(
        val cameraHeightMeters: Float,
        val horizontalFovDegrees: Float,
        val pitchDegrees: Float,
        val yawDegrees: Float,
        val datasetId: String,
        val groundTruthValidated: Boolean,
        val modelCoordinateFrameValidated: Boolean
    ) {
        val structurallyValid: Boolean get() =
            cameraHeightMeters.isFinite() && cameraHeightMeters in 0.4f..2.8f &&
            horizontalFovDegrees.isFinite() && horizontalFovDegrees in 30f..160f &&
            pitchDegrees.isFinite() && abs(pitchDegrees) <= 35f &&
            yawDegrees.isFinite() && abs(yawDegrees) <= 30f &&
            datasetId.isNotBlank()

        val ready: Boolean get() = structurallyValid &&
            groundTruthValidated && modelCoordinateFrameValidated
    }

    data class TimedResult(
        val capturedElapsedMs: Long,
        val inferredElapsedMs: Long,
        val laneConfidenceLeft: Float,
        val laneConfidenceRight: Float,
        val leadConfidence: Float,
        val leadDistanceMeters: Float?,
        val closingSpeedMetersPerSecond: Float?,
        val fromRealModel: Boolean
    )

    enum class DataStatus {
        NO_MODEL, UNCALIBRATED, INVALID, STALE, LOW_CONFIDENCE, VISIBLE
    }

    data class VisibleState(
        val status: DataStatus,
        val lanesVisible: Boolean,
        val leadVisible: Boolean,
        val leadDistanceMeters: Float?,
        val headwaySeconds: Float?,
        val timeToCollisionSeconds: Float?
    ) {
        val mayWarn: Boolean get() = status == DataStatus.VISIBLE && leadVisible
    }

    fun assess(
        input: TimedResult?,
        calibration: Calibration?,
        nowElapsedMs: Long,
        speedMetersPerSecond: Float?
    ): VisibleState {
        fun no(status: DataStatus) = VisibleState(
            status, false, false, null, null, null)
        if (input == null || !input.fromRealModel) return no(DataStatus.NO_MODEL)
        if (calibration?.ready != true) return no(DataStatus.UNCALIBRATED)
        val age = nowElapsedMs - input.capturedElapsedMs
        if (age !in 0..MAX_FRAME_AGE_MS ||
            input.inferredElapsedMs < input.capturedElapsedMs ||
            input.inferredElapsedMs > nowElapsedMs) return no(DataStatus.STALE)

        val confidence = listOf(
            input.laneConfidenceLeft, input.laneConfidenceRight, input.leadConfidence)
        if (confidence.any { !it.isFinite() || it !in 0f..1f })
            return no(DataStatus.INVALID)

        val lanes = input.laneConfidenceLeft >= MIN_LANE_PROBABILITY &&
            input.laneConfidenceRight >= MIN_LANE_PROBABILITY

        val distance = input.leadDistanceMeters
        val lead = input.leadConfidence >= MIN_LEAD_PROBABILITY &&
            distance != null && distance.isFinite() && distance in 1f..200f

        if (!lanes && !lead) return no(DataStatus.LOW_CONFIDENCE)

        val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f }
        val closing = input.closingSpeedMetersPerSecond?.takeIf {
            it.isFinite() && it > 0f
        }
        return VisibleState(
            DataStatus.VISIBLE, lanes, lead,
            if (lead) distance else null,
            if (lead && speed != null && speed > 0.1f)
                (distance!! / speed).coerceAtLeast(0f)
            else null,
            if (lead && closing != null)
                (distance!! / max(closing, 0.1f))
            else null
        )
    }
}
