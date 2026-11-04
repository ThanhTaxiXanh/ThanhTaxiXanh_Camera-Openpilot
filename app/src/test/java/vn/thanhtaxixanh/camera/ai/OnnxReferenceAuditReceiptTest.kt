package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Synthetic-only, no claim about real model output or independent origin. */
class OnnxReferenceAuditReceiptTest {
    private fun fixture()=ReferenceTensorFixture.Vector(
        ByteArray(32){(it+1).toByte()},
        FloatArray(SupercomboV027Contract.INPUT_ELEMENTS){128f},
        FloatArray(SupercomboV027Contract.INPUT_ELEMENTS){129f},
        FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS){(it%17).toFloat()}
    )

    @Test fun canonicalBigEndianDigestMatchesKnownRawBytes() {
        val bytes=byteArrayOf(0x3f,0x80.toByte(),0,0,0xc0.toByte(),0,0,0)
        val md=MessageDigest.getInstance("SHA-256")
        val expected=md.digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        assertEquals(expected,OnnxReferenceAuditReceipt.hashFloat32Be(
            floatArrayOf(1f,-2f)))
        assertNotEquals(OnnxReferenceAuditReceipt.hashFloat32Be(
            floatArrayOf(0f)),OnnxReferenceAuditReceipt.hashFloat32Be(
            floatArrayOf(-0f)))
    }

    @Test fun syntheticMatchIsUnverifiedByDesign() {
        val ref=fixture()
        val report=OnnxReferenceAuditReceipt.inspect(ref.modelHash,ref,
            ref.expectedOutput.copyOf())
        assertEquals("MATCHED_UNVERIFIED",report.verdict)
        assertEquals(report.referenceOutputSha256,report.actualOutputSha256)
        val text=report.toText()
        assertTrue(text.startsWith("TTXONNX-AUDIT-01\n"))
        assertTrue(text.contains("reference_independence=UNVERIFIED"))
        assertTrue(text.contains("scope=LOCAL_PARITY_ONLY_NOT_ADAS_ACCEPTANCE"))
        assertFalse(text.contains("verdict=PASS"))
    }

    @Test fun outputMutationIsVisibleAndNotPromoted() {
        val ref=fixture()
        val output=ref.expectedOutput.copyOf()
        output[50]+=0.5f
        val report=OnnxReferenceAuditReceipt.inspect(ref.modelHash,ref,output)
        assertEquals("OUTPUT_MISMATCH",report.verdict)
        assertEquals(1,report.comparison.mismatches)
        assertNotEquals(report.referenceOutputSha256,report.actualOutputSha256)
    }

    @Test fun wrongModelAndNanOutputsFailClosed() {
        val ref=fixture()
        try {
            OnnxReferenceAuditReceipt.inspect(ByteArray(32),ref,ref.expectedOutput)
            fail("Model hash mismatch must throw")
        } catch (_:IllegalArgumentException) {}
        val nonFinite=ref.expectedOutput.copyOf()
        nonFinite[1]=Float.NaN
        try {
            OnnxReferenceAuditReceipt.inspect(ref.modelHash,ref,nonFinite)
            fail("NaN must throw")
        } catch (_:IllegalArgumentException) {}
    }
}
