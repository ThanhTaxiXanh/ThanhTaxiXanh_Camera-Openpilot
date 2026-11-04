package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

/** Pure deterministic multi-second local video pipeline fixture. */
class StreamCadenceAuditTest {
    private val stage=StreamCadenceAudit.Stage

    private fun simulate(
        frameCount:Int = 601,
        receiveEveryMs:Long = 33L,
        sourcePtsStepUs:Long = 33_000L,
        inputEvery:Int = 1,
        decodedEvery:Int = 1,
        releaseEvery:Int = 1,
        callbackEvery:Int = 1,
        decodePtsBehind:Int = 0,
        releasePtsBehind:Int = 0
    ):StreamCadenceAudit {
        val monitor=StreamCadenceAudit()
        for (i in 0 until frameCount) {
            val now=i*receiveEveryMs
            val pts=1_000_000L+i*sourcePtsStepUs
            assertTrue(monitor.observe(stage.AU_RECEIVED,pts,now))
            if(i%inputEvery==0)
                assertTrue(monitor.observe(stage.CODEC_INPUT,pts,now))
            if(i%decodedEvery==0 && i>=decodePtsBehind) {
                val decodePts=1_000_000L+(i-decodePtsBehind)*sourcePtsStepUs
                assertTrue(monitor.observe(stage.CODEC_OUTPUT,decodePts,now))
            }
            if(i%releaseEvery==0 && i>=releasePtsBehind) {
                val releasePts=1_000_000L+(i-releasePtsBehind)*sourcePtsStepUs
                assertTrue(monitor.observe(stage.SURFACE_RELEASE,releasePts,now))
            }
            if(callbackEvery>0 && i%callbackEvery==0 && i>=releasePtsBehind) {
                val cbPts=1_000_000L+(i-releasePtsBehind)*sourcePtsStepUs
                assertTrue(monitor.observe(stage.RENDER_CALLBACK,cbPts,now))
            }
        }
        return monitor
    }

    @Test fun steady30FpsLocalFlowDoesNotClaimPhysicalLatency() {
        val m=simulate()
        val s=m.snapshot(19_800L)
        assertEquals(StreamCadenceAudit.Status.FLOW_OBSERVED,s.status)
        assertTrue(s.sourceFps!! in 29.0..31.0)
        assertEquals(s.sourceFps!!,s.codecInputFps!!,0.001)
        assertEquals(s.sourceFps!!,s.codecOutputFps!!,0.001)
        assertEquals(s.sourceFps!!,s.surfaceReleaseFps!!,0.001)
        assertEquals(s.sourceFps!!,s.matchedRenderCallbackFps!!,0.001)
        assertTrue(s.mediaClockFps!! in 29.0..31.0)
        assertEquals(0.0,s.mediaGapToCodecOutputMs!!,0.00001)
        assertTrue(s.note.contains("NOT camera-to-glass"))
        assertEquals(0L,s.invalidEvents)
    }

    @Test fun localCodecAndSurfaceBottlenecksAreDistinct() {
        val input=simulate(inputEvery=2,decodedEvery=2,releaseEvery=2,
            callbackEvery=2).snapshot(19_800L)
        assertEquals(StreamCadenceAudit.Status.CODEC_INPUT_LAGGING,input.status)
        val codec=simulate(decodedEvery=2,releaseEvery=2,
            callbackEvery=2).snapshot(19_800L)
        assertEquals(StreamCadenceAudit.Status.CODEC_OUTPUT_LAGGING,codec.status)
        val surface=simulate(releaseEvery=2,
            callbackEvery=2).snapshot(19_800L)
        assertEquals(StreamCadenceAudit.Status.SURFACE_RELEASE_LAGGING,surface.status)
        val absent=simulate(callbackEvery=0).snapshot(19_800L)
        assertEquals(StreamCadenceAudit.Status.RENDER_CALLBACK_UNCONFIRMED,
            absent.status)
        assertEquals(0.0,absent.matchedRenderCallbackFps!!,0.001)
    }

