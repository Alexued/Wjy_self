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
        color = Color.parseColor("#771E2D27") // 极富禅意的松绿树影深色蒙层
        style = Paint.Style.FILL
    }

    // 画笔：选框边框
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#8FBC8F") // 抹茶绿描边
        style = Paint.Style.STROKE
        strokeWidth = 2.5f // 更精细优雅的细线
        isAntiAlias = true
    }

    // L型角标画笔
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#5C8271") // 深抹茶绿重色角标
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
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
        color = Color.parseColor("#5C8271")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // 确认按钮区域
    private val confirmPaint = Paint().apply {
        color = Color.parseColor("#5C8271") // 抹茶重色
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 取消按钮区域
    private val cancelPaint = Paint().apply {
        color = Color.parseColor("#E2EFE7") // 抹茶淡色背景
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val confirmTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val cancelTextPaint = Paint().apply {
        color = Color.parseColor("#5C8271")
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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

        // 1. 全屏松绿树影半透明蒙层
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (hasSelection || currentMode != TouchMode.NONE) {
            // 2. 选区挖洞
            canvas.drawRect(selectionRect, clearPaint)
            // 3. 抹茶绿细线选框
            canvas.drawRect(selectionRect, borderPaint)

            // 4. 画专业 L-型调节手柄
            if (hasSelection && currentMode == TouchMode.NONE) {
                drawHandles(canvas)
            }

            // 5. 尺寸标注气泡 (Zen 风格浮空标签)
            val sizeText = " 📐 ${selectionRect.width().toInt()} × ${selectionRect.height().toInt()} "
            val textWidth = sizePaint.measureText(sizeText)
            val textHeight = sizePaint.fontMetrics.descent - sizePaint.fontMetrics.ascent
            val cx = selectionRect.centerX()
            val ty = if (selectionRect.top > 90) selectionRect.top - 30f else selectionRect.bottom + 60f
            
            // 绘制气泡背景
            val bubbleRect = RectF(cx - textWidth/2 - 20f, ty - textHeight/2 - 12f, cx + textWidth/2 + 20f, ty + textHeight/2 + 4f)
            val bubbleBgPaint = Paint().apply {
                color = Color.parseColor("#FAF7F2") // 禅暖沙色底色
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawRoundRect(bubbleRect, 20f, 20f, bubbleBgPaint)
            
            // 绘制气泡描边
            val bubbleBorderPaint = Paint().apply {
                color = Color.parseColor("#D5CDBC") // 柔砂描边
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
            canvas.drawRoundRect(bubbleRect, 20f, 20f, bubbleBorderPaint)
            
            // 绘制文字
            canvas.drawText(sizeText, cx, ty - sizePaint.fontMetrics.ascent - textHeight/2 - 4f, sizePaint)
        }

        // 6. 按钮或悬浮提示
        if (hasSelection && currentMode == TouchMode.NONE) {
            drawButtons(canvas)
        } else if (!hasSelection && currentMode == TouchMode.NONE) {
            // 绘制高大上 Zen 风格提示框
            val tipText = " 💡 拖动手指框选截图区域 "
            textPaint.color = Color.parseColor("#5C8271")
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val textWidth = textPaint.measureText(tipText)
            val textHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
            val cx = width / 2f
            val cy = height / 2f
            
            val tipRect = RectF(cx - textWidth/2 - 30f, cy - textHeight/2 - 20f, cx + textWidth/2 + 30f, cy + textHeight/2 + 20f)
            val tipBgPaint = Paint().apply {
                color = Color.parseColor("#FAF7F2") // 暖沙背景
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawRoundRect(tipRect, 32f, 32f, tipBgPaint)
            
            val tipBorderPaint = Paint().apply {
                color = Color.parseColor("#D5CDBC")
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
            canvas.drawRoundRect(tipRect, 32f, 32f, tipBorderPaint)
            
            canvas.drawText(tipText, cx, cy - textPaint.fontMetrics.ascent - textHeight/2, textPaint)
        }
    }

    private fun drawHandles(canvas: Canvas) {
        val r = selectionRect
        val len = 36f // 角标线段长度
        
        // 1. 左上角 L
        canvas.drawLine(r.left, r.top, r.left + len, r.top, cornerPaint)
        canvas.drawLine(r.left, r.top, r.left, r.top + len, cornerPaint)
        
        // 2. 右上角 L
        canvas.drawLine(r.right, r.top, r.right - len, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, cornerPaint)
        
        // 3. 左下角 L
        canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left, r.bottom - len, cornerPaint)
        
        // 4. 右下角 L
        canvas.drawLine(r.right, r.bottom, r.right - len, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - len, cornerPaint)
    }

    private fun drawButtons(canvas: Canvas) {
        val rect = selectionRect
        val btnW = 190f
        val btnH = 80f
        val gap = 48f
        val btnY = (rect.bottom + 50f).coerceAtMost(height - btnH - 30f)

        // 确认按钮
        confirmBtnRect = RectF(
            rect.centerX() - btnW - gap / 2, btnY,
            rect.centerX() - gap / 2, btnY + btnH
        )
        canvas.drawRoundRect(confirmBtnRect, btnH / 2, btnH / 2, confirmPaint)
        canvas.drawText("✓ 确认", confirmBtnRect.centerX(), confirmBtnRect.centerY() - (confirmTextPaint.fontMetrics.ascent + confirmTextPaint.fontMetrics.descent) / 2, confirmTextPaint)

        // 取消按钮
        cancelBtnRect = RectF(
            rect.centerX() + gap / 2, btnY,
            rect.centerX() + btnW + gap / 2, btnY + btnH
        )
        canvas.drawRoundRect(cancelBtnRect, btnH / 2, btnH / 2, cancelPaint)
        canvas.drawText("✕ 取消", cancelBtnRect.centerX(), cancelBtnRect.centerY() - (cancelTextPaint.fontMetrics.ascent + cancelTextPaint.fontMetrics.descent) / 2, cancelTextPaint)
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
