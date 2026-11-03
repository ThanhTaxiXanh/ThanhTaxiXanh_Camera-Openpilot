package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.Locale

/**
 * TTX Camera AI — compact PLAYBACK BENCHMARK ONLY.
 *
 * Backend 1: public IJKPlayer/FFmpeg similar to the official 70mai playback
 * architecture. This is NOT the proprietary 70mai SDK or the same native
 * build / options as version 4.5.0.
 *
 * Backend 2: clean-room RTSP/RTP + MediaCodec based on the architectural
 * features visible in xADAS. NO xADAS binaries or model weights.
 *
 * AI, OpenCV, Openpilot, CameraX, LibVLC and Media3 are intentionally absent.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private enum class Backend { IJK, DIRECT }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var ijkButton: Button
    private lateinit var directButton: Button
    private lateinit var stopButton: Button

    private var backend: Backend? = null
    private var direct: DirectRtspPlayer? = null
    private var ijk: IjkMediaPlayer? = null
    private var network: Network? = null
    private var url: String? = null
    private var generation = 0
    private var retryCount = 0
    private var preferUdp = true
    private var startedAt = 0L
    private var previousSampleTime = 0L
    private var previousPackets = 0L
    private var previousAccessUnits = 0L
    private var previousDecoded = 0L
    private var previousRendered = 0L
    private var ijkRecoveryPending = false
    private var ijkRetryCount = 0
    private var ijkOpenedAtMs = 0L
    private var ijkFirstVideoAtMs = 0L
    private var ijkLastHealthyMs = 0L
    private var ijkHadFps = false
    private var ijkZeroFpsSamples = 0
    private var ijkLastStopCause = "Chưa có"
    private var lastMessage = "Chưa kết nối"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 16, 22))
            setPadding(12, 10, 12, 10)
        }
        val header = label("TTX CAMERA AI · VIDEO BENCHMARK", 17f, Color.WHITE)
        header.gravity = Gravity.CENTER
        root.addView(header)

        surfaceView = SurfaceView(this).apply {
            // SurfaceView normally punches a transparent hole through the Activity
            // window to expose its separate video layer behind the window.
            // V1.0.1 set an opaque BLACK View background here, painting over
            // that hole. The decoder actually produced frames, but users saw
            // a black screen in BOTH IJKPlayer and Direct MediaCodec modes.
            // Never draw an opaque background onto the SurfaceView itself.
            background = null
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(this@MainActivity)
            keepScreenOn = true
        }
        root.addView(surfaceView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ijkButton = Button(this).apply {
            text = "70MAI · IJK/FFMPEG"
            textSize = 11f
            setOnClickListener { begin(Backend.IJK) }
        }
        directButton = Button(this).apply {
            text = "xADAS · MEDIACODEC"
            textSize = 11f
            setOnClickListener { begin(Backend.DIRECT) }
        }
        buttons.addView(ijkButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(directButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)

        stopButton = Button(this).apply {
            text = "DỪNG PHÁT"
            textSize = 12f
            setOnClickListener {
                stopPlayback()
                showStatus("Đã dừng và giải phóng decoder")
            }
        }
        root.addView(stopButton)

        statusView = label("Kết nối Wi-Fi 70mai A500S để thử phát trực tiếp", 13f,
            Color.rgb(168, 220, 200))
        detailView = label("Chỉ thử nghiệm phát video. AI/Openpilot đã tạm loại khỏi APK.", 12f,
            Color.LTGRAY)
        root.addView(statusView)
        root.addView(detailView)
        root.addView(label(
            "70mai = IJKPlayer nguồn mở (không phải bản quyền 70mai 4.5.0)." +
                " xADAS = bộ phát RTSP/MediaCodec tự triển khai.", 10f,
            Color.rgb(149, 155, 161)))
        setContentView(root)
        ui.post(statusTicker)
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(7, 7, 7, 7)
        setTextIsSelectable(true)
    }

    private fun showStatus(text: String) {
        lastMessage = text.take(350)
        if (::statusView.isInitialized) statusView.text = lastMessage
    }

    private fun begin(mode: Backend) {
        stopPlayback()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = CameraConnectionCheck.wifi(cm)
        val gw = wifi.gateway
        if (wifi.network == null || gw == null) {
            showStatus("Không thấy Wi-Fi 70mai. " + wifi.detail)
            return
        }
        if (!surfaceView.holder.surface.isValid) {
            showStatus("Surface chưa sẵn sàng. Thử lại khi khung video xuất hiện.")
            return
        }
        network = wifi.network
        url = "rtsp://" + gw + ":554/livestream/12"
        backend = mode
        retryCount = 0
        preferUdp = true
        startedAt = SystemClock.elapsedRealtime()
        ijkRetryCount = 0
        ijkRecoveryPending = false
        ijkLastStopCause = "Chưa có"
        showStatus(wifi.detail + " · " + url)
        when (mode) {
            Backend.IJK -> startIjk(generation)
            Backend.DIRECT -> startDirect(generation)
        }
    }

    private fun startIjk(token: Int) {
        if (token != generation || backend != Backend.IJK) return
        val currentUrl = url ?: return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = network ?: return
        if (!surfaceView.holder.surface.isValid) {
            showStatus("Surface không còn khả dụng; đang dừng IJK.")
            return
        }
        if (!cm.bindProcessToNetwork(wifi)) {
            showStatus("Không thể gắn IJK/FFmpeg vào Wi-Fi camera")
            backend = null
            return
        }
        ijkOpenedAtMs = SystemClock.elapsedRealtime()
        ijkFirstVideoAtMs = 0L
        ijkLastHealthyMs = 0L
        ijkZeroFpsSamples = 0
        ijkHadFps = false
        try {
            val player = IjkMediaPlayer()
            ijk = player
            // Keep the exact V1.0.2 settings reported as smooth.
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 32768L)
            player.setDisplay(surfaceView.holder)
            player.setDataSource(currentUrl)
            player.setOnPreparedListener {
                if (token == generation && backend == Backend.IJK && ijk === player) {
                    it.start()
                    showStatus("70mai IJK: đang phát (phiên mới)")
                }
            }
            player.setOnCompletionListener {
                if (token == generation && backend == Backend.IJK && ijk === player)
                    recoverIjk(token, "RTSP kết thúc (OnCompletion)")
            }
            player.setOnErrorListener { _, what, extra ->
                if (token == generation && backend == Backend.IJK && ijk === player)
                    recoverIjk(token, "IJK OnError=$what/$extra")
                true
            }
            player.setOnInfoListener { _, what, _ ->
                if (token == generation && backend == Backend.IJK && ijk === player) {
                    when (what) {
                        3 -> {
                            ijkFirstVideoAtMs = SystemClock.elapsedRealtime()
                            ijkLastHealthyMs = ijkFirstVideoAtMs
                            showStatus("IJK: đã nhận khung hình đầu tiên")
                        }
                        701 -> showStatus("IJK: đang đệm dữ liệu...")
                        702 -> showStatus("IJK: tiếp tục phát sau đệm")
                    }
                }
                false
            }
            player.prepareAsync()
        } catch (error: Throwable) {
            if (token == generation && backend == Backend.IJK) {
                recoverIjk(token, "Khởi tạo IJK: " +
                    error.javaClass.simpleName + ": " + (error.message ?: "").take(100))
            }
        }
    }

    /**
     * Recovery for a camera ending an IJK RTSP session at approximately 30s.
     * Does not claim to fix missing authenticated camera CGI heartbeat.
     * Old native IJK is released BEFORE opening a new session.
     */
    private fun recoverIjk(token: Int, reason: String) {
        if (token != generation || backend != Backend.IJK || ijkRecoveryPending) return
        ijkRecoveryPending = true
        ijkLastStopCause = reason.take(140)
        val now = SystemClock.elapsedRealtime()
        if (ijkFirstVideoAtMs > 0L && now - ijkFirstVideoAtMs >= 15_000L)
            ijkRetryCount = 0
        ijkRetryCount++
        if (ijkRetryCount > 5) {
            showStatus("IJK dừng sau 5 lần lỗi liên tiếp: " + reason)
            val old = ijk
            ijk = null
            backend = null
            ijkRecoveryPending = false
            Thread({ releaseIjk(old) }, "TTX-IJK-Release").start()
            return
        }
        val delay = longArrayOf(500L, 1200L, 2500L, 4000L, 6000L)[ijkRetryCount-1]
        val old = ijk
        ijk = null
        showStatus("IJK ngắt sau " + ((now - ijkOpenedAtMs)/1000) +
            "s · " + reason + " · khôi phục " + ijkRetryCount + "/5")
        Thread({
            releaseIjk(old)
            ui.postDelayed({
                if (token != generation || backend != Backend.IJK ||
                    !ijkRecoveryPending) return@postDelayed
                ijkRecoveryPending = false
                startIjk(token)
            }, delay)
        },"TTX-IJK-Release").start()
    }

    private fun releaseIjk(player: IjkMediaPlayer?) {
        if (player == null) return
        try { player.setDisplay(null) } catch (_: Throwable) {}
        try { player.stop() } catch (_: Throwable) {}
        try { player.release() } catch (_: Throwable) {}
    }

    private fun startDirect(token: Int) {
        val wifi = network ?: return
        val currentUrl = url ?: return
        if (!surfaceView.holder.surface.isValid) return
        previousSampleTime = 0L
        previousPackets = 0L
        previousAccessUnits = 0L
        previousDecoded = 0L
        previousRendered = 0L
        val selectedUdp = preferUdp
        val player = DirectRtspPlayer(
            wifi, currentUrl, surfaceView.holder.surface,
            { false },
            { message ->
                runOnUiThread {
                    if (token == generation && backend == Backend.DIRECT)
                        showStatus("xADAS-style: " + message)
                }
            },
            {
                runOnUiThread {
                    if (token == generation && backend == Backend.DIRECT)
                        showStatus("MediaCodec đã xuất khung giải mã · " +
                            if (selectedUdp) "RTP/UDP" else "RTP/TCP")
                }
            },
            { _, _, _ -> }, // Deliberately no AI/frame copying in benchmark
            { reason ->
                runOnUiThread {
                    if (token == generation && backend == Backend.DIRECT)
                        recoverDirect(token, reason)
                }
            },
            selectedUdp
        )
        direct = player
        player.start()
    }

    private fun recoverDirect(token: Int, reason: String) {
        if (token != generation || backend != Backend.DIRECT) return
        if (preferUdp && (reason.contains("zero video packets", true) ||
                reason.contains("zero H264 access units", true))) {
            preferUdp = false
            showStatus("A500S UDP không có video → thử TCP mới")
        }
        if (SystemClock.elapsedRealtime() - startedAt > 60_000L)
            retryCount = 0
        retryCount++
        if (retryCount > 3) {
            showStatus("Direct đã ngắt sau 3 lần thử: " + reason)
            stopPlayback()
            return
        }
        direct?.close()
        direct = null
        val delay = 1200L * retryCount
        showStatus("RTSP ngắt · thử lại " + retryCount + "/3 trong " +
            delay + "ms · " + reason.take(90))
        ui.postDelayed({
            if (token == generation && backend == Backend.DIRECT) startDirect(token)
        }, delay)
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            if (::detailView.isInitialized) {
                val d = direct
                if (backend == Backend.DIRECT && d != null) {
                    val now = SystemClock.elapsedRealtime()
                    val s = d.counters()
                    val dt = if (previousSampleTime == 0L) 2.0
                        else (now - previousSampleTime).coerceAtLeast(1L) / 1000.0
                    val packets = (s.rtp.packets - previousPackets).coerceAtLeast(0L)
                    val au = (s.receivedAccessUnits - previousAccessUnits).coerceAtLeast(0L)
                    val decoded = (s.decoded - previousDecoded).coerceAtLeast(0L)
                    val shown = (s.rendered - previousRendered).coerceAtLeast(0L)
                    detailView.text = String.format(Locale.US,
                        "%s · RTP %.1f/s · AU %.1f/s · giải mã %.1f/s · Surface %.1f/s\n" +
                            "RTP gap %d · H264 hỏng %d · đệm %d/48\n" +
                            "UDP đến lệch: %d · khôi phục %d · thiếu %d gói\n" +
                            "RTSP heartbeat %d/%d OK · reconnect %d/3",
                        s.transportName, packets / dt, au / dt, decoded / dt, shown / dt,
                        s.rtp.sequenceGaps, s.discardedAccessUnits, s.queueDepth,
                        s.udpReordered, s.udpRecovered, s.udpSkipped,
                        s.keepAliveOk, s.keepAliveSent, retryCount)
                    previousPackets = s.rtp.packets
                    previousAccessUnits = s.receivedAccessUnits
                    previousDecoded = s.decoded
                    previousRendered = s.rendered
                    previousSampleTime = now
                } else if (backend == Backend.IJK) {
                    val media = ijk
                    val now = SystemClock.elapsedRealtime()
                    var decodeFps = 0f
                    var outputFps = 0f
                    var cachedMs = 0L
                    var nativePlaying = false
                    if (media != null) {
                        try {
                            decodeFps = media.videoDecodeFramesPerSecond
                            outputFps = media.videoOutputFramesPerSecond
                            cachedMs = media.videoCachedDuration
                            nativePlaying = media.isPlaying
                        } catch (_: Throwable) {}
                    }
                    if (!ijkRecoveryPending && media != null) {
                        if (outputFps > 2f) {
                            ijkHadFps = true
                            ijkLastHealthyMs = now
                            ijkZeroFpsSamples = 0
                        } else if (ijkHadFps && now - ijkLastHealthyMs > 5500L) {
                            ijkZeroFpsSamples++
                            if (ijkZeroFpsSamples >= 2)
                                recoverIjk(generation,
                                    "Không còn khung IJK >5 giây (decode=" +
                                    "%.1f/output=%.1f)".format(
                                        Locale.US, decodeFps, outputFps))
                        } else if (ijkFirstVideoAtMs == 0L &&
                            now - ijkOpenedAtMs > 12000L) {
                            recoverIjk(generation, "IJK chưa xuất khung sau 12 giây")
                        }
                    }
                    detailView.text = String.format(Locale.US,
                        "IJK/FFmpeg · %d×%d · giải mã %.1f FPS · xuất %.1f FPS\n" +
                            "Đệm %dms · đang phát %s · phiên %ds · khôi phục %d/5\n" +
                            "Lỗi cuối: %s",
                        media?.videoWidth ?: 0, media?.videoHeight ?: 0,
                        decodeFps, outputFps, cachedMs,
                        if (nativePlaying) "CÓ" else "KHÔNG",
                        (now - ijkOpenedAtMs).coerceAtLeast(0L) / 1000L,
                        ijkRetryCount, ijkLastStopCause)
                } else {
                    detailView.text = "Chỉ gồm hai bộ phát: IJKPlayer và Direct MediaCodec."
                }
            }
            ui.postDelayed(this, 1000L)
        }
    }

    private fun stopPlayback() {
        generation++
        backend = null
        direct?.close()
        direct = null
        ijkRecoveryPending = false
        val old = ijk
        ijk = null
        if (old != null) Thread({ releaseIjk(old) }, "TTX-IJK-Stop").start()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.bindProcessToNetwork(null)
        network = null
        url = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { stopPlayback() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopPlayback()
        super.onDestroy()
    }
}
