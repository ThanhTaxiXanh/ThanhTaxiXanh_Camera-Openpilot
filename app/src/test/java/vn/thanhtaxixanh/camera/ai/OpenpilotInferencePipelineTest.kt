package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenpilotInferencePipelineTest {
    private class FakeEngine : VisionInferenceEngine {
        @Volatile var calls = 0
        override fun run(img:FloatArray,bigImg:FloatArray):FloatArray {
            assertEquals(SupercomboV027Contract.INPUT_ELEMENTS,img.size)
            assertEquals(SupercomboV027Contract.INPUT_ELEMENTS,bigImg.size)
            calls++
            return FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS)
        }
        override fun close() {}
    }

    @Test fun endToEndSyntheticImagePairIsParsedButNotShownAsCalibrated() {
        val now=42_000L
        val engine=FakeEngine()
        val latch=CountDownLatch(1)
        var output:OpenpilotInferencePipeline.Outcome?=null
        val p=OpenpilotInferencePipeline(
            engine,VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(),null,512,256,
            nowMs={now}) {
                output=it
                latch.countDown()
            }
        try {
            val previous=IntArray(512*256) { 0xff101010.toInt() }
            val current=IntArray(512*256) { 0xffd0d0d0.toInt() }
            assertTrue(p.offer(OpenpilotInferencePipeline.PairSample(
                previous,current,now-40)))
            assertTrue(latch.await(5,TimeUnit.SECONDS))
            assertNotNull(output?.model)
            assertEquals(4,output!!.model!!.lanes.size)
            assertTrue(output!!.projectedLanes.isEmpty())
            assertTrue(output!!.status.startsWith("RAW_ONLY"))
            assertEquals(1L,p.metrics().processed)
        } finally { p.close() }
    }

    @Test fun rejectsStaleAndBadDimensionFramesBeforeInference() {
        val engine=FakeEngine()
        val p=OpenpilotInferencePipeline(engine,
            VirtualCameraWarp.Matrix.identity(),
            VirtualCameraWarp.Matrix.identity(),null,512,256,
            nowMs={9000L}) {}
        try {
            assertFalse(p.offer(OpenpilotInferencePipeline.PairSample(
                IntArray(10),IntArray(10),9000L)))
            // Expired input is rejected immediately at producer admission,
            // not counted as an accepted queue item for a worker to drop.
            assertFalse(p.offer(OpenpilotInferencePipeline.PairSample(
                IntArray(512*256),IntArray(512*256),1000L)))
            assertEquals(0L,p.metrics().received)
            assertEquals(0,engine.calls)
            assertTrue(p.metrics().stale>=1)
        } finally { p.close() }
    }
}
