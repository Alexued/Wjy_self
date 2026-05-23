package com.example.aiassistant

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** 安全取字符串，null 值返回 fallback 而非 "null" */
private fun jsonStr(json: JSONObject, key: String, fallback: String = ""): String {
    return if (json.isNull(key)) fallback else json.optString(key, fallback)
}

private fun arrStr(arr: JSONArray, index: Int, fallback: String = ""): String {
    return if (arr.isNull(index)) fallback else arr.optString(index, fallback)
}

/**
 * 花生老师（huasheng）专属结果渲染器。
 * 每种题型独立渲染，通过动态构建 View 适配花生老师的输出格式。
 *
 * 新老师需创建 ScreenCaptureService+Renderers_{teacherId}.kt，
 * 并在 ScreenCaptureService+Result.kt 分派中添加对应分支。
 */

// ── Schema 检测 ────────────────────────────────────────────────────────

internal fun detectSchemaType(json: JSONObject): QuestionType {
    if (json.has("blanks") || json.has("blanks_analysis")) return QuestionType.LUO_JI_TIAN_KONG
    if (json.has("visual_analysis") || json.has("pattern_type")) return QuestionType.TU_XING_TUI_LI
    if (json.has("structure_analysis") || json.has("key_clues")) return QuestionType.YU_JU_BIAO_DA
    if (json.has("key_elements") || json.has("definition_text")) return QuestionType.DING_YI_PAN_DUAN
    if (json.has("word_pair") || json.has("relationship_type")) return QuestionType.LEI_BI_TUI_LI
    if (json.has("logical_chain") || json.has("argument_structure") || json.has("reasoning_type")) return QuestionType.LUO_JI_PAN_DUAN
    return QuestionType.PIAN_DUAN_YUE_DU // 默认兼容旧格式
}

// ── 渲染分发 ────────────────────────────────────────────────────────────

internal fun ScreenCaptureService.renderHuasheng(card: View, json: JSONObject, type: QuestionType) {
    hideAllSections(card)
    card.findViewById<View>(R.id.tv_result)?.visibility = View.GONE
    when (type) {
        QuestionType.PIAN_DUAN_YUE_DU -> renderHuashengPianDuanYueDu(card, json)
        QuestionType.LUO_JI_TIAN_KONG -> renderHuashengLuoJiTianKong(card, json)
        QuestionType.YU_JU_BIAO_DA -> renderHuashengYuJuBiaoDa(card, json)
        QuestionType.TU_XING_TUI_LI -> renderHuashengTuXingTuiLi(card, json)
        QuestionType.DING_YI_PAN_DUAN -> renderHuashengDingYiPanDuan(card, json)
        QuestionType.LEI_BI_TUI_LI -> renderHuashengLeiBiTuiLi(card, json)
        QuestionType.LUO_JI_PAN_DUAN -> renderHuashengLuoJiPanDuan(card, json)
    }
    renderUsedToolsTags(card, resources.displayMetrics.density)
}

internal fun ScreenCaptureService.renderUsedToolsTags(card: View, d: Float) {
    val layoutTags = card.findViewById<LinearLayout>(R.id.layout_tags) ?: return
    val dp4 = (4 * d).toInt()
    val dp6 = (6 * d).toInt()
    val dp8 = (8 * d).toInt()

    if (usedToolsInCurrentRequest.isNotEmpty()) {
        layoutTags.visibility = View.VISIBLE
        val toolsCopy = synchronized(usedToolsInCurrentRequest) { usedToolsInCurrentRequest.toList() }
        for (tool in toolsCopy) {
            var exists = false
            for (i in 0 until layoutTags.childCount) {
                val child = layoutTags.getChildAt(i) as? TextView
                if (child?.text?.toString() == "已用: $tool") {
                    exists = true
                    break
                }
            }
            if (exists) continue

            val toolTag = TextView(this).apply {
                text = "已用: $tool"
                setTextColor(0xFF5C8271.toInt()) // Zen theme primary text color
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_tag_blue) // Zen warm sand sandstone background
                setPadding(dp8, dp4, dp8, dp4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp6, 0, 0, 0)
                }
            }
            layoutTags.addView(toolTag)
        }
    }
}


// ── 1. 片段阅读（现有逻辑移植）───────────────────────────────────────────

internal fun ScreenCaptureService.renderHuashengPianDuanYueDu(card: View, json: JSONObject) {
    val d = resources.displayMetrics.density
    val dp4 = (4*d).toInt(); val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt()
    val dp10 = (10*d).toInt(); val dp12 = (12*d).toInt()

    // Tags
    val layoutTags = card.findViewById<LinearLayout>(R.id.layout_tags)
    val tvTagQ = card.findViewById<TextView>(R.id.tv_tag_question_type)
    val tvTagP = card.findViewById<TextView>(R.id.tv_tag_passage_type)
    val qType = cleanHtmlText(jsonStr(json,"question_type", ""))
    val pType = cleanHtmlText(jsonStr(json,"passage_type", ""))
    val structArr = json.optJSONArray("structure_type")
    // 清除之前动态添加的 structure 标签（保留 tvTagQ 和 tvTagP）
    if (layoutTags != null && layoutTags.childCount > 2) {
        layoutTags.removeViews(2, layoutTags.childCount - 2)
    }
    if (qType.isNotEmpty() || pType.isNotEmpty() || (structArr != null && structArr.length() > 0)) {
        layoutTags?.visibility = View.VISIBLE
        tvTagQ?.text = "■ $qType"; tvTagQ?.visibility = if (qType.isNotEmpty()) View.VISIBLE else View.GONE
        tvTagP?.text = "■ $pType"; tvTagP?.visibility = if (pType.isNotEmpty()) View.VISIBLE else View.GONE
        // 动态添加行文结构标签
        if (structArr != null && structArr.length() > 0) {
            for (i in 0 until structArr.length()) {
                val struct = cleanHtmlText(arrStr(structArr, i))
                if (struct.isNotEmpty()) {
                    val tag = TextView(this).apply {
                        text = "■ $struct"
                        setTextColor(0xFF7C3AED.toInt()); textSize = 11f
                        setTypeface(null, Typeface.BOLD)
                        setBackgroundResource(R.drawable.bg_tag_blue)
                        setPadding(dp8, dp4, dp8, dp4)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(if ((layoutTags?.childCount ?: 0) > 1) dp6 else 0, 0, 0, 0) }
                    }
                    layoutTags?.addView(tag)
                }
            }
        }
    } else { layoutTags?.visibility = View.GONE }

    // Question
    renderQuestionSection(card, json, d)

    // Answer
    renderAnswerSection(card, json, d)

    // Options analysis
    renderOptionsAnalysis(card, json, d)

    // Logical Labels
    renderLogicalLabels(card, json, d)

    // 解析（之前遗漏了）
    renderAnalysis(card, json, d)
}

// ── 2. 逻辑填空 ─────────────────────────────────────────────────────────

