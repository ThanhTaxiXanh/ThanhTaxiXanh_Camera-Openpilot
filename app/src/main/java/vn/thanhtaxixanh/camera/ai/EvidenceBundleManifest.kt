package vn.thanhtaxixanh.camera.ai

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant

/**
 * TTXEVD01: external, SELF-DECLARED evidence manifest.
 *
 * Cross-checks SHA-256 for the selected TTXYUV01, TTXREF01 and the
 * installed ONNX, and checks that TTXREF01 embeds that exact ONNX hash.
 * Every manifest field may be authored by its submitter; this is NOT
 * proof of provenance, copyright license, camera identity, ground
 * truth, signed attestation or real optical latency.
 */
object EvidenceBundleManifest {
    const val MAX_MANIFEST_BYTES=2048
    private val idPattern=Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{2,95}")
    private val hashPattern=Regex("[0-9a-f]{64}")
    private val timePattern=Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")

    data class Metadata(
        val sourceId:String,
        val recordedUtc:String,
        val instrumentId:String,
        val permissionId:String,
        val yuvSha256:String,
        val refSha256:String,
        val modelSha256:String
    )
    enum class Status {
        MATCHED_SELF_DECLARED,
        YUV_HASH_MISMATCH,
        REFERENCE_HASH_MISMATCH,
        MODEL_MISSING,
        MODEL_HASH_MISMATCH,
        REFERENCE_MODEL_MISMATCH
    }
    data class Report(
        val status:Status,
        val metadata:Metadata,
        val note:String =
            "SHA lineage matches only submitter-declared records. NO external identity, license, optical camera, reference ground truth, signed attestation or ADAS validation"
    ) {
        val hashesMatch:Boolean get()=status==Status.MATCHED_SELF_DECLARED
        val provenanceVerified:Boolean get()=false
        val permitsAdas:Boolean get()=false
    }

    /**
     * Fixed ASCII UTF-8 LF protocol, exactly eight newline-delimited
     * records and no ignored unknown fields or duplicated keys:
     *
     * TTXEVD01
     * source_id=...
     * recorded_utc=YYYY-MM-DDTHH:MM:SSZ
     * instrument_id=...
     * permission_id=...
     * yuv_sha256=<64 lower hex>
     * ref_sha256=<64 lower hex>
     * model_sha256=<64 lower hex>
     */
    fun read(input:InputStream):Metadata {
        val bytes=ByteArrayOutputStream()
        val buffer=ByteArray(256)
        while(true) {
            val n=input.read(buffer)
            if(n<0) break
            if(n==0) continue
            require(bytes.size()+n<=MAX_MANIFEST_BYTES) {
                "Evidence manifest over 2 KiB"
            }
            bytes.write(buffer,0,n)
        }
        val raw=bytes.toByteArray()
        require(raw.isNotEmpty() && raw.all {
            val x=it.toInt() and 255
            x==10 || x in 32..126
        }) { "Only printable ASCII and LF in TTXEVD01" }
        val lines=raw.toString(Charsets.US_ASCII).removeSuffix("\n").split("\n")
        require(lines.size==8 && lines[0]=="TTXEVD01") {
            "Expected canonical eight-line TTXEVD01"
        }
        fun field(index:Int,key:String):String {
            val prefix="$key="
            require(lines[index].startsWith(prefix)) { "Expected $key" }
            val value=lines[index].removePrefix(prefix)
            require(value.isNotEmpty()) { "$key must not be blank" }
            return value
        }
        val source=field(1,"source_id")
        val at=field(2,"recorded_utc")
        val instrument=field(3,"instrument_id")
        val permission=field(4,"permission_id")
        require(idPattern.matches(source) && idPattern.matches(instrument) &&
            idPattern.matches(permission)) { "Invalid evidence source/instrument/permission identifier" }
        val canonicalUtc=if(timePattern.matches(at)) {
            try { Instant.parse(at).toString()==at }
            catch (_:java.time.format.DateTimeParseException) { false }
        } else false
        require(canonicalUtc) { "Non-canonical recorded UTC timestamp" }
        val y=field(5,"yuv_sha256")
        val v=field(6,"ref_sha256")
        val m=field(7,"model_sha256")
        require(hashPattern.matches(y) && hashPattern.matches(v) &&
            hashPattern.matches(m)) { "Expected lowercase SHA-256 hex" }
        return Metadata(source,at,instrument,permission,y,v,m)
    }

    /** Constant-memory SHA-256 of bounded user-supplied evidence bytes. */
    private fun digest(input:InputStream,maxBytes:Long):ByteArray {
        val hash=MessageDigest.getInstance("SHA-256")
        var total=0L
        val buffer=ByteArray(65536)
        while(true) {
            val n=input.read(buffer)
            if(n<0) break
            if(n==0) continue
            total+=n
            require(total<=maxBytes) { "Evidence file exceeds allowed size" }
            hash.update(buffer,0,n)
        }
        require(total>0L) { "Empty evidence file" }
        return hash.digest()
    }

    fun verify(
        metadata:Metadata,
        yuv:InputStream,
        reference:InputStream,
        model:File
    ):Report {
        val actualYuv=yuv.use { digest(it,OfflineYuvFixture.MAX_FILE_BYTES) }
        if(!MessageDigest.isEqual(actualYuv,fromHex(metadata.yuvSha256))) {
            reference.close()
            return Report(Status.YUV_HASH_MISMATCH,metadata)
        }
        val actualRef=reference.use { digest(it,ReferenceTensorFixture.EXPECTED_BYTES) }
        if(!MessageDigest.isEqual(actualRef,fromHex(metadata.refSha256)))
            return Report(Status.REFERENCE_HASH_MISMATCH,metadata)
        if(!model.isFile || model.length()<=1024L ||
            model.length()>ModelInstallGuard.MAX_BYTES)
            return Report(Status.MODEL_MISSING,metadata)
        val actualModel=model.inputStream().use {
            digest(it,ModelInstallGuard.MAX_BYTES)
        }
        if(!MessageDigest.isEqual(actualModel,fromHex(metadata.modelSha256)))
            return Report(Status.MODEL_HASH_MISMATCH,metadata)
        // Caller must additionally ensure the *reference format* has
        // already been parsed; this compares its embedded model SHA.
        return Report(Status.MATCHED_SELF_DECLARED,metadata)
    }

    fun verifyEmbeddedReferenceModel(
        report:Report,reference:ReferenceTensorFixture.Vector
    ):Report {
        if(!report.hashesMatch) return report
        return if(MessageDigest.isEqual(
            reference.modelHash,fromHex(report.metadata.modelSha256))) report
        else Report(Status.REFERENCE_MODEL_MISMATCH,report.metadata)
    }

    private fun fromHex(text:String):ByteArray {
        require(hashPattern.matches(text)) { "Invalid SHA-256" }
        return ByteArray(32) { i ->
            text.substring(i*2,i*2+2).toInt(16).toByte()
        }
    }
}
