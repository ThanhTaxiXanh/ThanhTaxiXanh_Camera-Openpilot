package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Independent research UI derived from user-visible categories in xADAS 0.3.0.
 * No third-party model, proprietary resource, or xADAS layout asset is bundled.
 */
class AdasSettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var page: LinearLayout
    private val bg = Color.rgb(9, 12, 16)
    private val card = Color.rgb(27, 32, 40)
    private val green = Color.rgb(160, 232, 139)
    private val secondary = Color.rgb(166, 177, 188)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AdasPrefs.of(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
            setBackgroundColor(bg)
        }
        val content = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        setContentView(content)
        val back = TextView(this).apply {
            text = "‹  Cài đặt TTX Camera AI"
            textSize = 22f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(18))
            setOnClickListener { finish() }
        }
        page.addView(back)
        info("BẢN NGHIÊN CỨU", "Chỉ có cấu hình nguồn và video đang hoạt động. Parser AI đã phục hồi nhưng inference, hiệu chuẩn và cảnh báo vẫn chưa kích hoạt.")

        heading("Camera và nhận diện")
        info("70mai A500S", "Phát IJK/FFmpeg và Direct RTSP/MediaCodec. Các profile khác chỉ bật khi adapter đã được kiểm tra.")
        info("Nhận diện xe và khoảng cách", "Chưa có mô hình phát hiện xe mặc định. Khoảng cách tính từ mô hình tự nạp chỉ là ước lượng và chưa hiệu chuẩn.")
        action("Trở về màn hình camera") { finish() }

        heading("Cảnh báo khoảng cách")
        futureSlider("Tốc độ bắt đầu đo", AdasPrefs.START_SPEED, 0, 150, 10, "km/h",
            "Lưu tham số; chưa có bộ đo tốc độ GPS đáng tin cậy để kích hoạt cảnh báo.")
        futureChoice("Phương pháp cảnh báo", AdasPrefs.ALERT_MODE,
            listOf("Việt Nam", "Theo giây"), "Theo giây",
            "Chế độ Việt Nam chỉ là tham chiếu giao diện xADAS; chưa xác thực quy định và chưa kích hoạt cảnh báo.")
        futureSlider("Khoảng cách theo thời gian", AdasPrefs.HEADWAY_TENTHS, 10, 30, 20, "0,1 giây",
            "Mặc định 2,0 giây. Chưa tính headway/TTC bởi thiếu khoảng cách và tốc độ tương đối đã kiểm định.")
        info("Âm thanh cảnh báo", "Chưa triển khai. Ứng dụng không giả lập cảnh báo khoảng cách để tránh thông tin sai lệch.")

        heading("Hiển thị khi lái · Đã áp dụng")
        toggle("Vẽ vùng làn đường xanh", "Đa giác chỉ xuất hiện khi có hai biên hợp lý; kết quả thử nghiệm, không bảo đảm chính xác.",
            AdasPrefs.LANE_AREA, true)
        toggle("Vẽ đường biên làn", "Chỉ có hiệu lực sau khi có model AI được cấp quyền, ảnh YUV và hiệu chuẩn hợp lệ.",
            AdasPrefs.LANE_LINES, true)
        toggle("Hiển thị khoảng cách xe", "Chỉ hiển thị khi có mô hình hoạt động và kết quả hiệu chuẩn hợp lệ.",
            AdasPrefs.SHOW_LEAD, true)
        toggle("Mũi tên chỉ xe phía trước", "Chỉ hiển thị khi có phát hiện xe thực sự, không vẽ mục tiêu giả.",
            AdasPrefs.LEAD_ARROW, true)
        toggle("Hiển thị nhãn TTX", "Hiển thị nhãn nghiên cứu trên lớp HUD.",
            AdasPrefs.SHOW_LOGO, true)
        toggle("Làm tròn khoảng cách theo 5 m", "Chỉ áp dụng cho số ước tính từ 20 m trở lên.",
            AdasPrefs.ROUND_DISTANCE, false)
        toggle("Ẩn thanh trạng thái Android", "Áp dụng khi trở lại màn hình chính.",
            AdasPrefs.HIDE_STATUS_BAR, false)

        heading("Hình học camera · Chẩn đoán ngoại tuyến")
        info("Sai số chiếu lại và độ méo camera",
            "TTXCAL01/02: kiểm tra mốc 3D/2D, fx/fy/cx/cy và độ méo ống kính; không được coi là hiệu chuẩn thực.")
        info("Sai số khoảng cách xe phía trước",
            "TTXLEAD01: so sánh mét đo độc lập và mét dự đoán từ pipeline đã định nghĩa; không suy ra mét từ tensor RAW.")
        action("KIỂM TRA MỐC CAMERA & KHOẢNG CÁCH") {
            startActivity(Intent(this, CameraCalibrationAuditActivity::class.java))
        }

        heading("Lắp đặt trên xe · Lưu để hiệu chỉnh")
        futureSlider("Chiều cao đặt camera", AdasPrefs.CAMERA_HEIGHT_CM, 60, 200, 150, "cm",
            "Cần đo chiều cao và hiệu chuẩn hướng camera để sử dụng tính khoảng cách thật.")
        futureSlider("Bù tốc độ GPS", AdasPrefs.GPS_OFFSET, -5, 5, 2, "km/h",
            "Chưa dùng cho cảnh báo; chỉ lưu tham số cấu hình.")

        heading("Chạy nền và chia sẻ")
        info("PiP · Cửa sổ nổi", "Chưa bật vì cần kiểm thử Android foreground service, quyền hiển thị và tính ổn định của camera.")
        info("Chia sẻ khoảng cách với ứng dụng khác", "Chưa xuất dữ liệu khoảng cách chưa hiệu chuẩn sang ứng dụng khác.")
        futureSlider("Chuyển nền tối sau", AdasPrefs.NIGHT_MINUTES, 1, 30, 1, "phút",
            "Đã lưu tham số nhưng chưa có bộ chuyển nền tự động.")
        heading("Chung")
        choice("Đơn vị hiển thị", AdasPrefs.SPEED_UNIT,
            listOf("km/h · m", "mph · ft"), "km/h · m")
        info("Chẩn đoán RTSP", "Quay về màn hình chính và chọn KIỂM TRA WIFI / RTSP A500S để đọc Content-Base, SDP và phản hồi camera.")
        info("Giới hạn an toàn", "Không dùng kết quả thị giác thử nghiệm để tự lái, phanh, giữ làn hoặc quyết định khoảng cách an toàn.")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun heading(title: String) {
        page.addView(TextView(this).apply {
            text = title
            setTextColor(green)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(22), dp(4), dp(10))
        })
    }

    private fun panel(): LinearLayout {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(13).toFloat()
            }
        }
        page.addView(block, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
        return block
    }

    private fun title(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(white)
        setTypeface(null, Typeface.BOLD)
    }

    private fun detail(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(secondary)
        setPadding(0, dp(5), 0, 0)
    }

    private fun info(label: String, description: String) {
        val box = panel()
        box.addView(title(label))
        box.addView(detail(description))
    }

    private fun action(label: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        page.addView(b)
    }

    @Suppress("DEPRECATION")
    private fun toggle(label: String, description: String, key: String, default: Boolean) {
        val box = panel()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(title(label))
        labels.addView(detail(description))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val switch = Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        row.addView(switch)
        box.addView(row)
    }

    private fun choice(label: String, key: String, options: List<String>, default: String) {
        val box = panel()
        box.addView(title(label))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val buttons = mutableListOf<Button>()
        fun refresh() {
            val selected = prefs.getString(key, default)
            for (button in buttons) {
                val active = button.text.toString() == selected
                button.setTextColor(if (active) Color.BLACK else white)
                button.setBackgroundColor(if (active) green else Color.rgb(48, 54, 63))
            }
        }
        for (option in options) {
            val button = Button(this).apply {
                text = option
                isAllCaps = false
                textSize = 11f
                setOnClickListener {
                    prefs.edit().putString(key, option).apply()
                    refresh()
                }
            }
            buttons += button
            row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        box.addView(row)
        refresh()
    }

    private fun futureChoice(label: String, key: String, options: List<String>,
                             default: String, explanation: String) {
        choice(label + " · CHƯA KÍCH HOẠT", key, options, default)
        val previous = page.getChildAt(page.childCount - 1) as LinearLayout
        previous.addView(detail(explanation))
    }

    private fun futureSlider(label: String, key: String, min: Int, max: Int,
                             default: Int, unit: String, explanation: String) {
        val box = panel()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(title(label + " · LƯU CẤU HÌNH"), LinearLayout.LayoutParams(0, -2, 1f))
        val value = title("")
        header.addView(value)
        box.addView(header)
        val slider = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = prefs.getInt(key, default).coerceIn(min, max)
        }
        fun display(amount: Int): String = if (key == AdasPrefs.HEADWAY_TENTHS)
            String.format(java.util.Locale.US, "%.1f giây", amount / 10f)
        else amount.toString() + " " + unit
        value.text = display(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = display(progress)
                if (fromUser) prefs.edit().putInt(key, progress).apply()
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        box.addView(slider)
        box.addView(detail(explanation))
    }
}