internal fun ScreenCaptureService.renderHuashengLuoJiTianKong(card: View, json: JSONObject) {
    val d = resources.displayMetrics.density
    val dp4 = (4*d).toInt(); val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt()
    val dp10 = (10*d).toInt(); val dp12 = (12*d).toInt()

    // ── 标签：题型判断 ──
    val layoutTags = card.findViewById<LinearLayout>(R.id.layout_tags)
    val tvTagQ = card.findViewById<TextView>(R.id.tv_tag_question_type)
    val tvTagP = card.findViewById<TextView>(R.id.tv_tag_passage_type)
    tvTagP?.visibility = View.GONE // 逻辑填空不用 passage_type
    val skillTags = json.optJSONArray("skill_tags")
    if (skillTags != null && skillTags.length() > 0) {
        layoutTags?.visibility = View.VISIBLE
        val tags = StringBuilder()
        for (i in 0 until skillTags.length()) {
            if (i > 0) tags.append(" · ")
            tags.append(cleanHtmlText(arrStr(skillTags, i)))
        }
        tvTagQ?.text = "■ $tags"; tvTagQ?.visibility = View.VISIBLE
    } else { layoutTags?.visibility = View.GONE }

    // ── 题干 ──
    renderQuestionSection(card, json, d)

    // ── 文段核心 ──
    val coreMeaning = cleanHtmlText(jsonStr(json,"core_meaning", ""))
    if (coreMeaning.isNotEmpty()) {
        addDynamicSection(card, "文段核心", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = formatSpannableText(coreMeaning); textSize = 13f; setTextColor(0xFF1F2937.toInt())
            setTypeface(null, Typeface.BOLD); setLineSpacing(0f, 1.4f)
            setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // ── 破题点 ──
    val breakthrough = cleanHtmlText(jsonStr(json,"breakthrough", ""))
    if (breakthrough.isNotEmpty()) {
        addDynamicSection(card, "破题点", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = "🔑 $breakthrough"; textSize = 13f; setTextColor(0xFF92400E.toInt())
            setLineSpacing(0f, 1.4f)
            setBackgroundResource(R.drawable.bg_option_correct); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // ── 答案 ──
    renderAnswerSection(card, json, d)

    // ── 逐空分析 ──
    val blanks = json.optJSONArray("blanks_analysis")
    if (blanks != null && blanks.length() > 0) {
        addDynamicSection(card, "逐空分析", d)
        val container = getDynamicContainer(card)
        for (i in 0 until blanks.length()) {
            val b = blanks.optJSONObject(i) ?: continue
            val pos = cleanHtmlText(jsonStr(b,"position", "空格${i+1}"))
            val ctx = cleanHtmlText(jsonStr(b,"context_hint", ""))
            val answer = cleanHtmlText(jsonStr(b,"answer", ""))
            val summary = cleanHtmlText(jsonStr(b,"summary", ""))

            val cardView = makeCard(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setPadding(dp10, dp8, dp10, dp8)
                setBackgroundResource(R.drawable.bg_question_box)
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, dp8)
            }

            // 空位标题 + 答案
            val hdr = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp6) }
            }
            hdr.addView(TextView(this).apply {
                text = pos; setTextColor(0xFF2563EB.toInt()); textSize = 13f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp8, 0) }
            })
            hdr.addView(createTag(answer, "#059669", R.drawable.bg_tag_green, d))
            cardView.addView(hdr)

            // 语境
            if (ctx.isNotEmpty()) {
                cardView.addView(TextView(this).apply {
                    text = "语境：$ctx"; textSize = 12f; setTextColor(0xFF6B7280.toInt())
                    setLineSpacing(0f, 1.3f)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp6) }
                })
            }

            // 候选词分析
            val candidates = b.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                for (j in 0 until candidates.length()) {
                    val c = candidates.optJSONObject(j) ?: continue
                    val word = cleanHtmlText(jsonStr(c,"word", ""))
                    val correct = c.optBoolean("correct", false)
                    val dimension = cleanHtmlText(jsonStr(c,"dimension", ""))
                    val cr = cleanHtmlText(jsonStr(c,"reason", ""))

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
                        setPadding(dp8, dp4, dp8, dp4)
                        setBackgroundResource(if (correct) R.drawable.bg_option_correct else R.drawable.bg_option_incorrect)
                    }
                    val topRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val badge = TextView(this).apply {
                        text = if (correct) "✓" else "✗"
                        setTextColor(if (correct) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
                        textSize = 11f; setTypeface(null, Typeface.BOLD)
                        setBackgroundResource(if (correct) R.drawable.bg_tag_green else R.drawable.bg_tag_red)
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams((16*d).toInt(), (16*d).toInt()).apply { setMargins(0, 0, dp6, 0) }
                    }
                    topRow.addView(badge)
                    topRow.addView(TextView(this).apply {
                        text = word; setTextColor(0xFF1F2937.toInt()); textSize = 12.5f
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (dimension.isNotEmpty()) {
                        topRow.addView(createTag(dimension, "#7C3AED", R.drawable.bg_tag_blue, d))
                    }
                    row.addView(topRow)
                    if (cr.isNotEmpty()) {
                        row.addView(TextView(this).apply {
                            text = cr; textSize = 11f; setTextColor(0xFF4B5563.toInt())
                            setLineSpacing(0f, 1.2f)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp4, 0, 0) }
                        })
                    }
                    cardView.addView(row)
                }
            }

            // 本空小结
            if (summary.isNotEmpty()) {
                cardView.addView(TextView(this).apply {
                    text = "💡 $summary"; textSize = 11.5f; setTextColor(0xFF374151.toInt())
                    setLineSpacing(0f, 1.3f)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp6, 0, 0) }
                })
            }
            container?.addView(cardView)
        }
    }

    // ── 代回验证 ──
    val verification = cleanHtmlText(jsonStr(json,"verification", ""))
    if (verification.isNotEmpty()) {
        addDynamicSection(card, "代回验证", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = formatSpannableText(verification); textSize = 12.5f; setTextColor(0xFF374151.toInt())
            setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
            setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // ── 本题积累 ──
    val accumulation = cleanHtmlText(jsonStr(json,"accumulation", ""))
    if (accumulation.isNotEmpty()) {
        addDynamicSection(card, "本题积累", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = "📝 $accumulation"; textSize = 12f; setTextColor(0xFF6B7280.toInt())
            setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }
}

// ── 3. 语句表达 ─────────────────────────────────────────────────────────

internal fun ScreenCaptureService.renderHuashengYuJuBiaoDa(card: View, json: JSONObject) {
    val d = resources.displayMetrics.density
    val dp4 = (4*d).toInt(); val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt()
    val dp10 = (10*d).toInt()

    // ── 标签：子题型 ──
    val layoutTags = card.findViewById<LinearLayout>(R.id.layout_tags)
    val tvTagQ = card.findViewById<TextView>(R.id.tv_tag_question_type)
    val tvTagP = card.findViewById<TextView>(R.id.tv_tag_passage_type)
    tvTagP?.visibility = View.GONE
    val subtype = cleanHtmlText(jsonStr(json,"question_subtype", ""))
    if (subtype.isNotEmpty()) {
        layoutTags?.visibility = View.VISIBLE
        tvTagQ?.text = "■ $subtype"; tvTagQ?.visibility = View.VISIBLE
    } else { layoutTags?.visibility = View.GONE }

    // ── 题干 ──
    renderQuestionSection(card, json, d)

    // ── 答案 ──
    renderAnswerSection(card, json, d)

    // ── 文段结构分析 ──
    val structure = cleanHtmlText(jsonStr(json,"structure_analysis", ""))
    if (structure.isNotEmpty()) {
        addDynamicSection(card, "文段结构分析", d, topMarginDp = 8)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = formatSpannableText(structure); textSize = 13f; setTextColor(0xFF374151.toInt())
            setLineSpacing(0f, 1.4f); setTextIsSelectable(true)
            setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // ── 关键线索 ──
    val clues = json.optJSONArray("key_clues")
    if (clues != null && clues.length() > 0) {
        addDynamicSection(card, "关键线索", d, topMarginDp = 8)
        val container = getDynamicContainer(card)
        val tagRow = com.google.android.flexbox.FlexboxLayout(this).apply {
            flexDirection = com.google.android.flexbox.FlexDirection.ROW
            flexWrap = com.google.android.flexbox.FlexWrap.WRAP
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        for (i in 0 until clues.length()) {
            val clue = cleanHtmlText(arrStr(clues, i))
            if (clue.isNotEmpty()) {
                val tag = createTag(clue, "#7C3AED", R.drawable.bg_tag_blue, d)
                val lp = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, dp4, dp4)
                }
                tag.layoutParams = lp
                tagRow.addView(tag)
            }
        }
        container?.addView(tagRow)
    }

    // ── 选项分析 ──
    renderOptionsAnalysis(card, json, d)
    // 收紧选项与易错点之间的间距
    val tvOptTitle = card.findViewById<TextView>(R.id.tv_options_title)
    if (tvOptTitle?.visibility == View.VISIBLE) {
        (tvOptTitle.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp4
    }

    // ── 易错点提醒 ──
    val pitfall = cleanHtmlText(jsonStr(json,"pitfall", ""))
    if (pitfall.isNotEmpty()) {
        addDynamicSection(card, "易错点提醒", d, topMarginDp = 4)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = "⚠ $pitfall"; textSize = 12.5f; setTextColor(0xFF92400E.toInt())
            setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
            setBackgroundResource(R.drawable.bg_option_correct); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }
}

// ── 4. 图形推理 ─────────────────────────────────────────────────────────

internal fun ScreenCaptureService.renderHuashengTuXingTuiLi(card: View, json: JSONObject) {
    val d = resources.displayMetrics.density
    val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt(); val dp10 = (10*d).toInt()

    renderAnswerSection(card, json, d)

    val patternType = cleanHtmlText(jsonStr(json,"pattern_type", ""))
    val ruleDesc = cleanHtmlText(jsonStr(json,"rule_description", ""))
    val visualAnalysis = cleanHtmlText(jsonStr(json,"visual_analysis", ""))

    if (patternType.isNotEmpty() || ruleDesc.isNotEmpty()) {
        addDynamicSection(card, "图形规律分析", d)
        val container = getDynamicContainer(card)

        if (patternType.isNotEmpty()) {
            container?.addView(createTag(patternType, "#7C3AED", R.drawable.bg_tag_blue, d))
        }

        if (ruleDesc.isNotEmpty()) {
            container?.addView(TextView(this).apply {
                text = "规律：$ruleDesc"; textSize = 13f; setTextColor(0xFF374151.toInt())
                setLineSpacing(0f, 1.4f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp8, 0, 0) }
                setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            })
        }

        if (visualAnalysis.isNotEmpty()) {
            container?.addView(TextView(this).apply {
                text = formatSpannableText(visualAnalysis); textSize = 12f; setTextColor(0xFF4B5563.toInt())
                setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp8, 0, 0) }
            })
        }
    }

    renderOptionsAnalysis(card, json, d)
    renderAnalysis(card, json, d)
}

