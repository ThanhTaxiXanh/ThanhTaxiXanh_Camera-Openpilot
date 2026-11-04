package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Local-only pinhole reprojection inspection. Never saves a calibration,
 * sets model-axis validation flags or activates ADAS warnings/overlays.
 */
class CameraCalibrationAuditActivity : Activity() {
    private lateinit var status: TextView
    private val requestCode = 623

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 24, 26, 24)
            setBackgroundColor(Color.rgb(12,17,23))
        }
        fun label(textValue: String, size: Float = 15f) = TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0,12,0,12)
        }
        container.addView(label("TTX · KIỂM TRA HÌNH HỌC CAMERA", 19f))
        container.addView(label(
            "Chỉ phân tích mốc đo 3D và tọa độ ảnh được cung cấp hợp lệ. " +
                "Không tự tính nội tham số, không đọc ảnh camera, " +
                "không tạo bằng chứng hiệu chuẩn từ dữ liệu giả."))
        status = label("Chưa có bộ mốc đo. Định dạng: TTXCAL01 · ASCII CSV " +
            "· tối đa 64 KiB · tối thiểu 8 mốc trải đều trong ảnh.", 13f)
        container.addView(status)
        container.addView(Button(this).apply {
            text = "CHỌN TỆP MỐC ĐO CAMERA (.ttxcal)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, requestCode)
            }
        })
        container.addView(label(
            "RMS/P95 là sai số chiếu lại của ma trận camera giả định, " +
                "không phải xác nhận tọa độ model ONNX hay khoảng cách xe. " +
                "Kể cả khi đạt ngưỡng sai số, ADAS VẪN KHÓA.", 13f))
        container.addView(Button(this).apply {
            text = "QUAY LẠI"
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(container) })
    }

    @Deprecated("Uses Storage Access Framework for local-only imports")
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != this.requestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Đang đọc mốc đo và kiểm tra sai số hình học..."
        Thread({
            val result = try {
                val evidence = contentResolver.openInputStream(uri)?.use {
                    CameraCalibrationFixture.read(it)
                } ?: error("Không thể mở tệp CSV")
                val audit = evidence.audit()
                val numbers = if (audit.rmsErrorPx != null &&
                    audit.p95ErrorPx != null && audit.maxErrorPx != null) {
                    String.format(Locale.US,
                        "RMS %.2f px · P95 %.2f px · Max %.2f px",
                        audit.rmsErrorPx, audit.p95ErrorPx, audit.maxErrorPx)
                } else "Không đủ điều kiện tính sai số"
                "Kết quả: ${audit.status}\n" +
                    "Số mốc: ${audit.inputMarkers} · " +
                    "${evidence.imageWidth}×${evidence.imageHeight}\n" +
                    numbers + "\n" + audit.note +
                    "\nCHỈ CHẨN ĐOÁN · KHÔNG MỞ KHÓA ADAS"
            } catch (e: Exception) {
                "Không thể kiểm tra mốc đo: " +
                    (e.message ?: e.javaClass.simpleName).take(180) +
                    "\nKhông có thay đổi hiệu chuẩn."
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) status.text = result
            }
        }, "TTX-Camera-Geometry-Audit").start()
    }
}
