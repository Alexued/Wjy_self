package com.example.aiassistant

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 结果卡片管理 + JSON 渲染 + HTML 文本格式化（扩展函数）
 */

// ── 卡片生命周期 ──────────────────────────────────────────────────────

internal fun ScreenCaptureService.showResultCard() {
    removeResultCard()

    val displayMode = AppPreferences.getCardDisplayMode(this)
    val isDefaultFullscreen = AppPreferences.isDefaultFullscreen(this)

    var initialW: Int
    var initialH: Int
    var initialX: Int
    var initialY: Int
    var gravity = Gravity.TOP or Gravity.START
    var animate = false

    when {
        isDefaultFullscreen -> {
            initialW = screenWidth; initialH = screenHeight
            initialX = 0; initialY = 0
        }
        displayMode == AppPreferences.CARD_MODE_BOTTOM -> {
            // 底部弹出模式：全宽，屏幕75%高度，从底部弹出
            initialW = screenWidth
            initialH = (screenHeight * 0.75).toInt()
            initialX = 0
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            initialY = 0
            animate = true
        }
        displayMode == AppPreferences.CARD_MODE_ATTACHED -> {
            // 附着悬浮球模式：在悬浮球左侧弹出
            val ballParams = floatBallParams
            if (ballParams != null) {
                initialW = dpToPx(340)
                initialH = dpToPx(280)
                // 卡片在球的左侧，留 12dp 间距
                initialX = ballParams.x - initialW - dpToPx(12)
                // 卡片顶部对齐球的顶部
                initialY = ballParams.y
                // 如果左侧放不下，放到球的右侧
                if (initialX < 0) {
                    initialX = ballParams.x + ballParams.width + dpToPx(12)
                }
                // 如果右侧也放不下，居中显示
                if (initialX + initialW > screenWidth) {
                    initialX = (screenWidth - initialW) / 2
                }
                // 如果底部超出屏幕，向上调整
                if (initialY + initialH > screenHeight) {
                    initialY = screenHeight - initialH - dpToPx(16)
                }
            } else {
                initialW = dpToPx(340); initialH = dpToPx(280)
                initialX = (screenWidth - initialW) / 2
                initialY = screenHeight / 2
            }
        }
        AppPreferences.isCardBoundsSaved(this) -> {
            val bounds = AppPreferences.getCardBounds(this)
            initialX = bounds[0]; initialY = bounds[1]
            initialW = bounds[2]; initialH = bounds[3]
        }
        else -> {
            initialW = dpToPx(340); initialH = dpToPx(280)
            initialX = (screenWidth - initialW) / 2
            initialY = screenHeight / 2
        }
    }

    val params = WindowManager.LayoutParams(
        initialW, initialH,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        x = initialX
        y = initialY
    }

    resultCardView = LayoutInflater.from(this).inflate(R.layout.layout_float_result, null)

    // 底部弹出模式：去掉底部圆角，像从屏幕底端生长出来
    if (displayMode == AppPreferences.CARD_MODE_BOTTOM) {
        resultCardView?.findViewById<View>(R.id.float_result_card)?.setBackgroundResource(R.drawable.bg_float_card_bottom)
    }

    resultCardView?.findViewById<View>(R.id.btn_close_result)?.setOnClickListener {
        removeResultCard()
    }
    resultCardView?.findViewById<View>(R.id.btn_edit_card)?.setOnClickListener {
        toggleEditMode(resultCardView!!)
    }
    resultCardView?.findViewById<View>(R.id.btn_export_card)?.setOnClickListener {
        exportCardAsImage(resultCardView!!)
    }

    if (displayMode == AppPreferences.CARD_MODE_BOTTOM) {
        setupBottomDrawerBehavior(resultCardView!!, params)
    } else {
        setupResultCardInteractions(resultCardView!!, params)
    }

    resultCardParams = params
    windowManager.addView(resultCardView, params)

    // 底部弹出动画：高度从0增长到目标高度，像从底部生长出来
    if (animate) {
        val targetHeight = initialH
        params.height = 1
        try { windowManager.updateViewLayout(resultCardView, params) } catch (_: Exception) {}
        resultCardView?.animate()
            ?.setDuration(300)
            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
            ?.setUpdateListener { animation ->
                params.height = (targetHeight * animation.animatedFraction).toInt().coerceAtLeast(1)
                try { windowManager.updateViewLayout(resultCardView, params) } catch (_: Exception) {}
            }
            ?.start()
    }
}

