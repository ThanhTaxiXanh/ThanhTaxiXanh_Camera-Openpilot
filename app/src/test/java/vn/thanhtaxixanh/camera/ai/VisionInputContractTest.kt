package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Pure host tests of our CURRENT experimental YUV packer, not xADAS parity. */
class VisionInputContractTest {
    private val elements = SupercomboV027Contract.INPUT_ELEMENTS
    private val plane = SupercomboV027Contract.HEIGHT *
        SupercomboV027Contract.WIDTH

    @Test fun acceptsGoldenStudioRangeAndRejectsRenormalizedInputs() {
        val prev=IntArray(512*256) { 0xff101010.toInt() }
        val next=IntArray(512*256) { 0xffdedede.toInt() }
        val frame=OpenpilotYuvPacker.packPair(prev,next)
        VisionInputContract.requireRawStudioByteTensors(frame,frame.copyOf())
        val normalized=frame.map { it / 255.0f }.toFloatArray()
        try {
            VisionInputContract.verify(normalized)
            fail("Normalized [0,1] tensor cannot be fed to raw-input model")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun rejectsNonfiniteAndOutOfRangeLumaOrChroma() {
        val t=FloatArray(elements) { 128f }
        VisionInputContract.verify(t)
        t[0]=Float.NaN
        expectInvalid(t)
        t[0]=236f // luma maximum is 235
        expectInvalid(t)
        t[0]=16f
        t[4*plane]=241f // chroma maximum is 240
        expectInvalid(t)
        t[4*plane]=15f // studio minimum is 16
        expectInvalid(t)
        t[4*plane]=128f
        VisionInputContract.verify(t)
    }

    @Test fun requiresExactlyTwelveChannelsAndBothNamedInputs() {
        try {
            VisionInputContract.verify(FloatArray(elements-1))
            fail("Tensor shape mismatch cannot enter ONNX")
        } catch (_: IllegalArgumentException) {}
        val good=FloatArray(elements) { 128f }
        val bad=good.copyOf()
        bad[6*plane]=Float.POSITIVE_INFINITY
        try {
            VisionInputContract.requireRawStudioByteTensors(good,bad)
            fail("One invalid ONNX input invalidates the full pair")
        } catch (_: IllegalArgumentException) {}
    }

    private fun expectInvalid(t: FloatArray) {
        try {
            VisionInputContract.verify(t)
            fail("This sample must fail closed")
        } catch (_: IllegalArgumentException) {}
    }
}
