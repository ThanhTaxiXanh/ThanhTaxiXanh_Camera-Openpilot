package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic H264 SPS RBSP fixtures with Exp-Golomb encoding.
 * No A500S physical stream was captured or decoded by these JVM tests.
 */
class H264SpsDimensionsTest {
    private class Bits {
        private val stream = ArrayList<Int>()
        fun bit(value: Int) { require(value in 0..1); stream.add(value) }
        fun bits(value: Int, length: Int) {
            for (i in length-1 downTo 0) bit((value ushr i) and 1)
        }
        fun ue(value: Int) {
            require(value in 0..10000)
            val codeNum = value + 1
            val count = 32 - Integer.numberOfLeadingZeros(codeNum)
            repeat(count - 1) { bit(0) }
            bits(codeNum, count)
        }
        fun finish(): ByteArray {
            // rbsp_stop_one_bit and rbsp_alignment_zero_bit
            bit(1)
            while (stream.size % 8 != 0) bit(0)
            return ByteArray(stream.size / 8) { offset ->
                var byte = 0
                repeat(8) { i -> byte = (byte shl 1) or stream[offset*8+i] }
                byte.toByte()
            }
        }
    }

    private fun sps(
        widthMbs: Int,
        heightMapUnits: Int,
        profile: Int = 66,
        chroma: Int = 1,
        frameOnly: Boolean = true,
        left: Int = 0,
        right: Int = 0,
        top: Int = 0,
        bottom: Int = 0
    ): ByteArray {
        val b = Bits()
        b.bits(profile, 8)
        b.bits(0, 8) // constraint flags
        b.bits(30, 8) // level_idc
        b.ue(0) // seq_parameter_set_id
        if (profile == 100) {
            b.ue(chroma)
            if (chroma == 3) b.bit(0) // separate_colour_plane_flag
            b.ue(0); b.ue(0) // 8-bit Y and UV
            b.bit(0) // qpprime_y_zero_transform_bypass_flag
            b.bit(0) // seq_scaling_matrix_present_flag
        }
        b.ue(0) // log2_max_frame_num_minus4
        b.ue(0) // pic_order_cnt_type
        b.ue(0) // log2_max_pic_order_cnt_lsb_minus4
        b.ue(1) // max_num_ref_frames
        b.bit(0) // gaps_in_frame_num_value_allowed_flag
        b.ue(widthMbs - 1)
        b.ue(heightMapUnits - 1)
        b.bit(if (frameOnly) 1 else 0)
        if (!frameOnly) b.bit(0) // mb_adaptive_frame_field_flag
        b.bit(1) // direct_8x8_inference_flag
        val cropped = left + right + top + bottom > 0
        b.bit(if (cropped) 1 else 0)
        if (cropped) {
            b.ue(left);b.ue(right);b.ue(top);b.ue(bottom)
        }
        b.bit(0) // vui_parameters_present_flag
        return byteArrayOf(0x67) + b.finish()
    }

    @Test fun a500s640x480BaselineSpsYieldsConfiguredPictureSize() {
        val bytes=sps(40,30)
        assertEquals(H264SpsDimensions.Size(640,480),
            H264SpsDimensions.parse(bytes))
        assertEquals(H264SpsDimensions.Size(640,480),
            H264SpsDimensions.configuredSize(bytes))
    }

    @Test fun highProfile1280x720IsNotAssumedToBe640() {
        val bytes=sps(80,45,profile=100)
        assertEquals(H264SpsDimensions.Size(1280,720),
            H264SpsDimensions.parse(bytes))
    }

    @Test fun highProfile1080CropRemovesEightBottomPixels() {
        // Coded map = 1920 x 1088; chroma 420 frame crop unit Y is 2.
        val bytes=sps(120,68,profile=100,bottom=4)
        assertEquals(H264SpsDimensions.Size(1920,1080),
            H264SpsDimensions.parse(bytes))
    }

    @Test fun highProfile422CropUsesOneLinePerUnit() {
        val bytes=sps(40,30,profile=100,chroma=2,bottom=8)
        assertEquals(H264SpsDimensions.Size(640,472),
            H264SpsDimensions.parse(bytes))
    }

    @Test fun interlacedMapUnitsScaleCodedHeight() {
        val bytes=sps(40,15,frameOnly=false)
        assertEquals(H264SpsDimensions.Size(640,480),
            H264SpsDimensions.parse(bytes))
    }

    @Test fun frameCroppingSupportsLeftAndRightEdges() {
        val bytes=sps(41,30,left=8,right=8) // 656-32
        assertEquals(H264SpsDimensions.Size(624,480),
            H264SpsDimensions.parse(bytes))
    }

    @Test fun invalidAndTruncatedSpsUsesExplicitKnownFallback() {
        val bad=listOf(
            byteArrayOf(),
            byteArrayOf(0x65,1,2,3,4), // IDR NAL, not SPS
            byteArrayOf(0x67,0x42),
            byteArrayOf(0xe7.toByte(),0x42,0,30,0), // forbidden_zero_bit
            byteArrayOf(0x67,0,0,0,0,0)
        )
        for (bytes in bad) {
            assertNull(H264SpsDimensions.parse(bytes))
            assertEquals(H264SpsDimensions.Size(640,480),
                H264SpsDimensions.configuredSize(bytes))
        }
        assertNull(H264SpsDimensions.parse(null))
    }

    @Test fun impossibleFrameCropIsNotForwardedToCodec() {
        val bytes=sps(40,30,right=400)
        assertNull(H264SpsDimensions.parse(bytes))
    }

    @Test fun unsupportedChromaModeFailsClosed() {
        // Change an otherwise valid SPS to a broken high profile followed
        // by all-zero Exp-Golomb bits (no terminating 1).
        val corrupt=byteArrayOf(0x67,100,0,30,0,0,0)
        assertNull(H264SpsDimensions.parse(corrupt))
    }

    @Test fun excessivelyLargeOrMaliciousParameterSetIsRejected() {
        assertNull(H264SpsDimensions.parse(ByteArray(4097) { 0x67 }))
        assertNull(H264SpsDimensions.parse(byteArrayOf(
            0x67,0x42,0,0,0,0,0,0,0,0)))
    }

    @Test fun baselineSpsStopBitAndEmulationGuardPreventTruncation() {
        val bytes=sps(40,30)
        assertEquals(640,H264SpsDimensions.parse(bytes)?.width)
        // A truncated suffix cannot be assumed to be a valid picture.
        assertNull(H264SpsDimensions.parse(bytes.copyOfRange(0,4)))
    }
}