// ── 5. 定义判断 ─────────────────────────────────────────────────────────

internal fun ScreenCaptureService.renderHuashengDingYiPanDuan(card: View, json: JSONObject) {
    val d = resources.displayMetrics.density
    val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt(); val dp10 = (10*d).toInt()

    val defText = cleanHtmlText(jsonStr(json,"definition_text", ""))
    val askType = cleanHtmlText(jsonStr(json,"ask_type", ""))

    if (defText.isNotEmpty()) {
        addDynamicSection(card, "定义原文" + if (askType.isNotEmpty()) "（提问：$askType）" else "", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = formatSpannableText(defText); textSize = 13f; setTextColor(0xFF374151.toInt())
            setLineSpacing(0f, 1.4f); setTextIsSelectable(true)
            setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // Key elements tags
    val keyElements = json.optJSONArray("key_elements")
    if (keyElements != null && keyElements.length() > 0) {
        addDynamicSection(card, "关键要素", d)
        val container = getDynamicContainer(card)
        val tagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp6)
        }
        val colors = intArrayOf(
            0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFF059669.toInt(), 0xFFD97706.toInt(), 0xFFDC2626.toInt()
        )
        for (i in 0 until keyElements.length()) {
            val elem = cleanHtmlText(arrStr(keyElements, i))
            if (elem.isNotEmpty()) {
                val color = "#%06X".format(0xFFFFFF and colors[i % colors.size])
                val tag = createTag(elem, color, R.drawable.bg_tag_blue, d)
                (tag.layoutParams as LinearLayout.LayoutParams).setMargins(if (tagRow.childCount > 0) dp6 else 0, 0, 0, 0)
                tagRow.addView(tag)
            }
        }
        container?.addView(tagRow)
    }

    renderQuestionSection(card, json, d)
    renderAnswerSection(card, json, d)
    renderOptionsAnalysis(card, json, d)
    renderAnalysis(card, json, d)
}

// ── 6. 类比推理（花生老师完整8段落结构）────────────────────────────────

