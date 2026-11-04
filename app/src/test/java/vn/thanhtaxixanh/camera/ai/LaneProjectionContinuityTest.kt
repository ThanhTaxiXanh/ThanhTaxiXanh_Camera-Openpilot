package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * All fixtures are synthetic geometry; these tests never assert xADAS parity
 * or permit production HUD rendering without independently verified inputs.
 */
class LaneProjectionContinuityTest {
    private val syntheticCalibration = VisionSafetyGate.Calibration(
        1.5f,90f,0f,0f,"synthetic-only",
        groundTruthValidated=true,
        modelCoordinateFrameValidated=true
    )

    private fun tensor(): FloatArray = FloatArray(
        SupercomboV027Contract.TENSOR_ELEMENTS).apply {
        // First lane only, with a near-certain *synthetic* existence logit.
        this[SupercomboV027Contract.head("lane_lines_prob").offset+1] = 12f
    }

    @Test fun offscreenPointsProduceSeparateContinuousSegmentsNotFalseBridge() {
        val tensor=tensor()
        val offset=SupercomboV027Contract.head("lane_lines").offset
        tensor[offset+12*2] = 100_000f
        tensor[offset+20*2] = 100_000f
        val result = LaneProjection.lanes(
            OpenpilotVisionDecoder.decode(tensor),
            syntheticCalibration, 640, 480)
        assertEquals(3,result.size)
        assertTrue(result.all { it.index == 0 })
        assertEquals(listOf(8,7,12),result.map { it.pixels.size })
        assertTrue(result.all { line ->
            line.pixels.all { point ->
                point.x.isFinite() && point.y.isFinite() &&
                    point.x in 0f..640f && point.y in 0f..480f
            }
        })
    }

    @Test fun tooShortFragmentsAreNeverExtrapolatedIntoAVisibleLane() {
        val tensor=tensor()
        val offset=SupercomboV027Contract.head("lane_lines").offset
        for (p in 4..32 step 4) tensor[offset+p*2] = 100_000f
        val result = LaneProjection.lanes(
            OpenpilotVisionDecoder.decode(tensor),
            syntheticCalibration, 640, 480)
        assertTrue(result.isEmpty())
    }

    @Test fun noUserFlagsCanOverrideUnverifiedModelCoordinates() {
        val tensor=tensor()
        val result=LaneProjection.lanes(
            OpenpilotVisionDecoder.decode(tensor),
            syntheticCalibration.copy(modelCoordinateFrameValidated=false),
            640,480)
        assertTrue(result.isEmpty())
    }
}
