package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * No physical handset or camera captured by these tests. They verify
 * that diagnostic TTXYUV02 never masquerades as temporal TTXYUV01.
 */
class A500sYuvSnapshotCaptureTest {
    private fun frame(value:Int=21,width:Int=640):Yuv420FrameConverter.Frame {
        val stride=648
        val y=ByteArray(stride*480){value.toByte()}
        val uv=ByteArray(stride*240)
        for(row in 0 until 240) for(x in 0 until 320) {
            uv[row*stride+2*x]=127.toByte()
            uv[row*stride+2*x+1]=129.toByte()
        }
        return Yuv420FrameConverter.Frame(width,480,
            Yuv420FrameConverter.Plane(y,stride,1),
            Yuv420FrameConverter.Plane(uv,stride,2),
            Yuv420FrameConverter.Plane(uv,stride,2,1))
    }

    @Test fun rejectsUnarmedFramesAndRetainsNothingOnDisable() {
        val c=A500sYuvSnapshotCapture()
        assertFalse(c.accept(frame(),1000))
        assertNull(c.snapshot())
        c.enable()
        assertTrue(c.accept(frame(),1000))
        assertEquals(1,c.count())
        c.disable()
        assertEquals(0,c.count())
        assertNull(c.snapshot())
    }

    @Test fun fourActualNonContiguousFramesExportWithExplicitNonTemporalHeader() {
        val c=A500sYuvSnapshotCapture()
        c.enable()
        assertTrue(c.accept(frame(17),1000))
        assertTrue(c.accept(frame(18),2500))
        assertTrue(c.accept(frame(19),6000))
        assertTrue(c.accept(frame(20),10_000))
        val captured=c.snapshot()!!
        assertEquals(4,captured.frames.size)
        assertEquals(3,captured.nonTemporalGaps)
        assertEquals(4,c.count())
        assertFalse(c.accept(frame(21),10_033))
        val bytes=ByteArrayOutputStream()
        OfflineYuvSnapshotFixture.write(captured,bytes)
        assertEquals(1_843_376,bytes.size())
        assertEquals("TTXYUV02",String(bytes.toByteArray().copyOfRange(0,8)))
        val loaded=OfflineYuvSnapshotFixture.read(
            ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(4,loaded.frames.size)
        assertEquals(3,loaded.nonTemporalGaps)
        assertEquals(17,loaded.frames[0].y[0].toInt() and 255)
        assertEquals(20,loaded.frames[3].y[0].toInt() and 255)
        try {
            OfflineYuvFixture.read(ByteArrayInputStream(bytes.toByteArray()))
            fail("Non-temporal TTXYUV02 cannot be accepted as TTXYUV01")
        } catch (_:IllegalArgumentException) {}
    }

    @Test fun ownerCanExportOneRealFrameAfterSidecarStopped() {
        val c=A500sYuvSnapshotCapture()
        c.enable()
        assertTrue(c.accept(frame(),5_000))
        val byteStream=ByteArrayOutputStream()
        OfflineYuvSnapshotFixture.write(c.snapshot()!!,byteStream)
        val loaded=OfflineYuvSnapshotFixture.read(
            ByteArrayInputStream(byteStream.toByteArray()))
        assertEquals(1,loaded.frames.size)
        assertEquals(0,loaded.nonTemporalGaps)
    }

    @Test fun twoSparseFramesAreNotSilentlyPresentedAsContinuous() {
        val c=A500sYuvSnapshotCapture()
        c.enable()
        c.accept(frame(21),1000)
        c.accept(frame(22),1100)
        c.accept(frame(23),4000)
        val snap=c.snapshot()!!
        assertEquals(3,snap.frames.size)
        assertEquals(1,snap.nonTemporalGaps)
        val bytes=ByteArrayOutputStream()
        OfflineYuvSnapshotFixture.write(snap,bytes)
        assertEquals(3,OfflineYuvSnapshotFixture.read(
            ByteArrayInputStream(bytes.toByteArray())).frames.size)
    }

    @Test fun duplicateOrRegressingReceiptClockCannotBeStored() {
        val c=A500sYuvSnapshotCapture()
        c.enable()
        assertTrue(c.accept(frame(),4000))
        assertFalse(c.accept(frame(),4000))
        assertFalse(c.accept(frame(),3999))
        assertEquals(2L,c.rejectedFrames())
        assertEquals(1,c.count())
    }

    @Test fun wrongFrameSizeAndNegativeTimeNeverBecomeRealEvidence() {
        val c=A500sYuvSnapshotCapture()
        c.enable()
        assertFalse(c.accept(frame(width=638),5000))
        assertFalse(c.accept(frame(),-1))
        assertEquals(0,c.count())
        assertEquals(2L,c.rejectedFrames())
    }

    @Test fun corruptSnapshotChecksumIsRefused() {
        val c=A500sYuvSnapshotCapture()
        c.enable()
        c.accept(frame(),900)
        val buf=ByteArrayOutputStream()
        OfflineYuvSnapshotFixture.write(c.snapshot()!!,buf)
        val bytes=buf.toByteArray()
        bytes[30]=(bytes[30].toInt() xor 0xff).toByte()
        try {
            OfflineYuvSnapshotFixture.read(ByteArrayInputStream(bytes))
            fail("corrupt actual YUV must be rejected")
        } catch (_:IllegalArgumentException) {}
    }

    @Test fun forgedFlagsAndTrailingDataAreRefused() {
        val c=A500sYuvSnapshotCapture()
        c.enable()
        c.accept(frame(),900)
        val buf=ByteArrayOutputStream()
        OfflineYuvSnapshotFixture.write(c.snapshot()!!,buf)
        val bytes=buf.toByteArray()
        val forged=bytes.clone()
        forged[15]=0
        try {
            OfflineYuvSnapshotFixture.read(ByteArrayInputStream(forged))
            fail("zero flag cannot be interpreted as temporal TTXYUV01")
        } catch (_:IllegalArgumentException) {}
        try {
            OfflineYuvSnapshotFixture.read(
                ByteArrayInputStream(bytes+byteArrayOf(1)))
            fail("trailing data cannot be accepted")
        } catch (_:IllegalArgumentException) {}
    }

    @Test fun noSourcePixelsMeansNoFileIsConstructed() {
        val c=A500sYuvSnapshotCapture()
        c.enable()
        assertNull(c.snapshot())
        try {
            OfflineYuvSnapshotFixture.write(
                OfflineYuvSnapshotFixture.Snapshot(emptyList()),
                ByteArrayOutputStream())
            fail("Cannot fabricate YUV frames")
        } catch (_:IllegalArgumentException) {}
    }
}