internal fun ScreenCaptureService.setupResultCardInteractions(view: View, params: WindowManager.LayoutParams) {
    val service = this
    val header = view.findViewById<View>(R.id.layout_result_header)

    // 1. 拖拽移动逻辑 (点击 Header)
    header.setOnTouchListener(object : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!AppPreferences.isDefaultFullscreen(service)) {
                        AppPreferences.saveCardBounds(service, params.x, params.y, params.width, params.height)
                    }
                    return true
                }
            }
            return false
        }
    })

    // 2. 8向缩放逻辑 (作用于根布局边缘)
    view.setOnTouchListener(object : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialWidth = 0
        private var initialHeight = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var mode = 0

        private val EDGE_SIZE = dpToPx(40)

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val ex = event.x
                    val ey = event.y
                    val w = view.width
                    val h = view.height

                    val isLeft = ex < EDGE_SIZE
                    val isRight = ex > w - EDGE_SIZE
                    val isTop = ey < EDGE_SIZE
                    val isBottom = ey > h - EDGE_SIZE

                    mode = when {
                        isLeft && isTop -> 5
                        isRight && isTop -> 6
                        isLeft && isBottom -> 7
                        isRight && isBottom -> 8
                        isLeft -> 1
                        isTop -> 2
                        isRight -> 3
                        isBottom -> 4
                        else -> 0
                    }

                    if (mode != 0) {
                        initialX = params.x
                        initialY = params.y
                        initialWidth = params.width
                        initialHeight = params.height
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 0) return false
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    var newX = initialX
                    var newY = initialY
                    var newW = initialWidth
                    var newH = initialHeight

                    val minW = dpToPx(240)
                    val minH = dpToPx(200)

                    if (mode in listOf(1, 5, 7)) {
                        newW = (initialWidth - dx).coerceAtLeast(minW)
                        newX = initialX + (initialWidth - newW)
                    }
                    if (mode in listOf(3, 6, 8)) {
                        newW = (initialWidth + dx).coerceAtLeast(minW)
                    }
                    if (mode in listOf(2, 5, 6)) {
                        newH = (initialHeight - dy).coerceAtLeast(minH)
                        newY = initialY + (initialHeight - newH)
                    }
                    if (mode in listOf(4, 7, 8)) {
                        newH = (initialHeight + dy).coerceAtLeast(minH)
                    }

                    params.x = newX
                    params.y = newY
                    params.width = newW
                    params.height = newH
                    windowManager.updateViewLayout(view, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (mode != 0) {
                        if (!AppPreferences.isDefaultFullscreen(service)) {
                            AppPreferences.saveCardBounds(service, params.x, params.y, params.width, params.height)
                        }
                        mode = 0
                        return true
                    }
                }
            }
            return false
        }
    })
}

/**
 * 底部弹出模式的抽屉行为：上拉展开、下拉收起/关闭
 */
internal fun ScreenCaptureService.setupBottomDrawerBehavior(view: View, params: WindowManager.LayoutParams) {
    val header = view.findViewById<View>(R.id.layout_result_header)
    val initialHeight = params.height
    val expandedHeight = (screenHeight * 0.95).toInt()
    val dismissThreshold = (screenHeight * 0.35).toInt()  // 下拉超过屏幕35%则关闭
    var isExpanded = false

    header.setOnTouchListener(object : View.OnTouchListener {
        private var startY = 0f
        private var startHeight = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = params.height
                    dragging = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val dy = (startY - event.rawY).toInt() // 上滑为正

                    // 下拉收缩（最小50dp），上拉展开
                    val minH = dpToPx(50)
                    val newH = (startHeight + dy).coerceIn(minH, expandedHeight)
                    if (newH != params.height) {
                        params.height = newH
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    val dy = (startY - event.rawY).toInt()
                    val currentH = params.height
                    val closeHeight = (initialHeight * 0.5).toInt()

                    // 下拉超过阈值 或 卡片已缩小到一半以下 → 直接关闭
                    if (dy < -dismissThreshold || currentH < closeHeight) {
                        removeResultCard()
                        return true
                    }

                    // 根据拖拽方向决定展开/收起
                    val expandThreshold = (screenHeight * 0.08).toInt()
                    val targetH = if (dy > expandThreshold) expandedHeight else initialHeight
                    isExpanded = targetH == expandedHeight

                    val startH = params.height
                    view.animate()
                        .setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .setUpdateListener { animation ->
                            val fraction = animation.animatedFraction
                            params.height = startH + ((targetH - startH) * fraction).toInt()
                            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                        }
                        .start()
                    return true
                }
            }
            return false
        }
    })
}

