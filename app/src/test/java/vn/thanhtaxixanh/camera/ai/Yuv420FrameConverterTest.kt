package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class Yuv420FrameConverterTest {
    private fun plane(vararg values:Int) =
        Yuv420FrameConverter.Plane(values.map { it.toByte() }.toByteArray(),2,1)

    @Test fun neutralChromaPreservesMonochrome() {
        val f=Yuv420FrameConverter.Frame(2,2,
            plane(16,235,128,128),plane(128,128),plane(128,128))
        val rgb=Yuv420FrameConverter.argb(f)
        assertEquals(0xff000000.toInt(),rgb[0])
        assertEquals(0xffffffff.toInt(),rgb[1])
        assertEquals(0xff828282.toInt(),rgb[2])
    }

    @Test fun handlesInterleavedUvAndPaddedRows() {
        val yy=byteArrayOf(81,81,0,81,81,0)
        val u=byteArrayOf(90,240,10)
        val v=byteArrayOf(240.toByte(),90,10)
        val f=Yuv420FrameConverter.Frame(
            2,2,Yuv420FrameConverter.Plane(yy,3,1),
            Yuv420FrameConverter.Plane(u,3,2),
            Yuv420FrameConverter.Plane(v,3,2))
        val result=Yuv420FrameConverter.argb(f)
        assertEquals(4,result.size)
        assertTrue(result.all { it == result[0] })
        assertTrue((result[0] ushr 16 and 255) > (result[0] and 255))
    }

    @Test fun boundsCheckingAvoidsSilentBlackFrames() {
        val y=Yuv420FrameConverter.Plane(byteArrayOf(0),1,1)
        val f=Yuv420FrameConverter.Frame(2,2,y,y,y)
        try {
            Yuv420FrameConverter.argb(f)
            fail("Truncated planes must not silently turn into invalid AI frames")
        } catch (_: IllegalArgumentException) {}
    }
}