internal fun ScreenCaptureService.renderHuashengLeiBiTuiLi(card: View, json: JSONObject) {
    val d = resources.displayMetrics.density
    val dp4 = (4*d).toInt(); val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt()
    val dp10 = (10*d).toInt(); val dp14 = (14*d).toInt()

    // ── §1 题干关系总览 ──
    val wordPair = cleanHtmlText(jsonStr(json,"word_pair", ""))
    val relType = cleanHtmlText(jsonStr(json,"relationship_type", ""))
    val relAnalysis = cleanHtmlText(jsonStr(json,"relationship_analysis", ""))

    if (wordPair.isNotEmpty() || relType.isNotEmpty() || relAnalysis.isNotEmpty()) {
        addDynamicSection(card, "题干关系总览", d)
        val container = getDynamicContainer(card)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp8) }
        }
        if (wordPair.isNotEmpty()) {
            header.addView(TextView(this).apply {
                text = wordPair; textSize = 15f; setTextColor(0xFF1F2937.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp8, 0) }
            })
        }
        if (relType.isNotEmpty()) {
            header.addView(createTag(relType, "#7C3AED", R.drawable.bg_tag_blue, d))
        }
        container?.addView(header)

        if (relAnalysis.isNotEmpty()) {
            container?.addView(TextView(this).apply {
                text = formatSpannableText(relAnalysis); textSize = 12.5f; setTextColor(0xFF4B5563.toInt())
                setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    // ── §2 造句验证 ──
    val sentence = cleanHtmlText(jsonStr(json, "sentence", ""))
    if (sentence.isNotEmpty()) {
        addDynamicSection(card, "造句验证", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = formatSpannableText(sentence); textSize = 13f; setTextColor(0xFF374151.toInt())
            setLineSpacing(0f, 1.4f); setTextIsSelectable(true)
            setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // ── §3 关系类型与技巧 ──
    val tech = if (json.has("technique")) json.optJSONObject("technique") else null
    if (tech != null) {
        addDynamicSection(card, "关系类型与技巧", d)
        val container = getDynamicContainer(card)

        val level1 = cleanHtmlText(jsonStr(tech, "level1", ""))
        val level2 = cleanHtmlText(jsonStr(tech, "level2", ""))
        val keyPoint = cleanHtmlText(jsonStr(tech, "key_point", ""))

        val tagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp6) }
        }
        if (level1.isNotEmpty()) {
            tagRow.addView(createTag("一级：$level1", "#2563EB", R.drawable.bg_tag_blue, d))
        }
        if (level2.isNotEmpty()) {
            val tag = createTag(level2, "#7C3AED", R.drawable.bg_tag_blue, d)
            (tag.layoutParams as LinearLayout.LayoutParams).setMargins(dp6, 0, 0, 0)
            tagRow.addView(tag)
        }
        if (tagRow.childCount > 0) container?.addView(tagRow)

        if (keyPoint.isNotEmpty()) {
            container?.addView(TextView(this).apply {
                text = "核心判断点：$keyPoint"; textSize = 12f; setTextColor(0xFF92400E.toInt())
                setLineSpacing(0f, 1.3f)
                setBackgroundResource(R.drawable.bg_option_correct); setPadding(dp10, dp6, dp10, dp6)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    // ── §4 逐项分析（表格风格） ──
    val optTable = json.optJSONArray("options_table")
    if (optTable != null && optTable.length() > 0) {
        addDynamicSection(card, "逐项分析", d)
        val container = getDynamicContainer(card)

        // 表头行
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_question_box)
            setPadding(dp8, dp6, dp8, dp6)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
        }
        val headerWeights = floatArrayOf(1f, 2.5f, 1f, 3f)
        val headerLabels = arrayOf("选项", "关系判断", "匹配", "理由")
        for (i in headerLabels.indices) {
            headerRow.addView(TextView(this).apply {
                text = headerLabels[i]; textSize = 11f; setTextColor(0xFF2563EB.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, headerWeights[i]).apply {
                    setMargins(if (i > 0) dp4 else 0, 0, 0, 0)
                }
            })
        }
        container?.addView(headerRow)

        // 数据行
        for (idx in 0 until optTable.length()) {
            val row = optTable.optJSONObject(idx) ?: continue
            val opt = cleanHtmlText(jsonStr(row, "option", ""))
            val rel = cleanHtmlText(jsonStr(row, "relationship", ""))
            val match = row.optBoolean("match", false)
            val reason = cleanHtmlText(jsonStr(row, "reason", ""))

            val dataRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                setPadding(dp8, dp6, dp8, dp6)
                setBackgroundResource(if (match) R.drawable.bg_option_correct else R.drawable.bg_option_incorrect)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
            }

            val optTv = TextView(this).apply {
                text = opt; textSize = 12f; setTextColor(0xFF1F2937.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            dataRow.addView(optTv)

            val relTv = TextView(this).apply {
                text = rel; textSize = 11f; setTextColor(0xFF4B5563.toInt())
                setLineSpacing(0f, 1.2f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f).apply { setMargins(dp4, 0, dp4, 0) }
            }
            dataRow.addView(relTv)

            val matchTv = TextView(this).apply {
                text = if (match) "✓ 是" else "✗ 否"; textSize = 11f
                setTextColor(if (match) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            dataRow.addView(matchTv)

            val reasonTv = TextView(this).apply {
                text = reason; textSize = 11f; setTextColor(0xFF6B7280.toInt())
                setLineSpacing(0f, 1.2f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
            }
            dataRow.addView(reasonTv)

            container?.addView(dataRow)
        }
    }

    // ── §5 二级辨析 ──
    val secondary = cleanHtmlText(jsonStr(json, "secondary_analysis", ""))
    if (secondary.isNotEmpty()) {
        addDynamicSection(card, "二级辨析", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = formatSpannableText(secondary); textSize = 12.5f; setTextColor(0xFF374151.toInt())
            setLineSpacing(0f, 1.4f); setTextIsSelectable(true)
            setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // ── 题干、答案、选项分析（基础渲染） ──
    renderQuestionSection(card, json, d)
    renderAnswerSection(card, json, d)
    renderOptionsAnalysis(card, json, d)

    // ── §6 最终答案（已由 renderAnswerSection + renderAnalysis 覆盖，此处加 summary 字段作为一句话理由） ──
    // renderAnalysis 会在最后渲染 analysis 字段

    // ── §7 易错点提醒 ──
    val pitfall = cleanHtmlText(jsonStr(json, "pitfall", ""))
    if (pitfall.isNotEmpty()) {
        addDynamicSection(card, "易错点提醒", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = "⚠ $pitfall"; textSize = 12.5f; setTextColor(0xFF92400E.toInt())
            setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
            setBackgroundResource(R.drawable.bg_option_correct); setPadding(dp10, dp8, dp10, dp8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // ── §8 同类题做法总结 ──
    val summary = cleanHtmlText(jsonStr(json, "summary", ""))
    if (summary.isNotEmpty()) {
        addDynamicSection(card, "同类题做法总结", d)
        val container = getDynamicContainer(card)
        container?.addView(TextView(this).apply {
            text = "📌 $summary"; textSize = 12.5f; setTextColor(0xFF1F2937.toInt())
            setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    // ── 兜底：analysis（一句话理由） ──
    renderAnalysis(card, json, d)
}

// ── 7. 逻辑判断 ─────────────────────────────────────────────────────────

internal fun ScreenCaptureService.renderHuashengLuoJiPanDuan(card: View, json: JSONObject) {
    val d = resources.displayMetrics.density
    val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt(); val dp10 = (10*d).toInt(); val dp12 = (12*d).toInt()

    renderQuestionSection(card, json, d)
    renderAnswerSection(card, json, d)

    // ── 渲染逻辑图 ────────────────────────────────────────────────────────
    if (json.has("diagram_type")) {
        renderHuashengLogicalDiagram(card, json, d)
    }

    val reasoningType = cleanHtmlText(jsonStr(json,"reasoning_type", ""))
    val argStructure = cleanHtmlText(jsonStr(json,"argument_structure", ""))

    if (reasoningType.isNotEmpty() || argStructure.isNotEmpty()) {
        addDynamicSection(card, "论证结构", d)
        val container = getDynamicContainer(card)

        if (reasoningType.isNotEmpty()) {
            container?.addView(createTag(reasoningType, "#1E40AF", R.drawable.bg_tag_blue, d))
        }

        if (argStructure.isNotEmpty()) {
            container?.addView(TextView(this).apply {
                text = formatSpannableText(argStructure); textSize = 12.5f; setTextColor(0xFF374151.toInt())
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp8, 0, 0) }
                setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            })
        }
    }

    // Logical chain
    val chain = json.optJSONArray("logical_chain")
    if (chain != null && chain.length() > 0) {
        addDynamicSection(card, "推理链条", d)
        val container = getDynamicContainer(card)
        for (i in 0 until chain.length()) {
            val step = chain.optJSONObject(i) ?: continue
            val stepNum = step.optInt("step", i + 1)
            val premise = cleanHtmlText(jsonStr(step,"premise", ""))
            val deduction = cleanHtmlText(jsonStr(step,"deduction", ""))

            val stepView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp6) }
                setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
            }
            stepView.addView(TextView(this).apply {
                text = "第${stepNum}步"; textSize = 11f; setTextColor(0xFF2563EB.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp6) }
            })
            if (premise.isNotEmpty()) {
                stepView.addView(TextView(this).apply {
                    text = "前提：$premise"; textSize = 12f; setTextColor(0xFF374151.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 2) }
                })
            }
            if (deduction.isNotEmpty()) {
                stepView.addView(TextView(this).apply {
                    text = "推导：$deduction"; textSize = 12f; setTextColor(0xFF6B7280.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
            container?.addView(stepView)
        }
    }

    renderOptionsAnalysis(card, json, d)
    renderAnalysis(card, json, d)
}

// ── 公共渲染组件 ────────────────────────────────────────────────────────

/** 题目区域（题库命中时优先使用题库数据） */
internal fun ScreenCaptureService.renderQuestionSection(card: View, json: JSONObject, d: Float) {
    val dp10 = (10*d).toInt(); val dp8 = (8*d).toInt()
    val layoutQ = card.findViewById<View>(R.id.layout_question)
    val tvQ = card.findViewById<TextView>(R.id.tv_question)

    // 优先使用题库数据
    val bankMatch = lastBankMatch
    var qText = if (bankMatch != null && bankMatch.stem.isNotEmpty()) {
        bankMatch.stem
    } else {
        cleanTextKeepSpan(jsonStr(json,"question", ""))
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    }
    // 填空题：把不间断空格（NBSP, U+00A0）和全角空格（U+3000）替换为可见下划线
    qText = qText.replace(Regex("[\\u00A0\\u3000]{2,}"), " ________ ")
    qText = qText.replace(Regex("■.*$"), "").trim()

    // 根据 AI 返回的 keywords 数组，自动在题目中标红关键词
    val keywords = json.optJSONArray("keywords")
    if (keywords != null && keywords.length() > 0) {
        for (i in 0 until keywords.length()) {
            val kw = keywords.optString(i, "").trim()
            if (kw.isNotEmpty() && qText.contains(kw) && !qText.contains("~~$kw~~")) {
                qText = qText.replace(kw, "~~$kw~~")
            }
        }
    }

    if (qText.isNotEmpty()) {
        layoutQ?.visibility = View.VISIBLE
        tvQ?.text = formatSpannableText(qText)
    } else { layoutQ?.visibility = View.GONE }
}

/** 答案区域（题库命中时优先使用题库数据） */
internal fun ScreenCaptureService.renderAnswerSection(card: View, json: JSONObject, d: Float) {
    val layoutA = card.findViewById<View>(R.id.layout_answer)
    val tvA = card.findViewById<TextView>(R.id.tv_answer)

    // 优先使用题库答案
    val bankMatch = lastBankMatch
    val answer = if (bankMatch != null && bankMatch.answer.isNotEmpty()) {
        bankMatch.answer
    } else {
        cleanHtmlText(jsonStr(json,"correct_answer", ""))
    }

    if (answer.isNotEmpty()) { layoutA?.visibility = View.VISIBLE; tvA?.text = answer }
    else { layoutA?.visibility = View.GONE }
}

/** 选项分析（题库命中时优先使用题库数据） */
internal fun ScreenCaptureService.renderOptionsAnalysis(card: View, json: JSONObject, d: Float) {
    val dp4 = (4*d).toInt(); val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt()
    val dp10 = (10*d).toInt()

    val tvOT = card.findViewById<TextView>(R.id.tv_options_title)
    val layoutOpts = card.findViewById<LinearLayout>(R.id.layout_options_container)

    // 优先用 AI 的 options_analysis（有详细解析），没有则用题库选项
    val bankMatch = lastBankMatch
    val aiOpts = json.optJSONArray("options_analysis")
    val useBankOptions = bankMatch != null && bankMatch.options.isNotEmpty() && (aiOpts == null || aiOpts.length() == 0)

    if (useBankOptions) {
        tvOT?.visibility = View.VISIBLE; layoutOpts?.visibility = View.VISIBLE; layoutOpts?.removeAllViews()
        val answer = bankMatch!!.answer.trim()
        // 答案转索引：A→0, B→1, C→2, D→3
        val answerIdx = if (answer.length == 1 && answer[0] in 'A'..'Z') answer[0] - 'A' else -1
        for (idx in 0 until bankMatch.options.size) {
            val opt = bankMatch.options[idx]
            val optText = opt.text
            // 按索引匹配答案，或按前缀匹配（兼容选项文本以字母开头的情况）
            val isCorrect = answer.isNotEmpty() && (
                (answerIdx >= 0 && idx == answerIdx) ||
                optText.startsWith(answer) || optText.startsWith("$answer.") || optText.startsWith("$answer、") || optText.startsWith("$answer ")
            )

            val optCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp6) }
                setPadding(dp10, dp8, dp10, dp8)
                setBackgroundResource(if (isCorrect) R.drawable.bg_option_correct else R.drawable.bg_option_incorrect)
            }
            val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams((20*d).toInt(), (20*d).toInt()).apply { setMargins(0, 0, dp6, 0) }
                text = if (isCorrect) "✓" else "✗"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (isCorrect) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(if (isCorrect) R.drawable.bg_tag_green else R.drawable.bg_tag_red)
            }
            hRow.addView(icon)
            val tvOpt = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(0xFF1F2937.toInt()); textSize = 13f
                setTypeface(null, Typeface.BOLD); text = optText
            }
            hRow.addView(tvOpt); optCard.addView(hRow)
            layoutOpts?.addView(optCard)
        }
        return
    }

    // AI返回的选项分析
    val opts = json.optJSONArray("options_analysis")
    if (opts != null && opts.length() > 0) {
        tvOT?.visibility = View.VISIBLE; layoutOpts?.visibility = View.VISIBLE; layoutOpts?.removeAllViews()
        for (idx in 0 until opts.length()) {
            val o = opts.optJSONObject(idx) ?: continue
            val correct = o.optBoolean("correct", false)
            val matches = if (o.has("matches")) o.optBoolean("matches", false) else correct
            val optText = cleanHtmlText(jsonStr(o,"option", ""))
            val reason = jsonStr(o,"reason", "")
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            val sType = cleanHtmlText(jsonStr(o,"strategy_type", ""))
            val sPos = cleanHtmlText(jsonStr(o,"strategy_position", ""))
            val eType = cleanHtmlText(jsonStr(o,"error_type", ""))

            val optCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp6) }
                setPadding(dp10, dp8, dp10, dp8)
                setBackgroundResource(if (correct || matches) R.drawable.bg_option_correct else R.drawable.bg_option_incorrect)
            }
            val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams((20*d).toInt(), (20*d).toInt()).apply { setMargins(0, 0, dp6, 0) }
                text = if (correct || matches) "✓" else "✗"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (correct || matches) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(if (correct || matches) R.drawable.bg_tag_green else R.drawable.bg_tag_red)
            }
            hRow.addView(icon)
            val tvOpt = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(0xFF1F2937.toInt()); textSize = 13f
                setTypeface(null, Typeface.BOLD); text = optText
            }
            hRow.addView(tvOpt); optCard.addView(hRow)

            if (reason.isNotEmpty()) {
                val tvR = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp4, 0, 0) }
                    setTextColor(0xFF4B5563.toInt()); textSize = 12.5f
                    setLineSpacing(0f, 1.2f); text = formatSpannableText(reason); setTextIsSelectable(true)
                }
                optCard.addView(tvR)
            }
            // Tags (片段阅读 style)
            val tRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp4, 0, 0) }
            }
            var hasTag = false
            if (correct && sType.isNotEmpty()) { tRow.addView(createTag(sType, "#92400E", R.drawable.bg_tag_orange, d)); hasTag = true }
            if (correct && sPos.isNotEmpty()) {
                val lbl = if (sPos == "pre") "前对策" else "后对策"
                val t = createTag(lbl, "#1E40AF", R.drawable.bg_tag_blue, d)
                (t.layoutParams as LinearLayout.LayoutParams).setMargins(dp4, 0, 0, 0)
                tRow.addView(t); hasTag = true
            }
            if (!correct && eType.isNotEmpty()) { tRow.addView(createTag(eType, "#DC2626", R.drawable.bg_tag_red, d)); hasTag = true }
            if (hasTag) optCard.addView(tRow)
            layoutOpts?.addView(optCard)
        }
    } else { tvOT?.visibility = View.GONE; layoutOpts?.visibility = View.GONE }
}

