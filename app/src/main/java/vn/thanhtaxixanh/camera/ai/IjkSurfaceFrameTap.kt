package vn.thanhtaxixanh.camera.ai

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit diagnostic: read back the EXISTING IJK video Surface as ARGB.
 * PixelCopy invokes a callback off the main thread; never runs an extra AVC
 * decoder. Copies may cost GPU time, be rescaled or repeat old video content.
 */
class IjkSurfaceFrameTap(
    private val video: SurfaceView,
    private val nativeState: () -> NativeState,
    private val onFrame: (IntArray, Long) -> Unit
) : Closeable {
    data class NativeState(
        val playing: Boolean, val fps: Float,
        val videoWidth: Int, val videoHeight: Int
    )

    private val gate = IjkSurfaceTapPolicy()
    private val callbackFaults = AtomicLong()
    private val ui = Handler(Looper.getMainLooper())
    private val callbackThread = HandlerThread("TTX-IJK-PixelCopy").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    @Volatile private var closed = false

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (closed) return
            val state = try { nativeState() } catch (_: Exception) {
                NativeState(false, 0f, 0, 0)
            }
            val ticket = gate.request(
                SystemClock.elapsedRealtime(), state.playing, state.fps,
                state.videoWidth, state.videoHeight,
                video.holder.surface.isValid && video.width > 0 && video.height > 0
            )
            if (ticket != null) {
                var bitmap: Bitmap? = null
                try {
                    // One 640x480 ARGB copy per admitted request. The image is
                    // not a native YUV frame, and no bitmap is persisted.
                    val image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
                    bitmap = image
                    PixelCopy.request(video, image, { result ->
                        try {
                            if (result == PixelCopy.SUCCESS) {
                                val pixels = IntArray(640 * 480)
                                image.getPixels(pixels, 0, 640, 0, 0, 640, 480)
                                if (gate.complete(ticket,
                                        SystemClock.elapsedRealtime(), true) &&
                                    gate.isCurrent(ticket)) {
                                    onFrame(pixels, ticket.requestedAtMs)
                                }
                            } else {
                                gate.complete(ticket, SystemClock.elapsedRealtime(), false)
                                callbackFaults.incrementAndGet()
                            }
                        } catch (_: Exception) {
                            gate.complete(ticket, SystemClock.elapsedRealtime(), false)
                            callbackFaults.incrementAndGet()
                        } finally {
                            image.recycle()
                        }
                    }, callbackHandler)
                } catch (_: Exception) {
                    gate.complete(ticket, SystemClock.elapsedRealtime(), false)
                    callbackFaults.incrementAndGet()
                    bitmap?.recycle()
                }
            }
            // Single in-flight request enforced by the gate. This periodic
            // UI callback does no pixel copies or model inference.
            if (!closed) ui.postDelayed(this, 350L)
        }
    }

    fun start() {
        if (closed) return
        gate.start()
        ui.post(tick)
    }

    fun metrics(): Pair<IjkSurfaceTapPolicy.Metrics, Long> =
        Pair(gate.metrics(), callbackFaults.get())

    override fun close() {
        closed = true
        gate.stop()
        ui.removeCallbacks(tick)
        callbackThread.quitSafely()
    }
}
