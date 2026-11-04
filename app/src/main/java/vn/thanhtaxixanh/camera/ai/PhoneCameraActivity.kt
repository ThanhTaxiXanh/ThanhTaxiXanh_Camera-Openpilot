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
        if (loadingAi) return
        if (vision != null) {
            vision?.close()
            vision=null
            previousVisionFrame=null
            previousVisionCaptureMs=0L
            aiButton.text="AI: TẮT · CHẨN ĐOÁN TENSOR"
            status.text="Đã tắt suy luận AI thử nghiệm."
            return
        }
        val model=File(filesDir,"models/vision.onnx")
        if (!model.isFile) {
            status.text="Hãy nhập model ONNX được phép sử dụng từ màn hình Openpilot."
            return
        }
        loadingAi=true
        aiButton.isEnabled=false
        status.text="Đang mở ONNX và xác minh model (không hiệu chuẩn)..."
        Thread({
            var candidate: OpenpilotInferencePipeline?=null
            var failure: String?=null
            try {
                val runner=VisionModelBackend.openEngine(model)
                    ?: error("Bản Lite không có runtime; cần bản TTX AI")
                // Synthetic center cropping for structural pipeline testing
                // only; the physical camera intrinsics/warp are NOT verified.
                val mainWarp=VirtualCameraWarp.Matrix(doubleArrayOf(
                    1.0,0.0,64.0,0.0,1.0,112.0,0.0,0.0,1.0))
                val wideWarp=VirtualCameraWarp.Matrix(doubleArrayOf(
                    1.2,0.0,13.0,0.0,1.2,86.0,0.0,0.0,1.0))
                candidate=OpenpilotInferencePipeline(
                    runner,mainWarp,wideWarp,
                    calibration=null,sourceWidth=640,sourceHeight=480,
                    nowMs={ SystemClock.elapsedRealtime() }) { result ->
                    runOnUiThread {
                        if (vision != null && !isFinishing)
                            status.text="Openpilot RAW: " + result.status +
                                " · xử lý " + result.runtimeMs + "ms" +
                                (result.timing?.sourceToResultMs?.let {
                                    " · từ nhận Camera2 " + it + "ms"
                                } ?: "") +
                                " · KHÔNG phải trễ cảnh thực · chưa hiệu chuẩn"
                    }
                }
            } catch (e:Exception) {
                failure=e.message ?: e.javaClass.simpleName
            }
            val newPipeline=candidate
            runOnUiThread {
                loadingAi=false
                aiButton.isEnabled=true
                if (isFinishing || isDestroyed) {
                    newPipeline?.close()
                } else if (failure!=null) {
                    status.text="Không mở được AI: " + failure
                    newPipeline?.close()
                } else {
                    vision=newPipeline
                    previousVisionFrame=null
                    previousVisionCaptureMs=0L
                    aiButton.text="AI: RAW ONNX ĐANG CHẠY · BẤM ĐỂ TẮT"
                    status.text="AI ONNX thử nghiệm trên YUV điện thoại; " +
                        "không dùng kết quả để cảnh báo."
                }
            }
        },"TTX-Openpilot-Load").start()
    }

    /** Camera callback performs at most one YUV copy every ~350ms. */
    private fun acceptYuv(image: android.media.Image) {
        framesReceived.incrementAndGet()
        val current=vision ?: return
        val now=SystemClock.elapsedRealtime()
        if (now-lastVisionCaptureMs<350L) return
        lastVisionCaptureMs=now
        try {
            val argb=Yuv420FrameConverter.argb(AndroidYuvImageBridge.snapshot(image))
            val prev=previousVisionFrame
            val previousAt=previousVisionCaptureMs
            previousVisionFrame=argb
            previousVisionCaptureMs=now
            if (prev!=null) {
                if (previousAt > 0L &&
                    now - previousAt in 1L..A500sRawVisionBridge.MAX_GAP_MS) {
                    current.offer(OpenpilotInferencePipeline.PairSample(prev,argb,now))
                } else {
                    // Camera2 pause/stream jump: old ONNX result must not
                    // masquerade as the next continuous video frame.
                    current.invalidate()
                }
            }
        } catch (e:Exception) {
            previousVisionFrame=null
            previousVisionCaptureMs=0L
            current.invalidate()
            runOnUiThread {
                status.text="Khung YUV không hợp lệ: "+e.javaClass.simpleName
            }
        }
    }

    override fun onResume() {
        super.onResume()
        worker=HandlerThread("TTX-PhoneCamera").apply { start() }
        handler=Handler(worker.looper)
        if (preview.holder.surface.isValid) openCamera()
    }

    private fun openCamera() {
        if (started || !preview.holder.surface.isValid ||
            !::handler.isInitialized) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 440)
            return
        }
        val manager=getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val id=manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(
                    CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
            if(id==null) {
                status.text="Không tìm thấy camera trên thiết bị."
                return
            }
            started=true
            manager.openCamera(id,object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera=device
                    configureCapture(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera=null
                    started=false
                    runOnUiThread { status.text="Camera đã ngắt kết nối" }
                }
                override fun onError(device: CameraDevice,error:Int) {
                    device.close()
                    camera=null
                    started=false
                    runOnUiThread { status.text="Camera2 lỗi $error" }
                }
            },handler)
        }catch(e: Exception){
            started=false
            status.text="Không thể mở camera: "+e.javaClass.simpleName
        }
    }

    private fun configureCapture(device: CameraDevice) {
        try {
            // Full-rate preview must not wait on YUV processing.
            val yuv=ImageReader.newInstance(640,480,ImageFormat.YUV_420_888,3)
            reader=yuv
            yuv.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { acceptYuv(it) }
            },handler)
            val surfaces=listOf(preview.holder.surface,yuv.surface)
            device.createCaptureSession(surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        if (camera !== device) {
                            value.close()
                            return
                        }
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
                            runOnUiThread {
                                status.text="Camera2 đang xem trực tiếp. " +
                                    "Luồng YUV hỗ trợ phân tích, AI chưa kích hoạt."
                            }
                        }catch(e:Exception) {
                            runOnUiThread { status.text="Camera2: "+e.message }
                        }
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) {
                        // On restricted devices, keep failure visible instead
                        // of incorrectly claiming image/YUV is available.
                        runOnUiThread {
                            status.text="Camera không hỗ trợ đồng thời Preview/YUV 640×480."
                        }
                    }
                },handler)
        }catch(e:Exception){
            runOnUiThread { status.text="Camera2 stream: "+e.javaClass.simpleName }
        }
    }

    private fun closeCamera() {
        started=false
        previousVisionFrame=null
        previousVisionCaptureMs=0L
        vision?.invalidate()
        try { session?.close() }catch(_:Exception){}
        session=null
        try { camera?.close() }catch(_:Exception){}
        camera=null
        try { reader?.close() }catch(_:Exception){}
        reader=null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (hasWindowFocus()) openCamera()
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
        vision?.close()
        vision=null
        previousVisionFrame=null
        previousVisionCaptureMs=0L
        if (::aiButton.isInitialized)
            aiButton.text="AI: TẮT · CHẨN ĐOÁN TENSOR"
        closeCamera()
        if (::worker.isInitialized) {
            worker.quitSafely()
            try { worker.join(1000L) } catch (_:InterruptedException){}
        }
        super.onPause()
    }
}