/** 逻辑标签（仅片段阅读使用） */
internal fun ScreenCaptureService.renderLogicalLabels(card: View, json: JSONObject, d: Float) {
    val dp4 = (4*d).toInt(); val dp8 = (8*d).toInt()
    val tvLT = card.findViewById<TextView>(R.id.tv_logical_title)
    val layoutLL = card.findViewById<LinearLayout>(R.id.layout_logical_labels)
    val labels = json.optJSONArray("logical_labels")
    if (labels != null && labels.length() > 0) {
        tvLT?.visibility = View.VISIBLE; layoutLL?.visibility = View.VISIBLE; layoutLL?.removeAllViews()
        for (idx in 0 until labels.length()) {
            val it = labels.optJSONObject(idx) ?: continue
            val unit = cleanHtmlText(jsonStr(it,"unit", ""))
            val label = cleanHtmlText(jsonStr(it,"label", ""))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (idx < labels.length() - 1) setMargins(0, 0, 0, dp8)
                }
            }
            val tag = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp8, 0) }
                text = label; textSize = 11f; setTextColor(0xFFDC2626.toInt())
                setTypeface(null, Typeface.BOLD); setBackgroundResource(R.drawable.bg_tag_red); setPadding(dp8, dp4, dp8, dp4)
            }
            row.addView(tag)
            val tvU = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = unit; textSize = 12f; setTextColor(0xFF374151.toInt()); setLineSpacing(0f, 1.3f)
            }
            row.addView(tvU); layoutLL?.addView(row)
        }
    } else { tvLT?.visibility = View.GONE; layoutLL?.visibility = View.GONE }
}

