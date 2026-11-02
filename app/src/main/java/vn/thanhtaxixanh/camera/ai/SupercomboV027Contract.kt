package vn.thanhtaxixanh.camera.ai

/**
 * Verified output concatenation order for the xADAS 0.2.7 vision graph.
 *
 * Evidence: ONNX final Concat -> vision_outputs -> vout_pass and matching
 * NCNN last Concat. The names identify head PURPOSE, NOT the meaning/units
 * of individual elements. This file contains no xADAS model weights or code.
 */
object SupercomboV027Contract {
    const val TENSOR_ELEMENTS = 1576
    const val CHANNELS = 12
    const val HEIGHT = 128
    const val WIDTH = 256
    const val INPUT_ELEMENTS = CHANNELS * HEIGHT * WIDTH

    data class Head(val name: String, val offset: Int, val length: Int) {
        val endExclusive: Int get() = offset + length
    }

    val heads = listOf(
        Head("meta", 0, 55),
        Head("desire_pred", 55, 32),
        Head("pose", 87, 12),
        Head("wide_from_device_euler", 99, 6),
        Head("road_transform", 105, 12),
        Head("lane_lines", 117, 528),
        Head("lane_lines_prob", 645, 8),
        Head("road_edges", 653, 264),
        Head("lead", 917, 144),
        Head("lead_prob", 1061, 3),
        Head("summarizer_features", 1064, 512)
    )
    private val byName: Map<String, Head> = heads.associateBy { it.name }

    init {
        var offset = 0
        for (head in heads) {
            require(head.offset == offset) { "Overlapping or missing head: " + head.name }
            offset = head.endExclusive
        }
        require(offset == TENSOR_ELEMENTS) { "Expected 1576 outputs; got " + offset }
    }

    fun head(name: String): Head = byName[name]
        ?: throw IllegalArgumentException("Unknown output head: " + name)

    fun slice(values: FloatArray, name: String): FloatArray {
        require(values.size == TENSOR_ELEMENTS) { "Invalid output size: " + values.size }
        val head = head(name)
        return values.copyOfRange(head.offset, head.endExclusive)
    }

    /** Summary for debugging only. Never assign metric units to raw values. */
    fun inspect(values: FloatArray): ModelOutputInspection {
        if (values.size != TENSOR_ELEMENTS) return ModelOutputInspection(
            false, "Expected 1576 outputs, received " + values.size, emptyList()
        )
        val summary = heads.map { head ->
            var finite = 0
            var min = Float.POSITIVE_INFINITY
            var max = Float.NEGATIVE_INFINITY
            for (index in head.offset until head.endExclusive) {
                val value = values[index]
                if (value.isFinite()) {
                    finite++
                    if (value < min) min = value
                    if (value > max) max = value
                }
            }
            ModelHeadInspection(head.name, head.length, finite,
                if (finite > 0) min else null,
                if (finite > 0) max else null)
        }
        return ModelOutputInspection(
            summary.all { it.finiteValues == it.totalValues },
            "Raw tensor inspection only; physical coordinates and lead distance NOT decoded",
            summary
        )
    }
}

data class ModelHeadInspection(
    val name: String,
    val totalValues: Int,
    val finiteValues: Int,
    val minRaw: Float?,
    val maxRaw: Float?
)

data class ModelOutputInspection(
    val allFinite: Boolean,
    val note: String,
    val heads: List<ModelHeadInspection>
)

/**
 * Port for an independently licensed model backend.
 * PREPROCESS IS UNSPECIFIED: callers MUST provide validated 12-channel tensors
 * following the actual model's expected YUV packing/warp/temporal ordering.
 * No fake RGB->12-channel conversion is provided.
 */
interface SupercomboInferencePort {
    fun run(img: FloatArray, bigImg: FloatArray): FloatArray
}

object SupercomboInferenceValidator {
    fun runChecked(
        port: SupercomboInferencePort,
        img: FloatArray,
        bigImg: FloatArray
    ): Pair<FloatArray, ModelOutputInspection> {
        require(img.size == SupercomboV027Contract.INPUT_ELEMENTS)
        require(bigImg.size == SupercomboV027Contract.INPUT_ELEMENTS)
        require(img.all { it.isFinite() } && bigImg.all { it.isFinite() })
        val output = port.run(img, bigImg)
        val inspected = SupercomboV027Contract.inspect(output)
        require(inspected.allFinite) { inspected.note }
        return Pair(output, inspected)
    }
}
