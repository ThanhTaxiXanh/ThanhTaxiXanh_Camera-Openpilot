package vn.thanhtaxixanh.camera.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/**
 * Native-style transparent HUD, independently implemented for TTX.
 *
 * Never draws synthetic lanes or distances. Real model output may be
 * projected only after verified camera calibration, must be fresh, and is
 * automatically erased when inference stops.
 */
class AdasHudView(context: Context) : View(context) {
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = Color.rgb(184, 229, 190)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val laneLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(72, 232, 139)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val distanceLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 25f
        color = Color.rgb(255, 205, 85)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    var speedKmh: Float? = null
        set(value) { field = value; invalidate() }
    var hudVisible = true
        set(value) { field = value; invalidate() }

    private var modelLanes: List<LaneProjection.ProjectedLane> = emptyList()
    private var validatedState: VisionSafetyGate.VisibleState? = null
    private var modelFrameTime = 0L
    private var sourceW = 0
    private var sourceH = 0
    private val clearExpired = Runnable { invalidate() }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    /** Only call on the UI thread, from a calibrated real inference result. */
    fun renderVerified(
        lanes: List<LaneProjection.ProjectedLane>,
        result: VisionSafetyGate.VisibleState,
        capturedElapsedMs: Long,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        if (result.status != VisionSafetyGate.DataStatus.VISIBLE ||
            sourceWidth < 2 || sourceHeight < 2 ||
            SystemClock.elapsedRealtime() - capturedElapsedMs !in
                0L..VisionSafetyGate.MAX_FRAME_AGE_MS) {
            clearVision()
            return
        }
        modelLanes = if (result.lanesVisible) lanes else emptyList()
        validatedState = result
        modelFrameTime = capturedElapsedMs
        sourceW = sourceWidth
        sourceH = sourceHeight
        removeCallbacks(clearExpired)
        postDelayed(clearExpired, VisionSafetyGate.MAX_FRAME_AGE_MS + 1)
        invalidate()
    }

    fun clearVision() {
        modelLanes = emptyList()
        validatedState = null
        modelFrameTime = 0L
        removeCallbacks(clearExpired)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hudVisible) return
        speedKmh?.let { speed ->
            if (speed.isFinite() && speed >= 0f)
                canvas.drawText("%.0f km/h".format(java.util.Locale.US, speed),
                    12f, 42f, caption)
        }
        canvas.drawText("TTX Camera AI · VIDEO / ADAS", 12f,
            (height - 24).coerceAtLeast(30).toFloat(), caption)

        if (modelFrameTime <= 0L ||
            SystemClock.elapsedRealtime() - modelFrameTime >
                VisionSafetyGate.MAX_FRAME_AGE_MS) return
        val state=validatedState ?: return
        if (sourceW <= 0 || sourceH <= 0) return
        val scale=min(width.toFloat()/sourceW,height.toFloat()/sourceH)
        val offsetX=(width-sourceW*scale)/2f
        val offsetY=(height-sourceH*scale)/2f

        if (state.lanesVisible) {
            for (lane in modelLanes) {
                if (lane.pixels.size < 2) continue
                val path=Path()
                lane.pixels.forEachIndexed { index,p ->
                    val x=offsetX+p.x*scale
                    val y=offsetY+p.y*scale
                    if (index==0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                canvas.drawPath(path,laneLine)
            }
        }
        // No fake vehicle rectangle is drawn: a metric-calibrated lead image
        // anchor is not available from raw xADAS model outputs alone.
        if (state.leadVisible && state.leadDistanceMeters!=null) {
            canvas.drawText(
                "%.0f m · CHỈ THAM KHẢO".format(
                    java.util.Locale.US,state.leadDistanceMeters),
                12f,(height-65).coerceAtLeast(38).toFloat(),distanceLabel)
        }
    }
}
