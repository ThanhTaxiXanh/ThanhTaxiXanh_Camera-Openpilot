package vn.thanhtaxixanh.camera.ai

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent phone camera source (Camera2): Surface preview with an optional
 * YUV_420_888 CPU-readable stream for future AI, without CameraX dependency.
 *
 * This is not equivalent to xADAS phone-camera AI yet. No live inference or
 * uncalibrated distance warnings are emitted.
 */
class PhoneCameraActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var started = false
    private val framesReceived = AtomicLong(0L)
    @Volatile private var vision: OpenpilotInferencePipeline? = null
    private var lastVisionCaptureMs = 0L
    @Volatile private var previousVisionFrame: IntArray? = null
    @Volatile private var previousVisionCaptureMs = 0L
    private lateinit var aiButton: Button
    private var loadingAi = false
    private val cameraLease = PhoneCameraSessionLease()
    private val modelLease = PhoneCameraSessionLease()
    private val yuvLock = Any()
    private val sensorFrameGate = Camera2SensorFrameGate()
    private val rejectedCameraTimestamps = AtomicLong(0L)
    // Camera2 timestamp source REALTIME alone is compatible with boot clock.
    @Volatile private var realtimeSensorClock = false
    @Volatile private var inForeground = false
    private var activeCameraToken = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(12,12,12,12)
        }
        body.addView(TextView(this).apply {
            text="TTX · CAMERA ĐIỆN THOẠI"
            textSize=17f
            gravity=Gravity.CENTER
        })
        preview=SurfaceView(this).apply {
            background=null
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(this@PhoneCameraActivity)
        }
        body.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        status=TextView(this).apply {
            text="Camera2: chưa mở camera"
            textSize=13f
            setPadding(10,12,10,12)
        }
        body.addView(status)
        aiButton=Button(this).apply {
            text="AI: TẮT · CHẨN ĐOÁN TENSOR"
            setOnClickListener { toggleVisionDiagnostics() }
        }
        body.addView(aiButton)
        body.addView(Button(this).apply {
            text="ĐÓNG CAMERA"
            setOnClickListener { finish() }
        })
        setContentView(body)
    }


    /**
     * Toggle real ONNX inference of an authorized user model.
     * Reconstructed camera warp is NOT calibrated: raw diagnostics only,
     * never lanes/distance/warnings. The Lite variant has no runtime.
     */
    private fun toggleVisionDiagnostics() {
        // A model load runs on a background thread. Cancellation must be
        // accepted immediately, rather than waiting for the ONNX open call.
        if (loadingAi) {
            modelLease.revoke()
            loadingAi=false
            aiButton.isEnabled=true
            aiButton.text="AI: TẮT · CHẨN ĐOÁN TENSOR"
            status.text="Đã hủy mở ONNX; model đến muộn sẽ được đóng."
            return
        }
        if (vision != null) {
            modelLease.revoke()
            val previous=vision
            vision=null
            previous?.close()
            synchronized(yuvLock) {
                previousVisionFrame=null
                previousVisionCaptureMs=0L
                lastVisionCaptureMs=0L
            }
            aiButton.text="AI: TẮT · CHẨN ĐOÁN TENSOR"
            status.text="Đã tắt suy luận AI thử nghiệm."
            return
        }
        val cameraToken=activeCameraToken
        if (!inForeground || !started || camera==null || session==null ||
            !cameraLease.owns(cameraToken)) {
            status.text="Cần mở Camera2 preview hợp lệ trước khi bật RAW ONNX."
            return
        }
        if (!realtimeSensorClock) {
            status.text="Camera2 không có timestamp cảm biến REALTIME; " +
                "không bật RAW ONNX vì không thể xác minh tuổi ảnh. " +
                "Preview vẫn hoạt động."
            return
        }
        val model=File(filesDir,"models/vision.onnx")
        if (!model.isFile) {
            status.text="Hãy nhập model ONNX được phép sử dụng từ màn hình Openpilot."
            return
        }

        val modelToken=modelLease.begin()
        loadingAi=true
        aiButton.isEnabled=true
        aiButton.text="AI: ĐANG NẠP · BẤM ĐỂ HỦY"
        status.text="Đang mở ONNX (không hiệu chuẩn)..."
        Thread({
            var candidate: OpenpilotInferencePipeline?=null
            var failure: String?=null
            try {
                if (modelLease.owns(modelToken) &&
                    cameraLease.owns(cameraToken)) {
                    val runner=VisionModelBackend.openEngine(model)
                        ?: error("Bản Lite không có runtime; cần bản TTX AI")
                    try {
                        // These are diagnostic-only, UNCALIBRATED transforms.
                        val mainWarp=VirtualCameraWarp.Matrix(doubleArrayOf(
                            1.0,0.0,64.0,0.0,1.0,112.0,0.0,0.0,1.0))
                        val wideWarp=VirtualCameraWarp.Matrix(doubleArrayOf(
                            1.2,0.0,13.0,0.0,1.2,86.0,0.0,0.0,1.0))
                        candidate=OpenpilotInferencePipeline(
                            runner,mainWarp,wideWarp,
                            calibration=null,sourceWidth=640,sourceHeight=480,
                            nowMs={ SystemClock.elapsedRealtime() }) { result ->
                            runOnUiThread {
                                if (modelLease.owns(modelToken) &&
                                    cameraLease.owns(cameraToken) &&
                                    vision != null && inForeground &&
                                    !isFinishing && !isDestroyed) {
                                    status.text="Openpilot RAW: " + result.status +
                                        " · xử lý " + result.runtimeMs + "ms" +
                                        (result.timing?.sourceToResultMs?.let {
                                            " · từ nhận Camera2 " + it + "ms"
                                        } ?: "") +
                                        " · KHÔNG phải trễ cảnh thực · chưa hiệu chuẩn"
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        runner.close()
                        throw error
                    }
                }
            } catch (error: Exception) {
                failure=error.message ?: error.javaClass.simpleName
            }
            val loaded=candidate
            runOnUiThread {
                // onPause, rapid reentry, Surface teardown and explicit
                // cancellation all revoke ownership before this runs.
                if (!modelLease.owns(modelToken) ||
                    !cameraLease.owns(cameraToken) ||
                    !inForeground || isFinishing || isDestroyed) {
                    loaded?.close()
                    return@runOnUiThread
                }
                loadingAi=false
                aiButton.isEnabled=true
                if (failure!=null || loaded==null) {
                    modelLease.revoke()
                    status.text="Không mở được AI: " + (failure ?: "mất model")
                    loaded?.close()
                    aiButton.text="AI: TẮT · CHẨN ĐOÁN TENSOR"
                } else {
                    vision=loaded
                    synchronized(yuvLock) {
                        previousVisionFrame=null
                        previousVisionCaptureMs=0L
                        lastVisionCaptureMs=0L
                    }
                    aiButton.text="AI: RAW ONNX ĐANG CHẠY · BẤM ĐỂ TẮT"
                    status.text="ONNX RAW thử nghiệm trên YUV điện thoại; " +
                        "không dùng kết quả để cảnh báo."
                }
            }
        },"TTX-Openpilot-Load").start()
    }

    /** Copy at most one real 640x480 YUV image every ~350ms. */
    private fun acceptYuv(image: android.media.Image, lease: Long) {
        if (!cameraLease.owns(lease)) return
        framesReceived.incrementAndGet()
        // Close/reset takes the same short lock, so a previous Camera2
        // callback cannot restore an old pair AFTER a Surface transition.
        synchronized(yuvLock) {
            if (!cameraLease.owns(lease)) return
            val current=vision ?: return
            val nowNs=SystemClock.elapsedRealtimeNanos()
            val clock = sensorFrameGate.inspect(
                image.timestamp, nowNs, realtimeSensorClock)
            if (!clock.accepted) {
                val count=rejectedCameraTimestamps.incrementAndGet()
                if (previousVisionFrame!=null) current.invalidate()
                previousVisionFrame=null
                previousVisionCaptureMs=0L
                lastVisionCaptureMs=0L
                if (count==1L) runOnUiThread {
                    if (cameraLease.owns(lease) && vision === current &&
                        inForeground) status.text=
                            "Đã loại YUV Camera2: "+clock.verdict+
                            " · dùng thời điểm cảm biến, không dùng giờ callback."
                }
                return
            }
            val captureAtMs=clock.captureElapsedMs ?: return
            val now=nowNs/1_000_000L
            if (now-lastVisionCaptureMs<350L) return
            lastVisionCaptureMs=now
            try {
                val snapshot=AndroidYuvImageBridge.snapshot(image)
                require(snapshot.width==640 && snapshot.height==480) {
                    "Camera2 cropped YUV must match 640x480 model contract"
                }
                val argb=Yuv420FrameConverter.argb(snapshot)
                if (!cameraLease.owns(lease) || vision !== current) return
                val prev=previousVisionFrame
                val previousAt=previousVisionCaptureMs
                previousVisionFrame=argb
                previousVisionCaptureMs=captureAtMs
                if (prev!=null) {
                    if (previousAt>0L &&
                        captureAtMs-previousAt in 1L..A500sRawVisionBridge.MAX_GAP_MS) {
                        if (cameraLease.owns(lease) && vision === current)
                            current.offer(OpenpilotInferencePipeline.PairSample(
                                prev,argb,captureAtMs))
                    } else {
                        current.invalidate()
                    }
                }
            } catch (error: Exception) {
                previousVisionFrame=null
                previousVisionCaptureMs=0L
                current.invalidate()
                runOnUiThread {
                    if (cameraLease.owns(lease) && vision === current &&
                        inForeground)
                        status.text="Khung YUV không hợp lệ: "+
                            error.javaClass.simpleName
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inForeground=true
        worker=HandlerThread("TTX-PhoneCamera").apply { start() }
        handler=Handler(worker.looper)
        if (preview.holder.surface.isValid) openCamera()
    }

    private fun openCamera() {
        if (started || !inForeground || isFinishing || isDestroyed ||
            !preview.holder.surface.isValid || !::handler.isInitialized) return
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA),440)
            return
        }
        val manager=getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val id=manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(
                    CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
            if (id==null) {
                status.text="Không tìm thấy camera trên thiết bị."
                return
            }
            // Only promise YUV420 at 640x480 if Camera2 advertises it.
            val map=manager.getCameraCharacteristics(id).get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supported=map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.any { it.width==640 && it.height==480 } == true
            if (!supported) {
                status.text="Camera2 không công bố YUV420 640×480; " +
                    "không mở stream AI với kích thước tự đoán."
                return
            }
            realtimeSensorClock=manager.getCameraCharacteristics(id).get(
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

            started=true
            val lease=cameraLease.begin()
            activeCameraToken=lease
            manager.openCamera(id,object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    runOnUiThread {
                        if (!inForeground || !cameraLease.owns(lease) ||
                            !preview.holder.surface.isValid ||
                            isFinishing || isDestroyed) {
                            device.close()
                        } else {
                            camera=device
                            configureCapture(device,lease)
                        }
                    }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    runOnUiThread {
                        if (cameraLease.owns(lease)) {
                            closeCamera()
                            status.text="Camera đã ngắt kết nối."
                        }
                    }
                }
                override fun onError(device: CameraDevice,error:Int) {
                    device.close()
                    runOnUiThread {
                        if (cameraLease.owns(lease)) {
                            closeCamera()
                            status.text="Camera2 lỗi "+error
                        }
                    }
                }
            },handler)
        } catch (error:Exception) {
            closeCamera()
            status.text="Không thể mở camera: "+error.javaClass.simpleName
        }
    }

    private fun configureCapture(device: CameraDevice,lease: Long) {
        if (!cameraLease.owns(lease)) {
            device.close()
            return
        }
        try {
            val yuv=ImageReader.newInstance(640,480,ImageFormat.YUV_420_888,3)
            reader=yuv
            yuv.setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.use { image ->
                        if (cameraLease.owns(lease)) acceptYuv(image,lease)
                    }
                } catch (_:IllegalStateException) {
                    // ImageReader may have been closed during Surface teardown.
                }
            },handler)
            val surfaces=listOf(preview.holder.surface,yuv.surface)
            device.createCaptureSession(surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        runOnUiThread {
                            if (!cameraLease.owns(lease) ||
                                camera !== device || !inForeground ||
                                !preview.holder.surface.isValid) {
                                value.close()
                            } else {
                                session=value
                                try {
                                    val request=device.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(preview.holder.surface)
                                        addTarget(yuv.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    }
                                    value.setRepeatingRequest(request.build(),null,handler)
                                    status.text="Camera2 preview + YUV420 đang hoạt động. " +
                                        (if (realtimeSensorClock)
                                            "Timestamp cảm biến REALTIME; " +
                                                "RAW ONNX chỉ là chẩn đoán."
                                        else "Timestamp cảm biến UNKNOWN; " +
                                            "đã khóa RAW ONNX để tránh tuổi ảnh giả.")
                                } catch (error:Exception) {
                                    closeCamera()
                                    status.text="Camera2: "+error.javaClass.simpleName
                                }
                            }
                        }
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) {
                        value.close()
                        runOnUiThread {
                            if (cameraLease.owns(lease)) {
                                closeCamera()
                                status.text="Camera không hỗ trợ đồng thời Preview/YUV."
                            }
                        }
                    }
                },handler)
        } catch (error:Exception) {
            closeCamera()
            status.text="Camera2 stream: "+error.javaClass.simpleName
        }
    }

    private fun closeCamera() {
        cameraLease.revoke()
        activeCameraToken=0L
        started=false

        modelLease.revoke()
        loadingAi=false
        val prior=vision
        vision=null
        prior?.close()
        if (::aiButton.isInitialized) {
            aiButton.isEnabled=true
            aiButton.text="AI: TẮT · CHẨN ĐOÁN TENSOR"
        }
        synchronized(yuvLock) {
            sensorFrameGate.reset()
            previousVisionFrame=null
            previousVisionCaptureMs=0L
            lastVisionCaptureMs=0L
        }
        realtimeSensorClock=false
        rejectedCameraTimestamps.set(0L)

        try { session?.close() } catch (_:Exception) {}
        session=null
        try { camera?.close() } catch (_:Exception) {}
        camera=null
        try { reader?.close() } catch (_:Exception) {}
        reader=null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (inForeground) openCamera()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format:Int,width:Int,height:Int){}
    override fun surfaceDestroyed(holder: SurfaceHolder) { closeCamera() }

    override fun onRequestPermissionsResult(code:Int,p:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,p,results)
        if (code==440) {
            if (results.firstOrNull()==PackageManager.PERMISSION_GRANTED) openCamera()
            else status.text="Chưa cấp quyền camera. Không thể mở preview."
        }
    }

    override fun onPause() {
        inForeground=false
        closeCamera()
        if (::worker.isInitialized) worker.quitSafely()
        // Never join a native Camera2 callback thread on Android UI.
        super.onPause()
    }
}