internal fun ScreenCaptureService.removeResultCard() {
    isCapturing = false
    cancelCaptureTimeout()
    isEditMode = false
    clearDynamicState()
    resultCardView?.let {
        try { windowManager.removeView(it) } catch (_: Exception) {}
        resultCardView = null
    }
    if (AppPreferences.isSilentSearchEnabled(this)) {
        silentSearchText = null
        silentSearchReady = false
        reattachSmallBall()
    }
}

// ── 内容更新 ──────────────────────────────────────────────────────────

internal fun ScreenCaptureService.showLoading(text: String) {
    mainHandler.post {
        resultCardView?.let { card ->
            card.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
            card.findViewById<TextView>(R.id.tv_loading)?.text = text
            card.findViewById<View>(R.id.layout_content_wrapper)?.visibility = View.GONE
        }
    }
}

internal fun ScreenCaptureService.updateResultCard(text: String, isAiResponse: Boolean = false, onRenderFail: (() -> Unit)? = null) {
    mainHandler.post {
        resultCardView?.let { card ->
            card.findViewById<View>(R.id.layout_loading)?.visibility = View.GONE
            card.findViewById<View>(R.id.layout_content_wrapper)?.visibility = View.VISIBLE
            val tvResult = card.findViewById<TextView>(R.id.tv_result)

            if (isAiResponse) {
                val json = tryParseJsonResponse(text)
                if (json != null) {
                    android.util.Log.d("ResultCard", "JSON fields: ${json.keys().asSequence().toList()}")
                    android.util.Log.d("ResultCard", "lastBankMatch=${lastBankMatch?.id}, stem=${lastBankMatch?.stem?.take(30)}, answer=${lastBankMatch?.answer}, options=${lastBankMatch?.options?.size}")
                    clearDynamicSections(card)
                    try {
                        val type = detectSchemaType(json)
                        android.util.Log.d("ResultCard", "Schema detected: $type, teacher: ${TeacherManager.activeTeacher.id}")
                        when (TeacherManager.activeTeacher.id) {
                            "huasheng" -> renderHuasheng(card, json, type)
                            else -> renderHuasheng(card, json, type)
                        }
                        showBankMatchTag(card)
                        // 诊断：哪些区域可见
                        val qVis = card.findViewById<View>(R.id.layout_question)?.visibility == View.VISIBLE
                        val aVis = card.findViewById<View>(R.id.layout_answer)?.visibility == View.VISIBLE
                        val oVis = card.findViewById<View>(R.id.layout_options_container)?.visibility == View.VISIBLE
                        val tVis = card.findViewById<View>(R.id.layout_tags)?.visibility == View.VISIBLE
                        val tvVis = card.findViewById<TextView>(R.id.tv_result)?.visibility == View.VISIBLE
                        android.util.Log.d("ResultCard", "Render done: question=$qVis, answer=$aVis, options=$oVis, tags=$tVis, rawText=$tvVis")
                    } catch (e: Exception) {
                        android.util.Log.e("ResultCard", "Render failed for type", e)
                        if (onRenderFail != null) {
                            onRenderFail()
                        } else {
                            hideAllSections(card)
                            clearDynamicSections(card)
                            tvResult?.visibility = View.VISIBLE
                            tvResult?.text = formatSpannableText(cleanTextKeepSpan(text))
                        }
                    }
                } else {
                    android.util.Log.d("ResultCard", "JSON parse failed, showing raw text. text(200)=${text.take(200)}")
                    if (onRenderFail != null) {
                        onRenderFail()
                    } else {
                        hideAllSections(card)
                        clearDynamicSections(card)
                        tvResult?.visibility = View.VISIBLE
                        tvResult?.text = formatSpannableText(cleanTextKeepSpan(text))
                    }
                }
            } else {
                hideAllSections(card)
                clearDynamicSections(card)
                tvResult?.visibility = View.VISIBLE
                tvResult?.text = cleanHtmlText(text)
            }
        }
    }
}