/** 通用分析总结段落 */
internal fun ScreenCaptureService.renderAnalysis(card: View, json: JSONObject, d: Float) {
    // AI 返回的解析字段名不固定，按优先级查找
    val rawAnalysis = json.opt("analysis") ?: json.opt("explanation") ?: json.opt("解析")
    val dp4 = (4*d).toInt(); val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt(); val dp10 = (10*d).toInt()

    when (rawAnalysis) {
        is JSONObject -> {
            // 嵌套结构：每个空/段落独立渲染，通用处理所有 key-value
            for (blankKey in rawAnalysis.keys()) {
                val blankValue = rawAnalysis.opt(blankKey) ?: continue
                val title = when {
                    blankKey.contains("first") -> "第一空解析"
                    blankKey.contains("second") -> "第二空解析"
                    blankKey.contains("third") -> "第三空解析"
                    blankKey.contains("fourth") -> "第四空解析"
                    blankKey.contains("overall") -> "整体解析"
                    else -> "解析"
                }
                addDynamicSection(card, title, d)
                val container = getDynamicContainer(card)
                renderGenericValue(container, blankValue, dp4, dp6, dp8, dp10, d)
            }
        }
        is JSONArray -> {
            val analysisText = StringBuilder()
            for (i in 0 until rawAnalysis.length()) {
                val item = rawAnalysis.opt(i)
                if (item is String) {
                    analysisText.appendLine(item)
                } else {
                    analysisText.appendLine(item.toString())
                }
            }
            if (analysisText.isNotEmpty()) {
                addDynamicSection(card, "解析", d)
                val container = getDynamicContainer(card)
                container?.addView(TextView(this).apply {
                    text = formatSpannableText(cleanHtmlText(analysisText.toString()))
                    textSize = 12.5f; setTextColor(0xFF4B5563.toInt())
                    setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
        is String -> {
            val analysis = cleanHtmlText(rawAnalysis)
            if (analysis.isEmpty()) return
            addDynamicSection(card, "解析", d)
            val container = getDynamicContainer(card)
            container?.addView(TextView(this).apply {
                text = formatSpannableText(analysis); textSize = 12.5f; setTextColor(0xFF4B5563.toInt())
                setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        else -> {
            val analysis = cleanHtmlText(rawAnalysis?.toString() ?: "")
            if (analysis.isEmpty()) return
            addDynamicSection(card, "解析", d)
            val container = getDynamicContainer(card)
            container?.addView(TextView(this).apply {
                text = formatSpannableText(analysis); textSize = 12.5f; setTextColor(0xFF4B5563.toInt())
                setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }
}

/** 通用 JSON 值渲染：自动处理 String / JSONObject / JSONArray */
private fun ScreenCaptureService.renderGenericValue(
    container: LinearLayout?, value: Any?, dp4: Int, dp6: Int, dp8: Int, dp10: Int, d: Float,
    depth: Int = 0
) {
    if (depth > 10) return
    when (value) {
        is String -> {
            val text = cleanHtmlText(value)
            if (text.isNotEmpty()) {
                container?.addView(TextView(this).apply {
                    this.text = formatSpannableText(text); textSize = 12.5f; setTextColor(0xFF374151.toInt())
                    setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
                    setBackgroundResource(R.drawable.bg_question_box); setPadding(dp10, dp8, dp10, dp8)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp6) }
                })
            }
        }
        is JSONObject -> {
            for (key in value.keys()) {
                val v = value.opt(key) ?: continue
                when (v) {
                    is String -> {
                        val text = cleanHtmlText(v)
                        if (text.isNotEmpty()) {
                            // 带 key 标签的文本行
                            val row = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp8, dp6, dp8, dp6)
                                setBackgroundResource(R.drawable.bg_question_box)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
                            }
                            row.addView(TextView(this).apply {
                                this.text = key; textSize = 11f; setTextColor(0xFF2563EB.toInt())
                                setTypeface(null, Typeface.BOLD)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
                            })
                            row.addView(TextView(this).apply {
                                this.text = formatSpannableText(text); textSize = 12f; setTextColor(0xFF374151.toInt())
                                setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
                            })
                            container?.addView(row)
                        }
                    }
                    is JSONObject -> {
                        // 嵌套对象：递归渲染
                        val subContainer = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp8, dp6, dp8, dp6)
                            setBackgroundResource(R.drawable.bg_question_box)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
                        }
                        subContainer.addView(TextView(this).apply {
                            this.text = key; textSize = 11f; setTextColor(0xFF2563EB.toInt())
                            setTypeface(null, Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
                        })
                        renderGenericValue(subContainer, v, dp4, dp6, dp8, dp10, d, depth + 1)
                        container?.addView(subContainer)
                    }
                    is JSONArray -> {
                        val subContainer = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
                        }
                        subContainer.addView(TextView(this).apply {
                            this.text = key; textSize = 11f; setTextColor(0xFF2563EB.toInt())
                            setTypeface(null, Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp4) }
                        })
                        renderGenericValue(subContainer, v, dp4, dp6, dp8, dp10, d, depth + 1)
                        container?.addView(subContainer)
                    }
                }
            }
        }
        is JSONArray -> {
            for (i in 0 until value.length()) {
                val item = value.opt(i) ?: continue
                renderGenericValue(container, item, dp4, dp6, dp8, dp10, d, depth + 1)
            }
        }
    }
}

// ── 动态容器工具 ────────────────────────────────────────────────────────

private var dynamicSectionTitle: TextView? = null
private var dynamicContainer: LinearLayout? = null

internal fun ScreenCaptureService.addDynamicSection(card: View, title: String, d: Float, topMarginDp: Int = 12) {
    val dpTop = (topMarginDp*d).toInt(); val dpBot = (topMarginDp/2*d).toInt()
    val contentLayout = (card.findViewById<View>(R.id.zoomable_content) as? android.view.ViewGroup)?.getChildAt(0) as? LinearLayout ?: return
    dynamicSectionTitle = TextView(this).apply {
        this.text = title; textSize = 13f; setTextColor(0xFF1F2937.toInt())
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dpTop, 0, dpBot) }
    }
    contentLayout.addView(dynamicSectionTitle)
    dynamicContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    contentLayout.addView(dynamicContainer)
}

internal fun ScreenCaptureService.getDynamicContainer(card: View): LinearLayout? = dynamicContainer

internal fun clearDynamicState() {
    dynamicSectionTitle = null
    dynamicContainer = null
}

internal fun ScreenCaptureService.makeCard(w: Int, h: Int): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(w, h)
    }

