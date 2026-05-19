package com.example.aiassistant.pomodoro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PomodoroProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokePx = 8f * resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = Color.parseColor("#E5E7EB")
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = Color.parseColor("#3B82F6")
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private var progress: Float = 1f

    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped != progress) {
            progress = clamped
            invalidate()
        }
    }

    fun setProgressColor(color: Int) {
        if (progressPaint.color != color) {
            progressPaint.color = color
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = bgPaint.strokeWidth / 2
        rect.set(half, half, width - half, height - half)
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)
        if (progress > 0f) {
            canvas.drawArc(rect, -90f, 360f * progress, false, progressPaint)
        }
    }
}
