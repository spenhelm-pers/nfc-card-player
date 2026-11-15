package com.spencer.tagtunes

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * The backdrop behind the book cover: a soft lamp glow, a row of old book spines
 * (some with brass bands) and the wooden shelf they stand on.
 */
class ShelfView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Spine(val w: Float, val h: Float, val color: Int, val topBand: Boolean, val bottomBand: Boolean)

    private val spines = listOf(
        Spine(22f, 188f, 0xFF4B2E2A.toInt(), true, true),
        Spine(16f, 204f, 0xFF2F3F35.toInt(), false, false),
        Spine(26f, 172f, 0xFF5A4630.toInt(), false, false),
        Spine(18f, 214f, 0xFF2C3546.toInt(), true, false),
        Spine(14f, 160f, 0xFF3D2F3F.toInt(), false, false),
        Spine(24f, 196f, 0xFF463A2E.toInt(), true, true),
        Spine(20f, 222f, 0xFF4B2E2A.toInt(), false, false),
        Spine(15f, 180f, 0xFF2F3F35.toInt(), true, false),
        Spine(23f, 200f, 0xFF2C3546.toInt(), false, false),
        Spine(18f, 168f, 0xFF5A4630.toInt(), false, false),
        Spine(21f, 210f, 0xFF3D2F3F.toInt(), true, false),
        Spine(16f, 186f, 0xFF4B2E2A.toInt(), false, false),
        Spine(25f, 176f, 0xFF463A2E.toInt(), false, false),
        Spine(19f, 218f, 0xFF2F3F35.toInt(), true, true),
        Spine(14f, 164f, 0xFF2C3546.toInt(), false, false),
        Spine(24f, 198f, 0xFF5A4630.toInt(), false, false),
        Spine(17f, 206f, 0xFF4B2E2A.toInt(), true, false),
        Spine(22f, 182f, 0xFF3D2F3F.toInt(), false, false),
        Spine(20f, 212f, 0xFF2F3F35.toInt(), false, false),
    )

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** Space under the book: plank thickness plus its bottom margin. */
    val bookBaseOffset = 22f * dp

    /** Height of the book standing on the shelf; the glow and spines scale to it. */
    var coverHeight = 292f * dp
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val baseY = h - bookBaseOffset
        val scale = (coverHeight / (292f * dp)).coerceIn(0.5f, 1.5f)

        // Lamp glow centred on the book
        val glowR = 200f * dp * scale
        val cy = baseY - coverHeight / 2
        paint.shader = RadialGradient(
            w / 2, cy, glowR,
            intArrayOf(0x2BE2C48A, 0x00E2C48A), floatArrayOf(0f, 0.65f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w / 2, cy, glowR, paint)
        paint.shader = null

        // Spines, repeated until the row is full
        var x = 6f * dp
        var i = 0
        val gap = 3f * dp
        val corner = 2f * dp
        while (x < w) {
            val s = spines[i % spines.size]
            val sw = s.w * dp * scale
            val sh = s.h * dp * scale
            paint.color = s.color
            rect.set(x, baseY - sh, x + sw, baseY + corner)
            canvas.drawRoundRect(rect, corner, corner, paint)
            paint.color = 0xFF8C7246.toInt()
            val band = 3f * dp
            if (s.topBand) canvas.drawRect(x, baseY - sh + 19f * dp * scale, x + sw, baseY - sh + 19f * dp * scale + band, paint)
            if (s.bottomBand) canvas.drawRect(x, baseY - 22f * dp * scale, x + sw, baseY - 22f * dp * scale + band, paint)
            x += sw + gap
            i++
        }

        // The shelf plank, its top highlight and a soft shadow under it
        val plankTop = baseY
        val plankBottom = plankTop + 14f * dp
        paint.color = 0xFF4E3A2B.toInt()
        canvas.drawRect(0f, plankTop, w, plankBottom, paint)
        paint.color = 0xFF6B5140.toInt()
        canvas.drawRect(0f, plankTop, w, plankTop + 2f * dp, paint)
        paint.shader = LinearGradient(
            0f, plankBottom, 0f, plankBottom + 10f * dp,
            0x59000000, 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, plankBottom, w, plankBottom + 10f * dp, paint)
        paint.shader = null
    }
}
