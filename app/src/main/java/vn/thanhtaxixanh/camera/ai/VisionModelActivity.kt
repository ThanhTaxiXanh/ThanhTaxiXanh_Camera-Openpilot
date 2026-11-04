package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
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
    private lateinit var rightsCheck: CheckBox

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
        rightsCheck = CheckBox(this).apply {
            text = "Tôi xác nhận có quyền sử dụng tệp ONNX tự nhập"
            setTextColor(Color.WHITE)
            isChecked = false
        }
        body.addView(rightsCheck)
        body.addView(Button(this).apply {
            text = "NHẬP MODEL ONNX ĐƯỢC PHÉP SỬ DỤNG"
            setOnClickListener {
                if (!rightsCheck.isChecked) {
                    statusView.text = "Xác nhận quyền sử dụng model trước khi chọn tệp."
                    return@setOnClickListener
                }
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
        val attested = rightsCheck.isChecked
        if (!attested) {
            statusView.text = "Chưa xác nhận quyền sử dụng model."
            return
        }
        statusView.text = "Đang kiểm tra graph ONNX và SHA-256 rồi thay thế an toàn..."
        Thread({
            val report = try {
                val stream = contentResolver.openInputStream(uri)
                    ?: error("Không đọc được model")
                val installed = ModelInstallGuard.install(
                    input = stream,
                    destination = File(filesDir,"models/vision.onnx"),
                    graphCheck = { candidate -> VisionModelBackend.inspect(candidate) },
                    userAffirmsRights = attested
                )
                "Model mới: " + installed.bytes/1048576L + " MiB; SHA-256 " +
                    installed.sha256.take(16) + "…\n" +
                    installed.graphDetail +
                    "\nChỉ kiểm tra graph. KHÔNG xác thực hiệu chuẩn hoặc bật ADAS."
            } catch (e: Exception) {
                "Nhập model thất bại: " +
                    (e.message ?: e.javaClass.simpleName).take(200) +
                    "\nModel hợp lệ trước đó không bị xóa."
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) statusView.text = report
            }
        }, "TTX-Atomic-Model-Import").apply { isDaemon = true; start() }
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
