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
        canvas.drawText("TTX Camera AI · VIDEO / ADAS", 12f,
            (height - 24).coerceAtLeast(30).toFloat(), label)
    }
}
