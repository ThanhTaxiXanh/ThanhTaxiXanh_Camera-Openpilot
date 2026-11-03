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
        body.addView(Button(this).apply {
            text="ĐÓNG CAMERA"
            setOnClickListener { finish() }
        })
        setContentView(body)
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
                source.acquireLatestImage()?.use {
                    // The raw YUV source is now wired for a separate AI worker.
                    // Decoding/warping is intentionally off until a validated
                    // permitted model is enabled; never block the camera thread.
                    framesReceived.incrementAndGet()
                }
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
        closeCamera()
        if (::worker.isInitialized) {
            worker.quitSafely()
            try { worker.join(1000L) } catch (_:InterruptedException){}
        }
        super.onPause()
    }
}
