package com.example.aiassistant

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * 全屏蒙层 + 手指拖拽画矩形框选截图区域
 * 支持：
 *  - 首次拖拽创建选区
 *  - 选中后拖拽整体移动
 *  - 选中后通过 8 个手柄点调整大小
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

    private val cancelPaint = Paint(confirmPaint).apply { color = Color.parseColor("#555555") }

    private val confirmTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // 手柄圆点画笔
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var selectionRect = RectF()
    private var lastX = 0f
    private var lastY = 0f
    
    private enum class TouchMode { NONE, CREATE, MOVE, RESIZE_L, RESIZE_T, RESIZE_R, RESIZE_B, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var currentMode = TouchMode.NONE
    private var hasSelection = false

    private var confirmBtnRect = RectF()
    private var cancelBtnRect = RectF()

    private val TOUCH_THRESHOLD = 50f // 触控热区大小
    private val HANDLE_RADIUS = 12f   // 视觉手柄圆点大小

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) 
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 1. 优先检查按钮点击
                if (hasSelection) {
                    if (confirmBtnRect.contains(x, y)) {
                        deliverResult()
                        return true
                    }
                    if (cancelBtnRect.contains(x, y)) {
                        onCancelled()
                        return true
                    }
                }

                // 2. 检查是调整已有选区还是新建
                currentMode = getTouchMode(x, y)
                lastX = x
                lastY = y

                if (currentMode == TouchMode.NONE) {
                    selectionRect.set(x, y, x, y)
                    currentMode = TouchMode.CREATE
                    hasSelection = false
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                
                when (currentMode) {
                    TouchMode.CREATE -> {
                        selectionRect.right = x
                        selectionRect.bottom = y
                    }
                    TouchMode.MOVE -> {
                        selectionRect.offset(dx, dy)
                    }
                    TouchMode.RESIZE_L -> selectionRect.left += dx
                    TouchMode.RESIZE_T -> selectionRect.top += dy
                    TouchMode.RESIZE_R -> selectionRect.right += dx
                    TouchMode.RESIZE_B -> selectionRect.bottom += dy
                    TouchMode.RESIZE_TL -> { selectionRect.left += dx; selectionRect.top += dy }
                    TouchMode.RESIZE_TR -> { selectionRect.right += dx; selectionRect.top += dy }
                    TouchMode.RESIZE_BL -> { selectionRect.left += dx; selectionRect.bottom += dy }
                    TouchMode.RESIZE_BR -> { selectionRect.right += dx; selectionRect.bottom += dy }
                    else -> {}
                }
                
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                normalizeRect()
                if (selectionRect.width() > 30 && selectionRect.height() > 30) {
                    hasSelection = true
                } else {
                    hasSelection = false
                }
                currentMode = TouchMode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun getTouchMode(x: Float, y: Float): TouchMode {
        if (!hasSelection) return TouchMode.NONE

        val r = selectionRect
        // 四角优先级最高
        if (isNear(x, r.left, y, r.top)) return TouchMode.RESIZE_TL
        if (isNear(x, r.right, y, r.top)) return TouchMode.RESIZE_TR
        if (isNear(x, r.left, y, r.bottom)) return TouchMode.RESIZE_BL
        if (isNear(x, r.right, y, r.bottom)) return TouchMode.RESIZE_BR
        
        // 四边
        if (Math.abs(x - r.left) < TOUCH_THRESHOLD && y > r.top && y < r.bottom) return TouchMode.RESIZE_L
        if (Math.abs(x - r.right) < TOUCH_THRESHOLD && y > r.top && y < r.bottom) return TouchMode.RESIZE_R
        if (Math.abs(y - r.top) < TOUCH_THRESHOLD && x > r.left && x < r.right) return TouchMode.RESIZE_T
        if (Math.abs(y - r.bottom) < TOUCH_THRESHOLD && x > r.left && x < r.right) return TouchMode.RESIZE_B
        
        // 内部移动
        if (r.contains(x, y)) return TouchMode.MOVE
        
        return TouchMode.NONE
    }

    private fun isNear(x1: Float, x2: Float, y1: Float, y2: Float): Boolean {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy < TOUCH_THRESHOLD * TOUCH_THRESHOLD
    }

    private fun normalizeRect() {
        val l = minOf(selectionRect.left, selectionRect.right).coerceAtLeast(0f)
        val t = minOf(selectionRect.top, selectionRect.bottom).coerceAtLeast(0f)
        val r = maxOf(selectionRect.left, selectionRect.right).coerceAtMost(width.toFloat())
        val b = maxOf(selectionRect.top, selectionRect.bottom).coerceAtMost(height.toFloat())
        selectionRect.set(l, t, r, b)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 全屏半透明蒙层
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (hasSelection || currentMode != TouchMode.NONE) {
            // 2. 选区挖洞
            canvas.drawRect(selectionRect, clearPaint)
            // 3. 虚线边框
            canvas.drawRect(selectionRect, borderPaint)

            // 4. 画调节手柄
            if (hasSelection && currentMode == TouchMode.NONE) {
                drawHandles(canvas)
            }

            // 5. 尺寸标注
            val sizeText = "${selectionRect.width().toInt()} × ${selectionRect.height().toInt()}"
            val ty = if (selectionRect.top > 80) selectionRect.top - 20f else selectionRect.bottom + 50f
            canvas.drawText(sizeText, selectionRect.centerX(), ty, sizePaint)
        }

        // 6. 按钮或提示
        if (hasSelection && currentMode == TouchMode.NONE) {
            drawButtons(canvas)
        } else if (!hasSelection && currentMode == TouchMode.NONE) {
            canvas.drawText("拖动手指框选截图区域", width / 2f, height / 2f, textPaint)
        }
    }

    private fun drawHandles(canvas: Canvas) {
        val r = selectionRect
        // 四角
        canvas.drawCircle(r.left, r.top, HANDLE_RADIUS, handlePaint)
        canvas.drawCircle(r.right, r.top, HANDLE_RADIUS, handlePaint)
        canvas.drawCircle(r.left, r.bottom, HANDLE_RADIUS, handlePaint)
        canvas.drawCircle(r.right, r.bottom, HANDLE_RADIUS, handlePaint)
        // 四边中点
        canvas.drawCircle(r.centerX(), r.top, HANDLE_RADIUS, handlePaint)
        canvas.drawCircle(r.centerX(), r.bottom, HANDLE_RADIUS, handlePaint)
        canvas.drawCircle(r.left, r.centerY(), HANDLE_RADIUS, handlePaint)
        canvas.drawCircle(r.right, r.centerY(), HANDLE_RADIUS, handlePaint)
    }

    private fun drawButtons(canvas: Canvas) {
        val rect = selectionRect
        val btnW = 180f
        val btnH = 70f
        val gap = 40f
        val btnY = (rect.bottom + 40f).coerceAtMost(height - btnH - 30f)

        // 确认按钮
        confirmBtnRect = RectF(
            rect.centerX() - btnW - gap / 2, btnY,
            rect.centerX() - gap / 2, btnY + btnH
        )
        canvas.drawRoundRect(confirmBtnRect, 16f, 16f, confirmPaint)
        canvas.drawText("✓ 确认", confirmBtnRect.centerX(), confirmBtnRect.centerY() + 14f, confirmTextPaint)

        // 取消按钮
        cancelBtnRect = RectF(
            rect.centerX() + gap / 2, btnY,
            rect.centerX() + btnW + gap / 2, btnY + btnH
        )
        canvas.drawRoundRect(cancelBtnRect, 16f, 16f, cancelPaint)
        canvas.drawText("✕ 取消", cancelBtnRect.centerX(), cancelBtnRect.centerY() + 14f, confirmTextPaint)
    }

    private fun deliverResult() {
        val rect = Rect(
            selectionRect.left.toInt(), selectionRect.top.toInt(),
            selectionRect.right.toInt(), selectionRect.bottom.toInt()
        )
        if (rect.width() > 30 && rect.height() > 30) {
            onAreaSelected(rect)
        } else {
            onCancelled()
        }
    }
}
