package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LiveYuvEvidenceCaptureTest {
    private fun frame(value:Int=17,width:Int=640):Yuv420FrameConverter.Frame {
        val yStride=648
        val uvStride=648
        val y=ByteArray(yStride*480){value.toByte()}
        val interleaved=ByteArray(uvStride*240)
        for(row in 0 until 240) for(col in 0 until 320) {
            interleaved[row*uvStride+col*2]=123.toByte()
            interleaved[row*uvStride+col*2+1]=231.toByte()
        }
        return Yuv420FrameConverter.Frame(width,480,
            Yuv420FrameConverter.Plane(y,yStride,1),
            Yuv420FrameConverter.Plane(interleaved,uvStride,2),
            Yuv420FrameConverter.Plane(interleaved,uvStride,2,1))
    }
    @Test fun fourNativeFormatFramesWriteAndReadCanonicalFixture() {
        val capture=LiveYuvEvidenceCapture(4)
        assertNull(capture.accept(frame(),1000))
        capture.arm()
        assertNull(capture.accept(frame(),1000))
        assertNull(capture.accept(frame(18),1300))
        assertNull(capture.accept(frame(19),1600))
        val full=capture.accept(frame(20),1900)
        assertNotNull(full)
        assertFalse(capture.isArmed())
        assertEquals(0,capture.count())
        val output=ByteArrayOutputStream()
        OfflineYuvFixture.write(full!!,output)
        assertEquals(1_843_376,output.size())
        val decoded=OfflineYuvFixture.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(4,decoded.frames.size)
        assertEquals(1900L,decoded.frames.last().receivedMs)
        assertEquals(20,decoded.frames.last().y[0].toInt())
        assertEquals(123,decoded.frames[0].u[0].toInt() and 255)
        assertEquals(231,decoded.frames[0].v[0].toInt() and 255)
        assertNull(capture.accept(frame(),2200))
    }
    @Test fun gapGeometryAndCancellationDiscardAllFrames() {
        val capture=LiveYuvEvidenceCapture(2)
        capture.arm()
        assertNull(capture.accept(frame(),1000))
        assertNull(capture.accept(frame(),1501))
        assertFalse(capture.isArmed())
        capture.arm()
        assertNull(capture.accept(frame(width=638),2000))
        assertFalse(capture.isArmed())
        capture.arm()
        assertNull(capture.accept(frame(),3000))
        capture.cancel()
        assertNull(capture.accept(frame(),3300))
        assertEquals(0,capture.count())
    }
    @Test fun writerValidatesEveryFrameBeforeTouchingDestination() {
        val f=OfflineYuvFixture.Frame(1000,
            ByteArray(OfflineYuvFixture.Y_BYTES),
            ByteArray(OfflineYuvFixture.UV_BYTES),
            ByteArray(OfflineYuvFixture.UV_BYTES))
        val bad=f.copy(receivedMs=1300,u=ByteArray(10))
        val out=ByteArrayOutputStream()
        try {
            OfflineYuvFixture.write(OfflineYuvFixture.Sequence(listOf(f,bad)),out)
            fail("Accepted incomplete plane")
        } catch(_:IllegalArgumentException) { assertEquals(0,out.size()) }
        try {
            OfflineYuvFixture.write(OfflineYuvFixture.Sequence(listOf(f,f)),out)
            fail("Accepted duplicate timestamps")
        } catch(_:IllegalArgumentException) { assertEquals(0,out.size()) }
    }
}
