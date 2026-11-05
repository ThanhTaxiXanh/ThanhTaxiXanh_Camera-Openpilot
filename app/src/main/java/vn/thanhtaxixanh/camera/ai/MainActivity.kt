package vn.thanhtaxixanh.camera.ai

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import android.view.View
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.Locale
import java.net.Inet4Address
import java.security.SecureRandom
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

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
    private lateinit var adasHud: AdasHudView
    private lateinit var gpsMonitor: GpsSpeedMonitor
    private var gpsEnabled = false
    private var pipTransitioning = false
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var ijkButton: Button
    private lateinit var directButton: Button
    private lateinit var stopButton: Button
    private lateinit var pairButton: Button
    private lateinit var latencyButton: Button
    private lateinit var directLatencyButton: Button
    private lateinit var yuvTapButton: Button
    private lateinit var rawAiButton: Button
    private lateinit var yuvCaptureButton: Button
    private val liveYuvCapture = LiveYuvEvidenceCapture(4)
    private var pendingYuvEvidence: OfflineYuvFixture.Sequence? = null
    private val saveYuvRequestCode = 734
    private lateinit var liveTraceButton: Button
    private var activeLiveTrace: FiveMinuteStreamEvidence? = null
    private var pendingLiveTrace: String? = null
    private val saveLiveTraceRequestCode = 735
    private val saveGpsPipTraceRequestCode = 736
    private lateinit var gpsPipTraceButton: Button
    private var activeGpsPipTrace: GpsPipFieldEvidence? = null
    private var pendingGpsPipTrace: String? = null
    @Volatile private var rawVision: OpenpilotInferencePipeline? = null
    @Volatile private var rawBridge: A500sRawVisionBridge? = null
    @Volatile private var rawVisionRequested = false
    private var rawVisionLoading = false
    // UI-only nonce prevents a cancelled model load from resurrecting when
    // the owner quickly switches RAW ON/OFF during the same RTSP session.
    private var rawVisionSequence = 0L
    private val rawResults = AtomicLong(0L)
    private val rawPairResetPending = AtomicBoolean(false)
    @Volatile private var rawModelStatus = "AI chưa bật"
    @Volatile private var yuvTapEnabled = false
    private val yuvFrames = AtomicLong(0L)
    @Volatile private var yuvShape = "chưa có khung"
    private var ijkProfile = IjkPlaybackProfile.SMOOTH
    private var activeIjkProfile = IjkPlaybackProfile.SMOOTH
    private var directLatencyPolicy = DirectLatencyPolicy.SMOOTH
    private var activeDirectLatencyPolicy = DirectLatencyPolicy.SMOOTH

    private var backend: Backend? = null
    private var direct: DirectRtspPlayer? = null
    private val directCallbackEpoch = DirectCallbackEpochGate()
    private var ijk: IjkMediaPlayer? = null
    private var network: Network? = null
    private var url: String? = null
    private var ownerSession: A500sOwnerSession? = null
    @Volatile private var generation = 0
    private var retryCount = 0
    private val directReconnectBudget = DirectReconnectBudget()
    private var directRecoveryPending = false
    private var preferUdp = true
    private var startedAt = 0L
    private var previousSampleTime = 0L
    private var previousPackets = 0L
    private var previousMarkers = 0L
    private var previousRtpClocks = 0L
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
        gpsMonitor = GpsSpeedMonitor(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 16, 22))
            setPadding(12, 10, 12, 10)
        }
        val header = label("TTX CAMERA AI · xADAS REBUILD", 17f, Color.WHITE)
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
        val videoContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        }
        adasHud = AdasHudView(this)
        videoContainer.addView(adasHud, FrameLayout.LayoutParams(-1, -1))
        root.addView(videoContainer, LinearLayout.LayoutParams(
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

        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun nav(title: String, target: Class<out Activity>): Button =
            Button(this).apply {
                text = title
                textSize = 10f
                setOnClickListener { startActivity(Intent(this@MainActivity, target)) }
            }
        navigation.addView(nav("NGUỒN CAMERA", CameraSourceSettingsActivity::class.java),
            LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(nav("CÀI ĐẶT ADAS", AdasSettingsActivity::class.java),
            LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(nav("OPENPILOT", VisionModelActivity::class.java),
            LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(Button(this).apply {
            text = "GPS"
            textSize = 10f
            setOnClickListener { toggleGps() }
        }, LinearLayout.LayoutParams(0, -2, 0.6f))
        root.addView(navigation)
        // One tap into independent Camera2 evidence, no 70mai Wi-Fi needed.
        root.addView(nav("CAMERA ĐIỆN THOẠI · THỬ NGHIỆM 60 GIÂY",
            PhoneCameraActivity::class.java))

        latencyButton = Button(this).apply {
            text = ijkProfile.buttonTitle
            textSize = 11f
            setOnClickListener {
                ijkProfile = ijkProfile.next()
                text = ijkProfile.buttonTitle
                if (backend == Backend.IJK) {
                    showStatus("Đã chọn " + ijkProfile.shortLabel +
                        ". Bấm 70MAI · IJK/FFMPEG để mở lại với cấu hình mới.")
                } else {
                    showStatus("IJK sẽ dùng " + ijkProfile.shortLabel +
                        " khi bắt đầu phát. Chế độ mượt ban đầu không bị thay đổi.")
                }
            }
        }
        root.addView(latencyButton)

        directLatencyButton = Button(this).apply {
            text = directLatencyPolicy.buttonTitle
            textSize = 11f
            setOnClickListener {
                directLatencyPolicy = directLatencyPolicy.next()
                text = directLatencyPolicy.buttonTitle
                showStatus("Direct " + directLatencyPolicy.description +
                    (if (backend == Backend.DIRECT)
                        ". Bấm xADAS · MEDIACODEC để áp dụng phiên mới."
                    else ". Có hiệu lực khi phát Direct lần tới."))
            }
        }
        root.addView(directLatencyButton)

        pairButton = Button(this).apply {
            text = "GHÉP ĐÔI & HEARTBEAT A500S (KHÔNG GIẢM TRỄ)"
            textSize = 11f
            setOnClickListener { pairA500s() }
        }
        root.addView(pairButton)

        yuvTapButton = Button(this).apply {
            text = "YUV A500S: TẮT · CHẨN ĐOÁN OFFSCREEN"
            textSize = 11f
            setOnClickListener {
                if (backend != Backend.DIRECT) {
                    showStatus("YUV offscreen chỉ có ở bộ phát Direct MediaCodec. " +
                        "IJK vẫn phát độc lập, không sao chép Surface.")
                    return@setOnClickListener
                }
                yuvTapEnabled = !yuvTapEnabled
                if (!yuvTapEnabled) {
                    stopRawVision()
                    liveYuvCapture.cancel()
                    yuvCaptureButton.text = "LƯU 4 KHUNG YUV A500S · TẮT"
                }
                text = if (yuvTapEnabled) "YUV A500S: BẬT · KHÔNG PHÁT CẢNH BÁO"
                    else "YUV A500S: TẮT · CHẨN ĐOÁN OFFSCREEN"
                showStatus(if (yuvTapEnabled)
                    "Đang đợi IDR để tạo decoder YUV thứ hai; " +
                        "giữ nguyên Surface và luồng RTSP đang phát."
                    else "Đã tắt nhánh YUV; video Direct tiếp tục độc lập.")
            }
        }
        root.addView(yuvTapButton)

        rawAiButton = Button(this).apply {
            text = "ONNX A500S: TẮT · RAW-ONLY"
            textSize = 11f
            setOnClickListener { toggleA500sRawVision() }
        }
        root.addView(rawAiButton)

        yuvCaptureButton = Button(this).apply {
            text = "LƯU 4 KHUNG YUV A500S · TẮT"
            textSize = 11f
            setOnClickListener {
                if (backend != Backend.DIRECT || !yuvTapEnabled) {
                    showStatus("Chỉ thu khi Direct MediaCodec và YUV offscreen đang bật.")
                } else if (liveYuvCapture.isArmed()) {
                    liveYuvCapture.cancel()
                    text = "LƯU 4 KHUNG YUV A500S · TẮT"
                    showStatus("Đã hủy thu YUV, không tạo tệp.")
                } else if (pendingYuvEvidence != null) {
                    showStatus("Hãy hoàn tất bước chọn nơi lưu trước.")
                } else {
                    liveYuvCapture.arm()
                    text = "ĐANG THU 4 KHUNG YUV · BẤM ĐỂ HỦY"
                    showStatus("Thu bốn khung từ decoder phụ. " +
                        "Bạn sẽ chọn vị trí lưu sau khi đủ khung.")
                }
            }
        }
        root.addView(yuvCaptureButton)

        liveTraceButton = Button(this).apply {
            text = "THU BIÊN BẢN VIDEO 5 PHÚT"
            textSize = 11f
            setOnClickListener {
                val audit = activeLiveTrace
                if (audit?.isActive() == true) {
                    audit.stop(SystemClock.elapsedRealtime(), "OWNER_STOP")
                    pendingLiveTrace = audit.report()
                    activeLiveTrace = null
                    text = "XUẤT BIÊN BẢN TTXLIVE01"
                    showStatus("Đã dừng thu. Có thể xuất biên bản, nhưng chưa xác minh trễ cảnh thực.")
                } else if (pendingLiveTrace != null) {
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE, "TTX_A500S_5min_local_trace.txt")
                            }, saveLiveTraceRequestCode
                        )
                    } catch (error: Exception) {
                        showStatus("Không mở được chọn nơi lưu: " +
                            error.javaClass.simpleName)
                    }
                } else {
                    val selected = when (backend) {
                        Backend.DIRECT -> FiveMinuteStreamEvidence.Backend.DIRECT
                        Backend.IJK -> FiveMinuteStreamEvidence.Backend.IJK
                        null -> null
                    }
                    if (selected == null || (backend == Backend.DIRECT && direct == null) ||
                        (backend == Backend.IJK && ijk == null)) {
                        showStatus("Hãy phát video Direct hoặc IJK trước khi thu 5 phút.")
                    } else {
                        val newAudit = FiveMinuteStreamEvidence()
                        newAudit.start(selected, SystemClock.elapsedRealtime())
                        activeLiveTrace = newAudit
                        text = "ĐANG THU 5 PHÚT · BẤM ĐỂ DỪNG"
                        showStatus("Đang ghi tiến độ video mỗi giây. " +
                            "Chưa đo độ trễ cảnh thực; cần quay đồng thời cảnh và màn hình.")
                    }
                }
            }
        }
        root.addView(liveTraceButton)

        gpsPipTraceButton = Button(this).apply {
            text = "THU KIỂM TRA GPS / PiP / CÀI ĐẶT"
            textSize = 11f
            setOnClickListener {
                val trace = activeGpsPipTrace
                if (trace?.isActive() == true) {
                    trace.stop(SystemClock.elapsedRealtime())
                    pendingGpsPipTrace = trace.report()
                    activeGpsPipTrace = null
                    text = "XUẤT BIÊN BẢN TTXGPIP01"
                    showStatus("Đã ghi sự kiện GPS/PiP. Xuất TTXGPIP01 để " +
                        "kiểm tra độc lập trên thiết bị.")
                } else if (pendingGpsPipTrace != null) {
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE,
                                    "TTX_GPS_PiP_Settings_phone_trace.txt")
                            }, saveGpsPipTraceRequestCode)
                    } catch (error: Exception) {
                        showStatus("Không mở được trình lưu TTXGPIP01: " +
                            error.javaClass.simpleName)
                    }
                } else {
                    val started = GpsPipFieldEvidence()
                    started.start(SystemClock.elapsedRealtime())
                    activeGpsPipTrace = started
                    text = "ĐANG GHI GPS / PiP · BẤM ĐỂ DỪNG"
                    showStatus("Đang ghi dấu vết GPS/PiP/cài đặt, không lưu tọa độ. " +
                        "Hãy bật GPS, phát IJK và thử vào/thoát PiP, đổi đơn vị.")
                }
            }
        }
        root.addView(gpsPipTraceButton)

        root.addView(Button(this).apply {
            text = "PiP · CỬA SỔ VIDEO NHỎ"
            textSize = 11f
            setOnClickListener {
                activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
                    GpsPipFieldEvidence.Event.PIP_REQUESTED)
                if (backend != Backend.IJK || ijk == null) {
                    activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
                        GpsPipFieldEvidence.Event.PIP_REQUEST_REJECTED)
                    showStatus("PiP thử nghiệm chỉ bật khi đang phát IJK.")
                    return@setOnClickListener
                }
                if (!packageManager.hasSystemFeature(
                        PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
                        GpsPipFieldEvidence.Event.PIP_REQUEST_REJECTED)
                    showStatus("Thiết bị không hỗ trợ Picture-in-Picture.")
                    return@setOnClickListener
                }
                try {
                    pipTransitioning = true
                    val entered = enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(4, 3)).build())
                    if (entered) {
                        // Only onPictureInPictureModeChanged(true) confirms entry.
                        activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
                            GpsPipFieldEvidence.Event.PIP_REQUEST_RETURNED_TRUE)
                    } else {
                        activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
                            GpsPipFieldEvidence.Event.PIP_REQUEST_REJECTED)
                        // Android can return false WITHOUT throwing. A stuck
                        // transition flag would then mis-handle Surface teardown.
                        pipTransitioning = false
                        showStatus("PiP bị Android từ chối; tiếp tục xem video bình thường.")
                    }
                } catch (e: Exception) {
                    activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
                        GpsPipFieldEvidence.Event.PIP_REQUEST_EXCEPTION)
                    pipTransitioning = false
                    showStatus("PiP không thể mở: " + e.javaClass.simpleName)
                }
            }
        })

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
        detailView = label(
            "Đã phục hồi cài đặt và parser Openpilot. Chưa có suy luận AI thực tế.", 12f,
            Color.LTGRAY)
        root.addView(statusView)
        root.addView(detailView)
        root.addView(label(
            "RTSP/IJK + MediaCodec; các bộ phân tích AI cần model được phép dùng " +
                "và hiệu chuẩn, không hiển thị khoảng cách suy đoán.", 10f,
            Color.rgb(149, 155, 161)))
        setContentView(root)
        ui.post(statusTicker)
    }

    override fun onResume() {
        super.onResume()
        val prefs = AdasPrefs.of(this)
        window.decorView.systemUiVisibility =
            if (prefs.getBoolean(AdasPrefs.HIDE_STATUS_BAR, false))
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            else 0
        activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
            GpsPipFieldEvidence.Event.SETTINGS_RESUMED)
        if (::adasHud.isInitialized) {
            // Logo preference is NOT a global HUD-off switch: users must
            // retain GPS speed when they hide the TTX research label.
            adasHud.showBrandLabel = prefs.getBoolean(AdasPrefs.SHOW_LOGO, true)
            adasHud.speedUnit = HudDisplayFormat.UnitMode.fromPreference(
                prefs.getString(AdasPrefs.SPEED_UNIT, "km/h · m"))
            adasHud.roundLeadDistance = prefs.getBoolean(
                AdasPrefs.ROUND_DISTANCE, false)
        }
    }

    private fun toggleGps() {
        if (gpsEnabled) {
            activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
                GpsPipFieldEvidence.Event.GPS_TOGGLE_OFF)
            gpsMonitor.stop()
            gpsEnabled = false
            adasHud.speedKmh = null
            showStatus("GPS đã dừng")
            return
        }
        if (!gpsMonitor.hasPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 815)
            return
        }
        gpsEnabled = gpsMonitor.start()
        activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
            if (gpsEnabled) GpsPipFieldEvidence.Event.GPS_TOGGLE_ON
            else GpsPipFieldEvidence.Event.GPS_TOGGLE_OFF)
        showStatus(if (gpsEnabled) "GPS bật, chờ tốc độ từ vệ tinh"
            else "Không thể mở GPS. Hãy kiểm tra vị trí thiết bị.")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 815) {
            val granted = grantResults.firstOrNull() ==
                PackageManager.PERMISSION_GRANTED
            activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
                if (granted) GpsPipFieldEvidence.Event.GPS_PERMISSION_GRANTED
                else GpsPipFieldEvidence.Event.GPS_PERMISSION_DENIED)
            if (granted) toggleGps()
            else showStatus("Chưa cấp quyền GPS, không thể hiển thị tốc độ")
        }
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
        // Source selection is independent from the RTSP transport.
        if (CameraSourceCatalog.selected(this) == CameraSourceCatalog.Profile.PHONE) {
            stopPlayback()
            startActivity(Intent(this, PhoneCameraActivity::class.java))
            return
        }
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
        val source = CameraSourceCatalog.selected(this)
        url = try {
            CameraSourceCatalog.resolve(this, gw)
        } catch (e: Exception) {
            showStatus("Nguồn camera chưa sẵn sàng: " + (e.message ?: "Lỗi URL"))
            return
        }
        backend = mode
        retryCount = 0
        preferUdp = true
        startedAt = SystemClock.elapsedRealtime()
        if (mode == Backend.DIRECT) {
            // A reconnect uses the same buffer policy as the initial session.
            activeDirectLatencyPolicy = directLatencyPolicy
        }
        if (mode == Backend.IJK) {
            // Apply selected profile ONLY when a new playback session starts.
            // Internal reconnects must preserve the active profile, even if
            // the user has changed the profile selector meanwhile.
            activeIjkProfile = ijkProfile
            if (source == CameraSourceCatalog.Profile.A500S)
                launchOwnerHeartbeat(wifi.network, gw)
        }
        ijkRetryCount = 0
        ijkRecoveryPending = false
        ijkLastStopCause = "Chưa có"
        showStatus(wifi.detail + " · " + url)
        when (mode) {
            Backend.IJK -> startIjk(generation)
            Backend.DIRECT -> startDirect(generation)
        }
    }


    /**
     * Explicit owner interaction only. This workflow requires the physical
     * confirmation button on the A500S and stores a session token privately.
     * Re-pair if the camera was reset or a registration reply is rejected.
     */
    private fun pairA500s() {
        stopPlayback()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = CameraConnectionCheck.wifi(cm)
        val net = wifi.network
        val host = wifi.gateway
        val localIp = net?.let {
            cm.getLinkProperties(it)?.linkAddresses?.mapNotNull { address ->
                (address.address as? Inet4Address)?.hostAddress
            }?.firstOrNull()
        }
        if (net == null || host == null || localIp == null) {
            showStatus("Kết nối Wi-Fi A500S trước khi ghép đôi")
            return
        }
        val token = generation
        val session = A500sOwnerSession(net, host, localIp) { msg ->
            runOnUiThread {
                if (generation == token) showStatus(msg)
            }
        }
        ownerSession = session
        pairButton.isEnabled = false
        showStatus("Đang gửi yêu cầu ghép đôi. Hãy chuẩn bị xác nhận trên camera.")
        val userId = (1_000_000 + SecureRandom().nextInt(8_000_000)).toString()
        Thread({
            try {
                val pairedToken = session.pairWithPhysicalConfirmation(userId)
                runOnUiThread {
                    if (generation == token) {
                        getSharedPreferences("a500s_owner", MODE_PRIVATE)
                            .edit().putString("paired_token", pairedToken).apply()
                        showStatus("Ghép đôi A500S thành công; bật heartbeat 5 giây")
                        pairButton.isEnabled = true
                        begin(Backend.IJK)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (generation == token) {
                        pairButton.isEnabled = true
                        showStatus("Ghép đôi thất bại: " +
                            (e.message ?: e.javaClass.simpleName).take(180))
                    }
                }
            }
        }, "TTX-A500S-Physical-Pair").start()
    }

    private fun launchOwnerHeartbeat(wifi: Network, host: String) {
        val storedToken = getSharedPreferences("a500s_owner", MODE_PRIVATE)
            .getString("paired_token", null) ?: return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val phoneIp = cm.getLinkProperties(wifi)?.linkAddresses?.mapNotNull {
            (it.address as? Inet4Address)?.hostAddress
        }?.firstOrNull()
        if (phoneIp == null) {
            showStatus("Không tìm được địa chỉ Wi-Fi điện thoại cho đăng ký 70mai")
            return
        }
        try {
            val session = A500sOwnerSession(wifi, host, phoneIp) { msg ->
                runOnUiThread {
                    if (backend == Backend.IJK) showStatus(msg)
                }
            }
            ownerSession = session
            session.beginRegistration(storedToken)
        } catch (e: Exception) {
            showStatus("Không bật được owner heartbeat: " + e.javaClass.simpleName)
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
            // The SMOOTH profile is byte-for-byte equivalent in settings to
            // V1.0.2/V1.0.4, the one already reported smooth by the user.
            // For UDP_SMOOTH change ONLY transport, keep the smooth cache/codec.
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport",
                activeIjkProfile.rtspTransport)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "packet-buffering", activeIjkProfile.packetBuffering)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 32768L)
            if (activeIjkProfile.experimentalLowLatency) {
                // Experimental: reduce app-side buffering only. A500S may
                // already be encoding with a multi-second GOP/stream delay.
                // Do not enable nobuffer/skip_frame/codec_low_delay here:
                // combinations can corrupt H264 playback on some devices.
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 5L)
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                    "max_cached_duration", 600L)
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max_delay", 300000L)
            }
            player.setDisplay(surfaceView.holder)
            player.setDataSource(currentUrl)
            player.setOnPreparedListener {
                if (token == generation && backend == Backend.IJK && ijk === player) {
                    it.start()
                    showStatus("70mai IJK: đang phát (" + activeIjkProfile.shortLabel +
                        ", yêu cầu RTP/" + activeIjkProfile.rtspTransport.uppercase(Locale.US) + ")")
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
        activeLiveTrace?.reconnect(SystemClock.elapsedRealtime())
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

    /**
     * The owner explicitly enables model execution. A model is loaded only
     * from app-private storage; a successful graph load is not camera
     * calibration. These outputs NEVER reach AdasHudView or warning audio.
     */
    private fun toggleA500sRawVision() {
        if (rawVisionLoading) return
        if (rawVision != null) {
            stopRawVision()
            showStatus("ONNX RAW đã tắt; không lưu khung hình hoặc cảnh báo.")
            return
        }
        if (backend != Backend.DIRECT || !yuvTapEnabled) {
            showStatus("Bật Direct MediaCodec rồi chọn YUV A500S trước khi chạy ONNX RAW.")
            return
        }
        val model = File(filesDir, "models/vision.onnx")
        if (!model.isFile) {
            showStatus("Chưa có model ONNX được phép sử dụng. Nhập tại OPENPILOT.")
            return
        }
        rawVisionLoading = true
        rawVisionRequested = true
        val requestId = ++rawVisionSequence
        rawAiButton.isEnabled = false
        rawAiButton.text = "ONNX A500S: ĐANG NẠP MODEL"
        rawModelStatus = "Đang xác minh model đã nhập"
        val token = generation
        Thread({
            var result: OpenpilotInferencePipeline? = null
            var failure: String? = null
            var runner: VisionInferenceEngine? = null
            try {
                val opened = VisionModelBackend.openEngine(model)
                    ?: error("Bản Lite không có ONNX Runtime; sử dụng APK AI.")
                runner = opened
                val mainWarp = VirtualCameraWarp.Matrix(doubleArrayOf(
                    1.0,0.0,64.0,0.0,1.0,112.0,0.0,0.0,1.0))
                val wideWarp = VirtualCameraWarp.Matrix(doubleArrayOf(
                    1.2,0.0,13.0,0.0,1.2,86.0,0.0,0.0,1.0))
                result = OpenpilotInferencePipeline(
                    opened, mainWarp, wideWarp, calibration = null,
                    sourceWidth = A500sRawVisionBridge.EXPECTED_WIDTH,
                    sourceHeight = A500sRawVisionBridge.EXPECTED_HEIGHT,
                    nowMs = { SystemClock.elapsedRealtime() }
                ) { outcome ->
                    if (token == generation && requestId == rawVisionSequence &&
                        rawVisionRequested) {
                        rawResults.incrementAndGet()
                        val measured = outcome.timing?.sourceToResultMs
                        rawModelStatus = outcome.status +
                            " · xử lý " + outcome.runtimeMs + "ms" +
                            (if (measured != null)
                                " · nhận AU→tensor→model " + measured + "ms" +
                                    " (KHÔNG phải trễ cảnh thực)"
                            else "")
                    }
                }
                // Pipeline owns the engine and closes it only after inference
                // exits; never dispose native sessions from the codec thread.
                runner = null
            } catch (e: Exception) {
                failure = (e.message ?: e.javaClass.simpleName).take(180)
                try { runner?.close() } catch (_: Exception) {}
            }
            val candidate = result
            val problem = failure
            runOnUiThread {
                if (token != generation || requestId != rawVisionSequence ||
                    !rawVisionRequested || backend != Backend.DIRECT ||
                    !yuvTapEnabled || isDestroyed) {
                    candidate?.close()
                    return@runOnUiThread
                }
                rawVisionLoading = false
                rawAiButton.isEnabled = true
                if (candidate == null) {
                    rawVisionRequested = false
                    rawAiButton.text = "ONNX A500S: TẮT · RAW-ONLY"
                    rawModelStatus = problem ?: "Không thể tạo ONNX runner"
                    showStatus("ONNX RAW không khởi động: " + rawModelStatus)
                } else {
                    rawResults.set(0L)
                    rawVision = candidate
                    rawBridge = A500sRawVisionBridge(
                        nowMs = { SystemClock.elapsedRealtime() },
                        submit = { pair -> candidate.offer(pair) })
                    rawAiButton.text = "ONNX A500S: RAW ĐANG CHẠY · TẮT"
                    rawModelStatus = "YUV → temporal pair → ONNX; chưa hiệu chuẩn"
                    showStatus("ONNX RAW đã mở. CHỈ CHẨN ĐOÁN: " +
                        "không hiển thị làn, mét hoặc cảnh báo.")
                }
            }
        }, "TTX-A500S-RAW-ModelLoader").start()
    }

    private fun stopRawVision() {
        ++rawVisionSequence
        rawVisionRequested = false
        rawVisionLoading = false
        rawPairResetPending.set(false)
        rawBridge?.reset()
        rawBridge = null
        val old = rawVision
        rawVision = null
        old?.close()
        rawModelStatus = "AI đã tắt"
        if (::rawAiButton.isInitialized) {
            rawAiButton.isEnabled = true
            rawAiButton.text = "ONNX A500S: TẮT · RAW-ONLY"
        }
    }

    private fun startDirect(token: Int) {
        val wifi = network ?: return
        val currentUrl = url ?: return
        if (!surfaceView.holder.surface.isValid) return
        previousSampleTime = 0L
        previousPackets = 0L
        previousMarkers = 0L
        previousRtpClocks = 0L
        previousAccessUnits = 0L
        previousDecoded = 0L
        previousRendered = 0L
        val selectedUdp = preferUdp
        val callbackLease = directCallbackEpoch.begin()
        // A callback from an old RTSP session must never restart the new one.
        lateinit var player: DirectRtspPlayer
        player = DirectRtspPlayer(
            wifi, currentUrl, surfaceView.holder.surface,
            { yuvTapEnabled },
            { message ->
                runOnUiThread {
                    if (directCallbackEpoch.isCurrent(callbackLease) &&
                        token == generation && backend == Backend.DIRECT)
                        showStatus("xADAS-style: " + message)
                }
            },
            {
                runOnUiThread {
                    if (directCallbackEpoch.isCurrent(callbackLease) &&
                        token == generation && backend == Backend.DIRECT)
                        showStatus("MediaCodec đã xuất khung giải mã · " +
                            if (selectedUdp) "RTP/UDP" else "RTP/TCP")
                }
            },
            { _, _, _ -> }, // Deliberately no AI/frame copying in benchmark
            { reason ->
                runOnUiThread {
                    if (directCallbackEpoch.isCurrent(callbackLease) &&
                        token == generation && backend == Backend.DIRECT &&
                        direct === player)
                        recoverDirect(token, reason)
                }
            },
            selectedUdp,
            { yuv, capturedAtMs ->
                if (directCallbackEpoch.isCurrent(callbackLease) &&
                    token == generation && backend == Backend.DIRECT && yuvTapEnabled) {
                    yuvFrames.incrementAndGet()
                    yuvShape = "${yuv.width}x${yuv.height}"
                    // Only the sidecar worker copies frames. Preview remains independent.
                    val completed = liveYuvCapture.accept(yuv, capturedAtMs)
                    if (completed != null) runOnUiThread {
                        if (directCallbackEpoch.isCurrent(callbackLease) &&
                            token == generation && backend == Backend.DIRECT &&
                            yuvTapEnabled && !isDestroyed && pendingYuvEvidence == null) {
                            pendingYuvEvidence = completed
                            yuvCaptureButton.text = "ĐỦ 4 KHUNG · CHỌN NƠI LƯU"
                            try {
                                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_TITLE, "TTX-A500S-4frames.ttxyuv")
                                }, saveYuvRequestCode)
                            } catch (e: Exception) {
                                pendingYuvEvidence = null
                                yuvCaptureButton.text = "LƯU 4 KHUNG YUV A500S · TẮT"
                                showStatus("Không mở được chọn tệp: " +
                                    e.javaClass.simpleName)
                            }
                        }
                    }
                    // The bridge feeds only a RAW, uncalibrated model worker.
                    // Never route the result to HUD, sound or distance.
                    if (rawVisionRequested)
                        try {
                            if (rawPairResetPending.getAndSet(false))
                                rawBridge?.reset()
                            rawBridge?.accept(yuv, capturedAtMs)
                        }
                        catch (error: Exception) {
                            rawModelStatus = "YUV→ONNX: " + error.javaClass.simpleName
                            rawVision?.invalidate()
                            rawBridge?.reset()
                        }
                }
            },
            {
                // A damaged RTP AU, overload, or codec restart must discard
                // the previous temporal frame, even if the gap is <850 ms.
                if (directCallbackEpoch.isCurrent(callbackLease) &&
                    token == generation) {
                    // Nonblocking atomic fence: invalidate a YUV→ARGB copy
                    // already running on the offscreen worker, not merely the
                    // next frame after this callback returns.
                    rawBridge?.signalDiscontinuity()
                    rawPairResetPending.set(true)
                    liveYuvCapture.cancel()
                    // Nonblocking: an in-flight ONNX result from the damaged
                    // GOP is invalid even before the next YUV frame arrives.
                    rawVision?.invalidate()
                }
            },
            activeDirectLatencyPolicy
        )
        direct = player
        player.start()
    }

    private fun recoverDirect(token: Int, reason: String) {
        if (token != generation || backend != Backend.DIRECT ||
            directRecoveryPending) return
        val decision=directReconnectBudget.onInterrupted(
            SystemClock.elapsedRealtime())
        retryCount=decision.attemptsWithinWindow
        if (!decision.permitted) {
            showStatus("Direct đã dừng sau " + retryCount +
                " lần lỗi trong 60 giây · " + reason.take(110))
            stopPlayback()
            return
        }
        directRecoveryPending = true
        activeLiveTrace?.reconnect(SystemClock.elapsedRealtime())
        directCallbackEpoch.revoke()
        liveYuvCapture.cancel()
        if (::yuvCaptureButton.isInitialized)
            yuvCaptureButton.text = "LƯU 4 KHUNG YUV A500S · TẮT"
        if (preferUdp && (reason.contains("zero video packets", true) ||
                reason.contains("zero H264 access units", true))) {
            preferUdp = false
            showStatus("A500S UDP không có video → thử TCP mới")
        }
        val old=direct
        direct=null
        rawPairResetPending.set(true)
        rawVision?.invalidate()
        old?.close()
        showStatus("RTSP ngắt · đợi giải phóng MediaCodec · thử " +
            retryCount + "/3 · " + reason.take(90))
        // Do not sleep, join, or wait for a native decoder on Android UI,
        // Surface, codec callback, or RTP assembly threads.
        Thread({
            val released = try { old?.awaitCodecRelease(5_000L) ?: true }
                catch (_: InterruptedException) { false }
            ui.post {
                if (token != generation || backend != Backend.DIRECT ||
                    !directRecoveryPending) return@post
                if (!released) {
                    showStatus("MediaCodec cũ chưa giải phóng sau 5 giây; " +
                        "dừng an toàn, không mở decoder mới")
                    stopPlayback()
                    return@post
                }
                ui.postDelayed({
                    if (token == generation && backend == Backend.DIRECT &&
                        directRecoveryPending) {
                        directRecoveryPending=false
                        startDirect(token)
                    }
                }, decision.delayMs)
            }
        }, "TTX-Direct-Release-Barrier").apply {
            isDaemon=true
            start()
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            val latestGpsSpeed = if (::gpsMonitor.isInitialized && gpsEnabled)
                gpsMonitor.currentSpeed() else null
            if (::adasHud.isInitialized) adasHud.speedKmh = latestGpsSpeed
            val phoneTrace = activeGpsPipTrace
            if (phoneTrace?.isActive() == true && ::gpsMonitor.isInitialized &&
                ::surfaceView.isInitialized) {
                val prefs = AdasPrefs.of(this@MainActivity)
                phoneTrace.sample(GpsPipFieldEvidence.Snapshot(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    gpsRequested = gpsEnabled,
                    gpsPermission = gpsMonitor.hasPermission(),
                    gpsProviderEnabled = gpsMonitor.isGpsProviderEnabled(),
                    freshSpeedPresent = latestGpsSpeed != null,
                    inPip = isInPictureInPictureMode,
                    videoSurfaceValid = surfaceView.holder.surface.isValid,
                    playback = when (backend) {
                        Backend.DIRECT -> GpsPipFieldEvidence.Playback.DIRECT
                        Backend.IJK -> GpsPipFieldEvidence.Playback.IJK
                        null -> GpsPipFieldEvidence.Playback.NONE
                    },
                    unitsImperial = HudDisplayFormat.UnitMode.fromPreference(
                        prefs.getString(AdasPrefs.SPEED_UNIT, "km/h · m")) ==
                        HudDisplayFormat.UnitMode.IMPERIAL,
                    brandVisible = prefs.getBoolean(AdasPrefs.SHOW_LOGO, true)
                ))
            }
            if (::detailView.isInitialized) {
                val d = direct
                if (backend == Backend.DIRECT && d != null) {
                    val now = SystemClock.elapsedRealtime()
                    val s = d.counters()
                    val dt = if (previousSampleTime == 0L) 2.0
                        else (now - previousSampleTime).coerceAtLeast(1L) / 1000.0
                    val packets = (s.rtp.packets - previousPackets).coerceAtLeast(0L)
                    val markers = (s.rtp.markers - previousMarkers).coerceAtLeast(0L)
                    val rtpClocks = (s.rtp.timestampChanges - previousRtpClocks)
                        .coerceAtLeast(0L)
                    val au = (s.receivedAccessUnits - previousAccessUnits).coerceAtLeast(0L)
                    val decoded = (s.decoded - previousDecoded).coerceAtLeast(0L)
                    val shown = (s.rendered - previousRendered).coerceAtLeast(0L)
                    detailView.text = String.format(Locale.US,
                        "%s · RTP %.1f/s · AU %.1f/s · giải mã %.1f/s · Surface %.1f/s\n" +
                            "Nguồn RTP: marker %.1f/s · đổi PTS %.1f/s · jitter %.1fms\n" +
                            "RTP gap %d · H264 hỏng %d · đệm %d/%d\n" +
                            "UDP đến lệch: %d · khôi phục %d · thiếu %d gói\n" +
                            "RTSP heartbeat %d/%d OK · reconnect %d/3",
                        s.transportName, packets / dt, au / dt, decoded / dt, shown / dt,
                        markers / dt, rtpClocks / dt, s.rtp.jitterTicks / 90.0,
                        s.rtp.sequenceGaps, s.discardedAccessUnits, s.queueDepth,
                        s.maxAccessUnitQueue,
                        s.udpReordered, s.udpRecovered, s.udpSkipped,
                        s.keepAliveOk, s.keepAliveSent, retryCount)
                    detailView.append("\nRTP video PT đã lọc: gói ngoài luồng " +
                        s.foreignPayloadPackets +
                        " · RTP sai định dạng " + s.malformedRtpPackets)
                    val decode = s.decodeTimeline
                    detailView.append("\nDirect: " + activeDirectLatencyPolicy.name +
                        " · AU đợi cũ nhất " + (s.oldestQueueAgeMs?.toString() ?: "—") +
                        "ms · tự ngắt do tuổi đệm " + s.bufferAgeRestarts)
                    detailView.append("\nH264 GOP epoch: AU xếp hàng " +
                        s.gopFence.queuedAccessUnits +
                        " · lỗi " + s.gopFence.damageEvents +
                        " · reconnect decoder " + s.gopFence.reconnectRequests +
                        " · phiên cũ bị chặn " + s.gopFence.reconnectRequired)
                    detailView.append("\nAU nhận→MediaCodec output [KHÔNG PHẢI trễ cảnh thực]: " +
                        "P95 " + (decode.p95ReceiptToDecoderMs?.toString() ?: "—") +
                        "ms · mới nhất " + (decode.lastReceiptToDecoderMs?.toString() ?: "—") +
                        "ms · mẫu " + decode.matched +
                        " · không ghép PTS " + decode.unmatched +
                        " · bỏ output quá hạn " + s.staleDecodedSuppressed)
                    val rtpClock = s.rtpAuClock
                    val surfaceClock = s.surfaceTimeline
                    fun shownMs(value: Double?): String =
                        value?.let { String.format(Locale.US,"%.1f",it) } ?: "—"
                    detailView.append("\nRTP AU 90kHz: bước nhịp " +
                        shownMs(rtpClock.lastRtpStepMs) +
                        "ms · khoảng nhận " +
                        (rtpClock.lastReceiptStepMs?.toString() ?: "—") +
                        "ms · lệch cộng dồn " +
                        shownMs(rtpClock.currentSessionDriftMs) +
                        "ms · lỗi bước RTP " + rtpClock.rejectedAccessUnits +
                        " · wrap " + rtpClock.timestampWraps)
                    detailView.append("\nMediaCodec release→render callback [KHÔNG phải thời điểm màn hình quét]: " +
                        "P95 " + shownMs(surfaceClock.p95ReleaseToRenderMs) +
                        "ms · cuối " + shownMs(surfaceClock.lastReleaseToRenderMs) +
                        "ms · mẫu " + surfaceClock.matched +
                        " · thiếu sự kiện " + surfaceClock.unmatched)
                    val health = s.streamHealth
                    detailView.append("\nVIDEO HEALTH · " + health.status.name +
                        " · RTP " + (health.lastRtpAgoMs?.toString() ?: "—") +
                        "ms · AU " + (health.lastAuAgoMs?.toString() ?: "—") +
                        "ms · decode " +
                        (health.lastDecodeAgoMs?.toString() ?: "—") +
                        "ms · Surface " +
                        (health.lastSurfaceAgoMs?.toString() ?: "—") + "ms")
                    detailView.append("\nPTS AU: trùng " + health.duplicatePts +
                        " · đảo thứ tự " + health.backwardPts +
                        " · chưa tiến " + health.nonAdvancingAuCount +
                        " · lỗi đồng hồ " + health.invalidClocks +
                        " · đang phục hồi " +
                        (if (directRecoveryPending) "CÓ" else "KHÔNG"))
                    val cadence = s.streamCadence
                    detailView.append("\nNHỊP VIDEO 10s [chẩn đoán, KHÔNG phải trễ cảnh thực]: " +
                        cadence.status.name + " · " +
                        "RTP AU " + shownMs(cadence.sourceFps) +
                        " fps · đồng hồ nguồn ~" +
                        shownMs(cadence.mediaClockFps) + " fps" +
                        " · đưa vào codec " +
                        shownMs(cadence.codecInputFps) + " fps")
                    detailView.append("\nGiải mã " +
                        shownMs(cadence.codecOutputFps) +
                        " fps · thả Surface " +
                        shownMs(cadence.surfaceReleaseFps) +
                        " fps · render callback " +
                        shownMs(cadence.matchedRenderCallbackFps) +
                        " fps · lỗi mẫu " + cadence.invalidEvents)
                    detailView.append("\nKhoảng cách thời gian *media PTS* [không phải độ trễ vật lý]: " +
                        "nguồn→giải mã " +
                        shownMs(cadence.mediaGapToCodecOutputMs) +
                        "ms · nguồn→Surface " +
                        shownMs(cadence.mediaGapToSurfaceReleaseMs) +
                        "ms · nguồn im lặng " +
                        (cadence.sourceStallMs?.toString() ?: "—") + "ms")
                    s.sidecar?.let { yuv ->
                        detailView.append("\nYUV OFFSCREEN: " +
                            (if (yuvTapEnabled) "BẬT" else "TẮT") +
                            " · ${yuvFrames.get()} frame ${yuvShape}" +
                            " · giải mã ${yuv.decoded}" +
                            " · đợi IDR ${yuv.skippedToIdr}" +
                            " · reset ${yuv.resets} · tràn ${yuv.overflows}" +
                            " · AU quá hạn ${yuv.staleAccessUnits}" +
                            " · đệm quá hạn ${yuv.expiredBacklogResets}" +
                            " · YUV hết hạn ${yuv.staleYuvOutputs}" +
                            " · YUV sai epoch ${yuv.revokedYuvOutputs}" +
                            " · YUV sai định dạng ${yuv.unsupportedYuvOutputs}" +
                            " · lỗi ${yuv.errors}")
                        if (yuv.lastFailure.isNotBlank())
                            detailView.append("\n" + yuv.lastFailure)
                    }
                    rawBridge?.metrics()?.let { model ->
                        detailView.append("\nONNX RAW: " + rawModelStatus +
                            " · ${rawResults.get()} kết quả" +
                            " · pair ${model.pairs}" +
                            " · bỏ ${model.rejected}" +
                            " · ngắt nhịp ${model.discontinuities}" +
                            " · ${model.lastVerdict}")
                    }
                    rawVision?.metrics()?.let { m ->
                        detailView.append("\nONNX worker: nhận ${m.received}" +
                            " · xử lý ${m.processed} · quá hạn ${m.stale}" +
                            " · ghi đè ${m.overwritten} · lỗi ${m.errors}")
                    }
                    previousPackets = s.rtp.packets
                    previousMarkers = s.rtp.markers
                    previousRtpClocks = s.rtp.timestampChanges
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
                        "IJK/FFmpeg [%s] · %d×%d · giải mã %.1f FPS · xuất %.1f FPS\n" +
                            "Đệm nội bộ %.0f ms · đang phát %s · phiên %ds · khôi phục %d/5\n" +
                            "A500S CGI: %d/%d OK · %s\nLỗi cuối: %s",
                        activeIjkProfile.shortLabel,
                        media?.videoWidth ?: 0, media?.videoHeight ?: 0,
                        decodeFps, outputFps, cachedMs.toDouble(),
                        if (nativePlaying) "CÓ" else "KHÔNG",
                        (now - ijkOpenedAtMs).coerceAtLeast(0L) / 1000L,
                        ijkRetryCount, ownerSession?.ok ?: 0L,
                        ownerSession?.sent ?: 0L,
                        (ownerSession?.failure ?: "").ifBlank {
                            if (ownerSession == null) "chưa ghép đôi" else "đang duy trì"
                        }, ijkLastStopCause)
                } else {
                    detailView.text = "Chỉ gồm hai bộ phát: IJKPlayer và Direct MediaCodec."
                }
            }
            val audit = activeLiveTrace
            if (audit?.isActive() == true) {
                val clock = SystemClock.elapsedRealtime()
                val item = when (backend) {
                    Backend.DIRECT -> direct?.counters()?.let { counters ->
                        FiveMinuteStreamEvidence.Sample(
                            clock, FiveMinuteStreamEvidence.Backend.DIRECT,
                            releasedFrames = counters.rendered,
                            reconnecting = directRecoveryPending)
                    }
                    Backend.IJK -> ijk?.let { player ->
                        var fps: Float? = null
                        var playing = false
                        try {
                            fps = player.videoOutputFramesPerSecond
                            playing = player.isPlaying
                        } catch (_: Throwable) {}
                        FiveMinuteStreamEvidence.Sample(
                            clock, FiveMinuteStreamEvidence.Backend.IJK,
                            ijkOutputFps = fps, ijkPlaying = playing,
                            reconnecting = ijkRecoveryPending)
                    }
                    null -> null
                }
                if (item == null) audit.stop(clock, "PLAYER_UNAVAILABLE")
                else audit.sample(item)
                if (!audit.isActive()) {
                    pendingLiveTrace = audit.report()
                    activeLiveTrace = null
                    liveTraceButton.text = "XUẤT BIÊN BẢN TTXLIVE01"
                    showStatus("Biên bản TTXLIVE01 đã sẵn sàng. " +
                        "Hãy xuất tệp; không có phép đo độ trễ quang học.")
                }
            }
            ui.postDelayed(this, 1000L)
        }
    }

    @Deprecated("Android Storage Access Framework result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == saveGpsPipTraceRequestCode) {
            val report = pendingGpsPipTrace
            if (resultCode != RESULT_OK || data?.data == null || report == null) {
                showStatus("Chưa lưu TTXGPIP01; có thể bấm Xuất để thử lại.")
                return
            }
            val target = data.data ?: return
            Thread({
                val failure = try {
                    val stream = contentResolver.openOutputStream(target, "w")
                        ?: error("Không mở được tệp đích")
                    stream.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                    null
                } catch (error: Exception) {
                    error.message ?: error.javaClass.simpleName
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        if (failure == null) {
                            if (pendingGpsPipTrace === report) {
                                pendingGpsPipTrace = null
                                gpsPipTraceButton.text =
                                    "THU KIỂM TRA GPS / PiP / CÀI ĐẶT"
                            }
                            showStatus("Đã xuất TTXGPIP01. Cần đánh giá " +
                                "GPS/PiP trên điện thoại trước khi tính #19 PASS.")
                        } else showStatus("Không lưu được TTXGPIP01: " + failure)
                    }
                }
            }, "TTX-GPS-PiP-Trace-Writer").start()
            return
        }
        if (requestCode == saveLiveTraceRequestCode) {
            val report = pendingLiveTrace
            if (resultCode != RESULT_OK || data?.data == null || report == null) {
                showStatus("Chưa lưu TTXLIVE01; có thể bấm Xuất để chọn lại.")
                return
            }
            val uri = data.data ?: return
            Thread({
                val failure = try {
                    val stream = contentResolver.openOutputStream(uri, "w")
                        ?: error("Không mở được tệp xuất")
                    stream.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                    null
                } catch (error: Exception) {
                    error.message ?: error.javaClass.simpleName
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        if (failure == null) {
                            if (pendingLiveTrace === report) {
                                pendingLiveTrace = null
                                liveTraceButton.text = "THU BIÊN BẢN VIDEO 5 PHÚT"
                            }
                            showStatus("Đã lưu TTXLIVE01. Chỉ là dấu vết cục bộ; " +
                                "vẫn cần phép đo cảnh thực→màn hình.")
                        } else showStatus("Không lưu được TTXLIVE01: " + failure)
                    }
                }
            }, "TTX-Live-Trace-Writer").start()
            return
        }
        if (requestCode != saveYuvRequestCode) return
        val sample = pendingYuvEvidence
        pendingYuvEvidence = null
        yuvCaptureButton.text = "LƯU 4 KHUNG YUV A500S · TẮT"
        if (resultCode != RESULT_OK || data?.data == null || sample == null) {
            showStatus("Đã hủy lưu khung YUV.")
            return
        }
        val uri = data.data ?: return
        showStatus("Đang ghi tệp TTXYUV01...")
        Thread({
            val failure = try {
                val stream = contentResolver.openOutputStream(uri, "w")
                    ?: error("Không mở được tệp đích")
                stream.use { OfflineYuvFixture.write(sample, it) }
                null
            } catch (error: Exception) {
                error.message ?: error.javaClass.simpleName
            }
            runOnUiThread {
                if (!isDestroyed) showStatus(if (failure == null)
                    "Đã lưu TTXYUV01 (4 khung YUV RAW). " +
                        "Không phải bằng chứng hiệu chuẩn hoặc ground truth."
                else "Lưu thất bại (tệp có thể chưa hoàn chỉnh): " + failure)
            }
        }, "TTX-YUV-FileWriter").start()
    }

    private fun stopPlayback() {
        activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
            GpsPipFieldEvidence.Event.PLAYBACK_STOPPED)
        activeLiveTrace?.let { trace ->
            if (trace.isActive()) {
                trace.stop(SystemClock.elapsedRealtime(), "PLAYBACK_STOPPED")
                pendingLiveTrace = trace.report()
                if (::liveTraceButton.isInitialized)
                    liveTraceButton.text = "XUẤT BIÊN BẢN TTXLIVE01"
            }
            activeLiveTrace = null
        }
        directCallbackEpoch.revoke()
        directRecoveryPending = false
        directReconnectBudget.reset()
        retryCount = 0
        stopRawVision()
        liveYuvCapture.cancel()
        pendingYuvEvidence = null
        if (::yuvCaptureButton.isInitialized)
            yuvCaptureButton.text = "LƯU 4 KHUNG YUV A500S · TẮT"
        yuvTapEnabled = false
        if (::yuvTapButton.isInitialized)
            yuvTapButton.text = "YUV A500S: TẮT · CHẨN ĐOÁN OFFSCREEN"
        generation++
        backend = null
        ownerSession?.close()
        ownerSession = null
        if (::pairButton.isInitialized) pairButton.isEnabled = true
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (backend == Backend.IJK) {
            try { ijk?.setDisplay(holder) } catch (_: Throwable) {}
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (pipTransitioning || isInPictureInPictureMode) {
            try { ijk?.setDisplay(null) } catch (_: Throwable) {}
        } else stopPlayback()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        activeGpsPipTrace?.event(SystemClock.elapsedRealtime(),
            if (isInPictureInPictureMode)
                GpsPipFieldEvidence.Event.PIP_ENTERED_CALLBACK
            else GpsPipFieldEvidence.Event.PIP_EXITED_CALLBACK)
        pipTransitioning = false
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        val root = content?.getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            // Keep the preview/video surface only while in PiP.
            child.visibility =
                if (isInPictureInPictureMode && child !is FrameLayout) View.GONE
                else View.VISIBLE
        }
        if (backend == Backend.IJK && surfaceView.holder.surface.isValid) {
            try { ijk?.setDisplay(surfaceView.holder) } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        activeGpsPipTrace?.stop(SystemClock.elapsedRealtime(),
            "ACTIVITY_DESTROYED")
        activeGpsPipTrace = null
        ui.removeCallbacksAndMessages(null)
        gpsMonitor.stop()
        stopPlayback()
        super.onDestroy()
    }
}
