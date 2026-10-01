package io.meshcore.teletext

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/** A full-width divider used while a page chunk has not arrived yet. */
internal class MissingChunkSpan(
    private val width: Int,
    private val color: Int,
    private val gap: Int,
) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int = width

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val originalColor = paint.color
        val originalStrokeWidth = paint.strokeWidth
        val originalPathEffect = paint.pathEffect
        val originalStyle = paint.style
        paint.color = color
        paint.strokeWidth = maxOf(1f, gap / 8f)
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        val center = x + width / 2f
        val question = "?"
        val questionWidth = paint.measureText(question)
        val lineY = (top + bottom) / 2f
        canvas.drawLine(x, lineY, center - questionWidth / 2f - gap, lineY, paint)
        canvas.drawLine(center + questionWidth / 2f + gap, lineY, x + width, lineY, paint)
        paint.pathEffect = originalPathEffect
        paint.style = Paint.Style.FILL
        canvas.drawText(question, center - questionWidth / 2f, y.toFloat(), paint)
        paint.color = originalColor
        paint.strokeWidth = originalStrokeWidth
        paint.style = originalStyle
    }
}
