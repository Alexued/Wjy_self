package com.example.aiassistant

import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮球管理：主球 + 小悬浮球（静默搜题）+ 进度文字
 */

// ── 主悬浮球 ──────────────────────────────────────────────────────────

internal fun ScreenCaptureService.showFloatBall() {
    if (floatBallView != null) return

    // 悬浮球尺寸 60dp
    val ballSize = dpToPx(60)

    var flagsVal = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (AppPreferences.isKeepScreenOnEnabled(this)) {
        flagsVal = flagsVal or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    }

    floatBallParams = WindowManager.LayoutParams(
        ballSize, ballSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flagsVal,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 右下角位置：右侧 1/8，下方 3/4
        x = screenWidth - ballSize - dpToPx(16)
        y = screenHeight * 3 / 4 - ballSize / 2
    }

    floatBallView = LayoutInflater.from(this).inflate(R.layout.layout_float_ball, null)
    setupFloatBallTouch(floatBallView!!)
    try {
        windowManager.addView(floatBallView, floatBallParams)
    } catch (e: Exception) {
        Log.e(ScreenCaptureService.TAG, "showFloatBall addView failed", e)
        floatBallView = null
        Toast.makeText(this, "悬浮窗创建失败，请确认已授予悬浮窗权限", Toast.LENGTH_LONG).show()
        stopSelf()
    }
}

