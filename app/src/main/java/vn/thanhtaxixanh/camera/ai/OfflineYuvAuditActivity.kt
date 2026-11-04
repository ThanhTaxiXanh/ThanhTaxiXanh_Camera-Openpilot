package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * User-selected, local YUV420p fixture inspection. Not a playback source,
 * not camera evidence and never a path to enable ADAS alerts.
 */
class OfflineYuvAuditActivity:Activity() {
    private val pickCode=731
    private lateinit var resultView:TextView
    private var requestNumber=0

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(26,24,26,24)
            setBackgroundColor(Color.rgb(12,17,23))
        }
        fun textLine(msg:String):TextView=TextView(this).apply {
            text=msg;textSize=15f;setTextColor(Color.WHITE)
            setPadding(0,14,0,14)
        }
        body.addView(textLine("TTX · VIDEO YUV NGOẠI TUYẾN"))
        body.addView(textLine(
            "Tệp TTXYUV01 có 2–8 khung YUV420p 640×480, " +
                "mốc thời gian tăng 1–500 ms và checksum SHA-256 từng khung. " +
                "Chạy lại bridge A500S → 2 warp → 12 kênh tensor."
        ))
        body.addView(textLine(
            "Đây là chẩn đoán bằng dữ liệu do người dùng cung cấp. " +
                "Checksum không chứng minh xuất xứ. Không mở model ONNX, " +
                "không đo FPS thực, không xác nhận ảnh A500S, " +
                "không kích hoạt ADAS."
        ))
        resultView=textLine("Chưa chọn tệp kiểm thử TTXYUV01.")
        body.addView(Button(this).apply {
            text="CHỌN TỆP TTXYUV01 & KIỂM TRA OFFLINE"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
                },pickCode)
            }
        })
        body.addView(resultView)
        body.addView(Button(this).apply {
            text="TRỞ VỀ";setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }

    @Deprecated("Local-only SAF fixture import")
    override fun onActivityResult(code:Int,result:Int,data:Intent?) {
        super.onActivityResult(code,result,data)
        if(code!=pickCode || result!=RESULT_OK) return
        val uri=data?.data ?: return
        val current=++requestNumber
        resultView.text="Đang kiểm tra checksum và chạy lại pipeline YUV..."
        Thread({
            val report=try {
                val sequence=contentResolver.openInputStream(uri)?.use {
                    OfflineYuvFixture.read(it)
                } ?: error("Không mở được tệp YUV")
                val audit=OfflineYuvFixture.replay(sequence)
                "PASS OFFLINE TENSOR · " + audit.frames + " khung · " +
                    audit.pairs + " cặp\n" +
                    "SHA-256 tensor img: " + audit.mainHash + "\n" +
                    "SHA-256 tensor big_img: " + audit.wideHash + "\n" +
                    audit.note + "\nADAS VẪN KHÓA"
            } catch(e:Exception) {
                "FAIL OFFLINE: " + (e.message ?: e.javaClass.simpleName).take(180) +
                    "\nKhông lưu video, không thay đổi nguồn camera hoặc model."
            }
            runOnUiThread {
                if(current==requestNumber && !isDestroyed && !isFinishing)
                    resultView.text=report
            }
        },"TTX-Offline-YUV-Audit").apply { isDaemon=true;start() }
    }
}
