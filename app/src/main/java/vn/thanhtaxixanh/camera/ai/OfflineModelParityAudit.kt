package vn.thanhtaxixanh.camera.ai

import java.io.File
import java.security.MessageDigest

/**
 * OFFLINE three-way reference check:
 *
 * (1) installed licensed model SHA-256 == TT XREF model SHA-256,
 * (2) actual YUV -> first temporal pair -> dual warp -> model-input
 *     FLOAT32 tensors == user-supplied TT XREF tensor inputs,
 * (3) actual ONNX inference output == user-supplied TT XREF expected output.
 *
 * This is a reproduction test against a caller-supplied self-declared
 * reference. Even a MATCHED report does NOT establish independent truth,
 * camera intrinsics, lead range or authorized xADAS model parity.
 *
 * No models or frames are bundled, uploaded, recorded, or passed to HUD.
 * The invoking activity must use a background worker.
 */
object OfflineModelParityAudit {
    enum class Status {
        MISSING_MODEL, MODEL_HASH_MISMATCH, INPUT_MISMATCH,
        ENGINE_UNAVAILABLE, OUTPUT_MISMATCH, MATCHED_UNVERIFIED
    }

    data class Report(
        val status: Status,
        val pairs: Int,
        val inputMismatches: Int,
        val firstMismatchedInput: String?,
        val outputMismatches: Int?,
        val maxOutputError: Double?,
        val modelSha256Prefix: String,
        val note: String =
            "Reference is user-supplied; no real A500S frame provenance, optical calibration, ADAS or glass-to-glass proof"
    ) {
        val matched: Boolean get() = status == Status.MATCHED_UNVERIFIED
    }

    fun modelHash(model:File):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer=ByteArray(65536)
            while(true) {
                val n=input.read(buffer)
                if(n<0) break
                if(n>0) digest.update(buffer,0,n)
            }
        }
        return digest.digest()
    }

    private fun equalFloatsExact(
        generated:FloatArray,reference:FloatArray
    ):Pair<Int,Int?> {
        require(generated.size==SupercomboV027Contract.INPUT_ELEMENTS &&
            reference.size==SupercomboV027Contract.INPUT_ELEMENTS)
        var count=0
        var first:Int?=null
        for(i in generated.indices) {
            // Exact bit-parity for the two preprocessing paths; unlike
            // model-output tolerances, tiny pixel-packing differences
            // must not be silently treated as the same input tensors.
            if(generated[i].toRawBits()!=reference[i].toRawBits()) {
                if(first==null) first=i
                count++
            }
        }
        return count to first
    }

    fun compare(
        sequence:OfflineYuvFixture.Sequence,
        reference:ReferenceTensorFixture.Vector,
        model:File,
        openEngine:(File)->VisionInferenceEngine?
    ):Report {
        if(!model.isFile || model.length()<=1024L)
            return Report(Status.MISSING_MODEL,0,0,null,null,null,"")
        val hash=modelHash(model)
        val prefix=hash.joinToString("") { "%02x".format(it.toInt() and 255) }
            .take(16)
        if(!MessageDigest.isEqual(hash,reference.modelHash)) {
            return Report(Status.MODEL_HASH_MISMATCH,0,0,null,null,null,prefix)
        }
        var mismatch=0
        var firstMismatch:String?=null
        var actual:FloatArray?=null
        var noEngine=false
        // No model is executed before graph/model SHA and BOTH
        // preprocessing tensors are independently checked.
        val replay=OfflineYuvFixture.replay(sequence) { img,bigImg ->
            val (a,firstA)=equalFloatsExact(img,reference.img)
            val (b,firstB)=equalFloatsExact(bigImg,reference.bigImg)
            mismatch=a+b
            firstMismatch=when {
                firstA!=null -> "img[$firstA]"
                firstB!=null -> "big_img[$firstB]"
                else -> null
            }
            if(mismatch==0) {
                val engine=openEngine(model)
                if(engine==null) noEngine=true
                else engine.use {
                    actual=it.run(img,bigImg)
                }
            }
        }
        if(mismatch>0)
            return Report(Status.INPUT_MISMATCH,replay.pairs,mismatch,
                firstMismatch,null,null,prefix)
        if(noEngine || actual==null)
            return Report(Status.ENGINE_UNAVAILABLE,replay.pairs,0,null,
                null,null,prefix)
        val result=ReferenceTensorFixture.compare(
            reference.expectedOutput,actual!!)
        return Report(
            if(result.pass) Status.MATCHED_UNVERIFIED else Status.OUTPUT_MISMATCH,
            replay.pairs,0,null,result.mismatches,result.maxAbsoluteError,prefix)
    }
}
