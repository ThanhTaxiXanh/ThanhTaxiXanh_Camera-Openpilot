package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Deterministic host checks, not a physical Camera2 hardware test. */
class PhoneCameraFieldEvidenceTest {
    private val ms=1_000_000L
    private fun accepted(sensorMs:Long,nowMs:Long)=
        Camera2SensorFrameGate.Decision(
            Camera2SensorFrameGate.Verdict.ACCEPT,sensorMs,nowMs-sensorMs)
    private fun readyTrace(
        realtime:Boolean=true,
        rear:Boolean=true,
        surface:Boolean=true,
        samples:Boolean=true,
        goodClock:Boolean=true
    ):PhoneCameraFieldEvidence.Report {
        val session=PhoneCameraFieldEvidence("0",rear,realtime,10_000L)
        session.markPreviewConfigured()
        repeat(360) { session.onCaptureCompleted(1_000_000L+it) }
        repeat(100) { n ->
            val at=10_000L+n*350L
            session.onImage(at,if(goodClock) accepted(at-40L,at)
                else Camera2SensorFrameGate.Decision(
                    Camera2SensorFrameGate.Verdict.UNKNOWN_TIMEBASE))
            if(samples && session.shouldProbe(at))
                session.onYuvProbe(PhoneCameraYuvProbe.Result(
                    n.toLong(),32,208))
        }
        return session.finish(45_000L,surface)
    }

    @Test fun healthyCamera2MetadataIsReviewableNotAcceptancePass() {
        val r=readyTrace()
        assertTrue(r.reviewable)
        assertEquals(35_000L,r.elapsedMs)
        assertEquals(360L,r.cameraCaptures)
        assertEquals(100L,r.yuvCallbacks)
        assertEquals(100L,r.sensorValid)
        assertTrue(r.yuvCopies>=10L)
        assertTrue(r.text.startsWith("TTXPHONE01\n"))
        assertTrue(r.text.contains("status=READY_FOR_INDEPENDENT_REVIEW"))
        assertTrue(r.text.contains("criterion_8_not_automatically_PASS=true"))
        assertTrue(r.text.contains("saved_image_payload=false"))
        assertTrue(r.text.endsWith("END_TTXPHONE01\n"))
    }

    @Test fun shortRecordingNeverPassesEvidenceThreshold() {
        val d=PhoneCameraFieldEvidence("0",true,true,0L)
        d.markPreviewConfigured()
        repeat(180) {d.onCaptureCompleted(it.toLong())}
        repeat(40) {
            d.onImage(1000L+it*100L,accepted(980L+it*100L,1000L+it*100L))
            d.onYuvProbe(PhoneCameraYuvProbe.Result(1234L,50,100))
        }
        val r=d.finish(8_000L,true)
        assertFalse(r.reviewable)
        assertTrue(r.text.contains("status=INCOMPLETE_OR_UNVERIFIED"))
    }

    @Test fun unknownSensorClockOrNoProofFailsClosed() {
        assertFalse(readyTrace(realtime=false).reviewable)
        assertFalse(readyTrace(goodClock=false).reviewable)
        assertFalse(readyTrace(samples=false).reviewable)
        assertFalse(readyTrace(rear=false).reviewable)
        assertFalse(readyTrace(surface=false).reviewable)
    }

    @Test fun cameraCaptureCountAloneCannotPretendToProveYuv() {
        val e=PhoneCameraFieldEvidence("0",true,true,1_000L)
        e.markPreviewConfigured()
        repeat(900) { e.onCaptureCompleted(it.toLong()+1L) }
        val r=e.finish(50_000L,true)
        assertFalse(r.reviewable)
        assertEquals(900L,r.cameraCaptures)
        assertEquals(0L,r.yuvCallbacks)
        assertEquals(0L,r.yuvCopies)
    }

    @Test fun missingOrRegressingClockCannotBeCalledReviewable() {
        val e=PhoneCameraFieldEvidence("0",true,true,100L)
        e.markPreviewConfigured()
        repeat(200) {e.onCaptureCompleted(100_000L)}
        repeat(40) {i->
            val t=100L+i*1000L
            e.onImage(t,accepted(t-10L,t))
            e.onYuvProbe(PhoneCameraYuvProbe.Result(i.toLong(),16,240))
        }
        // UI or session clock regression after otherwise healthy frames.
        e.onImage(100L,accepted(80L,100L))
        val r=e.finish(45_000L,true)
        assertFalse(r.reviewable)
        assertTrue(r.text.contains("clock_regressions=1"))
    }

