package vn.thanhtaxixanh.camera.ai

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * User-supplied, independently authorized input/output reference vector.
 *
 * Binary V1 (big endian, no arbitrary length fields):
 * 8-byte ASCII "TTXREF01", 32-byte SHA-256 of the exact ONNX model,
 * img float32[393216], big_img float32[393216], vout_pass float32[1576].
 *
 * A PASS compares one explicitly provided reference only. It does NOT
 * establish that the reference is correct, licensed or physically calibrated.
 * No camera images, weights, timestamps or personal data are bundled here.
 */
object ReferenceTensorFixture {
    private val MAGIC = "TTXREF01".toByteArray(Charsets.US_ASCII)
    const val MODEL_HASH_BYTES = 32
    const val EXPECTED_BYTES =
        8L + MODEL_HASH_BYTES +
            (SupercomboV027Contract.INPUT_ELEMENTS.toLong() * 2L +
                SupercomboV027Contract.TENSOR_ELEMENTS.toLong()) * 4L

    data class Vector(
        val modelHash: ByteArray,
        val img: FloatArray,
        val bigImg: FloatArray,
        val expectedOutput: FloatArray
    )

    data class Comparison(
        val pass: Boolean,
        val mismatches: Int,
        val worstIndex: Int,
        val maxAbsoluteError: Double,
        val meanAbsoluteError: Double
    )

    /**
     * No trust in caller-supplied sizes: only a fixed 3,152,072-byte format.
     * Reject appended bytes, wrong float counts, NaN and incorrect ranges.
     */
    fun read(stream: InputStream): Vector {
        val source = DataInputStream(BufferedInputStream(stream, 65536))
        val header = ByteArray(MAGIC.size)
        source.readFully(header)
        require(header.contentEquals(MAGIC)) { "Invalid TTXREF01 header" }
        val hash = ByteArray(MODEL_HASH_BYTES)
        source.readFully(hash)
        fun floats(count: Int): FloatArray =
            FloatArray(count) { source.readFloat() }
        val img = floats(SupercomboV027Contract.INPUT_ELEMENTS)
        val big = floats(SupercomboV027Contract.INPUT_ELEMENTS)
        VisionInputContract.requireRawStudioByteTensors(img, big)
        val expected = floats(SupercomboV027Contract.TENSOR_ELEMENTS)
        require(expected.all { it.isFinite() }) { "Reference contains NaN/Inf" }
        require(source.read() == -1) { "Reference has unexpected trailing bytes" }
        return Vector(hash, img, big, expected)
    }

    /** Tolerance is for numerical runtime differences, not model accuracy. */
    fun compare(
        expected: FloatArray,
        actual: FloatArray,
        absTolerance: Double = 0.001,
        relativeTolerance: Double = 0.001
    ): Comparison {
        require(absTolerance.isFinite() && relativeTolerance.isFinite() &&
            absTolerance >= 0.0 && relativeTolerance >= 0.0)
        require(expected.size == SupercomboV027Contract.TENSOR_ELEMENTS &&
            actual.size == SupercomboV027Contract.TENSOR_ELEMENTS)
        require(expected.all { it.isFinite() } && actual.all { it.isFinite() }) {
            "Reference/ONNX output is not finite"
        }
        var errors = 0
        var peakIndex = -1
        var peak = 0.0
        var total = 0.0
        for (i in expected.indices) {
            val delta = abs(expected[i].toDouble() - actual[i].toDouble())
            total += delta
            if (delta > peak) {
                peak = delta
                peakIndex = i
            }
            if (delta > absTolerance +
                relativeTolerance * max(0.0, abs(expected[i].toDouble())))
                errors++
        }
        return Comparison(
            errors == 0, errors, peakIndex, peak, total / expected.size)
    }
}
