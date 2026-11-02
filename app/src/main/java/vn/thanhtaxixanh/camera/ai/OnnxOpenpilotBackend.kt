package vn.thanhtaxixanh.camera.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real on-device ONNX execution engine for a model the USER is authorized to use.
 *
 * This only validates the model contract and executes already-preprocessed
 * tensors. It does NOT turn arbitrary unwarped video into accurate ADAS.
 * It NEVER bundles or extracts proprietary xADAS weights.
 */
class OnnxOpenpilotBackend private constructor(
    private val env: OrtEnvironment,
    private val options: OrtSession.SessionOptions,
    private val session: OrtSession
) : SupercomboInferencePort, Closeable {
    companion object {
        private val imageShape = longArrayOf(1, 12, 128, 256)
        private val outputShape = longArrayOf(1, 1576)

        @JvmStatic
        fun open(model: File): OnnxOpenpilotBackend {
            require(model.isFile && model.length() > 0L) { "ONNX model file missing" }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            var sess: OrtSession? = null
            try {
                options.setIntraOpNumThreads(2)
                sess = environment.createSession(model.absolutePath, options)
                val inputs = sess.inputInfo
                val out = sess.outputInfo
                require(inputs.keys.containsAll(setOf("img", "big_img"))) {
                    "This ONNX is not the 2-input vision model"
                }
                require(out.containsKey("vout_pass")) {
                    "Missing vout_pass head"
                }
                fun validShape(info: TensorInfo?, expected: LongArray) =
                    info != null && info.shape.contentEquals(expected)
                require(validShape(inputs["img"]?.info as? TensorInfo, imageShape))
                require(validShape(inputs["big_img"]?.info as? TensorInfo, imageShape))
                require(validShape(out["vout_pass"]?.info as? TensorInfo, outputShape)) {
                    "Output shape is not [1,1576]"
                }
                return OnnxOpenpilotBackend(environment, options, sess)
            } catch (failure: Throwable) {
                try { sess?.close() } catch (_: Exception) {}
                try { options.close() } catch (_: Exception) {}
                throw failure
            }
        }
    }

    @Synchronized
    override fun run(img: FloatArray, bigImg: FloatArray): FloatArray {
        require(img.size == SupercomboV027Contract.INPUT_ELEMENTS)
        require(bigImg.size == SupercomboV027Contract.INPUT_ELEMENTS)
        require(img.all { it.isFinite() } && bigImg.all { it.isFinite() })
        fun inputBuffer(samples: FloatArray) =
            ByteBuffer.allocateDirect(samples.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(samples); flip() }
        val shape = longArrayOf(1, 12, 128, 256)
        OnnxTensor.createTensor(env, inputBuffer(img), shape).use { imageTensor ->
            OnnxTensor.createTensor(env, inputBuffer(bigImg), shape).use { bigTensor ->
                session.run(mapOf("img" to imageTensor, "big_img" to bigTensor)).use { result ->
                    val output = result.get("vout_pass").orElseThrow {
                        IllegalStateException("No vision output")
                    } as? OnnxTensor ?: error("Output is not a float tensor")
                    val raw = output.floatBuffer
                    raw.rewind()
                    val values = FloatArray(raw.remaining())
                    raw.get(values)
                    val report = SupercomboV027Contract.inspect(values)
                    check(report.allFinite) { report.note }
                    return values
                }
            }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }
}
