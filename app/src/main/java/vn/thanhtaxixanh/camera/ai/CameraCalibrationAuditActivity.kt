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
 * Local-only pinhole/Brown-Conrady and metric-range residual inspection. Never saves a calibration,
 * sets model-axis validation flags or activates ADAS warnings/overlays.
 */
class CameraCalibrationAuditActivity : Activity() {
    private lateinit var status: TextView
    private val requestCode = 623
    private val leadRequestCode = 624
    private val axisRequestCode = 625

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
        status = label("Chưa có bộ mốc đo: TTXCAL01 (pinhole) hoặc " +
            "TTXCAL02 (nội tham số + độ méo), tối đa 64 KiB.", 13f)
        container.addView(status)
        container.addView(Button(this).apply {
            text = "KIỂM TRA MỐC CAMERA (.ttxcal)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, requestCode)
            }
        })
        container.addView(Button(this).apply {
            text = "SO SÁNH KHOẢNG CÁCH ĐO THỰC (.ttxlead)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, leadRequestCode)
            }
        })
        container.addView(Button(this).apply {
            text = "KIỂM TRA HỆ TRỤC MODEL (.ttxaxis)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, axisRequestCode)
            }
        })
        container.addView(label(
            "TTXAXIS01 thử các giả thuyết dấu trục 3D và phát hiện " +
                "hệ trục mơ hồ. TTXLEAD02 yêu cầu cặp timestamp đồng hồ chung. " +
                "Cả hai vẫn chỉ là kiểm tra ngoại tuyến.", 13f))
        container.addView(label(
            "TTXLEAD01: thống kê sai số giữa số mét do thiết bị độc lập đo " +
                "và dự đoán mét từ nguồn bên ngoài. KHÔNG dùng tensor RAW " +
                "để suy diễn mét, không mở khóa cảnh báo.", 13f))
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
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == leadRequestCode) {
            inspectLeadFixture(uri)
            return
        }
        if (requestCode == axisRequestCode) {
            inspectAxisFixture(uri)
            return
        }
        if (requestCode != this.requestCode) return
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
                    "${evidence.imageWidth}×${evidence.imageHeight} · " +
                    (if (evidence.lens == null) "pinhole" else
                        "Brown-Conrady") + "\n" +
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
    private fun inspectLeadFixture(uri: android.net.Uri) {
        status.text = "Đang kiểm tra tệp đo khoảng cách độc lập..."
        Thread({
            val result = try {
                val evidence = contentResolver.openInputStream(uri)?.use {
                    LeadDistanceFixture.read(it)
                } ?: error("Không thể mở tệp .ttxlead")
                val audit = evidence.audit()
                val numbers = String.format(Locale.US,
                    "MAE %.2f m · RMSE %.2f m · P95 %.2f m\n" +
                        "MAPE %.2f %% · bias %+.2f m",
                    audit.meanAbsoluteErrorMeters,
                    audit.rootMeanSquareErrorMeters,
                    audit.p95AbsoluteErrorMeters,
                    audit.meanAbsolutePercentError,
                    audit.signedBiasMeters)
                val timing = evidence.timingAudit?.let { sync ->
                    "Cặp thời gian: ${sync.status} · " +
                        "Max lệch ${sync.maxSkewMs} ms · " +
                        "P95 lệch ${sync.p95SkewMs} ms\n"
                } ?: "TTXLEAD01: chưa có timestamp để đồng bộ\n"
                "Kết quả: ${audit.status}\n" +
                    timing +
                    "Dataset: ${evidence.datasetId} · ${audit.count} mốc đo\n" +
                    "Thiết bị đo (tự khai): ${evidence.referenceInstrumentId}\n" +
                    "SHA model (tự khai): ${evidence.claimedModelSha256.take(12)}…\n" +
                    numbers + "\n" + audit.note +
                    "\nKHÔNG XÁC THỰC NGUỒN ĐO · ADAS VẪN KHÓA"
            } catch (e: Exception) {
                "Tệp khoảng cách không hợp lệ: " +
                    (e.message ?: e.javaClass.simpleName).take(180)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) status.text = result
            }
        }, "TTX-Independent-Lead-Range-Audit").start()
    }

    private fun inspectAxisFixture(uri: android.net.Uri) {
        status.text = "Đang đối chiếu hệ trục 3D với các mốc độc lập..."
        Thread({
            val message = try {
                val evidence = contentResolver.openInputStream(uri)?.use {
                    ModelAxisFixture.read(it)
                } ?: error("Không thể mở tệp hệ trục TTXAXIS01")
                val report = evidence.audit()
                val scores = if (report.rmsMeters != null) {
                    String.format(Locale.US,
                        "RMS giả thuyết tốt nhất %.3f m · kế tiếp %.3f m",
                        report.rmsMeters,report.runnerUpRmsMeters)
                } else "Không đủ mốc đo 3D để đánh giá"
                "Kết quả: ${report.status}\n" +
                    "Dataset: ${evidence.datasetId} · ${report.count} mốc\n" +
                    "Dấu trục giả thuyết: ${report.bestHypothesis}\n" +
                    scores + "\n" + report.note +
                    "\nKHÔNG XÁC THỰC MODEL/HỆ TRỤC · ADAS VẪN KHÓA"
            } catch (e: Exception) {
                "Tệp hệ trục không hợp lệ: " +
                    (e.message ?: e.javaClass.simpleName).take(180)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) status.text = message
            }
        }, "TTX-Independent-Model-Axis-Audit").start()
    }

}
