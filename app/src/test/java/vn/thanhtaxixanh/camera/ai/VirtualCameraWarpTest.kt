package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class VirtualCameraWarpTest {
    @Test fun identityWarpKeepsEachPixel() {
        val pixels=IntArray(512*256) { i ->
            (0xff000000.toInt() or ((i and 255) shl 16) or
                (((i/512) and 255) shl 8) or (i and 255)) }
        val warped=VirtualCameraWarp.warp(pixels,512,256,
            VirtualCameraWarp.Matrix.identity())
        assertArrayEquals(pixels,warped)
    }
    @Test fun translationSelectsKnownSourcePixel() {
        val source=IntArray(512*256) { 0xff112233.toInt() }
        source[10*512+11]=0xffaabbcc.toInt()
        val tx=VirtualCameraWarp.Matrix(doubleArrayOf(
            1.0,0.0,1.0, 0.0,1.0,0.0, 0.0,0.0,1.0))
        val warped=VirtualCameraWarp.warp(source,512,256,tx)
        assertEquals(0xffaabbcc.toInt(),warped[10*512+10])
    }
    @Test fun invalidGeometryDoesNotGenerateSpuriousPixels() {
        val source=IntArray(512*256) { 0xffffffff.toInt() }
        val off=VirtualCameraWarp.Matrix(doubleArrayOf(
            1.0,0.0,999.0,0.0,1.0,999.0,0.0,0.0,1.0))
        val warped=VirtualCameraWarp.warp(source,512,256,off)
        assertEquals(0xff000000.toInt(),warped[0])
        assertEquals(0xff000000.toInt(),warped.last())
    }

    @Test fun warpMatrixIsImmutableAfterValidation() {
        val coefficients = doubleArrayOf(
            1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val matrix = VirtualCameraWarp.Matrix(coefficients)
        coefficients[2] = 9999.0
        matrix.values[5] = 9999.0
        val pixels = IntArray(512*256) { 0xff001122.toInt() }
        val coverage = VirtualCameraWarp.warpWithCoverage(
            pixels,512,256,matrix)
        assertEquals(1.0,coverage.coverage,0.0)
        assertArrayEquals(pixels,coverage.pixels)
    }

    @Test fun singularAndUnusableHomographiesAreRejectedBeforeWarp() {
        try {
            VirtualCameraWarp.Matrix(doubleArrayOf(
                1.0,0.0,0.0, 1.0,0.0,0.0, 0.0,0.0,1.0))
            fail("Warp cannot collapse 2D image onto a line")
        } catch (_: IllegalArgumentException) {}
        try {
            VirtualCameraWarp.Matrix(doubleArrayOf(
                1.0,0.0,0.0, 0.0,Double.NaN,0.0, 0.0,0.0,1.0))
            fail("Non-finite warp is invalid")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun mostlyOutOfFrameWarpCannotRunOnModel() {
        val pixels=IntArray(512*256) { 0xff999999.toInt() }
        // Exactly half of the columns are out of bounds.
        val half=VirtualCameraWarp.Matrix(doubleArrayOf(
            1.0,0.0,256.0, 0.0,1.0,0.0, 0.0,0.0,1.0))
        val sampled=VirtualCameraWarp.warpWithCoverage(pixels,512,256,half)
        assertEquals(0.5,sampled.coverage,0.0000001)
        assertEquals(0xff000000.toInt(),sampled.pixels.last())
        try {
            VirtualCameraWarp.pairToModelInput(pixels,pixels,512,256,half)
            fail("An offscreen image must not be silently fed to ONNX")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun observedA500sSyntheticCropsMeetCoverageWithoutCalibrationClaim() {
        val pixels=IntArray(640*480) { 0xff808080.toInt() }
        val center=VirtualCameraWarp.Matrix(doubleArrayOf(
            1.0,0.0,64.0,0.0,1.0,112.0,0.0,0.0,1.0))
        val wide=VirtualCameraWarp.Matrix(doubleArrayOf(
            1.2,0.0,13.0,0.0,1.2,86.0,0.0,0.0,1.0))
        assertEquals(1.0,
            VirtualCameraWarp.warpWithCoverage(pixels,640,480,center).coverage,0.0)
        assertEquals(1.0,
            VirtualCameraWarp.warpWithCoverage(pixels,640,480,wide).coverage,0.0)
        val tensor=VirtualCameraWarp.pairToModelInput(
            pixels,pixels,640,480,wide)
        VisionInputContract.verify(tensor)
    }

    @Test fun bilinearHalfPixelInterpolationHasDeterministicCenterValue() {
        val pixels=IntArray(512*256) { 0xff000000.toInt() }
        pixels[0] = 0xff000000.toInt()
        pixels[1] = 0xff640000.toInt()
        pixels[512] = 0xffc80000.toInt()
        pixels[513] = 0xff000000.toInt()
        val subpixel=VirtualCameraWarp.Matrix(doubleArrayOf(
            1.0,0.0,0.5, 0.0,1.0,0.5, 0.0,0.0,1.0))
        val actual=VirtualCameraWarp.warp(pixels,512,256,subpixel)[0]
        assertEquals(75,(actual ushr 16) and 255)
        assertEquals(0,(actual ushr 8) and 255)
    }

    @Test fun warpPairEmitsExact12ChannelContract() {
        val previous=IntArray(512*256) { 0xff222222.toInt() }
        val current=IntArray(512*256) { 0xffbbbbbb.toInt() }
        val input=VirtualCameraWarp.pairToModelInput(
            previous,current,512,256,VirtualCameraWarp.Matrix.identity())
        assertEquals(SupercomboV027Contract.INPUT_ELEMENTS,input.size)
        assertTrue(input.all { it.isFinite() })
        assertNotEquals(input[0],input[input.size/2])
    }
}
