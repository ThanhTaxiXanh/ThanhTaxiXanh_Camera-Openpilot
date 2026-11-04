package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class EvidenceBundleManifestTest {
    private fun bytes(seed:Int,size:Int)=ByteArray(size) {i->((i*73+seed) and 255).toByte()}
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun doc(yuv:ByteArray,ref:ByteArray,model:ByteArray)=(
        listOf("TTXEVD01",
            "source_id=A500S-OPERATOR-CLAIM-001",
            "recorded_utc=2026-11-04T14:35:00Z",
            "instrument_id=SELF-CLAIMED-CAMERA-DEVICE",
            "permission_id=USER-AFFIRMATION-NOT-VERIFIED",
            "yuv_sha256="+hash(yuv),
            "ref_sha256="+hash(ref),
            "model_sha256="+hash(model)).joinToString("\n")+"\n"
        ).toByteArray(Charsets.US_ASCII)

    private fun withModel(test:(File)->Unit) {
        val folder=Files.createTempDirectory("ttx-evidence").toFile()
        val model=File(folder,"vision.onnx")
        model.writeBytes(bytes(71,4096))
        try {test(model)} finally {folder.deleteRecursively()}
    }

    @Test fun matchingHashesRemainSelfDeclaredAndNeverAuthorizeAdas()=withModel { model ->
        val yuv=bytes(5,700)
        val ref=bytes(7,1230)
        val record=doc(yuv,ref,model.readBytes())
        val metadata=EvidenceBundleManifest.read(ByteArrayInputStream(record))
        assertEquals("2026-11-04T14:35:00Z",metadata.recordedUtc)
        val result=EvidenceBundleManifest.verify(
            metadata,ByteArrayInputStream(yuv),ByteArrayInputStream(ref),model)
        assertEquals(EvidenceBundleManifest.Status.MATCHED_SELF_DECLARED,result.status)
        assertTrue(result.hashesMatch)
        assertFalse(result.provenanceVerified)
        assertFalse(result.permitsAdas)
        assertTrue(result.note.contains("NO external identity"))
    }

    @Test fun substitutionOfEitherFileOrModelIsRejected()=withModel { model ->
        val yuv=bytes(5,700)
        val ref=bytes(7,1230)
        val metadata=EvidenceBundleManifest.read(
            ByteArrayInputStream(doc(yuv,ref,model.readBytes())))
        val newYuv=yuv.copyOf().apply {this[32]=0}
        val alteredY=EvidenceBundleManifest.verify(metadata,
            ByteArrayInputStream(newYuv),ByteArrayInputStream(ref),model)
        assertEquals(EvidenceBundleManifest.Status.YUV_HASH_MISMATCH,
            alteredY.status)
        val alteredR=EvidenceBundleManifest.verify(metadata,
            ByteArrayInputStream(yuv),ByteArrayInputStream(ref.copyOf().apply {this[15]=0}),model)
        assertEquals(EvidenceBundleManifest.Status.REFERENCE_HASH_MISMATCH,
            alteredR.status)
        model.writeBytes(bytes(91,4096))
        val alteredM=EvidenceBundleManifest.verify(metadata,
            ByteArrayInputStream(yuv),ByteArrayInputStream(ref),model)
        assertEquals(EvidenceBundleManifest.Status.MODEL_HASH_MISMATCH,
            alteredM.status)
    }

    @Test fun embeddedReferenceMustMatchSameModelEvenIfManifestFileHashesMatch() =
        withModel { model ->
            val yuv=bytes(5,700)
            val ref=bytes(7,1230)
            val metadata=EvidenceBundleManifest.read(
                ByteArrayInputStream(doc(yuv,ref,model.readBytes())))
            val ok=EvidenceBundleManifest.verify(metadata,
                ByteArrayInputStream(yuv),ByteArrayInputStream(ref),model)
            val fakeVector=ReferenceTensorFixture.Vector(ByteArray(32),
                FloatArray(SupercomboV027Contract.INPUT_ELEMENTS),
                FloatArray(SupercomboV027Contract.INPUT_ELEMENTS),
                FloatArray(SupercomboV027Contract.TENSOR_ELEMENTS))
            val broken=EvidenceBundleManifest.verifyEmbeddedReferenceModel(ok,fakeVector)
            assertEquals(EvidenceBundleManifest.Status.REFERENCE_MODEL_MISMATCH,broken.status)
            assertFalse(broken.permitsAdas)
            val actualHash=MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            val matching=EvidenceBundleManifest.verifyEmbeddedReferenceModel(
                ok,fakeVector.copy(modelHash=actualHash))
            assertTrue(matching.hashesMatch)
            assertFalse(matching.provenanceVerified)
        }

    @Test fun malformedManifestRejectedWithoutSilentlySkippingOrOverwritingFields() {
        val yuv=bytes(1,100)
        val ref=bytes(2,100)
        val model=bytes(3,4096)
        val valid=doc(yuv,ref,model)
        fun reject(payload:ByteArray) {
            try {
                EvidenceBundleManifest.read(ByteArrayInputStream(payload))
                fail("invalid manifest accepted")
            } catch (_:IllegalArgumentException) {}
        }
        reject(valid.copyOfRange(0,valid.size-15))
        reject(valid+"extra=ignored\n".toByteArray())
        reject(valid.copyOf().apply {this[0]='X'.code.toByte()})
        reject(valid.copyOf().apply {
            val str=toString(Charsets.US_ASCII)
            val start=str.indexOf("source_id=")
            this[start]=0x01
        })
        reject(valid.toString(Charsets.US_ASCII)
            .replace("yuv_sha256=","YUV_SHA256=").toByteArray())
        reject(valid.toString(Charsets.US_ASCII)
            .replace("recorded_utc=2026-11-04T14:35:00Z",
                "recorded_utc=2026-11-04T25:00:00Z").toByteArray())
        reject(valid.toString(Charsets.US_ASCII)
            .replace("permission_id=USER-AFFIRMATION-NOT-VERIFIED",
                "permission_id=not allowed spaces").toByteArray())
        reject(ByteArray(EvidenceBundleManifest.MAX_MANIFEST_BYTES+1) {
            'A'.code.toByte()
        })
    }

    @Test fun oversizedEvidenceRejectedAndModelMissingCannotBecomePass()=withModel { model ->
        val yuv=bytes(5,700)
        val ref=bytes(7,1230)
        val meta=EvidenceBundleManifest.read(
            ByteArrayInputStream(doc(yuv,ref,model.readBytes())))
        val missing=File(model.parentFile,"not-there.onnx")
        val noModel=EvidenceBundleManifest.verify(meta,
            ByteArrayInputStream(yuv),ByteArrayInputStream(ref),missing)
        assertEquals(EvidenceBundleManifest.Status.MODEL_MISSING,noModel.status)
        try {
            EvidenceBundleManifest.verify(meta,
                ByteArrayInputStream(ByteArray(OfflineYuvFixture.MAX_FILE_BYTES.toInt()+1)),
                ByteArrayInputStream(ref),model)
            fail("Oversize YUV must be rejected")
        } catch (_:IllegalArgumentException) {}
    }
}
