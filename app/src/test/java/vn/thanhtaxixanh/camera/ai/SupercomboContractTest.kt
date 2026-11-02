package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class SupercomboContractTest {
    @Test fun headLayoutIsContiguousAndComplete() {
        val heads = SupercomboV027Contract.heads
        assertEquals(11, heads.size)
        assertEquals(0, heads.first().offset)
        assertEquals(1576, heads.last().endExclusive)
        for (i in 1 until heads.size) {
            assertEquals(heads[i-1].endExclusive, heads[i].offset)
        }
    }

    @Test fun verifiedMajorHeadsHaveCorrectOffsets() {
        assertEquals(528, SupercomboV027Contract.head("lane_lines").length)
        assertEquals(117, SupercomboV027Contract.head("lane_lines").offset)
        assertEquals(653, SupercomboV027Contract.head("road_edges").offset)
        assertEquals(917, SupercomboV027Contract.head("lead").offset)
        assertEquals(1061, SupercomboV027Contract.head("lead_prob").offset)
        assertEquals(1064, SupercomboV027Contract.head("summarizer_features").offset)
    }

    @Test fun inspectionRejectsWrongSizeAndNonFinite() {
        assertFalse(SupercomboV027Contract.inspect(FloatArray(32)).allFinite)
        val data = FloatArray(1576)
        assertTrue(SupercomboV027Contract.inspect(data).allFinite)
        data[917] = Float.NaN
        val result = SupercomboV027Contract.inspect(data)
        assertFalse(result.allFinite)
        assertEquals(143, result.heads.single { it.name == "lead" }.finiteValues)
    }

    @Test fun sliceUsesOnlyHeadRange() {
        val data = FloatArray(1576) { it.toFloat() }
        val lane = SupercomboV027Contract.slice(data, "lane_lines")
        assertEquals(528, lane.size)
        assertEquals(117f, lane.first(), 0f)
        assertEquals(644f, lane.last(), 0f)
    }

    @Test fun backendInputShapeIsEnforced() {
        val fake = object : SupercomboInferencePort {
            override fun run(img: FloatArray, bigImg: FloatArray) = FloatArray(1576)
        }
        try {
            SupercomboInferenceValidator.runChecked(fake, FloatArray(7),
                FloatArray(SupercomboV027Contract.INPUT_ELEMENTS))
            fail("Input shape must be validated before inference")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
