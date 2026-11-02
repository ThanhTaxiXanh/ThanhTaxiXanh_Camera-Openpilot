package vn.thanhtaxixanh.camera.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.media3.ui.AspectRatioFrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var previewContainer: FrameLayout
    private lateinit var settingsScroll: ScrollView
    private var expandedVideo = false
    private var compactPreviewHeight = 0
    private var analyzedFrames = 0
    private var lastMetricTime = 0L
    // Live video has absolute priority over experimental AI analysis.
    private var analysisEnabled = false
    private var lastAnalysisRequestMs = 0L
    private var copiedFrames = 0
    private var droppedSamples = 0
    private var vlcBufferMs = 800
    private val copyThread = HandlerThread("TTX-PixelCopy", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val copyHandler = Handler(copyThread.looper)
    private lateinit var aiToggle: Button
    private lateinit var bufferToggle: Button
    private var sampleCaptureActive = false
    private var sampleGeneration = 0
    private var preparedSample: File? = null
    private lateinit var playerView: PlayerView
    private lateinit var phonePreview: PreviewView
    private lateinit var vlcSurface: VLCVideoLayout
    private var vlcPlayer: VlcCameraPlayer? = null
    private var vlcMode = true
    private lateinit var overlay: AdasOverlay
    private lateinit var status: TextView
    private lateinit var urlField: EditText
    private lateinit var engine: VisionEngine
    private var player: ExoPlayer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val candidates = listOf(
        "rtsp://192.168.0.1:554/livestream/12",
        "rtsp://192.168.0.1:554/livestream/0",
        "rtsp://192.168.0.1:554/liveRTSP/av4",
        "rtsp://192.72.1.1:554/liveRTSP/av4"
    )
    private var source = "none"
    private var candidateIndex = 0
    private var retryCount = 0
    private var lastFrame = 0L
    private var activeUrl = ""
    private var selectedWifi: Network? = null
    private var trialUrls = emptyList<String>()
    private var videoRendered = false
    private var retryActive = false
    private lateinit var report: TextView
    private val exportSample = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val sample = preparedSample
        preparedSample = null
        if (uri == null || sample == null) {
            sample?.delete()
            if (::status.isInitialized) show("Đã hủy xuất khung hình.")
        } else {
            worker.execute {
                val result = try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        sample.inputStream().use { it.copyTo(output) }
                    } ?: error("Không ghi được tệp ZIP")
                    "Đã xuất hai khung A500S thành ZIP. Bạn có thể gửi tệp để phân tích warp."
                } catch (e: Exception) {
                    "Không lưu được ZIP: " + (e.message ?: e.javaClass.simpleName)
                } finally { sample.delete() }
                runOnUiThread { if (::status.isInitialized) show(result) }
            }
        }
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startPhone() else show("Cần cấp quyền Camera cho nguồn điện thoại")
    }
    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        show("Đang kiểm tra mô hình TFLite…")
        worker.execute {
            val text = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    File(filesDir, "vehicles.tflite").outputStream().use { input.copyTo(it) }
                } ?: error("Không đọc được mô hình")
                engine.reloadModel()
            } catch (e: Exception) { "Nạp model lỗi: ${e.message}" }
            runOnUiThread { show(text) }
        }
    }

    private val importOnnx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        show("Đang xác minh mô hình ONNX…")
        worker.execute {
            val pending = File(filesDir, "ttx_onnx_pending.tmp")
            val ready = File(filesDir, "ttx_user_model.onnx")
            val message = try {
                contentResolver.openInputStream(uri)?.use { source ->
                    pending.outputStream().use { target ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            total += n
                            check(total <= 250L * 1024 * 1024) { "Model vượt giới hạn 250 MiB" }
                            target.write(buffer, 0, n)
                        }
                    }
                } ?: error("Không mở được tệp ONNX")
                OnnxOpenpilotBackend.open(pending).use { }
                check(pending.length() > 0L)
                if (ready.exists()) check(ready.delete()) { "Không thay thế được model cũ" }
                check(pending.renameTo(ready)) { "Không lưu được model" }
                "ONNX hợp lệ: 2 inputs / 1.576 outputs. Đã lưu offline; CHƯA sử dụng để vẽ ADAS vì thiếu warp/calibration và parser tọa độ."
            } catch (e: Exception) {
                "ONNX không tương thích hoặc lỗi: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                if (pending.exists()) pending.delete()
            }
            runOnUiThread {
                if (::report.isInitialized) report.text = message
                if (::status.isInitialized) show("Kiểm tra ONNX hoàn tất")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = VisionEngine(applicationContext)
        engine.reloadModel()
        root = FrameLayout(this)
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        previewContainer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            visibility = View.GONE
        }
        vlcSurface = VLCVideoLayout(this).apply { visibility = View.GONE }
        phonePreview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        overlay = AdasOverlay(this)
        previewContainer.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        previewContainer.addView(vlcSurface, FrameLayout.LayoutParams(-1, -1))
        previewContainer.addView(phonePreview, FrameLayout.LayoutParams(-1, -1))
        previewContainer.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        // Preserve video aspect in compact mode. Tap the picture to enter/exit fullscreen.
        compactPreviewHeight = (resources.displayMetrics.widthPixels * 9 / 16).coerceAtLeast(180)
        contentColumn.addView(previewContainer,
            LinearLayout.LayoutParams(-1, compactPreviewHeight))
        overlay.setOnClickListener { toggleVideoSize() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xDD0B1B28.toInt())
        }
        status = TextView(this).apply {
            text = "TTX Camera AI · Sẵn sàng (chưa xác minh A500S)"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
        }
        panel.addView(status)
        report = TextView(this).apply {
            text = "Kết nối Wi-Fi 70mai, sau đó bấm KIỂM TRA để xem gateway và kết quả RTSP."
            setTextColor(0xFFBBE3F1.toInt())
            textSize = 12f
            setTextIsSelectable(true)
            maxLines = 8
        }
        panel.addView(report)
        urlField = EditText(this).apply {
            setSingleLine()
            setText(getPreferences(MODE_PRIVATE).getString("url", candidates[0]))
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFFBBBBBB.toInt())
            textSize = 13f
            hint = "rtsp://IP:554/livestream/12"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        panel.addView(urlField)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, weight: Float = 1f, click: () -> Unit) {
            controls.addView(Button(this).apply { text = label; textSize = 11f; setOnClickListener { click() } },
                LinearLayout.LayoutParams(0, -2, weight))
        }
        button("70mai / VLC") { startRtsp(true) }
        button("Camera máy") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) startPhone()
            else requestCamera.launch(Manifest.permission.CAMERA)
        }
        button("Dừng") { stopSources(); show("Đã dừng") }
        panel.addView(controls)
        aiToggle = Button(this).apply {
            text = "AI THỬ NGHIỆM: TẮT · VIDEO ƯU TIÊN MƯỢT"
            textSize = 12f
            setOnClickListener {
                analysisEnabled = !analysisEnabled
                if (!analysisEnabled) {
                    overlay.visibility = View.INVISIBLE
                    show("AI tắt: chỉ giải mã và phát video trực tiếp.")
                } else {
                    overlay.visibility = View.VISIBLE
                    lastAnalysisRequestMs = 0L
                    show("AI OpenCV bật: lấy mẫu nhẹ, không được ảnh hưởng phát video.")
                }
                text = if (analysisEnabled)
                    "AI THỬ NGHIỆM: BẬT · LẤY MẪU 1 KHUNG/GIÂY"
                else "AI THỬ NGHIỆM: TẮT · VIDEO ƯU TIÊN MƯỢT"
            }
        }
        panel.addView(aiToggle)
        bufferToggle = Button(this).apply {
            text = "ĐỆM RTSP: 800 MS · ƯU TIÊN ỔN ĐỊNH"
            textSize = 12f
            setOnClickListener {
                vlcBufferMs = if (vlcBufferMs == 800) 300 else 800
                text = if (vlcBufferMs == 800)
                    "ĐỆM RTSP: 800 MS · ƯU TIÊN ỔN ĐỊNH"
                else "ĐỆM RTSP: 300 MS · ƯU TIÊN ĐỘ TRỄ THẤP"
                if (source == "rtsp" && vlcMode) startRtsp(true)
            }
        }
        panel.addView(bufferToggle)
        panel.addView(Button(this).apply {
            text = "MEDIA3 (SO SÁNH LỖI PLAY 404)"
            textSize = 12f
            setOnClickListener { startRtsp(false) }
        })
        panel.addView(Button(this).apply {
            text = "KIỂM TRA WIFI / RTSP A500S"
            textSize = 12f
            setOnClickListener { diagnose70mai() }
        })
        panel.addView(Button(this).apply {
            text = "OPENPILOT: KIỂM TRA ĐỊNH DẠNG MÔ HÌNH"
            textSize = 12f
            setOnClickListener {
                val contract = SupercomboV027Contract
                report.text = buildString {
                    append("Đã phân tích cấu trúc Supercombo trong xADAS 0.2.7:\n")
                    append("img & big_img: 1×12×128×256\n")
                    append("vout_pass: 1.576 float (11 nhánh)\n")
                    for (head in contract.heads) {
                        append(head.name).append(" · ")
                            .append(head.offset).append("..")
                            .append(head.endExclusive - 1).append("\n")
                    }
                    append("KHÔNG có trọng số Openpilot được cấp phép trong APK TTX.\n")
                    append("Chưa có bộ warp/YUV temporal đã được kiểm định.\n")
                    append("OpenCV hiện là backend hình học thử nghiệm, không phải suy luận Openpilot.")
                }
                show("Đã kiểm tra định dạng · Openpilot chưa kích hoạt")
            }
        })
        panel.addView(Button(this).apply {
            text = "CÀI ĐẶT ADAS · HUD / LÀN ĐƯỜNG / CẢNH BÁO"
            textSize = 12f
            setOnClickListener { startActivity(Intent(this@MainActivity, AdasSettingsActivity::class.java)) }
        })
        panel.addView(Button(this).apply {
            text = "NẠP ONNX CÓ QUYỀN SỬ DỤNG · KIỂM TRA MODEL"
            textSize = 12f
            setOnClickListener { importOnnx.launch(arrayOf("*/*")) }
        })
        panel.addView(Button(this).apply {
            text = "XUẤT 2 KHUNG HÌNH A500S (ZIP) · CHỈ KHI XE DỪNG"
            textSize = 12f
            setOnClickListener { exportCameraPair() }
        })
        val modelButton = Button(this).apply {
            text = "NẠP MODEL XE (.TFLITE) · KHÔNG BẮT BUỘC"
            textSize = 11f
            setOnClickListener { openModel.launch(arrayOf("*/*")) }
        }
        panel.addView(modelButton)
        panel.addView(TextView(this).apply {
            text = "Chạm vào video để bật/tắt xem lớn. Chỉ kiểm thử khi xe dừng."
            setTextColor(0xFF9ABBC6.toInt())
            textSize = 12f
        })
        settingsScroll = ScrollView(this).apply {
            addView(panel)
        }
        contentColumn.addView(settingsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(contentColumn, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        show("Chỉ nghiên cứu khi xe dừng. Khoảng cách chưa được hiệu chuẩn.")
    }

    private fun show(message: String) { status.text = message }

    private fun toggleVideoSize() {
        expandedVideo = !expandedVideo
        settingsScroll.visibility = if (expandedVideo) View.GONE else View.VISIBLE
        previewContainer.layoutParams = LinearLayout.LayoutParams(
            -1, if (expandedVideo) 0 else compactPreviewHeight,
            if (expandedVideo) 1f else 0f
        )
        previewContainer.requestLayout()
    }

    private fun stopSources() {
        source = "none"
        sampleGeneration++
        sampleCaptureActive = false
        retryCount = 0
        retryActive = false
        selectedWifi = null
        trialUrls = emptyList()
        videoRendered = false
        copiedFrames = 0
        droppedSamples = 0
        lastAnalysisRequestMs = 0L
        analyzedFrames = 0
        lastMetricTime = 0L
        handler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        vlcPlayer?.close()
        vlcPlayer = null
        vlcSurface.visibility = View.GONE
        playerView.player = null
        player?.release()
        player = null
        phonePreview.visibility = View.GONE
        playerView.visibility = View.GONE
        overlay.visibility = if (analysisEnabled) View.VISIBLE else View.INVISIBLE
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }

    private fun diagnose70mai() {
        show("Đang kiểm tra kết nối Wi-Fi và RTSP…")
        val manual = urlField.text.toString().trim()
        worker.execute {
            val wifi = CameraConnectionCheck.wifi(
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            val lines = mutableListOf(wifi.detail)
            if (wifi.network != null && wifi.gateway != null) {
                val urls = CameraConnectionCheck.urls(wifi.gateway, manual)
                for (url in urls) {
                    val test = CameraConnectionCheck.describe(wifi.network, url)
                    lines += url + " => " + test.status + " · " + test.note
                    if (test.ok || test.status.contains(" 401 ") || test.status.contains(" 403 ")) break
                }
            }
            runOnUiThread {
                report.text = lines.joinToString("\n")
                show("Hoàn tất kiểm tra. Giữ ngón tay trên báo cáo để sao chép.")
            }
        }
    }

    private fun startRtsp(useVlc: Boolean) {
        stopSources()
        vlcMode = useVlc
        source = "rtsp"
        show("Đang xác định đúng gateway 70mai…")
        val manual = urlField.text.toString().trim()
        worker.execute {
            val wifi = CameraConnectionCheck.wifi(
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            if (wifi.network == null || wifi.gateway == null) {
                runOnUiThread {
                    if (source == "rtsp") {
                        show("Không thấy Wi-Fi 70mai. Cần kết nối trước.")
                        report.text = wifi.detail
                        stopSources()
                    }
                }
                return@execute
            }
            val urls = CameraConnectionCheck.urls(wifi.gateway, manual)
            val lines = mutableListOf(wifi.detail)
            if (manual.isNotBlank() && !urls.contains(manual))
                lines += "Bỏ qua " + manual + ": khác gateway Wi-Fi " + wifi.gateway
            var found = -1
            for (i in urls.indices) {
                if (source != "rtsp") return@execute
                val test = CameraConnectionCheck.describe(wifi.network, urls[i])
                lines += test.url + " => " + test.status + " · " + test.note
                if (test.ok) { found = i; break }
                if (test.status.contains(" 401 ") || test.status.contains(" 403 ")) break
            }
            val selected = found
            runOnUiThread {
                if (source != "rtsp") return@runOnUiThread
                report.text = lines.takeLast(8).joinToString("\n")
                if (selected < 0) {
                    show("Không tìm thấy RTSP hợp lệ. Xem báo cáo chẩn đoán.")
                    stopSources()
                } else {
                    selectedWifi = wifi.network
                    if (vlcMode) {
                        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        if (!cm.bindProcessToNetwork(wifi.network)) {
                            show("Không thể ghim luồng VLC vào Wi-Fi 70mai")
                            stopSources()
                            return@runOnUiThread
                        }
                    }
                    trialUrls = urls
                    candidateIndex = selected
                    urlField.setText(urls[selected])
                    connectRtsp(urls[selected])
                    handler.postDelayed({ watchdog() }, 1300L)
                }
            }
        }
    }

    private fun connectRtsp(url: String) {
        if (source != "rtsp") return
        val network = selectedWifi ?: return
        if (vlcMode) {
            connectVlc(url)
            return
        }
        playerView.player = null
        player?.release()
        retryActive = false
        videoRendered = false
        activeUrl = url
        lastFrame = SystemClock.elapsedRealtime()
        phonePreview.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (source == "rtsp" && player === p) {
                    report.text = report.text.toString() +
                        "\nMedia3: " + error.errorCodeName + " / " +
                        (error.cause?.message ?: error.message)
                    show("RTSP lỗi giải mã hoặc phiên; đang thử nguồn khác…")
                    handler.postDelayed({ tryNextUrl() }, 600L)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (player === p && state == Player.STATE_READY)
                    show("RTSP đã sẵn sàng, chờ khung hình đầu tiên…")
            }
            override fun onRenderedFirstFrame() {
                if (player === p && source == "rtsp") {
                    videoRendered = true
                    lastFrame = SystemClock.elapsedRealtime()
                    show("ĐÃ NHẬN VIDEO 70MAI · " + activeUrl)
                }
            }
        })
        try {
            val media = MediaItem.fromUri(url)
            val factory = RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .setSocketFactory(network.socketFactory).setTimeoutMs(6000)
            p.setMediaSource(factory.createMediaSource(media))
            p.prepare()
            p.playWhenReady = true
            show("Đang kết nối RTSP qua Wi-Fi camera: " + url)
        } catch (e: Exception) {
            report.text = report.text.toString() + "\nRTSP: " + e.message
            handler.postDelayed({ tryNextUrl() }, 600L)
        }
    }

    private fun connectVlc(url: String) {
        if (source != "rtsp") return
        vlcPlayer?.close()
        vlcPlayer = null
        playerView.visibility = View.GONE
        vlcSurface.visibility = View.VISIBLE
        phonePreview.visibility = View.GONE
        retryActive = false
        videoRendered = false
        activeUrl = url
        lastFrame = SystemClock.elapsedRealtime()
        show("VLC đang thiết lập phiên A500S qua Wi-Fi…")
        try {
            val client = VlcCameraPlayer(this, vlcSurface, vlcBufferMs) { text, hasVideo ->
                runOnUiThread {
                    if (source != "rtsp" || !vlcMode) return@runOnUiThread
                    if (hasVideo) {
                        videoRendered = true
                        lastFrame = SystemClock.elapsedRealtime()
                        show("ĐÃ NHẬN VIDEO A500S (VLC) · " + activeUrl)
                    } else if (!videoRendered) {
                        show(text)
                        if (text.contains("lỗi") || text.contains("hết luồng")) {
                            report.text = report.text.toString() + "\n" + text
                            handler.postDelayed({ tryNextUrl() }, 750L)
                        }
                    }
                }
            }
            vlcPlayer = client
            client.play(url)
        } catch (e: Exception) {
            report.text = report.text.toString() + "\nVLC: " + (e.message ?: e.javaClass.simpleName)
            show("VLC lỗi khởi tạo. Thử nút Media3 để đối chiếu.")
            handler.postDelayed({ tryNextUrl() }, 700L)
        }
    }

    /** Capture two genuinely distinct-time frames from the actual LibVLC surface. */
    private fun exportCameraPair() {
        if (source != "rtsp" || !vlcMode || !videoRendered) {
            show("Cần phát hình bằng nút 70mai / VLC trước khi xuất mẫu.")
            return
        }
        if (sampleCaptureActive || !busy.compareAndSet(false, true)) {
            show("Bộ xử lý đang bận; hãy thử xuất mẫu lại.")
            return
        }
        val surface = findSurface(vlcSurface)
        if (surface == null) {
            sampleCaptureActive = false
            busy.set(false)
            show("Không tìm thấy SurfaceView VLC để lấy mẫu.")
            return
        }
        sampleCaptureActive = true
        val generation = ++sampleGeneration
        fun capture(done: (Bitmap, Long) -> Unit) {
            val image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(surface, image, { result ->
                    if (result == PixelCopy.SUCCESS &&
                        generation == sampleGeneration && source == "rtsp") {
                        done(image, SystemClock.elapsedRealtime())
                    } else {
                        image.recycle()
                        sampleCaptureActive = false
                        busy.set(false)
                        if (generation == sampleGeneration)
                            show("Không lấy được khung hình (PixelCopy $result).")
                    }
                }, handler)
            } catch (e: Exception) {
                image.recycle()
                sampleCaptureActive = false
                busy.set(false)
                show("PixelCopy lỗi: " + (e.message ?: "không rõ"))
            }
        }
        show("Đang chụp hai khung hình trên A500S…")
        capture { previous, firstMs ->
            handler.postDelayed({
                if (generation != sampleGeneration || source != "rtsp") {
                    previous.recycle()
                    sampleCaptureActive = false
                    busy.set(false)
                    return@postDelayed
                }
                capture { current, nextMs ->
                    sampleCaptureActive = false
                    busy.set(false)
                    val file = File(cacheDir, "ttx_a500s_sample_" + System.currentTimeMillis() + ".zip")
                    worker.execute {
                        val message = try {
                            CameraFramePairExporter.save(file, previous, current, firstMs, nextMs)
                            "Đã lấy hai khung, cách nhau " + (nextMs - firstMs) +
                                " ms. Chọn nơi lưu ZIP trên điện thoại."
                        } catch (e: Exception) {
                            file.delete()
                            "Không tạo được mẫu: " + (e.message ?: e.javaClass.simpleName)
                        } finally {
                            previous.recycle()
                            current.recycle()
                        }
                        runOnUiThread {
                            if (file.isFile && file.length() > 0) {
                                preparedSample?.delete()
                                preparedSample = file
                                show(message)
                                exportSample.launch(file.name)
                            } else show(message)
                        }
                    }
                }
            }, 350L)
        }
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView && view.holder.surface.isValid) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val found = findSurface(view.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    private fun tryNextUrl() {
        if (source != "rtsp" || retryActive) return
        retryActive = true
        playerView.player = null
        player?.release()
        player = null
        vlcPlayer?.close()
        vlcPlayer = null
        val network = selectedWifi ?: return
        val startIndex = candidateIndex + 1
        worker.execute {
            var next = -1
            val lines = mutableListOf<String>()
            for (i in startIndex until trialUrls.size) {
                if (source != "rtsp") return@execute
                val test = CameraConnectionCheck.describe(network, trialUrls[i])
                lines += test.url + " => " + test.status
                if (test.ok) { next = i; break }
            }
            val index = next
            runOnUiThread {
                if (source != "rtsp") return@runOnUiThread
                report.text = report.text.toString() + "\n" + lines.joinToString("\n")
                if (index < 0) {
                    show("Đã thử hết các đường dẫn. Cần kiểm tra kết nối/firmware.")
                    stopSources()
                } else {
                    candidateIndex = index
                    urlField.setText(trialUrls[index])
                    connectRtsp(trialUrls[index])
                }
            }
        }
    }

    private fun watchdog() {
        if (source != "rtsp") return
        val now = SystemClock.elapsedRealtime()
        // Critical: do not restart a playing LIVE555 stream just because AI
        // or PixelCopy did not deliver a new sample. Vout is not per-frame.
        if (!videoRendered && now - lastFrame > 12000L) {
            report.text = report.text.toString() + "\nVLC chưa tạo được video sau 12 giây."
            tryNextUrl()
        } else if (analysisEnabled && videoRendered &&
            now - lastAnalysisRequestMs >= 1000L) {
            val surface = if (vlcMode) findSurface(vlcSurface)
                else playerView.videoSurfaceView as? SurfaceView
            if (surface != null && surface.holder.surface.isValid &&
                busy.compareAndSet(false, true)) {
                lastAnalysisRequestMs = now
                val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                val generation = sampleGeneration
                try {
                    // Callback now executes OFF the UI thread.
                    PixelCopy.request(surface, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS && generation == sampleGeneration &&
                            source == "rtsp" && analysisEnabled) {
                            copiedFrames++
                            processFrame(bitmap)
                        } else {
                            droppedSamples++
                            bitmap.recycle()
                            busy.set(false)
                        }
                    }, copyHandler)
                } catch (_: Exception) {
                    droppedSamples++
                    bitmap.recycle()
                    busy.set(false)
                }
            } else if (surface != null) {
                droppedSamples++
            }
        }
        handler.postDelayed({ watchdog() }, 250L)
    }

    private fun startPhone() {
        stopSources()
        source = "phone"
        phonePreview.visibility = View.VISIBLE
        playerView.visibility = View.GONE
        vlcSurface.visibility = View.GONE
        show("Camera điện thoại: đang khởi động…")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (source != "phone") return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.surfaceProvider = phonePreview.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { proxy ->
                    if (!busy.compareAndSet(false, true)) { proxy.close(); return@setAnalyzer }
                    val bmp = try {
                        val frame = proxy.toBitmap()
                        val degrees = proxy.imageInfo.rotationDegrees
                        if (degrees == 0) frame else {
                            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
                        }
                    } catch (_: Exception) { null }
                    proxy.close()
                    if (bmp == null) { busy.set(false) }
                    else processFrame(bmp)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                show("Camera điện thoại · Làn đường: nghiên cứu")
            } catch (e: Exception) { show("CameraX lỗi: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(bitmap: Bitmap) {
        worker.execute {
            try {
                val result = engine.analyze(bitmap)
                analyzedFrames++
                val now = SystemClock.elapsedRealtime()
                runOnUiThread {
                    if (source == "none") return@runOnUiThread
                    overlay.render(result)
                    if (now - lastMetricTime >= 900L) {
                        lastMetricTime = now
                        val laneCount = listOfNotNull(result.left, result.right).size
                        val lead = result.vehicle
                        show("Nguồn: " + (if (source == "rtsp") "70mai A500S" else "Camera điện thoại") +
                            " · AI: " + analyzedFrames + " khung" +
                            " · Vạch: " + laneCount + "/2" +
                            (if (lead == null) " · Chưa có phát hiện xe"
                             else " · Xe ~" + lead.approxM.toInt() + "m (chưa hiệu chuẩn)"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { show("Xử lý ảnh lỗi: ${e.message}") }
            } finally {
                bitmap.recycle()
                busy.set(false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (AdasPrefs.of(this).getBoolean(AdasPrefs.HIDE_STATUS_BAR, false))
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (::overlay.isInitialized) overlay.invalidate()
    }

    override fun onDestroy() {
        preparedSample?.delete()
        stopSources()
        worker.execute { engine.close() }
        worker.shutdown()
        copyThread.quitSafely()
        super.onDestroy()
    }
}
