package vn.thanhtaxixanh.camera.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** Experimental rendering; no validated lane or metric distance claims. */
class AdasOverlay(context: Context) : View(context) {
    private val prefs = AdasPrefs.of(context)
    private val lanePaint = Paint(3).apply {
        color = Color.rgb(99, 231, 145); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(3).apply {
        color = Color.argb(76, 15, 196, 115); style = Paint.Style.FILL
    }
    private val leadPaint = Paint(3).apply {
        color = Color.rgb(255, 198, 60); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val arrowPaint = Paint(3).apply {
        color = Color.rgb(255, 198, 60); style = Paint.Style.FILL
    }
    private val labelPaint = Paint(3).apply {
        color = Color.WHITE; textSize = 25f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    var observation: Observation? = null
        private set
    private var lastRenderMillis = 0L
    private val staleLimitMillis = 1800L
    private val repaintStale = Runnable { invalidate() }

    fun render(value: Observation) {
        observation = value
        lastRenderMillis = SystemClock.elapsedRealtime()
        removeCallbacks(repaintStale)
        postDelayed(repaintStale, staleLimitMillis)
        invalidate()
    }

    private fun xAt(line: Segment, targetY: Float): Float? {
        val changeY = line.y2 - line.y1
        if (abs(changeY) < 12f) return null
        val value = line.x1 + (targetY - line.y1) * (line.x2 - line.x1) / changeY
        return if (value.isFinite()) value else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = observation ?: return
        if (SystemClock.elapsedRealtime() - lastRenderMillis >= staleLimitMillis) {
            canvas.drawText("AI: DỮ LIỆU CŨ – ĐÃ ẨN LÀN VÀ KHOẢNG CÁCH",
                12f, height - 14f, labelPaint)
            return
        }
        if (result.width < 1 || result.height < 1) return
        val scale = minOf(width.toFloat() / result.width, height.toFloat() / result.height)
        val dx = (width - result.width * scale) / 2f
        val dy = (height - result.height * scale) / 2f
        fun px(x: Float) = dx + x * scale
        fun py(y: Float) = dy + y * scale

        val left = result.left
        val right = result.right
        val nearY = result.height * .95f
        val farY = result.height * .61f
        val leftNear = left?.let { xAt(it, nearY) }
        val leftFar = left?.let { xAt(it, farY) }
        val rightNear = right?.let { xAt(it, nearY) }
        val rightFar = right?.let { xAt(it, farY) }
        val w = result.width.toFloat()
        val corridor = leftNear != null && leftFar != null &&
            rightNear != null && rightFar != null &&
            leftNear in w * .02f..w * .57f &&
            rightNear in w * .43f..w * .98f &&
            leftFar in w * .08f..w * .67f &&
            rightFar in w * .33f..w * .92f &&
            rightNear - leftNear in w * .20f..w * .90f &&
            rightFar - leftFar > w * .015f &&
            rightFar - leftFar < rightNear - leftNear

        if (corridor && prefs.getBoolean(AdasPrefs.LANE_AREA, true)) {
            val polygon = Path().apply {
                moveTo(px(leftNear!!), py(nearY))
                lineTo(px(leftFar!!), py(farY))
                lineTo(px(rightFar!!), py(farY))
                lineTo(px(rightNear!!), py(nearY))
                close()
            }
            canvas.drawPath(polygon, fillPaint)
        }
        if (prefs.getBoolean(AdasPrefs.LANE_LINES, true)) {
            fun drawBoundary(segment: Segment?, near: Float?, far: Float?) {
                if (segment == null) return
                if (corridor && near != null && far != null) {
                    canvas.drawLine(px(near), py(nearY), px(far), py(farY), lanePaint)
                } else {
                    canvas.drawLine(px(segment.x1), py(segment.y1),
                        px(segment.x2), py(segment.y2), lanePaint)
                }
            }
            drawBoundary(left, leftNear, leftFar)
            drawBoundary(right, rightNear, rightFar)
        }

        result.vehicle?.let { vehicle ->
            if (prefs.getBoolean(AdasPrefs.SHOW_LEAD, true)) {
                val box = vehicle.box
                canvas.drawRect(px(box.left), py(box.top), px(box.right), py(box.bottom), leadPaint)
                if (prefs.getBoolean(AdasPrefs.LEAD_ARROW, true)) {
                    val x = px(box.centerX())
                    val tip = (py(box.top) - 12f).coerceAtLeast(55f)
                    canvas.drawPath(Path().apply {
                        moveTo(x, tip)
                        lineTo(x - 13f, tip - 23f)
                        lineTo(x + 13f, tip - 23f)
                        close()
                    }, arrowPaint)
                }
                var distance = vehicle.approxM
                if (distance >= 20f && prefs.getBoolean(AdasPrefs.ROUND_DISTANCE, false))
                    distance = (distance / 5f).roundToInt() * 5f
                val imperial = prefs.getString(AdasPrefs.SPEED_UNIT, "km/h · m") == "mph · ft"
                val label = if (imperial)
                    "~" + (distance * 3.28084f).roundToInt() + " ft"
                    else "~" + distance.roundToInt() + " m"
                canvas.drawText(label + " · CHƯA HIỆU CHUẨN",
                    px(box.left).coerceAtLeast(8f), (py(box.top) - 42f).coerceAtLeast(45f),
                    labelPaint)
            }
        }
        if (prefs.getBoolean(AdasPrefs.SHOW_LOGO, true))
            canvas.drawText("TTX Camera AI · THỬ NGHIỆM", 12f, 33f, labelPaint)
        val seen = listOfNotNull(left, right).size
        val label = if (corridor) " · Vùng xanh thử nghiệm" else " · Chưa đủ biên"
        canvas.drawText("Làn " + seen + "/2" + label, 12f, height - 14f, labelPaint)
    }
}
