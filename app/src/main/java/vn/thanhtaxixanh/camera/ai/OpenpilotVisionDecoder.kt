package vn.thanhtaxixanh.camera.ai

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Inspectable parser for the vision heads of the 1,576-output research model.
 *
 * Shapes/operations are cross-checked against the public comma.ai Openpilot
 * modeld Parser and the recovered ONNX head order for xADAS 0.2.7.
 * No camera calibration, safety alerts or pixel overlay are performed here.
 *
 * Attribution: independent Kotlin implementation informed by MIT-licensed
 * https://github.com/commaai/openpilot/blob/master/openpilot/selfdrive/modeld/parse_model_outputs.py
 */
object OpenpilotVisionDecoder {
    const val POINTS = 33
    const val LANE_COUNT = 4
    const val ROAD_EDGE_COUNT = 2
    const val LEAD_HORIZONS = 3
    const val LEAD_STEPS = 6

    /** The sample grid of the Openpilot 33-point lane representation. */
    fun forwardMeters(point: Int): Float {
        require(point in 0 until POINTS)
        val k = point / 32f
        return 192f * k * k
    }

    fun sigmoid(logit: Float): Float {
        require(logit.isFinite())
        return if (logit >= 0) {
            (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
        } else {
            val t = exp(logit.toDouble())
            (t / (1.0 + t)).toFloat()
        }
    }

    private fun safeStd(log: Float): Float {
        require(log.isFinite())
        return exp(min(11f, max(-40f, log)).toDouble()).toFloat()
    }

    data class LaneSample(
        val distanceFromCameraM: Float,
        val lateralRaw: Float,
        val verticalRaw: Float,
        val lateralStdRaw: Float,
        val verticalStdRaw: Float
    )

    data class Lane(
        val index: Int,
        val existProb: Float,
        val deprecatedProb: Float,
        val points: List<LaneSample>
    )

    data class RoadEdge(
        val index: Int,
        val points: List<LaneSample>
    )

    data class LeadPoint(
        val longitudinalRaw: Float,
        val lateralRaw: Float,
        val velocityRaw: Float,
        val accelerationRaw: Float,
        val xStdRaw: Float,
        val yStdRaw: Float,
        val vStdRaw: Float,
        val aStdRaw: Float
    )

    data class LeadHorizon(
        val index: Int,
        val leadProb: Float,
        val trajectories: List<LeadPoint>
    )

    data class Snapshot(
        val lanes: List<Lane>,
        val roadEdges: List<RoadEdge>,
        val leads: List<LeadHorizon>,
        val note: String = "Raw Openpilot-style estimates; camera projection not calibrated"
    ) {
        /** The two central lane estimates; not a guarantee of ego-lane membership. */
        val centerLines: List<Lane> get() = lanes.subList(1, 3)
    }

    fun decode(output: FloatArray): Snapshot {
        require(output.size == SupercomboV027Contract.TENSOR_ELEMENTS) {
            "Expected 1,576 model outputs"
        }
        require(output.all { it.isFinite() }) { "Non-finite model output; discard entire frame" }

        val laneOffset = SupercomboV027Contract.head("lane_lines").offset
        val laneProbOffset = SupercomboV027Contract.head("lane_lines_prob").offset
        val roadOffset = SupercomboV027Contract.head("road_edges").offset
        val leadOffset = SupercomboV027Contract.head("lead").offset
        val leadProbOffset = SupercomboV027Contract.head("lead_prob").offset

        // 4 lanes * 33 points * (y,z) = 264 means, followed by 264 log-std.
        val laneItems = LANE_COUNT * POINTS * 2
        val lanes = (0 until LANE_COUNT).map { lane ->
            val points = (0 until POINTS).map { p ->
                val position = lane * POINTS * 2 + p * 2
                LaneSample(
                    distanceFromCameraM = forwardMeters(p),
                    lateralRaw = output[laneOffset + position],
                    verticalRaw = output[laneOffset + position + 1],
                    lateralStdRaw = safeStd(output[laneOffset + laneItems + position]),
                    verticalStdRaw = safeStd(output[laneOffset + laneItems + position + 1])
                )
            }
            // 8 values: two logits per lane; legacy 0 and primary 1.
            Lane(lane, sigmoid(output[laneProbOffset + lane * 2 + 1]),
                sigmoid(output[laneProbOffset + lane * 2]), points)
        }

        // 2 edges * 33 * (y,z) = 132 means, followed by 132 log-std.
        val edgeItems = ROAD_EDGE_COUNT * POINTS * 2
        val edges = (0 until ROAD_EDGE_COUNT).map { edge ->
            val points = (0 until POINTS).map { p ->
                val position = edge * POINTS * 2 + p * 2
                LaneSample(
                    distanceFromCameraM = forwardMeters(p),
                    lateralRaw = output[roadOffset + position],
                    verticalRaw = output[roadOffset + position + 1],
                    lateralStdRaw = safeStd(output[roadOffset + edgeItems + position]),
                    verticalStdRaw = safeStd(output[roadOffset + edgeItems + position + 1])
                )
            }
            RoadEdge(edge, points)
        }

        // In the 144-value non-MHP configuration:
        // 3 horizons * 6 samples * 4 attributes = 72 means + 72 log std.
        val leadItems = LEAD_HORIZONS * LEAD_STEPS * 4
        val leads = (0 until LEAD_HORIZONS).map { horizon ->
            val traj = (0 until LEAD_STEPS).map { step ->
                val position = horizon * LEAD_STEPS * 4 + step * 4
                LeadPoint(
                    longitudinalRaw = output[leadOffset + position],
                    lateralRaw = output[leadOffset + position + 1],
                    velocityRaw = output[leadOffset + position + 2],
                    accelerationRaw = output[leadOffset + position + 3],
                    xStdRaw = safeStd(output[leadOffset + leadItems + position]),
                    yStdRaw = safeStd(output[leadOffset + leadItems + position + 1]),
                    vStdRaw = safeStd(output[leadOffset + leadItems + position + 2]),
                    aStdRaw = safeStd(output[leadOffset + leadItems + position + 3])
                )
            }
            LeadHorizon(horizon, sigmoid(output[leadProbOffset + horizon]), traj)
        }

        return Snapshot(lanes, edges, leads)
    }
}
