package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class OpenpilotVisionDecoderTest {
    @Test fun zeroTensorHasExpectedShapesAndProbabilities() {
        val result = OpenpilotVisionDecoder.decode(FloatArray(1576))
        assertEquals(4, result.lanes.size)
        assertEquals(2, result.roadEdges.size)
        assertEquals(3, result.leads.size)
        assertEquals(33, result.lanes[1].points.size)
        assertEquals(6, result.leads[0].trajectories.size)
        assertEquals(.5f, result.lanes[1].existProb, .0001f)
        assertEquals(.5f, result.leads[0].leadProb, .0001f)
        assertEquals(1f, result.lanes[1].points.first().lateralStdRaw, .0001f)
    }

    @Test fun headMeanAndStdUseSeparateRanges() {
        val output = FloatArray(1576)
        output[117 + 2*33] = 2.75f
        output[117 + 264 + 2*33] = kotlin.math.ln(2.0).toFloat()
        output[645 + 2*1 + 1] = 3.0f
        output[653 + 132 + 2] = kotlin.math.ln(4.0).toFloat()
        output[917 + 1] = -.6f
        output[917 + 72 + 1] = kotlin.math.ln(3.0).toFloat()
        output[1061] = 2.0f
        val model = OpenpilotVisionDecoder.decode(output)
        assertEquals(2.75f, model.lanes[1].points[0].lateralRaw, 0f)
        assertEquals(2f, model.lanes[1].points[0].lateralStdRaw, .0001f)
        assertTrue(model.lanes[1].existProb > .9f)
        assertEquals(4f, model.roadEdges[0].points[1].lateralStdRaw, .0001f)
        assertEquals(-.6f, model.leads[0].trajectories[0].lateralRaw, 0f)
        assertEquals(3f, model.leads[0].trajectories[0].yStdRaw, .0001f)
        assertTrue(model.leads[0].leadProb > .8f)
    }

    @Test fun modelDistanceGridMatchesQuadraticOpenpilot() {
        assertEquals(0f, OpenpilotVisionDecoder.forwardMeters(0), 0f)
        assertEquals(48f, OpenpilotVisionDecoder.forwardMeters(16), .0001f)
        assertEquals(192f, OpenpilotVisionDecoder.forwardMeters(32), .0001f)
    }

    @Test fun invalidValuesNeverEnterHud() {
        val output = FloatArray(1576)
        output[117] = Float.NaN
        try {
            OpenpilotVisionDecoder.decode(output)
            fail("NaN must fail closed")
        } catch (_: IllegalArgumentException) {}
        assertTrue(OpenpilotVisionDecoder.sigmoid(50f) > .9999f)
        assertTrue(OpenpilotVisionDecoder.sigmoid(-50f) < .0001f)
    }
}
