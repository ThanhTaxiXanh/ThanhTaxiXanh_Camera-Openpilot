package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.MessageDigest

/**
 * User-authorized optional ONNX import: no model data is bundled or uploaded.
 * File import, graph contract and offline golden-vector comparison. RAW
 * diagnostics exist separately; calibrated ADAS displays remain disabled.
 */
class VisionModelActivity : Activity() {
    private lateinit var statusView: TextView
    private val requestCode = 204
    private val referenceCode = 205
    private val limitBytes = 250L * 1024L * 1024L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 24, 26, 24)
            setBackgroundColor(Color.rgb(12, 17, 23))
        }
        fun label(value: String, sp: Float = 15f) = TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(Color.WHITE)
            setPadding(0, 14, 0, 14)
        }
        body.addView(label("Openpilot Vision · Model", 22f))
        body.addView(label(
            "Đã khôi phục parser xADAS: 2 input [1,12,128,256], " +
                "đầu ra [1,1576] gồm lanes, road edges, lead, pose. " +
                "Đây là hợp đồng dữ liệu, không phải một mô hình đang chạy."))
        val dest = File(filesDir, "models/vision.onnx")
        statusView = label(if (dest.exists())
            "Có model tự nhập (" + dest.length()/1048576L + " MiB). AI chưa kích hoạt."
            else "Chưa nhập model. AI nhận diện làn và khoảng cách chưa kích hoạt.")
        body.addView(statusView)
        body.addView(Button(this).apply {
            text = "KIỂM TRA HỢP ĐỒNG MODEL (KHÔNG PHÁT CẢNH BÁO)"
            setOnClickListener {
                isEnabled = false
                val trigger = this
                statusView.text = "Đang kiểm tra cấu trúc mô hình..."
                Thread({
                    val result = VisionModelBackend.inspect(dest)
                    runOnUiThread {
                        trigger.isEnabled = true
                        statusView.text = result.detail
                    }
                }, "TTX-ONNX-Contract-Check").start()
            }
        })
        body.addView(Button(this).apply {
            text = "NHẬP MODEL ONNX ĐƯỢC PHÉP SỬ DỤNG"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, requestCode)
            }
        })
        body.addView(Button(this).apply {
            text = "ĐỐI CHIẾU VECTOR ONNX THAM CHIẾU (.ttxref)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, referenceCode)
            }
        })
        body.addView(label(
            "Chỉ nhập vector tham chiếu có quyền sử dụng. " +
                "Kết quả PASS chỉ chứng minh khớp với vector đã cung cấp; " +
                "KHÔNG xác minh hiệu chuẩn camera hay độ an toàn khi lái.", 13f))
        body.addView(label(
            "Model được lưu cục bộ, không upload. Cần kiểm tra preprocessing YUV, " +
                "virtual warp và hiệu chuẩn camera trước khi hiển thị khoảng cách. " +
                "Không trích xuất hoặc phân phối trọng số xADAS mà chưa có quyền.", 13f))
        body.addView(Button(this).apply {
            text = "TRỞ VỀ"
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }

    @Deprecated("Used only for a local Storage Access Framework import")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == referenceCode) {
            compareReferenceFile(uri)
            return
        }
        if (requestCode != this.requestCode) return
        statusView.text = "Đang xác minh và lưu model..."
        Thread({
            var temp: File? = null
            try {
                val dest = File(filesDir, "models/vision.onnx")
                dest.parentFile?.mkdirs()
                val working = File(dest.parentFile, "vision.onnx.part")
                temp = working
                val sha = MessageDigest.getInstance("SHA-256")
                var count = 0L
                val source = contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Không đọc được model")
                source.use { input ->
                    working.outputStream().use { output ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val n = input.read(bytes)
                            if (n < 0) break
                            count += n
                            require(count <= limitBytes) { "Model vượt giới hạn 250 MiB" }
                            sha.update(bytes, 0, n)
                            output.write(bytes, 0, n)
                        }
                    }
                }
                require(count > 1024) { "Tệp model không hợp lệ hoặc rỗng" }
                if (dest.exists()) dest.delete()
                check(working.renameTo(dest)) { "Không thể lưu model" }
                val hex = sha.digest().joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
                runOnUiThread {
                    statusView.text = "Đã lưu cục bộ " + count/1048576L + " MiB, SHA256 " +
                        hex.take(16) + "…; CHƯA chạy inference."
                }
            } catch (e: Exception) {
                temp?.delete()
                runOnUiThread { statusView.text = "Lỗi nhập model: " + e.message }
            }
        }, "TTX-Local-Model-Import").start()
    }

    /** A PASS never enables safety-critical HUD output or model calibration. */
    private fun compareReferenceFile(uri: android.net.Uri) {
        val model = File(filesDir, "models/vision.onnx")
        if (!model.isFile) {
            statusView.text = "Cần nhập model ONNX được phép sử dụng trước."
            return
        }
        statusView.text = "Đang kiểm tra hash model, vector và đầu ra ONNX..."
        Thread({
            val message = try {
                val digest = MessageDigest.getInstance("SHA-256")
                model.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                val sha = digest.digest()
                val vector = contentResolver.openInputStream(uri)?.use {
                    ReferenceTensorFixture.read(it)
                } ?: error("Không thể mở tệp tham chiếu.")
                require(MessageDigest.isEqual(sha, vector.modelHash)) {
                    "SHA-256 vector không khớp với model ONNX đang nhập."
                }
                val runner = VisionModelBackend.openEngine(model)
                    ?: error("Bản Lite không có ONNX; dùng APK AI.")
                runner.use { engine ->
                    val actual = engine.run(vector.img, vector.bigImg)
                    val comparison = ReferenceTensorFixture.compare(
                        vector.expectedOutput, actual)
                    if (comparison.pass)
                        "PASS VECTOR THAM CHIẾU: 1.576 giá trị, " +
                            "sai số tối đa ${comparison.maxAbsoluteError}. " +
                            "Chưa xác minh pixel warp, camera và ADAS."
                    else
                        "FAIL VECTOR: ${comparison.mismatches}/1576 sai khác; " +
                            "lớn nhất tại index ${comparison.worstIndex}, " +
                            "sai số ${comparison.maxAbsoluteError}. Không mở ADAS."
                }
            } catch (e: Exception) {
                "Không xác minh được vector: " +
                    (e.message ?: e.javaClass.simpleName).take(240)
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) statusView.text = message }
        }, "TTX-ONNX-Reference-Audit").start()
    }
}