/** 清除动态添加的类型专属 View（tv_result 之后的所有子 View） */
internal fun ScreenCaptureService.clearDynamicSections(card: View) {
    val contentLayout = card.findViewById<View>(R.id.zoomable_content)?.let {
        (it as? com.example.aiassistant.ZoomableLayout)?.getChildAt(0)
    } as? LinearLayout ?: return
    val tvResult = card.findViewById<View>(R.id.tv_result) ?: return
    val resultIdx = contentLayout.indexOfChild(tvResult)
    if (resultIdx >= 0) {
        while (contentLayout.childCount > resultIdx + 1) {
            contentLayout.removeViewAt(resultIdx + 1)
        }
    }
}

/** 在结果卡片顶部显示题库命中标签 */
internal fun ScreenCaptureService.showBankMatchTag(card: View) {
    val match = lastBankMatch ?: return
    val d = resources.displayMetrics.density
    val dp6 = (6 * d).toInt()
    val dp3 = (3 * d).toInt()
    val dp4 = (4 * d).toInt()

    val layoutTags = card.findViewById<LinearLayout>(R.id.layout_tags) ?: return
    layoutTags.visibility = View.VISIBLE

    // 题库命中标签（绿色）
    val bankTag = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp4 }
        text = "📚 题库命中"
        textSize = 10.5f
        setTextColor(0xFF10B981.toInt())
        setTypeface(null, Typeface.BOLD)
        setBackgroundResource(R.drawable.bg_tag_green)
        setPadding(dp6, dp3, dp6, dp3)
    }

    // 答案标签（蓝色）
    val answerTag = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp4 }
        text = "✓ 答案 ${match.answer}"
        textSize = 10.5f
        setTextColor(0xFF3B82F6.toInt())
        setTypeface(null, Typeface.BOLD)
        setBackgroundResource(R.drawable.bg_tag_blue)
        setPadding(dp6, dp3, dp6, dp3)
    }

    // 插入到标签行最前面
    layoutTags.addView(bankTag, 0)
    layoutTags.addView(answerTag, 1)
}

internal fun ScreenCaptureService.hideAllSections(card: View) {
    card.findViewById<View>(R.id.layout_tags)?.visibility = View.GONE
    card.findViewById<View>(R.id.layout_question)?.visibility = View.GONE
    card.findViewById<View>(R.id.layout_answer)?.visibility = View.GONE
    card.findViewById<View>(R.id.tv_options_title)?.visibility = View.GONE
    card.findViewById<View>(R.id.layout_options_container)?.visibility = View.GONE
    card.findViewById<View>(R.id.tv_logical_title)?.visibility = View.GONE
    card.findViewById<View>(R.id.layout_logical_labels)?.visibility = View.GONE
}

// ── JSON 解析与渲染 ───────────────────────────────────────────────────

internal fun ScreenCaptureService.tryParseJsonResponse(text: String): JSONObject? {
    return try {
        var cleaned = cleanTextKeepSpan(text)

        // 更稳健的 Markdown 代码块剥离
        val fencePattern = Regex("```[a-zA-Z]*\\s*")
        cleaned = cleaned.replace(fencePattern, "")
        cleaned = cleaned.replace("```", "")

        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1)
        }

        val json = JSONObject(cleaned)
        // 放宽条件：有任何已知字段就认为是有效 JSON
        val hasKnownField = json.has("question") || json.has("correct_answer") ||
            json.has("question_type") || json.has("options_analysis") ||
            json.has("blanks") || json.has("visual_analysis") ||
            json.has("structure_analysis") || json.has("key_elements") ||
            json.has("word_pair") || json.has("logical_chain") ||
            json.has("analysis") || json.has("passage_type") ||
            json.has("explanation") || json.has("correct_option")
        if (hasKnownField) json else null
    } catch (e: Exception) {
        android.util.Log.e("ResultCard", "JSON parse error: ${e.message}")
        null
    }
}

