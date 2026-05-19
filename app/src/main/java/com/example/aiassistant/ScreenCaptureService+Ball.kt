package com.example.aiassistant

import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮球管理：主球 + 小悬浮球（静默搜题）+ 进度文字
 */

// ── 主悬浮球 ──────────────────────────────────────────────────────────

internal fun ScreenCaptureService.showFloatBall() {
    if (floatBallView != null) return

    val sizeDp = AppPreferences.getFloatBallSize(this)
    val ballSize = dpToPx(sizeDp)
    floatBallParams = WindowManager.LayoutParams(
        ballSize, ballSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenWidth - ballSize - dpToPx(16)
        y = screenHeight / 3
    }

    floatBallView = LayoutInflater.from(this).inflate(R.layout.layout_float_ball, null)
    setupFloatBallTouch(floatBallView!!)
    windowManager.addView(floatBallView, floatBallParams)
}

/** 永久移除悬浮球（服务销毁时调用，同时置空引用） */
internal fun ScreenCaptureService.removeFloatBall() {
    floatBallView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
        floatBallView = null
    }
}

/** 临时隐藏悬浮球（截图时避免球出现在画面中，保留引用以便重新挂载） */
internal fun ScreenCaptureService.detachFloatBall() {
    floatBallView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
    }
}

internal fun ScreenCaptureService.reattachFloatBall() {
    floatBallView?.let { view ->
        try { windowManager.addView(view, floatBallParams) } catch (e: Exception) {
            Log.w(ScreenCaptureService.TAG, "reattachFloatBall failed", e)
        }
    }
}

internal fun ScreenCaptureService.setupFloatBallTouch(view: View) {
    val touchSlop = dpToPx(10).toFloat()
    var initialX = 0; var initialY = 0
    var touchStartX = 0f; var touchStartY = 0f
    var isDrag = false

    val longPressRunnable = Runnable {
        if (!isDrag) {
            Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
            AppPreferences.setFloatEnabled(this, false)
            stopSelf()
        }
    }

    view.setOnTouchListener { _, event ->
        val params = floatBallParams ?: return@setOnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDrag = false
                mainHandler.postDelayed(longPressRunnable, 800)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (!isDrag && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    isDrag = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (isDrag) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(view, params)
                    updateSmallBallPosition()
                    // 附着模式：同步移动结果卡片
                    updateAttachedCardPosition()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (!isDrag) {
                    onFloatBallClicked()
                }
                true
            }
            else -> false
        }
    }
}

/** 附着模式：悬浮球拖动时同步移动结果卡片 */
internal fun ScreenCaptureService.updateAttachedCardPosition() {
    if (AppPreferences.getCardDisplayMode(this) != AppPreferences.CARD_MODE_ATTACHED) return
    val cardView = resultCardView ?: return
    val cardP = resultCardParams ?: return
    val ballP = floatBallParams ?: return
    // 卡片在球的左侧，留 12dp 间距
    var newX = ballP.x - cardP.width - dpToPx(12)
    // 如果左侧放不下，放到球的右侧
    if (newX < 0) newX = ballP.x + ballP.width + dpToPx(12)
    // 如果右侧也放不下，居中
    if (newX + cardP.width > screenWidth) newX = (screenWidth - cardP.width) / 2
    cardP.x = newX
    cardP.y = ballP.y
    // 如果底部超出屏幕，向上调整
    if (cardP.y + cardP.height > screenHeight) cardP.y = screenHeight - cardP.height - dpToPx(16)
    try { windowManager.updateViewLayout(cardView, cardP) } catch (_: Exception) {}
}

internal fun ScreenCaptureService.updateFloatBallSize() {
    val view = floatBallView ?: return
    val params = floatBallParams ?: return
    val sizeDp = AppPreferences.getFloatBallSize(this)
    val ballSize = dpToPx(sizeDp)

    params.width = ballSize
    params.height = ballSize
    try {
        windowManager.updateViewLayout(view, params)
    } catch (e: Exception) {
        Log.e(ScreenCaptureService.TAG, "Update ball size failed", e)
    }
    if (smallBallView != null) {
        removeSmallBall()
        showSmallBall()
    }
}

// ── 小悬浮球（静默搜题） ──────────────────────────────────────────────