/** 动态更新悬浮窗常亮状态 */
internal fun ScreenCaptureService.updateKeepScreenOnState() {
    val view = floatBallView ?: return
    val params = floatBallParams ?: return
    var flagsVal = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (AppPreferences.isKeepScreenOnEnabled(this)) {
        flagsVal = flagsVal or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    }
    params.flags = flagsVal
    try {
        windowManager.updateViewLayout(view, params)
    } catch (e: Exception) {
        Log.e(ScreenCaptureService.TAG, "updateKeepScreenOnState failed", e)
    }
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
    var isLongPress = false

    val longPressRunnable = Runnable {
        if (!isDrag) {
            isLongPress = true
            if (AppPreferences.getLongPressAction(this@setupFloatBallTouch) == AppPreferences.LONG_PRESS_CLOSE) {
                Toast.makeText(this@setupFloatBallTouch, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
                AppPreferences.setFloatEnabled(this@setupFloatBallTouch, false)
                stopSelf()
            } else {
                showBallMenu()
            }
        }
    }

    // 按压缩放动画
    fun animateScale(scale: Float) {
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(100)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
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
                isLongPress = false
                mainHandler.postDelayed(longPressRunnable, 800)
                // 按下时缩小
                animateScale(0.92f)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (!isDrag && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    isDrag = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    // 开始拖动时恢复大小
                    animateScale(1.0f)
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                // 恢复大小
                animateScale(1.0f)
                if (event.action == MotionEvent.ACTION_UP && !isDrag && !isLongPress) {
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

// ── 小悬浮球（静默搜题） ──────────────────────────────────────────────

internal fun ScreenCaptureService.showSmallBall() {
    if (smallBallView != null) return
    val mainBallSize = dpToPx(60)
    val smallBallSize = dpToPx(36)

    smallBallParams = WindowManager.LayoutParams(
        smallBallSize, smallBallSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenWidth - mainBallSize - smallBallSize - dpToPx(12)
        y = screenHeight * 3 / 4 - mainBallSize / 2 + (mainBallSize - smallBallSize) / 2
    }

    smallBallView = LayoutInflater.from(this).inflate(R.layout.layout_small_ball, null)
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
    smallParams.x = mainParams.x - smallBallSize - dpToPx(6)
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
    val cachedText = silentSearchText ?: return
    if (silentSearchReady) {
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
        floatBallView?.let { root ->
            val ivBall = root.findViewById<ImageView>(R.id.iv_ball)
            val tvProgress = root.findViewById<TextView>(R.id.tv_ball_progress)
            ivBall?.setImageDrawable(null)
            tvProgress?.let {
                it.text = text
                it.visibility = View.VISIBLE
            }
        }
    }
}

internal fun ScreenCaptureService.hideBallProgress() {
    mainHandler.post {
        floatBallView?.let { root ->
            val ivBall = root.findViewById<ImageView>(R.id.iv_ball)
            val tvProgress = root.findViewById<TextView>(R.id.tv_ball_progress)
            ivBall?.setImageResource(R.drawable.ic_visual_search)
            tvProgress?.visibility = View.GONE
        }
    }
}

// ── 悬浮球长按菜单 ──────────────────────────────────────────────────

internal fun ScreenCaptureService.showBallMenu() {
    dismissBallMenu()

    val ballParams = floatBallParams ?: return

    val menuView = LayoutInflater.from(this).inflate(R.layout.layout_ball_menu, null)

    // 根据用户配置的菜单尺寸调整大小
    applyBallMenuSize(menuView)

    // 先以临时参数添加到窗口，测量实际宽度
    val tempParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = -9999 // 先放到屏幕外
        y = ballParams.y
    }
    windowManager.addView(menuView, tempParams)

    // 测量菜单宽度
    menuView.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val menuWidth = menuView.measuredWidth

    // 更新位置：悬浮球左侧
    tempParams.x = ballParams.x - menuWidth - dpToPx(8)
    if (tempParams.x < 0) tempParams.x = ballParams.x + ballParams.width + dpToPx(8)
    try { windowManager.updateViewLayout(menuView, tempParams) } catch (_: Exception) {}

    // 按用户配置显示/隐藏菜单项
    val enabledItems = AppPreferences.getBallMenuItems(this)

    val dictItem = menuView.findViewById<View>(R.id.menu_dict)
    val captureItem = menuView.findViewById<View>(R.id.menu_capture_mode)
    val clickActionItem = menuView.findViewById<View>(R.id.menu_click_action)
    val closeItem = menuView.findViewById<View>(R.id.menu_close_ball)
    
    val divider1 = menuView.findViewById<View>(R.id.menu_divider_1)
    val divider2 = menuView.findViewById<View>(R.id.menu_divider_2)
    val divider3 = menuView.findViewById<View>(R.id.menu_divider_3)

    dictItem.visibility = if (enabledItems.contains("dict")) View.VISIBLE else View.GONE
    captureItem.visibility = if (enabledItems.contains("capture_mode")) View.VISIBLE else View.GONE
    clickActionItem.visibility = if (enabledItems.contains("click_action")) View.VISIBLE else View.GONE
    closeItem.visibility = if (enabledItems.contains("close")) View.VISIBLE else View.GONE

    // 智能连贯的分隔符控制
    divider1.visibility = if (dictItem.visibility == View.VISIBLE && (captureItem.visibility == View.VISIBLE || clickActionItem.visibility == View.VISIBLE || closeItem.visibility == View.VISIBLE)) View.VISIBLE else View.GONE
    divider2.visibility = if (captureItem.visibility == View.VISIBLE && (clickActionItem.visibility == View.VISIBLE || closeItem.visibility == View.VISIBLE)) View.VISIBLE else View.GONE
    divider3.visibility = if (clickActionItem.visibility == View.VISIBLE && closeItem.visibility == View.VISIBLE) View.VISIBLE else View.GONE

    // 获取新增的名师菜单项
    val teacherItem = menuView.findViewById<TextView>(R.id.menu_switch_teacher)

    // 显示并更新内容
    teacherItem?.let {
        it.visibility = View.VISIBLE
        it.text = "请益 · ${TeacherManager.activeTeacher.name}"
        it.setOnClickListener {
            dismissBallMenu()
            showTeacherSelectDialog()
        }
    }

    dictItem.setOnClickListener {
        dismissBallMenu()
        ScreenCaptureService.isDictOcrMode = true
        onFloatBallClicked()
    }

    captureItem.setOnClickListener {
        dismissBallMenu()
        val currentMode = AppPreferences.getCaptureMode(this)
        val newMode = if (currentMode == AppPreferences.MODE_FIXED_AREA) AppPreferences.MODE_CUSTOM_AREA else AppPreferences.MODE_FIXED_AREA
        AppPreferences.setCaptureMode(this, newMode)
        val modeName = if (newMode == AppPreferences.MODE_FIXED_AREA) "固定区域" else "自定义区域"
        Toast.makeText(this, "已切换为：$modeName 截图", Toast.LENGTH_SHORT).show()
    }

    clickActionItem.setOnClickListener {
        dismissBallMenu()
        val currentAction = AppPreferences.getFloatClickAction(this)
        val newAction = if (currentAction == AppPreferences.CLICK_ACTION_RECORD_WRONG) AppPreferences.CLICK_ACTION_AI_ANALYZE else AppPreferences.CLICK_ACTION_RECORD_WRONG
        AppPreferences.setFloatClickAction(this, newAction)
        val actionName = if (newAction == AppPreferences.CLICK_ACTION_RECORD_WRONG) "仅记录错题" else "AI 智能分析"
        Toast.makeText(this, "单击动作已切换为：$actionName", Toast.LENGTH_SHORT).show()
    }

    closeItem.setOnClickListener {
        dismissBallMenu()
        Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
        AppPreferences.setFloatEnabled(this, false)
        stopSelf()
    }

    ballMenuView = menuView

    // 点击菜单外部关闭
    menuView.setOnTouchListener { _, event ->
        if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
            dismissBallMenu()
        }
        false
    }
}

internal fun ScreenCaptureService.dismissBallMenu() {
    ballMenuView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
        ballMenuView = null
    }
}

/** 根据用户配置的菜单尺寸调整菜单项大小 */
private fun ScreenCaptureService.applyBallMenuSize(menuView: View) {
    val itemHeightDp = AppPreferences.getBallMenuSizeDp(this)
    // 按比例缩放其他尺寸
    val scale = itemHeightDp / 36f
    val textSize = (12.5f * scale).coerceIn(10f, 16f)
    val paddingH = (12 * scale).toInt().coerceIn(6, 20)
    val paddingV = (2 * scale).toInt().coerceIn(1, 6)
    val iconPadding = (8 * scale).toInt().coerceIn(4, 14)
    val headerSize = (9f * scale).coerceIn(7f, 12f)

    val itemIds = listOf(R.id.menu_dict, R.id.menu_capture_mode, R.id.menu_click_action, R.id.menu_switch_teacher, R.id.menu_close_ball)
    for (id in itemIds) {
        menuView.findViewById<TextView>(id)?.let { tv ->
            tv.layoutParams.height = dpToPx(itemHeightDp)
            tv.textSize = textSize
            tv.setPadding(dpToPx(paddingH), dpToPx(paddingV), dpToPx(paddingH), dpToPx(paddingV))
            tv.compoundDrawablePadding = dpToPx(iconPadding)
        }
    }
    (menuView as? android.view.ViewGroup)?.getChildAt(0)?.let { header ->
        (header as? TextView)?.textSize = headerSize
    }
}

// ── 词典浮窗（可拖拽、可缩放、记住大小） ────────────────────────────

internal fun ScreenCaptureService.showDictionaryOverlay() {
    dismissDictionaryOverlay()

    // 读取保存的尺寸，默认 340x480dp
    val savedW = AppPreferences.getDictCardWidth(this)
    val savedH = AppPreferences.getDictCardHeight(this)
    val cardW = if (savedW > 0) savedW else dpToPx(340)
    val cardH = if (savedH > 0) savedH else dpToPx(480)

    // 定位到悬浮球左下方
    val ballP = floatBallParams
    var posX: Int
    var posY: Int
    if (ballP != null) {
        posX = ballP.x - cardW - dpToPx(8)
        posY = ballP.y + ballP.height + dpToPx(8)
        if (posX < 0) posX = ballP.x + ballP.width + dpToPx(8)
        if (posY + cardH > screenHeight) posY = screenHeight - cardH - dpToPx(16)
    } else {
        posX = dpToPx(16)
        posY = screenHeight / 4
    }

    val params = WindowManager.LayoutParams(
        cardW, cardH,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = posX
        y = posY
    }

    val overlayView = LayoutInflater.from(this).inflate(R.layout.layout_dict_overlay, null)

    val etSearch = overlayView.findViewById<EditText>(R.id.et_dict_search)
    val rvResults = overlayView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_dict_results)
    val tvEmpty = overlayView.findViewById<TextView>(R.id.tv_dict_empty)
    val btnClose = overlayView.findViewById<View>(R.id.btn_dict_close)
    val header = overlayView.findViewById<View>(R.id.dict_header)
    val resizeHandle = overlayView.findViewById<View>(R.id.dict_resize_handle)

    rvResults.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

    // ── 拖拽移动（按住 header） ──
    header.setOnTouchListener(object : View.OnTouchListener {
        private var initX = 0; private var initY = 0
        private var touchX = 0f; private var touchY = 0f
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initX + event.rawX - touchX).toInt()
                    params.y = (initY + event.rawY - touchY).toInt()
                    try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    return true
                }
            }
            return false
        }
    })

    // ── 缩放（按住底部拖拽条） ──
    resizeHandle.setOnTouchListener(object : View.OnTouchListener {
        private var initW = 0; private var initH = 0
        private var touchY = 0f
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initW = params.width; initH = params.height
                    touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newH = (initH + event.rawY - touchY).toInt().coerceIn(dpToPx(200), screenHeight - params.y - dpToPx(16))
                    params.height = newH
                    try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    AppPreferences.saveDictCardSize(this@showDictionaryOverlay, params.width, params.height)
                    return true
                }
            }
            return false
        }
    })

    // ── 关闭按钮 ──
    btnClose.setOnClickListener { dismissDictionaryOverlay() }

    // ── 点击半透明背景关闭 ──
    overlayView.setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_UP) {
            val card = overlayView.findViewById<View>(R.id.dict_card)
            if (card != null) {
                val loc = IntArray(2)
                card.getLocationOnScreen(loc)
                if (event.rawX < loc[0] || event.rawX > loc[0] + card.width ||
                    event.rawY < loc[1] || event.rawY > loc[1] + card.height) {
                    dismissDictionaryOverlay()
                    return@setOnTouchListener true
                }
            }
        }
        false
    }

    windowManager.addView(overlayView, params)
    dictOverlayView = overlayView

    // ── 切换为可聚焦以弹出键盘 ──
    etSearch.requestFocus()
    etSearch.postDelayed({
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }, 200)

    // ── 防抖搜索 ──
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var searchRunnable: Runnable? = null

    etSearch.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            searchRunnable?.let { handler.removeCallbacks(it) }
            searchRunnable = Runnable {
                val q = s?.toString()?.trim() ?: ""
                if (q.isBlank()) {
                    tvEmpty.text = "输入内容开始查询"
                    tvEmpty.visibility = View.VISIBLE
                    rvResults.visibility = View.GONE
                    return@Runnable
                }
                com.example.aiassistant.dictionary.DictionaryManager.searchAsync(q) { result ->
                    if (result.isEmpty) {
                        tvEmpty.text = "未找到结果"
                        tvEmpty.visibility = View.VISIBLE
                        rvResults.visibility = View.GONE
                    } else {
                        tvEmpty.visibility = View.GONE
                        rvResults.visibility = View.VISIBLE
                        rvResults.adapter = com.example.aiassistant.dictionary.DictionaryAdapter(result)
                    }
                }
            }
            handler.postDelayed(searchRunnable!!, 150)
        }
    })

    // ── 读取剪贴板 ──
    val clipText = try {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
    } catch (_: SecurityException) { null }

    if (!clipText.isNullOrBlank()) {
        etSearch.setText(clipText)
    }
}

internal fun ScreenCaptureService.dismissDictionaryOverlay() {
    dictOverlayView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
        dictOverlayView = null
    }
}



private fun ScreenCaptureService.showTeacherSelectDialog() {
    val teachers = TeacherManager.allTeachers
    val items = teachers.map { it.name }.toTypedArray()
    val activeTeacher = TeacherManager.activeTeacher
    val checkedItem = teachers.indexOfFirst { it.id == activeTeacher.id }.coerceAtLeast(0)

    val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        .setTitle("选择当前分析名师")
        .setSingleChoiceItems(items, checkedItem) { dialog, which ->
            val selectedTeacher = teachers[which]
            TeacherManager.switchTeacher(this, selectedTeacher.id)
            Toast.makeText(this, "当前分析名师已切换为：${selectedTeacher.name}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        .setNegativeButton("取消", null)

    val dialog = builder.create()
    if (android.os.Build.VERSION.SDK_INT >= 26) {
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    } else {
        @Suppress("DEPRECATION")
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
    }
    dialog.show()
}
