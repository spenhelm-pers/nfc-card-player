package com.spencer.tagtunes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A thin rounded line that fills from the left, with an optional dot at the end.
 *
 * Set [onScrub] to make it touchable: tapping jumps to that spot and dragging scrubs.
 * The view's height is the touch area; the line is always drawn thin in the middle.
 */
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

    /** True while a finger is down on the bar. Callers should not push new values in meanwhile. */
    var isDragging = false
        private set

    /**
     * Called with the touched fraction as the finger moves (done = false) and once when it
     * lifts (done = true). A plain tap gives a single done = true call. Null = display only.
     */
    var onScrub: ((fraction: Float, done: Boolean) -> Unit)? = null

    private val inset get() = if (thumbColor != 0) 7f * dp else 2f * dp

    private fun fractionAt(x: Float): Float {
        val start = inset
        val end = width - inset
        return if (end <= start) 0f else ((x - start) / (end - start)).coerceIn(0f, 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = onScrub ?: return super.onTouchEvent(event)
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep a scroll view or the like from stealing a sideways drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                isPressed = true
                fraction = fractionAt(event.x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                fraction = fractionAt(event.x)
                listener(fraction, false)
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) return false
                fraction = fractionAt(event.x)
                endDrag()
                performClick()
                listener(fraction, true)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                endDrag()
                listener(fraction, true)
            }
        }
        return true
    }

    private fun endDrag() {
        isDragging = false
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
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
            // The dot grows a little while held so a kid can see they've grabbed it.
            canvas.drawCircle(x, cy, (if (isDragging) 10f else 7f) * dp, paint)
        }
    }
}
