package vn.thanhtaxixanh.camera.ai

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Pure host regression; real Android Image planes not exercised. */
class YuvPlaneSnapshotTest {
    private fun plane(bytes:ByteArray,row:Int,pixel:Int,x:Int,y:Int,w:Int,h:Int) =
        YuvPlaneSnapshot.copy(ByteBuffer.wrap(bytes),row,pixel,x,y,w,h)

    @Test fun oddCropLeftShiftsChromaIndex() {
        val y=plane(ByteArray(16){81},8,1,1,0,4,2)
        val u=plane(byteArrayOf(90,128.toByte(),240.toByte(),0),4,1,0,0,3,1)
        val v=plane(byteArrayOf(240.toByte(),128.toByte(),90,0),4,1,0,0,3,1)
        val rgb=Yuv420FrameConverter.argb(
            Yuv420FrameConverter.Frame(4,2,y,u,v,1,0))
        assertNotEquals(rgb[0],rgb[1])
        assertEquals(rgb[1],rgb[2])
        assertNotEquals(rgb[2],rgb[3])
        assertEquals(rgb[0],rgb[4])
    }

    @Test fun oddCropTopShiftsChromaRow() {
        val y=plane(ByteArray(12){81},2,1,0,1,2,4)
        val u=plane(byteArrayOf(90,0,128.toByte(),0,240.toByte(),0),
            2,1,0,0,1,3)
        val v=plane(byteArrayOf(240.toByte(),0,128.toByte(),0,90,0),
            2,1,0,0,1,3)
        val rgb=Yuv420FrameConverter.argb(
            Yuv420FrameConverter.Frame(2,4,y,u,v,0,1))
        assertNotEquals(rgb[0],rgb[2])
        assertEquals(rgb[2],rgb[4])
        assertNotEquals(rgb[4],rgb[6])
    }

    @Test fun paddedInterleavedUvReadsCorrectStride() {
        val b=byteArrayOf(90,0,140.toByte(),0,0,120,0,200.toByte(),0,0)
        val p=plane(b,5,2,0,0,2,2)
        assertEquals(90,p.sample(0,0))
        assertEquals(140,p.sample(1,0))
        assertEquals(120,p.sample(0,1))
        assertEquals(200,p.sample(1,1))
    }

    @Test fun nativeLimitIsRespectedEvenWhenCapacityIsLarger() {
        val b=ByteBuffer.allocate(64)
        b.limit(6)
        assertEquals(6,YuvPlaneSnapshot.copy(b,3,1,0,0,3,2).bytes.size)
        try {
            YuvPlaneSnapshot.copy(b,4,1,0,0,3,2)
            fail("byte beyond ByteBuffer.limit must be rejected")
        } catch (_:IllegalArgumentException) {}
    }

    @Test fun callerPositionAndLimitAreNotMutated() {
        val b=ByteBuffer.wrap(byteArrayOf(10,20,30,40,50,60))
        b.position(3)
        val p=YuvPlaneSnapshot.copy(b,3,1,0,0,3,2)
        assertEquals(3,b.position())
        assertEquals(6,b.limit())
        assertEquals(10,p.sample(0,0))
        assertEquals(60,p.sample(2,1))
    }

    @Test fun rejectsHugeOffsetsBeforeAllocation() {
        val b=ByteBuffer.allocate(4)
        try {
            YuvPlaneSnapshot.copy(b,Int.MAX_VALUE,Int.MAX_VALUE,
                1,1,2,2)
            fail("integer overflow could skip limit checking")
        } catch (_:IllegalArgumentException) {}
    }

    @Test fun invalidZeroStrideAndDimensionsFail() {
        val b=ByteBuffer.allocate(4)
        val bad=listOf(
            intArrayOf(0,1,0,0,2,2),
            intArrayOf(2,0,0,0,2,2),
            intArrayOf(2,1,-1,0,2,2),
            intArrayOf(2,1,0,0,0,2))
        bad.forEach { a ->
            try {
                YuvPlaneSnapshot.copy(b,a[0],a[1],a[2],a[3],a[4],a[5])
                fail("invalid native YUV plane")
            } catch (_:IllegalArgumentException) {}
        }
    }

    @Test fun evenCropMatchesDefaultArgbOutput() {
        val y=Yuv420FrameConverter.Plane(
            byteArrayOf(16,235.toByte(),128.toByte(),128.toByte()),2,1)
        val uv=Yuv420FrameConverter.Plane(byteArrayOf(128.toByte()),1,1)
        val original=Yuv420FrameConverter.Frame(2,2,y,uv,uv)
        val crop=Yuv420FrameConverter.Frame(2,2,y,uv,uv,0,0)
        assertArrayEquals(
            Yuv420FrameConverter.argb(original),
            Yuv420FrameConverter.argb(crop))
    }

    @Test fun invalidChromaPhaseRejected() {
        val p=Yuv420FrameConverter.Plane(byteArrayOf(16),1,1)
        try {
            Yuv420FrameConverter.Frame(2,2,p,p,p,2,0)
            fail("phase 2 is invalid")
        } catch (_:IllegalArgumentException) {}
    }
}
