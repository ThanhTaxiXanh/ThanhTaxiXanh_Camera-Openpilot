package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Synthetic YUV + fake engine only; independent hardware verification absent. */
class OfflineYuvOnnxEvidenceTest {
    private fun sequence(): OfflineYuvFixture.Sequence {
        val y=ByteArray(OfflineYuvFixture.Y_BYTES) { 96.toByte() }
        val u=ByteArray(OfflineYuvFixture.UV_BYTES) { 128.toByte() }
        val v=ByteArray(OfflineYuvFixture.UV_BYTES) { 128.toByte() }
        return OfflineYuvFixture.Sequence(listOf(
            OfflineYuvFixture.Frame(1_000L,y,u,v),
            OfflineYuvFixture.Frame(1_100L,y,u,v)
        ))
    }

    private fun model(): File {
        val f=File.createTempFile("ttx-original-synthetic-", ".onnx")
        f.writeBytes(ByteArray(2048) { (it and 255).toByte() })
        f.deleteOnExit()
        return f
    }

    private class FakeEngine(
        private val expectedLength: Int = SupercomboV027Contract.TENSOR_ELEMENTS
    ): VisionInferenceEngine {
        var calls=0
        var closed=false
        override fun run(img:FloatArray,bigImg:FloatArray):FloatArray {
            calls++
            VisionInputContract.requireRawStudioByteTensors(img,bigImg)
            return FloatArray(expectedLength) { (it % 31).toFloat() / 8f }
        }
        override fun close() { closed=true }
    }

    @Test fun deviceGeneratedReferenceUsesExactModelBytesAndRoundTrips() {
        val m=model()
        try {
            val runner=FakeEngine()
            val outcome=OfflineYuvOnnxEvidence.generate(sequence(),m) { runner }
            assertEquals(1,runner.calls)
            assertTrue(runner.closed)
            assertEquals(2,outcome.frames)
            assertEquals(1,outcome.pairs)
            assertArrayEquals(OfflineModelParityAudit.modelHash(m),
                outcome.vector.modelHash)
            val bytes=ByteArrayOutputStream()
            ReferenceTensorFixture.write(outcome.vector,bytes)
            assertEquals(ReferenceTensorFixture.EXPECTED_BYTES,
                bytes.size().toLong())
            val parsed=ReferenceTensorFixture.read(
                ByteArrayInputStream(bytes.toByteArray()))
            assertArrayEquals(outcome.vector.modelHash,parsed.modelHash)
            assertArrayEquals(outcome.vector.img,parsed.img,0f)
            assertArrayEquals(outcome.vector.bigImg,parsed.bigImg,0f)
            assertArrayEquals(outcome.vector.expectedOutput,
                parsed.expectedOutput,0f)
            assertEquals(outcome.inputMainSha256,
                OnnxReferenceAuditReceipt.hashFloat32Be(parsed.img))
            assertEquals(outcome.inputWideSha256,
                OnnxReferenceAuditReceipt.hashFloat32Be(parsed.bigImg))
            assertEquals(1576,parsed.expectedOutput.size)
        } finally { m.delete() }
    }

    @Test fun noModelDoesNotRunAnyEngine() {
        val missing=File("missing-ttx-model-should-not-exist.onnx")
        var calls=0
        try {
            OfflineYuvOnnxEvidence.generate(sequence(),missing) {
                calls++; FakeEngine()
            }
            fail("Absent model must be rejected")
        } catch (_:IllegalArgumentException) {}
        assertEquals(0,calls)
    }

    @Test fun liteWithoutOnnxNeverProducesReference() {
        val m=model()
        try {
            try {
                OfflineYuvOnnxEvidence.generate(sequence(),m) { null }
                fail("No engine in Lite")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("ONNX Runtime"))
            }
        } finally { m.delete() }
    }

    @Test fun wrongOutputTensorLengthNeverCreatesReference() {
        val m=model()
        val runner=FakeEngine(10)
        try {
            try {
                OfflineYuvOnnxEvidence.generate(sequence(),m) { runner }
                fail("Wrong ONNX output shape")
            } catch (_:IllegalArgumentException) {}
            assertTrue(runner.closed)
        } finally { m.delete() }
    }

    @Test fun badModelHashOrNonfiniteOutputRejectsBeforeBinaryWriting() {
        val valid=FloatArray(SupercomboV027Contract.INPUT_ELEMENTS) { 16f }
        val output=FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS) { 0f }
        val badHash=ReferenceTensorFixture.Vector(ByteArray(31),
            valid,valid,output)
        val stream=ByteArrayOutputStream()
        try {
            ReferenceTensorFixture.write(badHash,stream)
            fail("Wrong SHA size")
        } catch (_:IllegalArgumentException) {}
        assertEquals(0,stream.size())
        output[0]=Float.NaN
        try {
            ReferenceTensorFixture.write(
                ReferenceTensorFixture.Vector(ByteArray(32),valid,valid,output),
                stream)
            fail("NaN must fail")
        } catch (_:IllegalArgumentException) {}
        assertEquals(0,stream.size())
    }

    @Test fun writtenReferenceRejectsTrailingBytesAndChangedMagic() {
        val valid=FloatArray(SupercomboV027Contract.INPUT_ELEMENTS) { 16f }
        val output=FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS) { 0f }
        val buffer=ByteArrayOutputStream()
        ReferenceTensorFixture.write(
            ReferenceTensorFixture.Vector(ByteArray(32),valid,valid,output),
            buffer)
        val clean=buffer.toByteArray()
        try {
            ReferenceTensorFixture.read(
                ByteArrayInputStream(clean+byteArrayOf(0)))
            fail("Trailing evidence bytes must fail")
        } catch (_:IllegalArgumentException) {}
        clean[0]=0x00
        try {
            ReferenceTensorFixture.read(ByteArrayInputStream(clean))
            fail("Modified TTXREF01 magic must fail")
        } catch (_:IllegalArgumentException) {}
    }
}
