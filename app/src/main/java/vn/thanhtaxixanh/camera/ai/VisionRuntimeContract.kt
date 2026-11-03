package vn.thanhtaxixanh.camera.ai

import java.io.Closeable
import java.io.File

/** Engine-neutral contract used by the Lite and AI Android build variants. */
data class ModelContractReport(
    val runtimeAvailable: Boolean,
    val validGraph: Boolean,
    val detail: String
)

/** A real model inference port. Supplying fake lane geometry is forbidden. */
interface VisionInferenceEngine : Closeable {
    fun run(img: FloatArray, bigImg: FloatArray): FloatArray
}

/**
 * Exactly one implementation is built per product flavor:
 * src/lite/ has NO native ONNX library; src/ai/ links ONNX Runtime.
 */
interface VisionRuntimeContract {
    fun inspect(modelFile: File): ModelContractReport
}
