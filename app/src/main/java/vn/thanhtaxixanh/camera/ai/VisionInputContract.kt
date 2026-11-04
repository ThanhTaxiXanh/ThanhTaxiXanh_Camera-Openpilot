package vn.thanhtaxixanh.camera.ai

/**
 * Preflight contract for the CURRENT experimental studio-range packer.
 *
 * A compatible graph shape alone does not demonstrate that its actual model
 * was trained with this YUV pixel order, camera warp or temporal order.
 * This validator detects accidentally [0,1] normalized, NaN, negative,
 * overflow and incorrectly shaped tensors under that CURRENT packer contract.
 */
object VisionInputContract {
    const val MIN_RAW = 16.0f
    const val MAX_LUMA = 235.0f
    const val MAX_CHROMA = 240.0f
    private const val CHANNEL_ELEMENTS =
        SupercomboV027Contract.HEIGHT * SupercomboV027Contract.WIDTH

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
            val channel = (i / CHANNEL_ELEMENTS) % 6
            val max = if (channel < 4) MAX_LUMA else MAX_CHROMA
            require(value.isFinite() && value in MIN_RAW..max) {
                "$name: invalid studio-range channel $channel at index $i"
            }
        }
    }
}
