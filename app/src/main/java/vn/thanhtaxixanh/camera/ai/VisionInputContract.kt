package vn.thanhtaxixanh.camera.ai

/**
 * Preflight contract for the CURRENT experimental studio-range packer.
 *
 * A compatible graph shape alone does not demonstrate that its actual model
 * was trained with this YUV pixel order, camera warp or temporal order.
 * This validator only guards against accidental [0,1] normalized, NaN,
 * negative, overflow or incorrectly shaped float32 tensors.
 */
object VisionInputContract {
    const val MIN_RAW = 0.0f
    const val MAX_RAW = 255.0f

    fun requireRawStudioByteTensors(img: FloatArray, bigImg: FloatArray) {
        verify(img, "img")
        verify(bigImg, "big_img")
    }

    fun verify(tensor: FloatArray, name: String = "tensor") {
        require(tensor.size == SupercomboV027Contract.INPUT_ELEMENTS) {
            "$name: expected ${SupercomboV027Contract.INPUT_ELEMENTS} values; " +
                "got ${tensor.size}"
        }
        for (i in tensor.indices) {
            val value = tensor[i]
            require(value.isFinite() && value in MIN_RAW..MAX_RAW) {
                "$name: invalid studio-range raw sample at index $i"
            }
        }
    }
}
