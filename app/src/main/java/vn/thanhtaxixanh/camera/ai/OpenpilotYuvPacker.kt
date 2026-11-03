package vn.thanhtaxixanh.camera.ai

/**
 * Research-only YUV420 2x2 pixel-unshuffle reference implementation.
 *
 * SOURCE FRAME: ARGB8888 already cropped / perspective-warped to 512 x 256.
 * OUTPUT: 6 planar float32 channels of shape 128 x 256 per frame.
 *
 * ONNX xADAS v0.2.7 includes a mean=127.5 and std=63.75 normalization.
 * Therefore this function leaves samples on a raw 0..255 scale.
 *
 * IMPORTANT: This is NOT yet established as bit-exact parity with xADAS.
 * Pixel warp matrix, UV coefficients and temporal order must be checked.
 * In particular, do not feed resized/unwarped images and label the result ADAS.
 */
object OpenpilotYuvPacker {
    const val SOURCE_WIDTH = 512
    const val SOURCE_HEIGHT = 256
    const val TARGET_WIDTH = 256
    const val TARGET_HEIGHT = 128
    private const val PLANE_SIZE = TARGET_WIDTH * TARGET_HEIGHT
    private const val FRAME_CHANNELS = 6

    fun packPair(previous: IntArray, current: IntArray): FloatArray {
        val output = FloatArray(SupercomboV027Contract.INPUT_ELEMENTS)
        // Chronological ordering is a hypothesis pending direct parity capture.
        packFrame(previous, output, 0)
        packFrame(current, output, PLANE_SIZE * FRAME_CHANNELS)
        return output
    }

    /** Each frame supplies 4 luma subpixels (Y00,Y01,Y10,Y11) plus U and V. */
    fun packFrame(pixels: IntArray, dest: FloatArray, offset: Int = 0) {
        require(pixels.size == SOURCE_WIDTH * SOURCE_HEIGHT) {
            "Expected 512x256 ARGB frame; got " + pixels.size + " pixels"
        }
        require(offset >= 0 && dest.size - offset >= PLANE_SIZE * FRAME_CHANNELS)
        val w = SOURCE_WIDTH
        for (row in 0 until TARGET_HEIGHT) {
            for (col in 0 until TARGET_WIDTH) {
                val upper = (row * 2) * w + col * 2
                val lower = upper + w
                val a = pixels[upper]
                val b = pixels[upper + 1]
                val c = pixels[lower]
                val d = pixels[lower + 1]
                val destIndex = row * TARGET_WIDTH + col
                dest[offset + destIndex] = y(a)
                dest[offset + PLANE_SIZE + destIndex] = y(b)
                dest[offset + 2 * PLANE_SIZE + destIndex] = y(c)
                dest[offset + 3 * PLANE_SIZE + destIndex] = y(d)
                dest[offset + 4 * PLANE_SIZE + destIndex] =
                    (u(a) + u(b) + u(c) + u(d)) / 4f
                dest[offset + 5 * PLANE_SIZE + destIndex] =
                    (v(a) + v(b) + v(c) + v(d)) / 4f
            }
        }
    }

    private fun red(c: Int) = (c ushr 16) and 255
    private fun green(c: Int) = (c ushr 8) and 255
    private fun blue(c: Int) = c and 255

    // BT.601 studio-range integer approximation: unverified vs native xADAS.
    private fun y(c: Int): Float = (16 +
        ((66 * red(c) + 129 * green(c) + 25 * blue(c) + 128) shr 8))
        .coerceIn(0, 255).toFloat()
    private fun u(c: Int): Float = (128 +
        ((-38 * red(c) - 74 * green(c) + 112 * blue(c) + 128) shr 8))
        .coerceIn(0, 255).toFloat()
    private fun v(c: Int): Float = (128 +
        ((112 * red(c) - 94 * green(c) - 18 * blue(c) + 128) shr 8))
        .coerceIn(0, 255).toFloat()
}
