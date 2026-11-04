package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * These are SYNTHETIC fixtures and fake ONNX engines.
 * A matching self-supplied vector does NOT prove independent model truth.
 */
class OfflineModelParityAuditTest {
    private fun frame(y:Int,atMs:Long)=OfflineYuvFixture.Frame(
        atMs,
        ByteArray(OfflineYuvFixture.Y_BYTES) { y.toByte() },
        ByteArray(OfflineYuvFixture.UV_BYTES) {128.toByte()},
        ByteArray(OfflineYuvFixture.UV_BYTES) {128.toByte()}
    )
    private fun sequence()=OfflineYuvFixture.Sequence(listOf(
        frame(96,1_000L),frame(160,1_080L)))
    private val expected=FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS) {
        (it%17).toFloat()
    }
    private fun engine(onRun:()->Unit={})=object:VisionInferenceEngine {
        override fun run(img:FloatArray,bigImg:FloatArray):FloatArray {
            onRun()
            VisionInputContract.requireRawStudioByteTensors(img,bigImg)
            return expected.copyOf()
        }
        override fun close() {}
    }

    private fun modelTemp():File {
        val dir=Files.createTempDirectory("ttx-parity").toFile()
        return File(dir,"licensed-by-test-only.onnx").also {
            it.writeBytes(ByteArray(4096) { i -> (i*5).toByte() })
        }
    }

    private fun reference(
        sequence:OfflineYuvFixture.Sequence,model:File
    ):ReferenceTensorFixture.Vector {
        var img:FloatArray?=null
        var big:FloatArray?=null
        val replay=OfflineYuvFixture.replay(sequence) { a,b->
            img=a.copyOf(); big=b.copyOf()
        }
        assertEquals(1,replay.pairs)
        return ReferenceTensorFixture.Vector(
            OfflineModelParityAudit.modelHash(model),
            img!!,big!!,expected.copyOf())
    }

    @Test fun exactFirstPairAndRealEnginePortProduceMatchedButUnverified() {
        val model=modelTemp()
        try {
            val seq=sequence()
            val ref=reference(seq,model)
            val ran=AtomicInteger()
            val result=OfflineModelParityAudit.compare(seq,ref,model) {
                engine { ran.incrementAndGet() }
            }
            assertEquals(OfflineModelParityAudit.Status.MATCHED_UNVERIFIED,
                result.status)
            assertTrue(result.matched)
            assertEquals(1,ran.get())
            assertEquals(0,result.inputMismatches)
            assertEquals(0,result.outputMismatches)
            assertTrue(result.note.contains("no real A500S"))
        } finally {model.parentFile?.deleteRecursively()}
    }

    @Test fun wrongModelShaPreventsOpeningAnyEngine() {
        val model=modelTemp()
        try {
            val seq=sequence()
            val ref=reference(seq,model)
            val corrupted=ref.copy(modelHash=ByteArray(32))
            val opens=AtomicInteger()
            val result=OfflineModelParityAudit.compare(seq,corrupted,model) {
                opens.incrementAndGet()
                engine()
            }
            assertEquals(OfflineModelParityAudit.Status.MODEL_HASH_MISMATCH,
                result.status)
            assertEquals(0,opens.get())
        } finally {model.parentFile?.deleteRecursively()}
    }

    @Test fun preprocessingMismatchBlocksEngineEvenWhenGraphShaMatches() {
        val model=modelTemp()
        try {
            val seq=sequence()
            val ref=reference(seq,model)
            val altered=ref.img.copyOf()
            altered[327]=altered[327]+1f
            val opens=AtomicInteger()
            val report=OfflineModelParityAudit.compare(
                seq,ref.copy(img=altered),model) {
                opens.incrementAndGet()
                engine()
            }
            assertEquals(OfflineModelParityAudit.Status.INPUT_MISMATCH,
                report.status)
            assertEquals(1,report.inputMismatches)
            assertEquals("img[327]",report.firstMismatchedInput)
            assertEquals(0,opens.get())
        } finally {model.parentFile?.deleteRecursively()}
    }

    @Test fun wrongBigImageIsDetectedIndependentlyOfMainWarp() {
        val model=modelTemp()
        try {
            val seq=sequence()
            val ref=reference(seq,model)
            val altered=ref.bigImg.copyOf()
            altered[10]=altered[10]+1f
            val result=OfflineModelParityAudit.compare(
                seq,ref.copy(bigImg=altered),model) { engine() }
            assertEquals(OfflineModelParityAudit.Status.INPUT_MISMATCH,result.status)
            assertEquals("big_img[10]",result.firstMismatchedInput)
        } finally {model.parentFile?.deleteRecursively()}
    }

    @Test fun outputMismatchAndMissingRuntimeNeverScorePass() {
        val model=modelTemp()
        try {
            val seq=sequence()
            val ref=reference(seq,model)
            val noEngine=OfflineModelParityAudit.compare(seq,ref,model) {null}
            assertEquals(OfflineModelParityAudit.Status.ENGINE_UNAVAILABLE,
                noEngine.status)
            assertFalse(noEngine.matched)
            val bad=ref.expectedOutput.copyOf()
            bad[77]+=0.2f
            val mismatch=OfflineModelParityAudit.compare(
                seq,ref.copy(expectedOutput=bad),model) {engine()}
            assertEquals(OfflineModelParityAudit.Status.OUTPUT_MISMATCH,
                mismatch.status)
            assertEquals(1,mismatch.outputMismatches)
            assertFalse(mismatch.matched)
        } finally {model.parentFile?.deleteRecursively()}
    }

    @Test fun absentModelRejectsFixtureBeforeInference() {
        val seq=sequence()
        val missing=File("/nonexistent-ttx-test/vision.onnx")
        val ref=ReferenceTensorFixture.Vector(ByteArray(32),
            FloatArray(SupercomboV027Contract.INPUT_ELEMENTS){128f},
            FloatArray(SupercomboV027Contract.INPUT_ELEMENTS){128f},
            expected)
        assertEquals(OfflineModelParityAudit.Status.MISSING_MODEL,
            OfflineModelParityAudit.compare(seq,ref,missing){engine()}.status)
    }
}