// ── 逻辑图渲染器实现 (Premium Matcha Green System) ───────────────────────

data class CircleData(val id: String, val label: String)
data class RelationData(val type: String, val between: List<String>, val label: String)

class EulerDiagramView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : android.view.View(context, attrs, defStyleAttr) {
    var circles: List<CircleData> = emptyList()
    var relations: List<RelationData> = emptyList()
    
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        textAlign = android.graphics.Paint.Align.CENTER
        color = 0xFF1F2937.toInt()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (circles.isEmpty()) return

        val colors = listOf(
            0x66F43F5E.toInt(), // 柔粉红
            0x663B82F6.toInt(), // 柔海蓝
            0x6610B981.toInt()  // 柔草绿
        )
        val strokeColors = listOf(
            0xFFF43F5E.toInt(),
            0xFF3B82F6.toInt(),
            0xFF10B981.toInt()
        )

        val r = kotlin.math.min(w, h) * 0.25f

        if (circles.size == 1) {
            val cx = w / 2f
            val cy = h / 2f
            paint.color = colors[0]
            canvas.drawCircle(cx, cy, r, paint)
            strokePaint.color = strokeColors[0]
            canvas.drawCircle(cx, cy, r, strokePaint)
            canvas.drawText(circles[0].label, cx, cy + 12f, textPaint)
        } else if (circles.size == 2) {
            val rel = relations.firstOrNull()?.type ?: "intersect"
            when (rel) {
                "contain" -> {
                    val cx = w / 2f
                    val cy = h / 2f
                    // 大圆
                    paint.color = colors[0]
                    canvas.drawCircle(cx, cy, r * 1.4f, paint)
                    strokePaint.color = strokeColors[0]
                    canvas.drawCircle(cx, cy, r * 1.4f, strokePaint)
                    canvas.drawText(circles[0].label, cx, cy - r * 0.7f, textPaint)
                    
                    // 小圆
                    paint.color = colors[1]
                    canvas.drawCircle(cx, cy, r * 0.7f, paint)
                    strokePaint.color = strokeColors[1]
                    canvas.drawCircle(cx, cy, r * 0.7f, strokePaint)
                    canvas.drawText(circles[1].label, cx, cy + 12f, textPaint)
                }
                "exclude" -> {
                    val cx1 = w * 0.28f
                    val cy1 = h / 2f
                    val cx2 = w * 0.72f
                    val cy2 = h / 2f
                    
                    paint.color = colors[0]
                    canvas.drawCircle(cx1, cy1, r, paint)
                    strokePaint.color = strokeColors[0]
                    canvas.drawCircle(cx1, cy1, r, strokePaint)
                    canvas.drawText(circles[0].label, cx1, cy1 + 12f, textPaint)
                    
                    paint.color = colors[1]
                    canvas.drawCircle(cx2, cy2, r, paint)
                    strokePaint.color = strokeColors[1]
                    canvas.drawCircle(cx2, cy2, r, strokePaint)
                    canvas.drawText(circles[1].label, cx2, cy2 + 12f, textPaint)
                }
                else -> {
                    val cx1 = w * 0.38f
                    val cy1 = h / 2f
                    val cx2 = w * 0.62f
                    val cy2 = h / 2f
                    
                    paint.color = colors[0]
                    canvas.drawCircle(cx1, cy1, r * 1.05f, paint)
                    strokePaint.color = strokeColors[0]
                    canvas.drawCircle(cx1, cy1, r * 1.05f, strokePaint)
                    canvas.drawText(circles[0].label, cx1 - 20f, cy1 + 12f, textPaint)
                    
                    paint.color = colors[1]
                    canvas.drawCircle(cx2, cy2, r * 1.05f, paint)
                    strokePaint.color = strokeColors[1]
                    canvas.drawCircle(cx2, cy2, r * 1.05f, strokePaint)
                    canvas.drawText(circles[1].label, cx2 + 20f, cy2 + 12f, textPaint)
                }
            }
        } else {
            val cx1 = w * 0.5f
            val cy1 = h * 0.35f
            val cx2 = w * 0.36f
            val cy2 = h * 0.65f
            val cx3 = w * 0.64f
            val cy3 = h * 0.65f
            
            paint.color = colors[0]
            canvas.drawCircle(cx1, cy1, r, paint)
            strokePaint.color = strokeColors[0]
            canvas.drawCircle(cx1, cy1, r, strokePaint)
            canvas.drawText(circles.getOrNull(0)?.label ?: "", cx1, cy1 + 12f, textPaint)
            
            paint.color = colors[1]
            canvas.drawCircle(cx2, cy2, r, paint)
            strokePaint.color = strokeColors[1]
            canvas.drawCircle(cx2, cy2, r, strokePaint)
            canvas.drawText(circles.getOrNull(1)?.label ?: "", cx2, cy2 + 12f, textPaint)
            
            paint.color = colors[2]
            canvas.drawCircle(cx3, cy3, r, paint)
            strokePaint.color = strokeColors[2]
            canvas.drawCircle(cx3, cy3, r, strokePaint)
            canvas.drawText(circles.getOrNull(2)?.label ?: "", cx3, cy3 + 12f, textPaint)
        }
    }
}

internal fun ScreenCaptureService.renderHuashengLogicalDiagram(card: View, json: JSONObject, d: Float) {
    val diagType = json.optString("diagram_type", "")
    if (diagType.isEmpty()) return
    
    when (diagType) {
        "arrow_graph" -> renderArrowGraph(card, json, d)
        "euler_diagram" -> renderEulerDiagram(card, json, d)
        "matrix_table" -> renderMatrixTable(card, json, d)
    }
}