internal fun ScreenCaptureService.renderJsonResult(card: View, json: JSONObject) {
    clearDynamicSections(card)
    val type = detectSchemaType(json)
    when (TeacherManager.activeTeacher.id) {
        "huasheng" -> renderHuasheng(card, json, type)
        else -> renderHuasheng(card, json, type)
    }
}

/** 保留兼容旧调用：内部使用的工具方法 */
internal fun ScreenCaptureService.createTagCompat(text: String, colorHex: String, bgRes: Int): TextView =
    createTag(text, colorHex, bgRes, resources.displayMetrics.density)


internal fun ScreenCaptureService.createTag(text: String, colorHex: String, bgRes: Int, d: Float): TextView {
    val dp6 = (6*d).toInt(); val dp3 = (3*d).toInt()
    return TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        this.text = text; textSize = 10.5f; setTextColor(android.graphics.Color.parseColor(colorHex))
        setTypeface(null, android.graphics.Typeface.BOLD); setBackgroundResource(bgRes); setPadding(dp6,dp3,dp6,dp3)
    }
}

// ── HTML 文本格式化 ───────────────────────────────────────────────────

internal fun ScreenCaptureService.stripHtmlSpan(text: String): String {
    return text.replace(Regex("<span[^>]*>"), "").replace("</span>", "")
}

