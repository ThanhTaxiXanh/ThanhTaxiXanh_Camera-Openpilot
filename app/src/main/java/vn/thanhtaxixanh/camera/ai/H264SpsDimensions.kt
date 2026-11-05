package vn.thanhtaxixanh.camera.ai

/**
 * Bounded, pure-JVM H.264 SPS picture dimensions for configuring MediaCodec.
 *
 * Reads the SPS NAL supplied by RTSP SDP sprop-parameter-sets; unlike
 * assumed output-format callbacks, this is available BEFORE configure().
 * Implements baseline/main/high SPS including chroma-dependent frame crop.
 * A malformed/unexpected SPS returns null; it never supplies guessed dimensions.
 *
 * This is only H.264 metadata, not a source/camera identity or physical
 * capture-to-display latency measurement.
 */
object H264SpsDimensions {
    data class Size(val width: Int, val height: Int)

    private val highProfiles = setOf(
        44, 83, 86, 100, 110, 118, 122, 128, 134, 135, 138, 139, 244
    )
    private class Reader(private val data: ByteArray) {
        private var bitPosition = 0

        fun bit(): Int {
            require(bitPosition < data.size * 8) { "Truncated H264 SPS" }
            val byte = data[bitPosition / 8].toInt() and 255
            val result = (byte ushr (7 - (bitPosition % 8))) and 1
            bitPosition++
            return result
        }
        fun bits(count: Int): Int {
            require(count in 0..24)
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                require(zeros <= 23) { "Oversize SPS Exp-Golomb" }
            }
            return ((1 shl zeros) - 1) + bits(zeros)
        }
        fun se(): Int {
            val value = ue()
            return if ((value and 1) != 0) (value + 1) / 2 else -(value / 2)
        }

        fun scalingList(size: Int) {
            var lastScale = 8
            var nextScale = 8
            repeat(size) {
                if (nextScale != 0) {
                    val delta = se()
                    nextScale = ((lastScale.toLong() + delta + 256L) % 256L)
                        .toInt()
                }
                if (nextScale != 0) lastScale = nextScale
            }
        }
    }

    /** Input is one raw SPS NAL (type 7, no Annex-B start code). */
    fun parse(nal: ByteArray?): Size? {
        if (nal == null || nal.size !in 5..4096 ||
            (nal[0].toInt() and 0x9f) != 0x07) return null
        return try {
            val rbsp = ArrayList<Byte>(nal.size)
            var zeros = 0
            for (i in 1 until nal.size) {
                val b = nal[i].toInt() and 255
                if (zeros >= 2 && b == 0x03) {
                    require(i + 1 < nal.size) { "Truncated emulation prevention" }
                    // The 0x03 is an emulation-prevention byte, not SPS data.
                    zeros = 0
                    continue
                }
                rbsp.add(nal[i])
                zeros = if (b == 0) zeros + 1 else 0
            }
            val reader = Reader(rbsp.toByteArray())
            val profile = reader.bits(8)
            reader.bits(8) // constraint_set flags and reserved bits
            reader.bits(8) // level_idc
            require(reader.ue() <= 31) { "Invalid seq_parameter_set_id" }

            var chroma = 1
            var separateColourPlane = false
            if (profile in highProfiles) {
                chroma = reader.ue()
                require(chroma in 0..3) { "Invalid chroma_format_idc" }
                if (chroma == 3) separateColourPlane = reader.bit() != 0
                require(reader.ue() <= 6 && reader.ue() <= 6) {
                    "Unsupported SPS bit depth"
                }
                reader.bit() // qpprime_y_zero_transform_bypass_flag
                if (reader.bit() != 0) {
                    val count = if (chroma == 3) 12 else 8
                    repeat(count) { i ->
                        if (reader.bit() != 0)
                            reader.scalingList(if (i < 6) 16 else 64)
                    }
                }
            }

            require(reader.ue() <= 12) { "Invalid frame_num length" }
            when (reader.ue()) {
                0 -> require(reader.ue() <= 12) { "Invalid POC length" }
                1 -> {
                    reader.bit() // delta_pic_order_always_zero_flag
                    reader.se() // offset_for_non_ref_pic
                    reader.se() // offset_for_top_to_bottom_field
                    val count = reader.ue()
                    require(count <= 255) { "Too many POC cycle offsets" }
                    repeat(count) { reader.se() }
                }
                2 -> Unit
                else -> return null
            }
            require(reader.ue() <= 16) { "Unreasonable reference count" }
            reader.bit() // gaps_in_frame_num_value_allowed_flag
            val widthInMbs = reader.ue() + 1
            val heightInMapUnits = reader.ue() + 1
            require(widthInMbs in 1..512 && heightInMapUnits in 1..512) {
                "Unreasonable SPS dimensions"
            }
            val frameMbsOnly = reader.bit()
            if (frameMbsOnly == 0) reader.bit() // mb_adaptive_frame_field_flag
            reader.bit() // direct_8x8_inference_flag
            var left = 0
            var right = 0
            var top = 0
            var bottom = 0
            if (reader.bit() != 0) {
                left = reader.ue()
                right = reader.ue()
                top = reader.ue()
                bottom = reader.ue()
            }

            val chromaArrayType = if (separateColourPlane) 0 else chroma
            val cropUnitX = when (chromaArrayType) {
                1, 2 -> 2
                else -> 1
            }
            val cropUnitY = (if (chromaArrayType == 1) 2 else 1) *
                (2 - frameMbsOnly)
            val width = widthInMbs * 16 - (left + right) * cropUnitX
            val height = heightInMapUnits * 16 * (2 - frameMbsOnly) -
                (top + bottom) * cropUnitY
            require(width in 16..8192 && height in 16..8192) {
                "SPS crop invalidates dimensions"
            }
            Size(width, height)
        } catch (_: Exception) { null }
    }

    /** Validated A500S stream is normally 640x480; this is only a fallback. */
    fun configuredSize(sps: ByteArray?): Size =
        parse(sps) ?: Size(640, 480)
}