    @Test fun steadyFrameRateMayStillCarryThreeSecondsOfHiddenMediaBacklog() {
        val behind=simulate(decodePtsBehind=90,releasePtsBehind=90)
            .snapshot(19_800L)
        assertEquals(StreamCadenceAudit.Status.CODEC_OUTPUT_MEDIA_BACKLOG,
            behind.status)
        assertTrue(behind.mediaGapToCodecOutputMs!! > 2_900.0)
        assertTrue(behind.codecOutputFps!! > 29.0)

        val releaseBehind=simulate(releasePtsBehind=90)
            .snapshot(19_800L)
        assertEquals(StreamCadenceAudit.Status.SURFACE_RELEASE_MEDIA_BACKLOG,
            releaseBehind.status)
        assertEquals(0.0,releaseBehind.mediaGapToCodecOutputMs!!,0.01)
        assertTrue(releaseBehind.mediaGapToSurfaceReleaseMs!! > 2_900.0)
    }

    @Test fun sourceRtpClockRateVersusLocalArrivalRateMismatchIsDetected() {
        val m=simulate(receiveEveryMs=50L,sourcePtsStepUs=40_000L,
            frameCount=401)
        val s=m.snapshot(20_000L)
        assertEquals(StreamCadenceAudit.Status.RTP_RECEIPT_RATE_MISMATCH,
            s.status)
        assertTrue(s.sourceFps!! in 19.0..21.0)
        assertTrue(s.mediaClockFps!! in 24.0..26.0)
    }

    @Test fun stallAndWarmupNeverMasqueradeAsHealthyFlow() {
        val m=simulate(frameCount=301)
        assertEquals(StreamCadenceAudit.Status.WARMUP,m.snapshot(4_000L).status)
        assertEquals(StreamCadenceAudit.Status.SOURCE_AU_STALLED,
            m.snapshot(15_000L).status)
    }

    @Test fun invalidClockInputsAndLongStreamCapacityAreBounded() {
        val s=StreamCadenceAudit(windowMs=4_000L,maxEventsPerStage=128)
        assertFalse(s.observe(stage.CODEC_OUTPUT,1_000L,10L))
        assertFalse(s.observe(stage.AU_RECEIVED,-1L,10L))
        assertTrue(s.observe(stage.AU_RECEIVED,1_000L,10L))
        assertFalse(s.observe(stage.AU_RECEIVED,2_000L,9L))
        assertEquals(3L,s.snapshot(10L).invalidEvents)
        for (i in 1..2000) {
            val now=10L+i*4L
            val pts=1_000L+i*40_000L
            assertTrue(s.observe(stage.AU_RECEIVED,pts,now))
            assertTrue(s.observe(stage.CODEC_INPUT,pts,now))
            assertTrue(s.observe(stage.CODEC_OUTPUT,pts,now))
            assertTrue(s.observe(stage.SURFACE_RELEASE,pts,now))
            assertTrue(s.observe(stage.RENDER_CALLBACK,pts,now))
        }
        val snap=s.snapshot(8_010L)
        assertTrue(snap.boundedEvictions > 0L)
        assertEquals(3L,snap.invalidEvents)
        assertNotEquals(StreamCadenceAudit.Status.SOURCE_AU_STALLED,snap.status)
    }

    @Test fun twentyMinuteSyntheticSessionDoesNotAccumulateUnboundedHistory() {
        val m=StreamCadenceAudit()
        var now=0L
        for (i in 0 until 36_000) {
            now=i*33L
            val pts=1_000_000L+i*33_000L
            m.observe(stage.AU_RECEIVED,pts,now)
            m.observe(stage.CODEC_INPUT,pts,now)
            m.observe(stage.CODEC_OUTPUT,pts,now)
            m.observe(stage.SURFACE_RELEASE,pts,now)
            if(i%10==0) m.observe(stage.RENDER_CALLBACK,pts,now)
        }
        val s=m.snapshot(now)
        assertEquals(StreamCadenceAudit.Status.RENDER_CALLBACK_UNCONFIRMED,
            s.status)
        assertTrue(s.elapsedMs > 1_000_000L)
        assertTrue(s.sourceFps!! in 29.0..31.0)
        assertEquals(0L,s.invalidEvents)
    }
}
