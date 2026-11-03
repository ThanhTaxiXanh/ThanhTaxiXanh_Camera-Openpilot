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
