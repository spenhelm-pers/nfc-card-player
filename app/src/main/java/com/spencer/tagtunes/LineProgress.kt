package com.spencer.tagtunes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** A thin rounded line that fills from the left, with an optional dot at the end. Display only. */
class LineProgress @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * dp
    }

    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var trackColor = 0xFF4A3A2F.toInt()
    var fillColor = 0xFFC9A15A.toInt()
    /** 0 = no dot. */
    var thumbColor = 0

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val inset = if (thumbColor != 0) 7f * dp else 2f * dp
        val start = inset
        val end = width - inset
        paint.style = Paint.Style.STROKE
        paint.color = trackColor
        canvas.drawLine(start, cy, end, cy, paint)
        val x = start + (end - start) * fraction
        if (fraction > 0f) {
            paint.color = fillColor
            canvas.drawLine(start, cy, x, cy, paint)
        }
        if (thumbColor != 0) {
            paint.style = Paint.Style.FILL
            paint.color = thumbColor
            canvas.drawCircle(x, cy, 7f * dp, paint)
        }
    }
}
