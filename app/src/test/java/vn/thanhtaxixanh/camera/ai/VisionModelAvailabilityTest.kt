package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test

class VisionModelAvailabilityTest {
    @Test fun modelContractIsNotEqualToSafetyCalibration() {
        val absent = VisionSafetyGate.assess(
            null, null, nowElapsedMs = 1_000L, speedMetersPerSecond = 15f)
        assertFalse(absent.mayWarn)
        assertNull(absent.leadDistanceMeters)
    }
    @Test fun noModelFileCannotPretendToHaveAUsableAI() {
        val report = VisionModelBackend.inspect(java.io.File("missing.vision.onnx"))
        assertFalse(report.validGraph)
    }
}
