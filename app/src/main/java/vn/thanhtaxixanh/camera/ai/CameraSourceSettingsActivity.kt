package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.net.ConnectivityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class CameraSourceSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profiles = CameraSourceCatalog.Profile.values()
        val prefs = getSharedPreferences(CameraSourceCatalog.PREFS, MODE_PRIVATE)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(13, 17, 23))
        }
        fun txt(message: String, size: Float = 15f) = TextView(this).apply {
            text = message
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(6, 12, 6, 12)
        }
        body.addView(txt("Nguồn camera · xADAS-style", 22f))
        body.addView(txt(
            "Đã nhận diện danh mục camera từ XAPK xADAS. Chỉ 70mai A500S " +
                "và RTSP tùy chỉnh hiện có đường phát đã triển khai.", 13f))
        val selector = Spinner(this).apply {
            adapter = ArrayAdapter(this@CameraSourceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, profiles.map { it.label })
            setSelection(profiles.indexOf(CameraSourceCatalog.selected(this@CameraSourceSettingsActivity)))
        }
        body.addView(selector)
        body.addView(txt("Đường dẫn khi chọn RTSP tùy chỉnh", 13f))
        val url = EditText(this).apply {
            hint = "rtsp://192.168.0.1:554/livestream/12"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setText(prefs.getString(CameraSourceCatalog.URL_KEY, ""))
        }
        body.addView(url)
        val status = txt("Không thay đổi cấu hình camera từ trang này.", 13f)
        body.addView(Button(this).apply {
            text = "LƯU NGUỒN"
            setOnClickListener {
                val selected = profiles[selector.selectedItemPosition]
                try {
                    CameraSourceCatalog.save(
                        this@CameraSourceSettingsActivity, selected, url.text.toString())
                    status.text = "Đã lưu: " + selected.label
                } catch (e: Exception) {
                    status.text = "Chưa áp dụng: " + (e.message ?: selected.label)
                }
            }
        })
        body.addView(status)
        body.addView(Button(this).apply {
            text = "TÌM RTSP TRÊN WI-FI CAMERA (CHỈ DESCRIBE)"
            setOnClickListener {
                isEnabled = false
                val button = this
                status.text = "Đang kiểm tra các đường dẫn RTSP phổ biến..."
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val wifi = CameraConnectionCheck.wifi(cm)
                val network = wifi.network
                val host = wifi.gateway
                if (network == null || host == null) {
                    status.text = "Cần kết nối vào Wi-Fi camera"
                    button.isEnabled = true
                    return@setOnClickListener
                }
                Thread({
                    val found = try {
                        RtspCandidateDiscovery.findFirst(network, host)
                    } catch (_: Exception) { null }
                    runOnUiThread {
                        button.isEnabled = true
                        if (found == null) {
                            status.text = "Không thấy endpoint trả RTSP 200; hãy nhập URL hợp lệ."
                        } else {
                            selector.setSelection(
                                CameraSourceCatalog.Profile.values().indexOf(
                                    CameraSourceCatalog.Profile.CUSTOM))
                            url.setText(found.url)
                            status.text = "Có SDP tại " + found.url +
                                ". Nhấn LƯU NGUỒN. Chưa bảo đảm PLAY thành công."
                        }
                    }
                }, "TTX-RTSP-Discovery").start()
            }
        })
        body.addView(Button(this).apply {
            text = "TRỞ VỀ"
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