internal fun ScreenCaptureService.cleanHtmlText(text: String): String {
    return text
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?div[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

/** 清理 HTML 但保留 <span> 标签（用于 formatSpannableText 红色标注） */
internal fun ScreenCaptureService.cleanTextKeepSpan(text: String): String {
    return text
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?div[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<(?!/?span\\b)[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

internal fun ScreenCaptureService.formatSpannableText(text: String): CharSequence {
    val builder = android.text.SpannableStringBuilder()
    var i = 0
    val spanOpen = "<span style='color:red'>"
    val spanClose = "</span>"
    while (i < text.length) {
        val tildeIdx = text.indexOf("~~", i)
        val spanIdx = text.indexOf(spanOpen, i)
        val nextTilde = if (tildeIdx != -1) tildeIdx else Int.MAX_VALUE
        val nextSpan = if (spanIdx != -1) spanIdx else Int.MAX_VALUE

        if (nextTilde == Int.MAX_VALUE && nextSpan == Int.MAX_VALUE) {
            builder.append(text.substring(i)); break
        }

        if (nextTilde <= nextSpan) {
            builder.append(text.substring(i, tildeIdx))
            val endIdx = text.indexOf("~~", tildeIdx + 2)
            if (endIdx != -1) {
                val hl = text.substring(tildeIdx + 2, endIdx)
                val s = builder.length; builder.append(hl)
                builder.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#2563EB")), s, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = endIdx + 2
            } else { builder.append(text.substring(tildeIdx)); break }
        } else {
            builder.append(text.substring(i, spanIdx))
            val closeIdx = text.indexOf(spanClose, spanIdx)
            if (closeIdx != -1) {
                val inner = text.substring(spanIdx + spanOpen.length, closeIdx)
                val lastChar = if (builder.isNotEmpty()) builder[builder.length - 1] else ' '
                val hasLeftBracket = lastChar == '(' || lastChar == '（'
                val afterSpan = closeIdx + spanClose.length
                val nextChar = if (afterSpan < text.length) text[afterSpan] else ' '
                val hasRightBracket = nextChar == ')' || nextChar == '）'

                if (hasLeftBracket) {
                    val s = builder.length - 1
                    builder.append(inner)
                    if (hasRightBracket) { builder.append(nextChar); i = afterSpan + 1 } else { builder.append("）"); i = afterSpan }
                    builder.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#DC2626")), s, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    val s = builder.length; builder.append("（$inner")
                    if (hasRightBracket) { builder.append(nextChar); i = afterSpan + 1 } else { builder.append("）"); i = afterSpan }
                    builder.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#DC2626")), s, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else { builder.append(text.substring(spanIdx)); break }
        }
    }
    return builder
}

// ── 编辑模式 ────────────────────────────────────────────────────────
private var isEditMode = false
internal var resultCardParams: WindowManager.LayoutParams? = null

internal fun ScreenCaptureService.toggleEditMode(card: View) {
    isEditMode = !isEditMode
    val btnEdit = card.findViewById<TextView>(R.id.btn_edit_card)
    btnEdit?.text = if (isEditMode) "✓" else "✎"
    btnEdit?.setTextColor(if (isEditMode) 0xFF10B981.toInt() else 0xFF3B82F6.toInt())

    val params = resultCardParams ?: return
    if (isEditMode) {
        // 去掉 FLAG_ALT_FOCUSABLE_IM 以允许键盘弹出
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
    } else {
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    }
    try { windowManager.updateViewLayout(card, params) } catch (_: Exception) {}

    swapTextEditable(card.findViewById(R.id.scroll_result), isEditMode)
}

private fun swapTextEditable(parent: View?, editable: Boolean) {
    if (parent == null) return
    if (parent is android.view.ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is TextView && child.id != R.id.btn_edit_card
                && child.id != R.id.btn_export_card && child.id != R.id.btn_close_result
                && child.id != R.id.tv_loading && child.tag != "no_edit") {
                if (editable && child !is android.widget.EditText) {
                    val editText = android.widget.EditText(child.context).apply {
                        setText(child.text)
                        textSize = child.textSize / child.context.resources.displayMetrics.scaledDensity
                        setTextColor(child.currentTextColor)
                        setTypeface(child.typeface)
                        setLineSpacing(child.lineSpacingExtra, child.lineSpacingMultiplier)
                        setPadding(child.paddingLeft, child.paddingTop, child.paddingRight, child.paddingBottom)
                        layoutParams = child.layoutParams
                        id = child.id
                        tag = "edit_swap" // 标记以便换回
                        background = android.graphics.drawable.ColorDrawable(0x20FFFFFF)
                        isFocusable = true; isFocusableInTouchMode = true
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    val vg = child.parent as? android.view.ViewGroup ?: continue
                    val idx = vg.indexOfChild(child)
                    vg.removeView(child)
                    vg.addView(editText, idx)
                } else if (!editable && child is android.widget.EditText && child.tag == "edit_swap") {
                    val textView = TextView(child.context).apply {
                        text = child.text
                        textSize = child.textSize / child.context.resources.displayMetrics.scaledDensity
                        setTextColor(child.currentTextColor)
                        setTypeface(child.typeface)
                        setLineSpacing(child.lineSpacingExtra, child.lineSpacingMultiplier)
                        setPadding(child.paddingLeft, child.paddingTop, child.paddingRight, child.paddingBottom)
                        layoutParams = child.layoutParams
                        id = child.id
                        setTextIsSelectable(true)
                    }
                    val vg = child.parent as? android.view.ViewGroup ?: continue
                    val idx = vg.indexOfChild(child)
                    vg.removeView(child)
                    vg.addView(textView, idx)
                }
            } else {
                swapTextEditable(child, editable)
            }
        }
    }
}

// ── 导出图片 ────────────────────────────────────────────────────────

internal fun ScreenCaptureService.exportCardAsImage(card: View) {
    try {
        val content = card.findViewById<View>(R.id.layout_content_wrapper) ?: return
        // 先关闭编辑模式
        if (isEditMode) toggleEditMode(card)

        val bitmap = android.graphics.Bitmap.createBitmap(
            content.width, content.height,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        content.draw(canvas)

        // 保存到缓存目录
        val file = java.io.File(cacheDir, "ai_answer_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()

        // 通过系统分享
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(shareIntent, "导出答案卡片")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
    } catch (e: Exception) {
        android.util.Log.e("ResultCard", "Export failed", e)
        android.widget.Toast.makeText(this, "导出失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

