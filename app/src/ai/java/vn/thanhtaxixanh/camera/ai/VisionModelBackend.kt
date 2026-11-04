package vn.thanhtaxixanh.camera.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Licensed-model-only ONNX engine in the AI product flavor.
 *
 * A valid model graph is necessary but NOT sufficient for usable ADAS.
 * No decoder frames are passed until camera warping, temporal YUV packing,
 * coordinate units and calibration have been validated separately.
 */
object VisionModelBackend : VisionRuntimeContract {
    /** Only the AI flavor can load an authorized model for true CPU inference. */
    fun openEngine(model: File): VisionInferenceEngine = OnnxVisionEngine.open(model)

    override fun inspect(modelFile: File): ModelContractReport {
        if (!modelFile.isFile || modelFile.length() == 0L)
            return ModelContractReport(true, false, "Chưa có model ONNX để kiểm tra")
        return try {
            OnnxVisionEngine.open(modelFile).use {
                ModelContractReport(
                    true, true,
                    "ONNX Runtime đã nạp mô hình. Đúng hai input " +
                        "[1,12,128,256] và output [1,1576]. " +
                        "Chưa xác minh ảnh YUV/warp, hiệu chuẩn hay AI realtime."
                )
            }
        } catch (error: Exception) {
            ModelContractReport(
                true, false,
                "Model không tương thích với xADAS v0.2.7: " +
                    (error.message ?: error.javaClass.simpleName).take(240)
            )
        }
    }
}

class OnnxVisionEngine private constructor(
    private val environment: OrtEnvironment,
    private val options: OrtSession.SessionOptions,
    private val session: OrtSession
) : VisionInferenceEngine {
    companion object {
        private val inputShape = longArrayOf(1, 12, 128, 256)
        private val outputShape = longArrayOf(1, 1576)

        fun open(model: File): OnnxVisionEngine {
            require(model.isFile && model.length() > 1024L)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            var session: OrtSession? = null
            try {
                options.setIntraOpNumThreads(2)
                session = env.createSession(model.absolutePath, options)
                val inputs = session.inputInfo
                val outputs = session.outputInfo
                require(inputs.keys == setOf("img", "big_img")) {
                    "Model phải có đúng hai input img và big_img."
                }
                require(outputs.containsKey("vout_pass")) {
                    "Model không có vout_pass."
                }
                // Shape-only matching accepts FLOAT16/INT32 models that cannot
                // consume our FloatBuffer input or return meaningful float32.
                fun hasFloat32Shape(info: TensorInfo?, shape: LongArray): Boolean =
                    info != null && info.type == OnnxJavaType.FLOAT &&
                        info.shape.contentEquals(shape)
                require(hasFloat32Shape(inputs["img"]?.info as? TensorInfo, inputShape)) {
                    "img must be FLOAT32 [1,12,128,256]"
                }
                require(hasFloat32Shape(inputs["big_img"]?.info as? TensorInfo, inputShape)) {
                    "big_img must be FLOAT32 [1,12,128,256]"
                }
                require(hasFloat32Shape(outputs["vout_pass"]?.info as? TensorInfo, outputShape)) {
                    "vout_pass must be FLOAT32 [1,1576]"
                }
                return OnnxVisionEngine(env, options, session)
            } catch (t: Throwable) {
                try { session?.close() } catch (_: Throwable) {}
                try { options.close() } catch (_: Throwable) {}
                throw t
            }
        }
    }

    /** Not invoked from a video callback; callers must run on an inference worker. */
    @Synchronized
    override fun run(img: FloatArray, bigImg: FloatArray): FloatArray {
        require(img.size == SupercomboV027Contract.INPUT_ELEMENTS)
        require(bigImg.size == SupercomboV027Contract.INPUT_ELEMENTS)
        VisionInputContract.requireRawStudioByteTensors(img, bigImg)
        fun tensor(data: FloatArray): OnnxTensor {
            val buf = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(data)
            buf.flip()
            return OnnxTensor.createTensor(
                environment, buf, longArrayOf(1, 12, 128, 256))
        }
        tensor(img).use { image ->
            tensor(bigImg).use { wide ->
                session.run(mapOf("img" to image, "big_img" to wide)).use { output ->
                    val result = output.get("vout_pass").orElseThrow {
                        IllegalStateException("Không tìm thấy vout_pass")
                    } as? OnnxTensor ?: error("Output không phải float32 tensor")
                    val buffer = result.floatBuffer
                    buffer.rewind()
                    val data = FloatArray(buffer.remaining())
                    buffer.get(data)
                    val inspected = SupercomboV027Contract.inspect(data)
                    require(inspected.allFinite) { inspected.note }
                    return data
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        session.close()
        options.close()
    }
}
