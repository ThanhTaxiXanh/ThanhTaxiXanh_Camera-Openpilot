package vn.thanhtaxixanh.camera.ai

import java.io.File

/**
 * Owner-authorized, local YUV replay -> exact Android ONNX input/output
 * reference. This is a device-produced measurement, NOT independent
 * ground truth, A500S provenance, calibrated geometry or ADAS certification.
 *
 * Only the FIRST accepted temporal pair is exported, using the same
 * experimental two virtual warps as OfflineYuvFixture.replay(). The
 * generated TTXREF01 may be compared later to an independent CPU ORT run
 * of the EXACT SAME legally usable model, offline without uploading frames.
 */
object OfflineYuvOnnxEvidence {
    data class Generated(
        val vector: ReferenceTensorFixture.Vector,
        val frames: Int,
        val pairs: Int,
        val inputMainSha256: String,
        val inputWideSha256: String
    )

    fun generate(
        sequence: OfflineYuvFixture.Sequence,
        model: File,
        openEngine: (File) -> VisionInferenceEngine?
    ): Generated {
        require(model.isFile && model.length() > 1024L &&
            model.length() <= ModelInstallGuard.MAX_BYTES) {
            "Import an authorized ONNX graph in the AI APK first"
        }
        // Calculate hash of bytes in the installed app-private model. Do not
        // trust a filename, owner-entered SHA or unverified manifest.
        val hash = OfflineModelParityAudit.modelHash(model)
        var capturedImg: FloatArray? = null
        var capturedWide: FloatArray? = null
        val replay = OfflineYuvFixture.replay(sequence) { img, wide ->
            check(capturedImg == null && capturedWide == null)
            VisionInputContract.requireRawStudioByteTensors(img, wide)
            capturedImg = img
            capturedWide = wide
        }
        val img = capturedImg ?: error("No valid temporal YUV pair")
        val wide = capturedWide ?: error("No valid wide YUV tensor")
        // The native ONNX Runtime is owned by the background worker and
        // closed even if inference throws. No outputs reach driving HUD.
        val runner = openEngine(model)
            ?: error("ONNX Runtime unavailable; use AI, not Lite")
        val result = runner.use { engine ->
            engine.run(img, wide)
        }
        require(result.size == SupercomboV027Contract.TENSOR_ELEMENTS &&
            result.all { it.isFinite() }) {
            "ONNX output must contain exactly 1576 finite Float32 values"
        }
        val mainHash = OnnxReferenceAuditReceipt.hashFloat32Be(img)
        val wideHash = OnnxReferenceAuditReceipt.hashFloat32Be(wide)
        check(replay.mainHash == mainHash && replay.wideHash == wideHash) {
            "Preprocessing lineage changed after replay"
        }
        return Generated(
            ReferenceTensorFixture.Vector(hash, img, wide, result),
            replay.frames, replay.pairs, mainHash, wideHash
        )
    }
}
