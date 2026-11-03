package vn.thanhtaxixanh.camera.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Displays no synthetic lane or distance before AI inference is calibrated. */
class AdasHudView(context: Context) : View(context) {
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = Color.rgb(184, 229, 190)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    var speedKmh: Float? = null
        set(value) { field = value; invalidate() }
    var hudVisible = true
        set(value) { field = value; invalidate() }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hudVisible) return
        speedKmh?.let { speed ->
            if (speed.isFinite() && speed >= 0f)
                canvas.drawText("%.0f km/h".format(java.util.Locale.US, speed),
                    12f, 42f, label)
        }
        canvas.drawText("TTX Camera AI · VIDEO / ADAS", 12f,
            (height - 24).coerceAtLeast(30).toFloat(), label)
    }
}
