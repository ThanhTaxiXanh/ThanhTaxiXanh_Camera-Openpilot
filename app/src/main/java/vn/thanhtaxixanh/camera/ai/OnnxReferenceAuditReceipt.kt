package vn.thanhtaxixanh.camera.ai

import java.security.MessageDigest

/**
 * Reproducible hashes for ONNX two-input / 1576-output parity inspection.
 * Exact big-endian IEEE754 raw Float32 bits match TTXREF01 representation.
 * A match CANNOT establish that the reference is independent or licensed.
 */
object OnnxReferenceAuditReceipt {
    private val alphabet = "0123456789abcdef"
    private fun hex(data: ByteArray): String = buildString(data.size * 2) {
        for (byte in data) {
            val n = byte.toInt() and 255
            append(alphabet[n ushr 4])
            append(alphabet[n and 15])
        }
    }

    fun hashFloat32Be(values: FloatArray): String {
        require(values.isNotEmpty() && values.all { it.isFinite() }) {
            "Empty or non-finite Float32 tensor"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bits = value.toRawBits()
            digest.update((bits ushr 24).toByte())
            digest.update((bits ushr 16).toByte())
            digest.update((bits ushr 8).toByte())
            digest.update(bits.toByte())
        }
        return hex(digest.digest())
    }

    data class Receipt(
        val modelSha256: String,
        val imageSha256: String,
        val bigImageSha256: String,
        val referenceOutputSha256: String,
        val actualOutputSha256: String,
        val comparison: ReferenceTensorFixture.Comparison
    ) {
        val verdict get() =
            if (comparison.pass) "MATCHED_UNVERIFIED" else "OUTPUT_MISMATCH"

        fun toText(): String = listOf(
            "TTXONNX-AUDIT-01",
            "verdict=" + verdict,
            "reference_independence=UNVERIFIED",
            "model_authorization=UNVERIFIED",
            "model_sha256=" + modelSha256,
            "img_float32_be_sha256=" + imageSha256,
            "big_img_float32_be_sha256=" + bigImageSha256,
            "reference_out_float32_be_sha256=" + referenceOutputSha256,
            "actual_out_float32_be_sha256=" + actualOutputSha256,
            "elements=" + SupercomboV027Contract.TENSOR_ELEMENTS,
            "mismatches=" + comparison.mismatches,
            "worst_index=" + comparison.worstIndex,
            "max_abs_error=" + comparison.maxAbsoluteError,
            "mean_abs_error=" + comparison.meanAbsoluteError,
            "scope=LOCAL_PARITY_ONLY_NOT_ADAS_ACCEPTANCE"
        ).joinToString("\n", postfix = "\n")
    }

    fun inspect(
        modelHash: ByteArray,
        vector: ReferenceTensorFixture.Vector,
        actual: FloatArray
    ): Receipt {
        require(modelHash.size == 32 && MessageDigest.isEqual(
            modelHash, vector.modelHash)) { "Model SHA256 mismatch" }
        VisionInputContract.requireRawStudioByteTensors(
            vector.img, vector.bigImg)
        val delta = ReferenceTensorFixture.compare(vector.expectedOutput, actual)
        return Receipt(
            hex(modelHash), hashFloat32Be(vector.img),
            hashFloat32Be(vector.bigImg),
            hashFloat32Be(vector.expectedOutput),
            hashFloat32Be(actual), delta
        )
    }
}
