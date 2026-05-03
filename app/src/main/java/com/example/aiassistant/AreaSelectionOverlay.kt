package com.example.aiassistant

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * 全屏蒙层 + 手指拖拽画矩形框选截图区域
 * 松手后回调选中的 Rect（屏幕坐标）
 */
class AreaSelectionOverlay(
    context: Context,
    private val onAreaSelected: (Rect) -> Unit,
    private val onCancelled: () -> Unit
) : View(context) {

    // 画笔：半透明蒙层
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }

    // 画笔：选框边框
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }

    // 画笔：选框填充
    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // 提示文字画笔
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // 尺寸标注文字画笔
    private val sizePaint = Paint().apply {
        color = Color.parseColor("#BBBBBB")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // 确认按钮区域
    private val confirmPaint = Paint().apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val confirmTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDrawing = false
    private var hasSelection = false

    // 确认/取消按钮的 RectF
    private var confirmBtnRect = RectF()
    private var cancelBtnRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // PorterDuff.Mode.CLEAR 需要
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 如果已有选区，检查是否点击了确认/取消按钮
                if (hasSelection) {
                    if (confirmBtnRect.contains(event.x, event.y)) {
                        deliverResult()
                        return true
                    }
                    if (cancelBtnRect.contains(event.x, event.y)) {
                        onCancelled()
                        return true
                    }
                }
                // 开始新的框选
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                isDrawing = true
                hasSelection = false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    endX = event.x
                    endY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    endX = event.x
                    endY = event.y
                    isDrawing = false
                    // 如果框选太小（< 30px），视为误触
                    val selRect = getSelectionRect()
                    if (selRect.width() > 30 && selRect.height() > 30) {
                        hasSelection = true
                    }
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 整个屏幕蒙层
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (isDrawing || hasSelection) {
            val rect = getSelectionRectF()
            // 清除选区内的蒙层（使选区透明，看到底下内容）
            canvas.drawRect(rect, clearPaint)
            // 画虚线边框
            canvas.drawRect(rect, borderPaint)

            // 显示尺寸标注
            val sizeText = "${rect.width().toInt()} × ${rect.height().toInt()}"
            val ty = if (rect.top > 50) rect.top - 12f else rect.bottom + 40f
            canvas.drawText(sizeText, rect.centerX(), ty, sizePaint)
        }

        if (hasSelection) {
            // 画确认和取消按钮（选区下方）
            val rect = getSelectionRectF()
            val btnW = 160f
            val btnH = 56f
            val gap = 24f
            val btnY = (rect.bottom + 24f).coerceAtMost(height - btnH - 16f)

            // 确认
            confirmBtnRect = RectF(
                rect.centerX() - btnW - gap / 2, btnY,
                rect.centerX() - gap / 2, btnY + btnH
            )
            canvas.drawRoundRect(confirmBtnRect, 14f, 14f, confirmPaint)
            canvas.drawText("✓ 确认", confirmBtnRect.centerX(), confirmBtnRect.centerY() + 12f, confirmTextPaint)

            // 取消
            val cancelPaint = Paint(confirmPaint).apply { color = Color.parseColor("#555555") }
            cancelBtnRect = RectF(
                rect.centerX() + gap / 2, btnY,
                rect.centerX() + btnW + gap / 2, btnY + btnH
            )
            canvas.drawRoundRect(cancelBtnRect, 14f, 14f, cancelPaint)
            canvas.drawText("✕ 取消", cancelBtnRect.centerX(), cancelBtnRect.centerY() + 12f, confirmTextPaint)
        } else if (!isDrawing) {
            // 没有选区时显示提示文字
            canvas.drawText("拖动手指框选截图区域", width / 2f, height / 2f, textPaint)
        }
    }

    private fun getSelectionRect(): Rect {
        val left   = minOf(startX, endX).toInt().coerceAtLeast(0)
        val top    = minOf(startY, endY).toInt().coerceAtLeast(0)
        val right  = maxOf(startX, endX).toInt().coerceAtMost(width)
        val bottom = maxOf(startY, endY).toInt().coerceAtMost(height)
        return Rect(left, top, right, bottom)
    }

    private fun getSelectionRectF(): RectF {
        val r = getSelectionRect()
        return RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    }

    private fun deliverResult() {
        val rect = getSelectionRect()
        if (rect.width() > 30 && rect.height() > 30) {
            onAreaSelected(rect)
        } else {
            onCancelled()
        }
    }
}
