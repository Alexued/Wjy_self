package com.example.aiassistant

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 自定义 FrameLayout，用于拦截边缘的触摸事件以优先处理缩放操作。
 * 解决子 View（如 ScrollView）消耗触摸事件导致边缘无法缩放的问题。
 */
class ResizeContainer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val EDGE_SIZE = (40 * resources.displayMetrics.density).toInt()

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val ex = event.x.toInt()
            val ey = event.y.toInt()
            val w = width
            val h = height

            val isLeft = ex < EDGE_SIZE
            val isRight = ex > w - EDGE_SIZE
            val isTop = ey < EDGE_SIZE
            val isBottom = ey > h - EDGE_SIZE

            // 如果触摸在四周边缘，则拦截事件（不传给子View），交由自己的 onTouch 处理
            if (isLeft || isRight || isTop || isBottom) {
                return true
            }
        }
        return super.onInterceptTouchEvent(event)
    }
}