private fun ScreenCaptureService.renderArrowGraph(card: View, json: JSONObject, d: Float) {
    val dp4 = (4*d).toInt(); val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt()
    val dp10 = (10*d).toInt(); val dp12 = (12*d).toInt(); val dp16 = (16*d).toInt()

    val nodesArray = json.optJSONArray("nodes") ?: return
    val edgesArray = json.optJSONArray("edges") ?: return

    addDynamicSection(card, "逻辑推理关系图", d)
    val container = getDynamicContainer(card) ?: return

    // 外层精致卡片容器 (高级抹茶绿渐变与细圆角边框)
    val diagramCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp4, 0, dp10)
        }
        setPadding(dp12, dp12, dp12, dp12)
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFF3FBF6.toInt()) // 极雅致淡抹茶绿底色
            cornerRadius = 16 * d
            setStroke(1, 0xFFD1E7DD.toInt()) // 嫩绿边框
        }
        background = drawable
        elevation = 2 * d
    }

    // 映射 nodes 快速存取
    val nodeMap = mutableMapOf<String, String>()
    for (i in 0 until nodesArray.length()) {
        val n = nodesArray.optJSONObject(i) ?: continue
        nodeMap[n.optString("id")] = n.optString("text")
    }

    // 我们为节点绘制竖向排列推理链
    for (i in 0 until nodesArray.length()) {
        val n = nodesArray.optJSONObject(i) ?: continue
        val nodeId = n.optString("id")
        val nodeText = n.optString("text")

        // 渲染 Node Card
        val nodeCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp16, 0, dp16, 0)
            }
            setPadding(dp12, dp8, dp12, dp8)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt()) // 干净白底
                cornerRadius = 10 * d
                setStroke(2, 0xFFE2E8F0.toInt()) // 软质边框
            }
            background = gd
        }
        nodeCard.addView(TextView(this).apply {
            text = "【$nodeId】$nodeText"
            textSize = 12.5f
            setTextColor(0xFF1F2937.toInt())
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        })
        diagramCard.addView(nodeCard)

        // 寻找此节点出发的所有 edges
        val edgeList = mutableListOf<JSONObject>()
        for (j in 0 until edgesArray.length()) {
            val e = edgesArray.optJSONObject(j) ?: continue
            if (e.optString("from") == nodeId) {
                edgeList.add(e)
            }
        }

        // 如果不是最后一个节点，且有边连接，或者直接渲染关系箭头
        if (i < nodesArray.length() - 1) {
            val edge = edgeList.firstOrNull() // 针对单链结构
            val relationLabel = edge?.optString("label", "推出") ?: "推出"
            val isNegative = edge?.optString("type") == "negative"
            
            val arrowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp4, 0, dp4)
                }
            }
            // 推出关系小气泡
            val bubble = TextView(this).apply {
                text = relationLabel
                textSize = 9.5f
                setTextColor(if (isNegative) 0xFFEF4444.toInt() else 0xFF2C5E43.toInt())
                setPadding(dp6, 2, dp6, 2)
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isNegative) 0xFFFEE2E2.toInt() else 0xFFE6EFEB.toInt())
                    cornerRadius = 4 * d
                }
                background = gd
            }
            arrowLayout.addView(bubble)
            // 向下箭头
            arrowLayout.addView(TextView(this).apply {
                text = "▼"
                textSize = 12f
                setTextColor(if (isNegative) 0xFFF43F5E.toInt() else 0xFF10B981.toInt())
            })
            diagramCard.addView(arrowLayout)
        }
    }
    container.addView(diagramCard)
}

private fun ScreenCaptureService.renderEulerDiagram(card: View, json: JSONObject, d: Float) {
    val dp4 = (4*d).toInt(); val dp8 = (8*d).toInt(); val dp10 = (10*d).toInt(); val dp12 = (12*d).toInt()

    val circlesArray = json.optJSONArray("circles") ?: return
    val relationsArray = json.optJSONArray("relations") ?: return

    addDynamicSection(card, "概念集合关系图 (欧拉图)", d)
    val container = getDynamicContainer(card) ?: return

    // 整个图大容器卡片
    val diagramCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp4, 0, dp10)
        }
        setPadding(dp12, dp12, dp12, dp12)
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFAFAFA.toInt())
            cornerRadius = 16 * d
            setStroke(1, 0xFFE5E7EB.toInt())
        }
        background = drawable
        elevation = 2 * d
    }

    val cList = mutableListOf<CircleData>()
    for (i in 0 until circlesArray.length()) {
        val c = circlesArray.optJSONObject(i) ?: continue
        cList.add(CircleData(c.optString("id"), c.optString("label")))
    }
    val rList = mutableListOf<RelationData>()
    for (i in 0 until relationsArray.length()) {
        val r = relationsArray.optJSONObject(i) ?: continue
        val bArr = r.optJSONArray("between")
        val bList = mutableListOf<String>()
        if (bArr != null) {
            for (j in 0 until bArr.length()) bList.add(bArr.optString(j))
        }
        rList.add(RelationData(r.optString("type"), bList, r.optString("label")))
    }

    // 动态装配 EulerDiagramView
    val eulerView = EulerDiagramView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (170 * d).toInt())
        this.circles = cList
        this.relations = rList
    }
    diagramCard.addView(eulerView)

    // 添加下方的关系逻辑说明
    if (rList.isNotEmpty()) {
        val relText = StringBuilder("集合关系解读：\n")
        rList.forEachIndexed { idx, rel ->
            relText.append("• ").append(rel.label)
            if (idx < rList.size - 1) relText.append("\n")
        }
        diagramCard.addView(TextView(this).apply {
            text = relText.toString()
            textSize = 12f
            setTextColor(0xFF2C5E43.toInt()) // 优雅抹茶深绿
            setLineSpacing(0f, 1.3f)
            setPadding(dp10, dp8, dp10, dp8)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF0F9F4.toInt()) // 浅萌抹茶绿
                cornerRadius = 8 * d
            }
            background = gd
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp8, 0, 0)
            }
        })
    }

    container.addView(diagramCard)
}

private fun ScreenCaptureService.renderMatrixTable(card: View, json: JSONObject, d: Float) {
    val dp4 = (4*d).toInt(); val dp6 = (6*d).toInt(); val dp8 = (8*d).toInt()
    val dp10 = (10*d).toInt(); val dp12 = (12*d).toInt()

    val headersArray = json.optJSONArray("headers") ?: return
    val rowsArray = json.optJSONArray("rows") ?: return

    addDynamicSection(card, "多维信息匹配表 (矩阵表格)", d)
    val container = getDynamicContainer(card) ?: return

    // 卡片外部容器
    val diagramCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp4, 0, dp10)
        }
        setPadding(dp12, dp12, dp12, dp12)
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = 16 * d
            setStroke(1, 0xFFE2E8F0.toInt())
        }
        background = drawable
        elevation = 2 * d
    }

    // 表格水平滚动包装器
    val scrollView = android.widget.HorizontalScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        isScrollbarFadingEnabled = true
    }

    // 表格纵向布局容器
    val tableLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // 渲染表头 Headers
    val headerRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFE8F0EC.toInt()) // 表头清雅绿背景
            cornerRadius = 6 * d
        }
    }
    for (i in 0 until headersArray.length()) {
        val text = headersArray.optString(i, "")
        headerRow.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF2C5E43.toInt()) // 深抹茶绿字
            setTypeface(null, Typeface.BOLD)
            setPadding(dp12, dp8, dp12, dp8)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((85 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }
    tableLayout.addView(headerRow)

    // 渲染各行数据 Rows
    for (rIdx in 0 until rowsArray.length()) {
        val rowArr = rowsArray.optJSONArray(rIdx) ?: continue
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp4, 0, 0)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                // 抹茶绿斑马纹隔行变色
                setColor(if (rIdx % 2 == 0) 0xFFFCFDFD.toInt() else 0xFFF3F8F5.toInt())
                cornerRadius = 6 * d
            }
        }
        for (cIdx in 0 until rowArr.length()) {
            val cellText = rowArr.optString(cIdx, "")
            rowLayout.addView(TextView(this).apply {
                this.text = cellText
                textSize = 11.5f
                setTextColor(0xFF4B5563.toInt())
                setPadding(dp12, dp8, dp12, dp8)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((85 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        tableLayout.addView(rowLayout)
    }

    scrollView.addView(tableLayout)
    diagramCard.addView(scrollView)
    container.addView(diagramCard)
}