internal fun ScreenCaptureService.showSmallBall() {
    if (smallBallView != null) return
    val mainSizeDp = AppPreferences.getFloatBallSize(this)
    val mainBallSize = dpToPx(mainSizeDp)
    val smallBallSize = mainBallSize / 2

    smallBallParams = WindowManager.LayoutParams(
        smallBallSize, smallBallSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenWidth - mainBallSize - smallBallSize - dpToPx(24)
        y = screenHeight / 3 + (mainBallSize - smallBallSize) / 2
    }

    smallBallView = LayoutInflater.from(this).inflate(R.layout.layout_float_ball, null)
    smallBallView?.setBackgroundResource(R.drawable.bg_small_float_ball)
    setupSmallBallTouch(smallBallView!!)
    windowManager.addView(smallBallView, smallBallParams)
}

internal fun ScreenCaptureService.removeSmallBall() {
    smallBallView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
        smallBallView = null
    }
}

internal fun ScreenCaptureService.detachSmallBall() {
    smallBallView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
    }
}

internal fun ScreenCaptureService.reattachSmallBall() {
    smallBallView?.let { view ->
        try { windowManager.addView(view, smallBallParams) } catch (e: Exception) {
            Log.w(ScreenCaptureService.TAG, "reattachSmallBall failed", e)
        }
    }
}

internal fun ScreenCaptureService.updateSmallBallPosition() {
    val mainParams = floatBallParams ?: return
    val smallView = smallBallView ?: return
    val smallParams = smallBallParams ?: return
    val mainBallSize = mainParams.width
    val smallBallSize = smallParams.width
    smallParams.x = mainParams.x - smallBallSize - dpToPx(8)
    smallParams.y = mainParams.y + (mainBallSize - smallBallSize) / 2
    try {
        windowManager.updateViewLayout(smallView, smallParams)
    } catch (_: Exception) {}
}

internal fun ScreenCaptureService.updateSmallBallVisibility() {
    if (AppPreferences.isSilentSearchEnabled(this)) {
        showSmallBall()
    } else {
        removeSmallBall()
    }
}

internal fun ScreenCaptureService.setupSmallBallTouch(view: View) {
    val touchSlop = dpToPx(10).toFloat()
    var initialX = 0; var initialY = 0
    var touchStartX = 0f; var touchStartY = 0f
    var isDrag = false

    view.setOnTouchListener { _, event ->
        val params = smallBallParams ?: return@setOnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDrag = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (!isDrag && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    isDrag = true
                }
                if (isDrag) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(view, params)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDrag) {
                    onSmallBallClicked()
                }
                true
            }
            else -> false
        }
    }
}

internal fun ScreenCaptureService.onSmallBallClicked() {
    if (silentSearchReady && silentSearchText != null) {
        val cachedText = silentSearchText!!
        silentSearchReady = false
        silentSearchText = null
        detachSmallBall()
        isSilentCapture = false
        showResultCard()
        updateResultCard(cachedText, isAiResponse = true)
        return
    }

    if (isCapturing || isSilentCapture) return

    retryCount.set(0)
    isSilentCapture = true
    isCapturing = true
    scheduleCaptureTimeout()
    detachSmallBall()

    val mode = AppPreferences.getCaptureMode(this)
    when (mode) {
        AppPreferences.MODE_FIXED_AREA -> {
            if (AppPreferences.isFixedRegionSet(this)) {
                captureAndCrop(AppPreferences.getFixedRegion(this))
            } else {
                captureAndShowSelector(saveAsFixed = true)
            }
        }
        else -> captureAndShowSelector(saveAsFixed = false)
    }
}

// ── 大球进度文字（静默搜题多轮推理时显示） ────────────────────────────

internal fun ScreenCaptureService.showBallProgress(text: String) {
    mainHandler.post {
        floatBallView?.findViewById<ImageView>(R.id.iv_ball)?.visibility = View.GONE
        floatBallView?.findViewById<TextView>(R.id.tv_ball_progress)?.let {
            it.text = text
            it.visibility = View.VISIBLE
        }
    }
}

internal fun ScreenCaptureService.hideBallProgress() {
    mainHandler.post {
        floatBallView?.findViewById<ImageView>(R.id.iv_ball)?.visibility = View.VISIBLE
        floatBallView?.findViewById<TextView>(R.id.tv_ball_progress)?.visibility = View.GONE
    }
}
