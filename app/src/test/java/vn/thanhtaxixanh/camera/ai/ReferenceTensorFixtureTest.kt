package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Synthetic vectors created here exercise the parser only; they are not a
 * genuine model-reference corpus or authorization to redistribute weights.
 */
class ReferenceTensorFixtureTest {
    private fun bytes(): ByteArray {
        val output=ByteArrayOutputStream(ReferenceTensorFixture.EXPECTED_BYTES.toInt())
        DataOutputStream(output).use { writer ->
            writer.write("TTXREF01".toByteArray(Charsets.US_ASCII))
            writer.write(ByteArray(32) { it.toByte() })
            repeat(SupercomboV027Contract.INPUT_ELEMENTS * 2) {
                writer.writeFloat(128f)
            }
            repeat(SupercomboV027Contract.TENSOR_ELEMENTS) {
                writer.writeFloat((it % 17).toFloat())
            }
        }
        return output.toByteArray()
    }

    @Test fun fixedSizeReferenceReadsBothFloat32ImagesAndExpectedModelOutput() {
        val data=bytes()
        assertEquals(ReferenceTensorFixture.EXPECTED_BYTES,data.size.toLong())
        val fixture=ReferenceTensorFixture.read(ByteArrayInputStream(data))
        assertEquals(0,fixture.modelHash[0].toInt())
        assertEquals(31,fixture.modelHash[31].toInt())
        assertEquals(128f,fixture.img[0],0f)
        assertEquals(128f,fixture.bigImg.last(),0f)
        assertEquals(16f,fixture.expectedOutput[16],0f)
        assertEquals(0f,fixture.expectedOutput[17],0f)
    }

    @Test fun invalidMagicTruncatedOrAppendedFilesAreRejected() {
        val valid=bytes()
        val wrongMagic=valid.copyOf().also { it[0] = 'X'.code.toByte() }
        expectFailure(wrongMagic)
        expectFailure(valid.copyOf(1024))
        expectFailure(valid + byteArrayOf(77))
    }

    @Test fun normalizedInputAndNanModelReferenceAreRejected() {
        val raw=bytes()
        val badInput=raw.copyOf()
        // Header 8 bytes + SHA-256 32 bytes, then big-endian float32.
        ByteBuffer.wrap(badInput).putFloat(40,0.5f)
        expectFailure(badInput)
        val badOutput=raw.copyOf()
        val offset=40 + SupercomboV027Contract.INPUT_ELEMENTS * 2 * 4
        ByteBuffer.wrap(badOutput).putFloat(offset,Float.NaN)
        expectFailure(badOutput)
    }

    @Test fun numericComparisonHonorsCombinedAbsoluteAndRelativeTolerance() {
        val expected=FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS) { 1.0f }
        val actual=expected.copyOf()
        actual[13]=1.0015f // below 0.001 absolute + 0.001 relative
        val pass=ReferenceTensorFixture.compare(expected,actual)
        assertTrue(pass.pass)
        assertEquals(0,pass.mismatches)
        actual[14]=1.02f
        val fail=ReferenceTensorFixture.compare(expected,actual)
        assertFalse(fail.pass)
        assertEquals(1,fail.mismatches)
        assertEquals(14,fail.worstIndex)
        assertEquals(.02,fail.maxAbsoluteError,.000001)
        actual[14]=Float.POSITIVE_INFINITY
        try {
            ReferenceTensorFixture.compare(expected,actual)
            fail("Nonfinite ONNX output must never score PASS")
        } catch (_: IllegalArgumentException) {}
    }

    private fun expectFailure(data: ByteArray) {
        try {
            ReferenceTensorFixture.read(ByteArrayInputStream(data))
            fail("Malformed or untrusted reference must not be accepted")
        } catch (_: Exception) {}
    }
}
