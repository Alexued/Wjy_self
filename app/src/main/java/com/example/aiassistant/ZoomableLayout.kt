package com.example.aiassistant

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * 放在 ScrollView 内部的缩放容器，支持双指缩放和单指平移（放大后）。
 * 未缩放时 ScrollView 正常滚动；放大后由此容器处理平移。
 */
class ZoomableLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var scaleFactor = 1f
    private val minScale = 0.5f
    private val maxScale = 3f

    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private var isScaling = false

    private val isZoomed: Boolean get() = scaleFactor > 1.01f

    init {
        clipChildren = false
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            parent.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            val ratio = newScale / scaleFactor
            // 以双指焦点为中心缩放
            panX = detector.focusX - ratio * (detector.focusX - panX)
            panY = detector.focusY - ratio * (detector.focusY - panY)
            scaleFactor = newScale
            clampPan()
            applyTransform()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
            if (scaleFactor < 1.01f) {
                // 缩回原始大小时归位，恢复 ScrollView 滚动
                scaleFactor = 1f
                panX = 0f
                panY = 0f
                applyTransform()
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 始终喂给 ScaleGestureDetector，确保即便子 View 消费了事件，也能检测到缩放
        scaleDetector.onTouchEvent(ev)
        if (isScaling) {
            // 如果正在缩放，取消子 View 的事件
            ev.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isScaling || isZoomed || ev.pointerCount > 1) {
            parent.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isScaling) return true

        if (isZoomed) {
            parent.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        panX += event.x - lastX
                        panY += event.y - lastY
                        clampPan()
                        applyTransform()
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }
            return true
        }
        return true
    }

    private fun clampPan() {
        val child = getChildAt(0) ?: return
        val cw = child.width.toFloat()
        val ch = child.height.toFloat()
        if (cw <= 0f || ch <= 0f) return
        
        // 当放大时，计算最大可平移距离
        val maxDx = ((cw * scaleFactor - width) / 2).coerceAtLeast(0f)
        val maxDy = ((ch * scaleFactor - height) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxDx, maxDx)
        panY = panY.coerceIn(-maxDy, maxDy)
    }

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        child.pivotX = width / 2f
        child.pivotY = height / 2f
        child.scaleX = scaleFactor
        child.scaleY = scaleFactor
        child.translationX = panX
        child.translationY = panY
    }
}
