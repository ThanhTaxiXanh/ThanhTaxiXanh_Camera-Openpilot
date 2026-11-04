package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelInstallGuardTest {
    private fun bytes(seed:Int)=ByteArray(4096) { i->((i*31+seed) and 255).toByte() }
    private val accepted:(File)->ModelContractReport={
        file -> ModelContractReport(true,file.length()==4096L,
            "Synthetic shape check only; not a genuine ONNX graph")
    }

    @Test fun importedBytesAreHashedAndAtomicallyInstalledOnlyAfterGraphPasses() {
        val dir=Files.createTempDirectory("ttx-model-stage").toFile()
        try {
            val model=File(dir,"vision.onnx")
            val original=bytes(11)
            model.writeBytes(original)
            val incoming=bytes(23)
            val receipt=ModelInstallGuard.install(
                ByteArrayInputStream(incoming),model,accepted,true)
            assertArrayEquals(incoming,model.readBytes())
            assertEquals(4096L,receipt.bytes)
            val hex=MessageDigest.getInstance("SHA-256")
                .digest(incoming).joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
            assertEquals(hex,receipt.sha256)
            assertTrue(receipt.note.contains("no source-rights verification"))
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidGraphNeverDeletesExistingGoodModel() {
        val dir=Files.createTempDirectory("ttx-invalid-model").toFile()
        try {
            val model=File(dir,"vision.onnx")
            val old=bytes(7)
            model.writeBytes(old)
            try {
                ModelInstallGuard.install(ByteArrayInputStream(bytes(31)),
                    model,{ModelContractReport(true,false,"Wrong graph shape")},true)
                fail("Graph rejection required")
            } catch (_:IllegalArgumentException) {}
            assertArrayEquals(old,model.readBytes())
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingRightsAndMissingRuntimeFailClosedWithoutReplacingOldModel() {
        val dir=Files.createTempDirectory("ttx-model-no-rights").toFile()
        try {
            val model=File(dir,"vision.onnx")
            val old=bytes(7)
            model.writeBytes(old)
            try {
                ModelInstallGuard.install(
                    ByteArrayInputStream(bytes(33)),model,accepted,false)
                fail("User attestation required")
            } catch (_:IllegalArgumentException) {}
            try {
                ModelInstallGuard.install(
                    ByteArrayInputStream(bytes(33)),model,
                    {ModelContractReport(false,false,"Lite lacks ONNX")},true)
                fail("Lite must not replace model without validation")
            } catch (_:IllegalArgumentException) {}
            assertArrayEquals(old,model.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptedOrOversizeImportsDoNotEraseValidModel() {
        val dir=Files.createTempDirectory("ttx-model-large").toFile()
        try {
            val model=File(dir,"vision.onnx")
            val old=bytes(21)
            model.writeBytes(old)
            for (size in listOf(0,1024,4096)) {
                try {
                    ModelInstallGuard.install(
                        ByteArrayInputStream(ByteArray(size)),model,accepted,
                        true,maxBytes=2000L)
                    fail("Expected input-length rejection")
                } catch (_:IllegalArgumentException) {}
                assertArrayEquals(old,model.readBytes())
            }
        } finally { dir.deleteRecursively() }
    }
}
