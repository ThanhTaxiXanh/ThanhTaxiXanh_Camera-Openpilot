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
        // Use a FIT_CENTER transform matching VLC/Media3 video instead of stretching
        // lane geometry across black letterboxing in portrait fullscreen.
        val scale = minOf(width.toFloat() / r.width, height.toFloat() / r.height)
        val dx = (width - r.width * scale) * 0.5f
        val dy = (height - r.height * scale) * 0.5f
        fun drawLine(s: Segment?) {
            if (s != null) canvas.drawLine(
                dx + s.x1 * scale, dy + s.y1 * scale,
                dx + s.x2 * scale, dy + s.y2 * scale, linePaint
            )
        }
        drawLine(r.left); drawLine(r.right)
        r.vehicle?.let { car ->
            val b = car.box
            canvas.drawRect(
                dx + b.left * scale, dy + b.top * scale,
                dx + b.right * scale, dy + b.bottom * scale, leadPaint
            )
            canvas.drawText(
                String.format(Locale.US, "~%.0f m · CHƯA HIỆU CHUẨN", car.approxM),
                dx + b.left * scale,
                (dy + b.top * scale - 14f).coerceAtLeast(42f), textPaint
            )
        }
        textPaint.textSize = 26f
        canvas.drawText("TTX Camera AI · THỬ NGHIỆM", 14f, 35f, textPaint)
        val detected = listOfNotNull(r.left, r.right).size
        canvas.drawText(
            "Vạch: " + detected + "/2 · " +
                if (r.vehicle == null) "Chưa có phát hiện xe" else "Ước lượng chưa hiệu chuẩn",
            14f, height - 16f, textPaint
        )
    }
}