    @Test fun unsupportedYuvOutputMustShowInReviewStatus() {
        val e=PhoneCameraFieldEvidence("0",true,true,1_000L)
        e.markPreviewConfigured()
        repeat(300) {e.onCaptureCompleted(1_000_000L)}
        repeat(50) {i->
            val t=1_000L+i*700L
            e.onImage(t,accepted(t-10L,t))
            e.onYuvProbe(PhoneCameraYuvProbe.Result(3L,0,255))
        }
        e.onProbeFailed(badFormat=true)
        val r=e.finish(40_000L,true)
        assertFalse(r.reviewable)
        assertTrue(r.text.contains("unsupported_format=1"))
        assertTrue(r.text.contains("yuv_copy_failures=1"))
    }

    @Test fun probeRateLimitedToOnePerSecondAndReopensAfterPause() {
        val e=PhoneCameraFieldEvidence("0",true,true,10_000L)
        assertFalse(e.shouldProbe(9_999L))
        assertTrue(e.shouldProbe(10_000L))
        assertFalse(e.shouldProbe(10_999L))
        assertTrue(e.shouldProbe(11_000L))
        assertFalse(e.shouldProbe(11_050L))
        assertTrue(e.shouldProbe(12_500L))
    }

    @Test fun actualStrideAwarePlanarPixelsProduceStableDigest() {
        val w=640
        val h=480
        val y=ByteArray(672*h) { (it%239).toByte() }
        val u=ByteArray(336*(h/2)) { (it%173).toByte() }
        val v=ByteArray(336*(h/2)) { (it%127).toByte() }
        val frame=Yuv420FrameConverter.Frame(w,h,
            Yuv420FrameConverter.Plane(y,672,1),
            Yuv420FrameConverter.Plane(u,336,1),
            Yuv420FrameConverter.Plane(v,336,1))
        val first=PhoneCameraYuvProbe.verify(frame)
        val second=PhoneCameraYuvProbe.verify(frame)
        assertEquals(first,second)
        assertTrue(first.minY<=first.maxY)
        assertTrue(first.crc32 in 0L..0xffffffffL)
        y[0]=(y[0]+1).toByte()
        val different=PhoneCameraYuvProbe.verify(frame)
        assertNotEquals(first.crc32,different.crc32)
    }

    @Test fun invalidPlaneOrDimensionsCannotFakeVerifiedYuvCopy() {
        val frame=Yuv420FrameConverter.Frame(640,480,
            Yuv420FrameConverter.Plane(byteArrayOf(17),1,1),
            Yuv420FrameConverter.Plane(byteArrayOf(128.toByte()),1,1),
            Yuv420FrameConverter.Plane(byteArrayOf(128.toByte()),1,1))
        try {
            PhoneCameraYuvProbe.verify(frame)
            fail("Truncated 640x480 YUV cannot yield proof")
        } catch (_:IllegalArgumentException) {}
        val small=Yuv420FrameConverter.Frame(4,4,
            Yuv420FrameConverter.Plane(ByteArray(16),4,1),
            Yuv420FrameConverter.Plane(ByteArray(4),2,1),
            Yuv420FrameConverter.Plane(ByteArray(4),2,1))
        try {
            PhoneCameraYuvProbe.verify(small)
            fail("Wrong source dimensions cannot yield proof")
        } catch (_:IllegalArgumentException) {}
    }

    @Test fun oddCropChromaPhaseAndInterleavedUvAreRead() {
        val w=640
        val h=480
        val y=ByteArray(w*h) { 90.toByte() }
        val u=ByteArray(640*241+2) { 128.toByte() }
        val v=ByteArray(640*241+2) { 130.toByte() }
        val f=Yuv420FrameConverter.Frame(w,h,
            Yuv420FrameConverter.Plane(y,w,1),
            Yuv420FrameConverter.Plane(u,640,2),
            Yuv420FrameConverter.Plane(v,640,2),
            chromaXPhase=1,chromaYPhase=1)
        val r=PhoneCameraYuvProbe.verify(f)
        assertEquals(90,r.minY)
        assertEquals(90,r.maxY)
        assertTrue(r.crc32>=0L)
    }
}
