package vn.thanhtaxixanh.camera.ai

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Failure-atomic import for a USER-AUTHORIZED model.
 *
 * Incoming bytes are staged inside the same app-private directory and
 * inspected via the flavor's actual ONNX graph verifier *before* they
 * replace the last valid model. A failed import leaves the old file alone.
 *
 * SHA-256 verifies copied bytes, not model copyright, training provenance,
 * camera calibration, model quality or safety-critical ADAS performance.
 */
object ModelInstallGuard {
    const val MAX_BYTES=250L*1024L*1024L

    data class Receipt(
        val bytes:Long,
        val sha256:String,
        val graphDetail:String,
        val note:String =
            "Graph import only; no source-rights verification or ADAS readiness"
    )

    private fun sha(file:File):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buffer=ByteArray(65536)
            while(true) {
                val n=source.read(buffer)
                if(n<0) break
                if(n>0) digest.update(buffer,0,n)
            }
        }
        return digest.digest()
    }

    @Synchronized fun install(
        input:InputStream,
        destination:File,
        graphCheck:(File)->ModelContractReport,
        userAffirmsRights:Boolean,
        maxBytes:Long=MAX_BYTES
    ):Receipt {
        require(userAffirmsRights) {
            "Chưa xác nhận quyền sử dụng model được nhập"
        }
        require(maxBytes in 1025L..MAX_BYTES)
        val directory=destination.absoluteFile.parentFile
            ?: error("No model directory")
        require(directory.isDirectory || directory.mkdirs()) {
            "Cannot create private model directory"
        }
        val stage=File.createTempFile("vision-import-", ".part",directory)
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            var count=0L
            input.use { source ->
                stage.outputStream().buffered().use { target ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        val n=source.read(buffer)
                        if(n<0) break
                        if(n==0) continue
                        count+=n
                        require(count<=maxBytes) { "Model quá giới hạn kích thước" }
                        digest.update(buffer,0,n)
                        target.write(buffer,0,n)
                    }
                }
            }
            require(count>1024L) { "Model ONNX quá nhỏ hoặc rỗng" }
            val hash=digest.digest()
            require(MessageDigest.isEqual(hash,sha(stage))) {
                "Model sao chép ra đĩa không trùng SHA-256 nguồn"
            }
            val report=graphCheck(stage)
            require(report.runtimeAvailable && report.validGraph) {
                "Không thể xác nhận graph ONNX: " + report.detail.take(200)
            }
            // Same-directory, atomic replacement. If the filesystem does
            // not support atomic rename, abort without deleting old model.
            Files.move(stage.toPath(),destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            return Receipt(count,hash.joinToString("") {
                "%02x".format(it.toInt() and 255)
            },report.detail)
        } finally {
            stage.delete()
        }
    }
}
