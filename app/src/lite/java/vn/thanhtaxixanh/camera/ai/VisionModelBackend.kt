package vn.thanhtaxixanh.camera.ai

import java.io.File

/**
 * Deliberately small playback-only flavor.
 * Never claims a model is executing or produces fabricated predictions.
 */
object VisionModelBackend : VisionRuntimeContract {
    /** Lite intentionally cannot execute neural inference. */
    fun openEngine(model: File): VisionInferenceEngine? = null

    override fun inspect(modelFile: File) = ModelContractReport(
        runtimeAvailable = false,
        validGraph = false,
        detail = "Bản Lite không chứa ONNX Runtime. " +
            "Chọn bản AI để kiểm tra hợp đồng model; " +
            "không có suy luận làn/khoảng cách trong bản Lite."
    )
}
