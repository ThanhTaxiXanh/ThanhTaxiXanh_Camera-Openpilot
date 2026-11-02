package vn.thanhtaxixanh.camera.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Locale

/** Overlay is a research HUD. It must never suggest the displayed distance is precise. */
class AdasOverlay(context: Context) : View(context) {
    private val linePaint = Paint(3).apply {
        color = android.graphics.Color.rgb(17, 230, 128)
        strokeWidth = 7f
        style = Paint.Style.STROKE
    }
    private val leadPaint = Paint(3).apply {
        color = android.graphics.Color.rgb(255, 211, 70)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(3).apply {
        color = android.graphics.Color.WHITE
        textSize = 39f
        setShadowLayer(5f, 2f, 2f, android.graphics.Color.BLACK)
    }
    var observation: Observation? = null
        private set

    fun render(value: Observation) { observation = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = observation ?: return
        val x = width.toFloat() / r.width
        val y = height.toFloat() / r.height
        fun drawLine(s: Segment?) {
            if (s != null) canvas.drawLine(s.x1 * x, s.y1 * y, s.x2 * x, s.y2 * y, linePaint)
        }
        drawLine(r.left); drawLine(r.right)
        r.vehicle?.let { car ->
            val b = car.box
            canvas.drawRect(b.left * x, b.top * y, b.right * x, b.bottom * y, leadPaint)
            canvas.drawText(
                String.format(Locale.US, "~%.0f m · CHƯA HIỆU CHUẨN", car.approxM),
                b.left * x, (b.top * y - 14f).coerceAtLeast(42f), textPaint
            )
        }
        canvas.drawText("TTX Camera AI · BẢN THỬ NGHIỆM", 18f, 50f, textPaint)
    }
}
