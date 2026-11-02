package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class OpenpilotYuvPackerTest {
    @Test fun makesTwoFrameTwelveChannelTensor() {
        val black = IntArray(512*256) { 0xff000000.toInt() }
        val white = IntArray(512*256) { 0xffffffff.toInt() }
        val packed = OpenpilotYuvPacker.packPair(black, white)
        assertEquals(393216, packed.size)
        assertEquals(16f, packed[0], 2f)
        assertEquals(235f, packed[6*128*256], 2f)
        assertTrue(packed.all { it.isFinite() && it >= 0f && it <= 255f })
    }

    @Test fun rejectsUnwarpedFrameSizes() {
        try {
            OpenpilotYuvPacker.packPair(IntArray(640*360), IntArray(512*256))
            fail("Should not silently use an arbitrary aspect ratio")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun neutralChromaOnGrayscale() {
        val gray = IntArray(512*256) { 0xff808080.toInt() }
        val packed = OpenpilotYuvPacker.packPair(gray, gray)
        val plane = 128*256
        assertEquals(128f, packed[4*plane], 2f)
        assertEquals(128f, packed[5*plane], 2f)
    }
}
