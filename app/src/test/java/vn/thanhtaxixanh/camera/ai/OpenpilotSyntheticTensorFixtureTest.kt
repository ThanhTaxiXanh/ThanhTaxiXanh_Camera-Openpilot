package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic golden vector for the CURRENT independent reference packer only.
 * It is NOT evidence of pixel-exact xADAS or licensed-model parity.
 */
class OpenpilotSyntheticTensorFixtureTest {
    @Test fun twoByTwoColorPatchHasDeterministicYuvAndTemporalChannelOrder() {
        val w = OpenpilotYuvPacker.SOURCE_WIDTH
        val previous = IntArray(w * OpenpilotYuvPacker.SOURCE_HEIGHT) {
            0xff000000.toInt()
        }
        previous[0] = 0xffff0000.toInt() // R
        previous[1] = 0xff00ff00.toInt() // G
        previous[w] = 0xff0000ff.toInt() // B
        previous[w + 1] = 0xffffffff.toInt() // white
        val current = IntArray(previous.size) { 0xffffffff.toInt() }
        val out = OpenpilotYuvPacker.packPair(previous, current)
        val plane = OpenpilotYuvPacker.TARGET_WIDTH *
            OpenpilotYuvPacker.TARGET_HEIGHT
        assertEquals(12 * plane, out.size)

        val expectedPrior = floatArrayOf(82f, 144f, 41f, 235f, 128f, 128f)
        val expectedNow = floatArrayOf(235f, 235f, 235f, 235f, 128f, 128f)
        for (i in 0 until 6) {
            assertEquals("previous channel $i", expectedPrior[i], out[i * plane], 0.01f)
            assertEquals("current channel $i", expectedNow[i],
                out[(i + 6) * plane], 0.01f)
        }
        // Adjacent output location comes from the untouched black pixels.
        assertEquals(16f, out[1], 0.01f)
        assertEquals(235f, out[6 * plane + 1], 0.01f)
    }
}
