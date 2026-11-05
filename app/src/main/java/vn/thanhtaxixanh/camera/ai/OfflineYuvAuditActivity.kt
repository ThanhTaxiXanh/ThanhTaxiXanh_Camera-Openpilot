package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * All data are selected by the owner and processed locally. This Activity
 * intentionally does not display lane, range or ADAS warnings. A matching
 * self-declared reference is NOT verified camera/model ground truth.
 */
class OfflineYuvAuditActivity:Activity() {
    private val pickCode=731
    private val referenceCode=732
    private val manifestCode=733
    private val exportOnnxReferenceCode=734
    private lateinit var resultView:TextView
    private var requestNumber=0
    private var selectedYuv:Uri?=null
    private var selectedReference:Uri?=null
    private lateinit var referenceButton:Button
    private lateinit var exportOnnxReferenceButton:Button
    private lateinit var evidenceButton:Button

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(26,24,26,24)
            setBackgroundColor(Color.rgb(12,17,23))
        }
        fun textLine(msg:String):TextView=TextView(this).apply {
            text=msg; textSize=15f; setTextColor(Color.WHITE)
            setPadding(0,14,0,14)
        }
        body.addView(textLine("TTX · VIDEO YUV NGOẠI TUYẾN V1.3.6"))
        body.addView(textLine(
            "Chọn TTXYUV01: 2–8 khung YUV420p 640×480. " +
                "Xác minh SHA-256 từng khung, chạy lại bridge A500S, " +
                "hai warp thử nghiệm và tensor FLOAT32 12 kênh."
        ))
        body.addView(textLine(
            "Tùy chọn kiểm tra sâu: dùng model ONNX đã tự nhập hợp lệ " +
                "và TTXREF01 độc lập. So sánh SHA model → hai tensor " +
                "đầu vào → 1.576 đầu ra ONNX thật. KHÔNG xác thực " +
                "xuất xứ camera, quyền model, hiệu chuẩn hay ADAS."
        ))
        resultView=textLine("Chưa chọn tệp TTXYUV01.")
        body.addView(Button(this).apply {
            text="1. CHỌN TTXYUV01 & KIỂM TRA TENSOR"
            setOnClickListener {
                pick(pickCode)
            }
        })
        referenceButton=Button(this).apply {
            text="2. CHỌN TTXREF01 & SO SÁNH ONNX THẬT (AI)"
            isEnabled=false
            setOnClickListener { pick(referenceCode) }
        }
        body.addView(referenceButton)
        exportOnnxReferenceButton=Button(this).apply {
            text="4. XUẤT TTXREF01 TỪ YUV + ONNX TRÊN ĐIỆN THOẠI"
            isEnabled=false
            setOnClickListener {
                if(selectedYuv==null) {
                    resultView.text="Cần chọn và kiểm tra TTXYUV01 trước."
                } else {
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type="application/octet-stream"
                                putExtra(Intent.EXTRA_TITLE,
                                    "TTX-Android-YUV-ONNX-output.ttxref")
                            },exportOnnxReferenceCode)
                    } catch(e:Exception) {
                        resultView.text="Không mở được nơi xuất TTXREF01: " +
                            e.javaClass.simpleName
                    }
                }
            }
        }
        body.addView(exportOnnxReferenceButton)
        body.addView(textLine(
            "TTXREF01 tạo trên Android có chứa tensor từ cảnh camera. " +
            "Chỉ lưu và đối chiếu cục bộ; không công khai khi chưa có quyền. " +
            "Đầu ra Android KHÔNG phải ground truth độc lập."
        ))
        evidenceButton=Button(this).apply {
            text="3. KIỂM TRA HỒ SƠ SHA NGUỒN DỮ LIỆU TTXEVD01"
            isEnabled=false
            setOnClickListener { pick(manifestCode) }
        }
        body.addView(evidenceButton)
        body.addView(resultView)
        body.addView(Button(this).apply {
            text="TRỞ VỀ";setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }

    private fun pick(code:Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="*/*"
        },code)
    }

    private fun formatModelResult(
        report:OfflineModelParityAudit.Report
    ):String {
        val detail=when(report.status) {
            OfflineModelParityAudit.Status.MISSING_MODEL ->
                "Chưa có model ONNX được cấp phép đã nhập"
            OfflineModelParityAudit.Status.MODEL_HASH_MISMATCH ->
                "SHA-256 model khác model của vector tham chiếu"
            OfflineModelParityAudit.Status.INPUT_MISMATCH ->
                "Không khớp dữ liệu tiền xử lý: " +
                    report.inputMismatches + " giá trị; đầu tiên " +
                    report.firstMismatchedInput
            OfflineModelParityAudit.Status.ENGINE_UNAVAILABLE ->
                "Không mở được ONNX Runtime; bản Lite không hỗ trợ"
            OfflineModelParityAudit.Status.OUTPUT_MISMATCH ->
                "Sai khác ONNX: " + report.outputMismatches +
                    "/1576; sai số tuyệt đối lớn nhất " +
                    String.format(Locale.US,"%.5f",
                        report.maxOutputError ?: 0.0)
            OfflineModelParityAudit.Status.MATCHED_UNVERIFIED ->
                "KHỚP VỚI THAM CHIẾU TỰ CUNG CẤP: model, " +
                    "hai tensor và đầu ra ONNX"
        }
        return detail + "\nSố cặp YUV kiểm tra: " + report.pairs +
            " · SHA model " + report.modelSha256Prefix +
            "\n" + report.note +
            "\nKHÔNG PHẢI CHỨNG NHẬN xADAS/ADAS. CẢNH BÁO VẪN KHÓA."
    }

    @Deprecated("Local-only Storage Access Framework fixture picker")
    override fun onActivityResult(code:Int,result:Int,data:Intent?) {
        super.onActivityResult(code,result,data)
        if(result!=RESULT_OK ||
            code !in listOf(pickCode,referenceCode,manifestCode,
                exportOnnxReferenceCode)) return
        val uri=data?.data ?: return
        val current=++requestNumber
        if(code==exportOnnxReferenceCode) {
            val yuvUri=selectedYuv
            if(yuvUri==null) {
                resultView.text="Cần chọn TTXYUV01 hợp lệ trước khi xuất."
                return
            }
            exportOnnxReferenceButton.isEnabled=false
            resultView.text="Đang tạo tensor YUV và chạy ONNX trên AI... " +
                "Không phát cảnh báo hoặc dùng đầu ra cho điều khiển xe."
            Thread({
                val status=try {
                    val sequence=contentResolver.openInputStream(yuvUri)?.use {
                        OfflineYuvFixture.read(it)
                    } ?: error("Không thể đọc TTXYUV01")
                    val model=File(filesDir,"models/vision.onnx")
                    val generated=OfflineYuvOnnxEvidence.generate(
                        sequence,model,
                        openEngine={ VisionModelBackend.openEngine(it) })
                    val bytes=ByteArrayOutputStream(
                        ReferenceTensorFixture.EXPECTED_BYTES.toInt())
                    ReferenceTensorFixture.write(generated.vector,bytes)
                    check(bytes.size().toLong()==
                        ReferenceTensorFixture.EXPECTED_BYTES)
                    val stream=contentResolver.openOutputStream(uri,"w")
                        ?: error("Không ghi được tài liệu")
                    stream.use { it.write(bytes.toByteArray()) }
                    "Đã xuất TTXREF01 từ " + generated.frames +
                        " khung YUV (" + generated.pairs + " cặp). " +
                        "SHA img: " + generated.inputMainSha256 +
                        "\nSHA big_img: " + generated.inputWideSha256 +
                        "\nBằng chứng chỉ chứng minh đầu ra Android tự tạo. " +
                        "Đối chiếu tệp với cùng model trên CPU độc lập; " +
                        "KHÔNG xác minh mô hình ADAS, quyền dùng hay camera thật."
                } catch(e:Exception) {
                    "Không xuất được TTXREF01: " +
                        (e.message ?: e.javaClass.simpleName).take(210) +
                        "\nTệp đã chọn có thể bị rỗng/không hoàn chỉnh. " +
                        "Không công nhận bằng chứng nếu chưa xác minh hash."
                }
                runOnUiThread {
                    if(current==requestNumber && !isDestroyed && !isFinishing) {
                        resultView.text=status
                        exportOnnxReferenceButton.isEnabled=selectedYuv!=null
                    }
                }
            },"TTX-Android-YUV-ONNX-Export").apply {
                isDaemon=true;start()
            }
            return
        }
        if(code==pickCode) {
            selectedYuv=uri
            selectedReference=null
            evidenceButton.isEnabled=false
            referenceButton.isEnabled=false
            exportOnnxReferenceButton.isEnabled=false
            resultView.text="Đang kiểm tra tệp YUV và hai tensor..."
            Thread({
                val message=try {
                    val sequence=contentResolver.openInputStream(uri)?.use {
                        OfflineYuvFixture.read(it)
                    } ?: error("Không mở được tệp YUV")
                    val audit=OfflineYuvFixture.replay(sequence)
                    "KHỚP ĐỊNH DẠNG YUV: " + audit.frames + " khung, " +
                        audit.pairs + " cặp\n" +
                        "SHA-256 img: " + audit.mainHash + "\n" +
                        "SHA-256 big_img: " + audit.wideHash + "\n" +
                        audit.note +
                        "\nCó thể chọn TTXREF01 để so sánh ONNX thật."
                } catch(e:Exception) {
                    selectedYuv=null
                    "KIỂM TRA YUV THẤT BẠI: " +
                        (e.message ?: e.javaClass.simpleName).take(180)
                }
                runOnUiThread {
                    if(current==requestNumber && !isDestroyed && !isFinishing) {
                        resultView.text=message
                        referenceButton.isEnabled=selectedYuv!=null
                        exportOnnxReferenceButton.isEnabled=selectedYuv!=null
                    }
                }
            },"TTX-Offline-YUV").apply { isDaemon=true; start() }
            return
        }
        val yuvUri=selectedYuv
        if(code==manifestCode) {
            val referenceUri=selectedReference
            if(yuvUri==null || referenceUri==null) {
                resultView.text="Cần kiểm tra TTXYUV01 và TTXREF01 trước."
                return
            }
            evidenceButton.isEnabled=false
            resultView.text="Đang đối chiếu hồ sơ SHA của ba tệp cục bộ..."
            Thread({
                val report=try {
                    val meta=contentResolver.openInputStream(uri)?.use {
                        EvidenceBundleManifest.read(it)
                    } ?: error("Không đọc được TTXEVD01")
                    val yuv=contentResolver.openInputStream(yuvUri)
                        ?: error("Không đọc được TTXYUV01")
                    val ref=contentResolver.openInputStream(referenceUri)
                        ?: error("Không đọc được TTXREF01")
                    val audit=EvidenceBundleManifest.verify(
                        meta,yuv,ref,File(filesDir,"models/vision.onnx"))
                    val resolved=if(audit.hashesMatch) {
                        val vector=contentResolver.openInputStream(referenceUri)?.use {
                            ReferenceTensorFixture.read(it)
                        } ?: error("Không mở được vector TTXREF01")
                        EvidenceBundleManifest.verifyEmbeddedReferenceModel(audit,vector)
                    } else audit
                    "TTXEVD01: " + resolved.status.name +
                        "\nNguồn tự khai: " + meta.sourceId +
                        "\nThiết bị tự khai: " + meta.instrumentId +
                        "\nThời điểm tự khai: " + meta.recordedUtc +
                        "\nQuyền sử dụng (mã tự khai): " + meta.permissionId +
                        "\n" + resolved.note +
                        "\nKHÔNG XÁC MINH NHÀ CUNG CẤP/ẢNH THỰC/ADAS."
                } catch(e:Exception) {
                    "KHÔNG HỢP LỆ TTXEVD01: " +
                        (e.message ?: e.javaClass.simpleName).take(220) +
                        "\nKhông cấp quyền ADAS hoặc tin vào nguồn gốc tệp."
                }
                runOnUiThread {
                    if(current==requestNumber && !isDestroyed && !isFinishing) {
                        resultView.text=report
                        evidenceButton.isEnabled=selectedReference!=null &&
                            selectedYuv!=null
                    }
                }
            },"TTX-Offline-Evidence").apply { isDaemon=true; start() }
            return
        }
        if(yuvUri==null) {
            resultView.text="Chọn và kiểm tra TTXYUV01 trước."
            return
        }
        resultView.text="Đang so sánh SHA model, hai tensor và ONNX..."
        referenceButton.isEnabled=false
        Thread({
            var parsedReference=false
            val message=try {
                val sequence=contentResolver.openInputStream(yuvUri)?.use {
                    OfflineYuvFixture.read(it)
                } ?: error("Không đọc được TTXYUV01")
                val ref=contentResolver.openInputStream(uri)?.use {
                    ReferenceTensorFixture.read(it)
                } ?: error("Không đọc được TTXREF01")
                parsedReference=true
                val model=File(filesDir,"models/vision.onnx")
                val report=OfflineModelParityAudit.compare(
                    sequence,ref,model,
                    openEngine={ candidate -> VisionModelBackend.openEngine(candidate) }
                )
                formatModelResult(report)
            } catch(e:Exception) {
                "KHÔNG THỂ SO SÁNH ONNX: " +
                    (e.message ?: e.javaClass.simpleName).take(240) +
                    "\nKhông có thay đổi model hay quyền kích hoạt ADAS."
            }
            runOnUiThread {
                if(current==requestNumber && !isDestroyed && !isFinishing) {
                    resultView.text=message
                    if(parsedReference) selectedReference=uri
                    referenceButton.isEnabled=selectedYuv!=null
                    evidenceButton.isEnabled=selectedYuv!=null &&
                        selectedReference!=null
                }
            }
        },"TTX-Offline-ONNX-Parity").apply { isDaemon=true; start() }
    }
}
