package vn.thanhtaxixanh.camera.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var phonePreview: PreviewView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = VisionEngine(applicationContext)
        engine.reloadModel()
        root = FrameLayout(this)
        playerView = PlayerView(this).apply { useController = false; visibility = View.GONE }
        phonePreview = PreviewView(this).apply { visibility = View.GONE }
        overlay = AdasOverlay(this)
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(phonePreview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

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
        button("70mai/RTSP") { startRtsp() }
        button("Camera máy") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) startPhone()
            else requestCamera.launch(Manifest.permission.CAMERA)
        }
        button("Dừng") { stopSources(); show("Đã dừng") }
        panel.addView(controls)
        val modelButton = Button(this).apply {
            text = "NẠP MODEL XE (.TFLITE) · KHÔNG BẮT BUỘC"
            textSize = 11f
            setOnClickListener { openModel.launch(arrayOf("*/*")) }
        }
        panel.addView(modelButton)
        val scroller = ScrollView(this).apply {
            addView(panel)
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        show("Chỉ nghiên cứu khi xe dừng. Khoảng cách chưa được hiệu chuẩn.")
    }

    private fun show(message: String) { status.text = message }

    private fun stopSources() {
        source = "none"
        retryCount = 0
        cameraProvider?.unbindAll()
        player?.release()
        player = null
        phonePreview.visibility = View.GONE
        playerView.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }

    private fun bindWifiIfPresent(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = manager.allNetworks.firstOrNull { net ->
            manager.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false
        return manager.bindProcessToNetwork(wifi)
    }

    private fun startRtsp() {
        stopSources()
        source = "rtsp"
        phonePreview.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        val text = urlField.text.toString().trim()
        if (!text.startsWith("rtsp://", true) && !text.startsWith("rtsps://", true)) {
            show("URL RTSP không hợp lệ"); stopSources(); return
        }
        val wifi = bindWifiIfPresent()
        candidateIndex = candidates.indexOf(text)
        retryCount = 0
        connectRtsp(text)
        if (!wifi) show("Không thấy Wi-Fi camera. Kiểm tra kết nối 70mai rồi thử lại.")
        handler.postDelayed({ watchdog() }, 3500L)
    }

    private fun connectRtsp(url: String) {
        if (source != "rtsp") return
        player?.release()
        activeUrl = url
        lastFrame = SystemClock.elapsedRealtime()
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (source == "rtsp" && player === p) {
                    show("Lỗi RTSP: ${error.errorCodeName}; đang thử nguồn kế tiếp")
                    handler.postDelayed({ tryNextUrl() }, 1500)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player === p) show("Đã nhận phiên RTSP; đang kiểm tra khung hình…")
            }
        })
        try {
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            show("RTSP lỗi: ${e.message}")
            handler.postDelayed({ tryNextUrl() }, 1200)
        }
    }

    private fun tryNextUrl() {
        if (source != "rtsp") return
        if (retryCount++ >= candidates.size) {
            show("Không nhận video. Kiểm tra ghép nối, IP gateway hoặc firmware A500S.")
            stopSources()
            return
        }
        candidateIndex = (candidateIndex + 1 + candidates.size) % candidates.size
        val next = candidates[candidateIndex]
        urlField.setText(next)
        show("Đang thử: $next")
        connectRtsp(next)
    }

    private fun watchdog() {
        if (source != "rtsp") return
        val elapsed = SystemClock.elapsedRealtime() - lastFrame
        if (elapsed > 8000L) {
            tryNextUrl()
        } else {
            val surface = playerView.videoSurfaceView
            if (surface is SurfaceView && surface.holder.surface.isValid &&
                busy.compareAndSet(false, true)) {
                val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(surface, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) {
                            lastFrame = SystemClock.elapsedRealtime()
                            processFrame(bitmap)
                        } else {
                            busy.set(false)
                            bitmap.recycle()
                        }
                    }, handler)
                } catch (_: Exception) {
                    bitmap.recycle(); busy.set(false)
                }
            }
        }
        handler.postDelayed({ watchdog() }, 330L)
    }

    private fun startPhone() {
        stopSources()
        source = "phone"
        phonePreview.visibility = View.VISIBLE
        playerView.visibility = View.GONE
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
                runOnUiThread {
                    if (source == "none") return@runOnUiThread
                    overlay.render(result)
                    val lead = result.vehicle
                    show(if (lead == null) result.note
                         else "Xe phía trước ~${lead.approxM.toInt()} m · CHƯA HIỆU CHUẨN")
                }
            } catch (e: Exception) {
                runOnUiThread { show("Xử lý ảnh lỗi: ${e.message}") }
            } finally {
                bitmap.recycle()
                busy.set(false)
            }
        }
    }

    override fun onDestroy() {
        stopSources()
        worker.execute { engine.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
