package com.example.aiassistant.questionbank

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.ModelManager
import com.example.aiassistant.OpenAIApiService
import com.example.aiassistant.QuestionType
import com.example.aiassistant.R
import com.example.aiassistant.skills.ToolRegistry
import com.google.android.material.checkbox.MaterialCheckBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class WrongQuestionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "wrong_question_id"
    }

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var currentId: String = ""
    private var currentQuestionType: QuestionType? = null
    private var isAiTabActive = false
    private var cachedItem: WrongQuestion? = null  // 缓存当前错题，避免重复反序列化

    private fun getCurrentItem(): WrongQuestion? {
        if (cachedItem?.id == currentId) return cachedItem
        cachedItem = WrongQuestionManager.getWrongQuestions(this).find { it.id == currentId }
        return cachedItem
    }

    // 解析区域视图
    private lateinit var tabBankAnalysis: TextView
    private lateinit var tabAiAnalysis: TextView
    private lateinit var tabIndicator: View
    private lateinit var contentBankAnalysis: View
    private lateinit var contentAiAnalysis: View
    private lateinit var layoutAiLoading: View
    private lateinit var layoutAiResult: LinearLayout
    private lateinit var tvAiPlaceholder: View
    private lateinit var btnStartAi: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_question_detail)

        currentId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }

        // 初始化视图
        initViews()

        // 设置点击事件
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btn_delete).setOnClickListener { confirmDelete() }
        findViewById<TextView>(R.id.btn_edit_summary).setOnClickListener { showEditSummaryDialog() }
        findViewById<TextView>(R.id.btn_export).setOnClickListener { exportAsImage() }

        loadDetail()
    }

    private fun initViews() {
        tabBankAnalysis = findViewById(R.id.tab_bank_analysis)
        tabAiAnalysis = findViewById(R.id.tab_ai_analysis)
        tabIndicator = findViewById(R.id.tab_indicator)
        contentBankAnalysis = findViewById(R.id.content_bank_analysis)
        contentAiAnalysis = findViewById(R.id.content_ai_analysis)
        layoutAiLoading = findViewById(R.id.layout_ai_loading)
        layoutAiResult = findViewById(R.id.layout_ai_result)
        tvAiPlaceholder = findViewById(R.id.tv_ai_placeholder)
        btnStartAi = findViewById(R.id.btn_start_ai)

        // Tab切换点击事件
        tabBankAnalysis.setOnClickListener { switchToTab(false) }
        tabAiAnalysis.setOnClickListener { switchToTab(true) }

        // 开始AI解析按钮
        btnStartAi.setOnClickListener { startAiAnalysis() }
    }

    private fun switchToTab(isAiTab: Boolean) {
        isAiTabActive = isAiTab

        if (isAiTab) {
            tabBankAnalysis.setTextColor(getColor(R.color.text_secondary))
            tabAiAnalysis.setTextColor(getColor(R.color.primary))
            tabIndicator.animate().translationX(tabAiAnalysis.width.toFloat()).setDuration(200).start()
            contentBankAnalysis.visibility = View.GONE
            contentAiAnalysis.visibility = View.VISIBLE
        } else {
            tabBankAnalysis.setTextColor(getColor(R.color.primary))
            tabAiAnalysis.setTextColor(getColor(R.color.text_secondary))
            tabIndicator.animate().translationX(0f).setDuration(200).start()
            contentBankAnalysis.visibility = View.VISIBLE
            contentAiAnalysis.visibility = View.GONE
        }
    }

    private fun loadDetail() {
        val item = getCurrentItem() ?: run {
            Toast.makeText(this, "错题不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 来源标记
        val tvBadge = findViewById<TextView>(R.id.tv_source_badge)
        if (item.isFromBank) {
            tvBadge.text = "来自题库"
            tvBadge.setTextColor(getColor(android.R.color.white))
            tvBadge.setBackgroundResource(R.drawable.bg_primary_chip)
        } else {
            tvBadge.text = "OCR识别"
            tvBadge.setTextColor(getColor(R.color.tag_blue_text))
            tvBadge.setBackgroundResource(R.drawable.bg_source_badge_ocr)
        }

        // 题干
        val tvStem = findViewById<TextView>(R.id.tv_stem)
        tvStem.text = if (item.isFromBank) item.bankStem else item.questionText

        // 截图（默认折叠）
        val layoutImageSection = findViewById<View>(R.id.layout_image_section)
        val headerImage = findViewById<View>(R.id.header_image)
        val ivImageArrow = findViewById<ImageView>(R.id.iv_image_arrow)
        val ivImage = findViewById<ImageView>(R.id.iv_image)
        var imageExpanded = false

        if (item.imagePath.isNotEmpty() && File(item.imagePath).exists()) {
            layoutImageSection.visibility = View.VISIBLE
            try {
                val bmp = BitmapFactory.decodeFile(item.imagePath)
                ivImage.setImageBitmap(bmp)
                ivImage.setOnClickListener { showImageZoomDialog(item.imagePath) }
            } catch (_: Exception) {
                layoutImageSection.visibility = View.GONE
            }

            headerImage.setOnClickListener {
                imageExpanded = !imageExpanded
                ivImage.visibility = if (imageExpanded) View.VISIBLE else View.GONE
                ivImageArrow.animate().rotation(if (imageExpanded) 180f else 0f).setDuration(200).start()
            }
        } else {
            layoutImageSection.visibility = View.GONE
        }

        // 选项（题库题）
        val cardOptions = findViewById<View>(R.id.card_options)
        val layoutOptions = findViewById<LinearLayout>(R.id.layout_options)
        if (item.isFromBank && item.bankOptions.isNotEmpty()) {
            cardOptions.visibility = View.VISIBLE
            layoutOptions.removeAllViews()
            for ((i, opt) in item.bankOptions.withIndex()) {
                val label = "${'A' + i}. $opt"
                val isCorrect = item.bankAnswer.isNotEmpty() && item.bankAnswer.first() == ('A' + i)

                val tv = TextView(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(
                        if (isCorrect) getColor(R.color.correct_green)
                        else getColor(R.color.text_primary)
                    )
                    if (isCorrect) {
                        setTypeface(null, Typeface.BOLD)
                        setBackgroundResource(R.drawable.bg_option_item_correct)
                    } else {
                        setBackgroundResource(R.drawable.bg_option_item)
                    }
                    setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (i > 0) topMargin = dpToPx(8)
                    }
                }
                layoutOptions.addView(tv)
            }
        } else {
            cardOptions.visibility = View.GONE
        }

        // 答案
        val cardAnswer = findViewById<View>(R.id.card_answer)
        val tvAnswer = findViewById<TextView>(R.id.tv_answer)
        if (item.isFromBank && item.bankAnswer.isNotEmpty()) {
            cardAnswer.visibility = View.VISIBLE
            tvAnswer.text = item.bankAnswer
        } else {
            cardAnswer.visibility = View.GONE
        }

        // 解析区域
        val cardAnalysisSection = findViewById<View>(R.id.card_analysis_section)
        val tvAnalysis = findViewById<TextView>(R.id.tv_analysis)
        if (item.isFromBank && item.bankAnalysis.isNotEmpty()) {
            cardAnalysisSection.visibility = View.VISIBLE
            tvAnalysis.text = item.bankAnalysis

            // 获取题目类型
            val moduleName = QuestionBankManager.getQuestionModuleName(item.bankQuestionId)
            currentQuestionType = mapModuleToQuestionType(moduleName)

            // 如果无法识别题型，禁用AI解析
            if (currentQuestionType == null) {
                btnStartAi.isEnabled = false
                btnStartAi.text = "该题型暂不支持AI解析"
                btnStartAi.alpha = 0.5f
            }
        } else {
            cardAnalysisSection.visibility = View.GONE
        }

        // 总结笔记
        updateSummaryDisplay(item)

        // 已总结复选框
        val cb = findViewById<MaterialCheckBox>(R.id.cb_summarized)
        cb.setOnCheckedChangeListener(null)
        cb.isChecked = item.isSummarized
        cb.setOnCheckedChangeListener { _, isChecked ->
            cachedItem = null
            WrongQuestionManager.updateWrongQuestion(this, item.id, isChecked, item.summary)
        }
    }

    private fun updateSummaryDisplay(item: WrongQuestion) {
        val tvSummary = findViewById<TextView>(R.id.tv_summary)
        if (item.summary.isNotEmpty()) {
            tvSummary.text = item.summary
            tvSummary.setTextColor(getColor(R.color.text_primary))
        } else {
            tvSummary.text = "暂无笔记，点击下方按钮添加"
            tvSummary.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun showEditSummaryDialog() {
        val item = getCurrentItem() ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12))
        }
        val et = EditText(this).apply {
            hint = "写下错题原因、考点或解析..."
            setText(item.summary)
            minLines = 4
            gravity = Gravity.TOP
            setBackgroundResource(R.drawable.bg_premium_pill_stroke)
            setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
            setTextColor(getColor(R.color.text_primary))
        }
        container.addView(et)

        AlertDialog.Builder(this)
            .setTitle("写总结笔记")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val note = et.text.toString().trim()
                cachedItem = null
                WrongQuestionManager.updateWrongQuestion(this, item.id, item.isSummarized, note)
                loadDetail()
                Toast.makeText(this, "总结已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除这道错题吗？该操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                WrongQuestionManager.deleteWrongQuestion(this, currentId)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImageZoomDialog(imagePath: String) {
        try {
            val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).create()
            val imgView = ImageView(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BitmapFactory.decodeFile(imagePath))
                setOnClickListener { dialog.dismiss() }
            }
            dialog.setView(imgView)
            dialog.show()
        } catch (_: Exception) {}
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ==================== AI解析功能 ====================

    private fun startAiAnalysis() {
        val questionType = currentQuestionType ?: return
        val item = getCurrentItem() ?: return

        // 显示加载状态
        tvAiPlaceholder.visibility = View.GONE
        btnStartAi.visibility = View.GONE
        layoutAiLoading.visibility = View.VISIBLE
        layoutAiResult.removeAllViews()

        // 初始化模型管理器并获取当前活跃模型配置
        ModelManager.init(this)
        val activeModelId = AppPreferences.getActiveModelId(this)
        val activeModel = ModelManager.get(activeModelId) ?: ModelManager.allModels.firstOrNull()

        val baseUrl = activeModel?.baseUrl ?: AppPreferences.getApiBaseUrl(this)
        val apiKey = activeModel?.apiKey ?: AppPreferences.getApiKey(this)
        val model = activeModel?.model ?: AppPreferences.getApiModel(this)
        val apiType = activeModel?.apiType ?: "openai"
        val thinking = activeModel?.thinkingDefault ?: false
        val thinkingBudget = activeModel?.thinkingBudget ?: 4096

        val useTools = AppPreferences.isToolCallingEnabled(this) && ToolRegistry.hasTools()

        var prompt = if (useTools) {
            getUniversalAgentPrompt()
        } else {
            AppPreferences.getPromptForType(this, questionType)
        }

        // 如果错题来自于题库，则注入题库上下文以确保答案准确性
        if (item.isFromBank) {
            val bankContext = buildString {
                appendLine("=== 题库已收录此题，以下为题库数据（正确答案已确定） ===")
                appendLine()
                appendLine("【题库题目】")
                appendLine(item.bankStem)
                appendLine()
                if (item.bankOptions.isNotEmpty()) {
                    appendLine("【题库选项】")
                    for ((i, opt) in item.bankOptions.withIndex()) {
                        appendLine("${'A' + i}. $opt")
                    }
                    appendLine()
                }
                appendLine("【题库正确答案】${item.bankAnswer}")
                appendLine()
                if (item.bankAnalysis.isNotBlank()) {
                    appendLine("【题库参考解析】")
                    appendLine(item.bankAnalysis)
                    appendLine()
                }
                appendLine("=== 以上为题库数据，请以此为基础进行分析 ===")
                appendLine("特别注意：")
                appendLine("1. 正确答案已确定为 ${item.bankAnswer}，请围绕该答案展开分析，对每个选项逐一说明选或不选的理由。")
                appendLine("2. 请在JSON中额外输出 keywords 数组，列出题目中的3-8个关键词/关键语句（用于在题目中标红高亮）。")
                appendLine()
            }
            prompt = bankContext + prompt
        }

        // 构建用户消息
        val userMessage = buildUserMessage(item)

        val onCompleteCallback = { result: String ->
            runOnUiThread {
                layoutAiLoading.visibility = View.GONE
                renderAiResult(result, questionType)
            }
        }

        val onErrorCallback = { error: String ->
            runOnUiThread {
                layoutAiLoading.visibility = View.GONE
                tvAiPlaceholder.visibility = View.VISIBLE
                btnStartAi.visibility = View.VISIBLE
                Toast.makeText(this, "AI解析失败: $error", Toast.LENGTH_LONG).show()
            }
        }

        if (useTools) {
            val toolsArray = ToolRegistry.toOpenAiToolsArrayForType(questionType)
            OpenAIApiService.analyzeWithTools(
                context = this,
                ocrText = userMessage,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                thinking = thinking,
                userMessage = userMessage,
                apiType = apiType,
                thinkingBudget = thinkingBudget,
                tools = toolsArray,
                onToolCall = { toolName ->
                    android.util.Log.d("WrongQuestionAI", "AI 正在调用工具：$toolName")
                },
                onComplete = onCompleteCallback,
                onError = onErrorCallback
            )
        } else {
            OpenAIApiService.analyzeText(
                ocrText = userMessage,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                thinking = thinking,
                userMessage = userMessage,
                apiType = apiType,
                thinkingBudget = thinkingBudget,
                onComplete = onCompleteCallback,
                onError = onErrorCallback
            )
        }
    }

    private fun getUniversalAgentPrompt(): String {
        return """
            你是一个顶级公务员行测考试解题专家（智能助教）。
            你的任务是对用户给出的行测题目（可能来自OCR识别，包含部分错别字或格式混乱）进行深度智能分析，并输出完美契合题型的结构化 JSON 解析。

            【⚠️核心工作流指令（非常重要）】
            为了保证解题的权威性和极致准确性，你被赋予了以下本地工具库进行「三层渐进式披露」加载：
            1. `get_solving_skill(question_type, sub_type)`: 获取某大题型下细分子题型的核心解题方法、选择原则与避坑高频陷阱（第二层：按需加载方法论）。
            2. `get_typical_special_rule(term)`: 获取各行测模块在高频专项考点、被定义概念、典型常识误区或公式上的标准解析要素与极限排除法则（第三层：按需加载特定考点深度细则）。

            【📢前置环节说明】：
            系统内置了题库前置检索环节。如果题目已被收录，系统会在本 Prompt 的头部以 `=== 题库已收录此题 ===` 块的形式自动注入经过人工校核 of 官方标准题干、选项、正确答案及官方解析。
            如果存在前置题库数据，代表正确答案已确凿无疑，你必须以该标准题库数据为基准展开分析，且保证最终输出的 `correct_answer` 与题库给出的正确答案完全一致。

            【执行步骤】：
            第一步：分析用户提供的题目文本，提取核心题干，识别它属于行测五大模块中的哪个【大题型】。
            第二步：【调用工具 - 第二层】—— 你必须首先调用 `get_solving_skill` 获取对应细分【小题型】的专属方法。
            第三步：【调用工具 - 第三层】—— 若遇到以下高频特征考点，你必须紧接着调用 `get_typical_special_rule(term)` 调取专属避坑细则与公式手册（严防凭感觉做题）：
                - 如果当前是「定义判断」题，且题干的被定义词属于高频典型词（如“体验式采访”、“隐性饥饿”、“无感审批”、“最大最小准则”等），调用 `get_typical_special_rule(term = "被定义词")`；
                - 如果当前是「类比推理」题，且涉及“鳄鱼/鲸鱼等生物常识判断”（非种属误判）、“9大命名方式”、“物理与化学变化变化判定”或“并列之矛盾与反对区别”，调用 `get_typical_special_rule(term = "非种属误判" 或 "9大命名方式" 或 "物理变化与化学变化" 或 "并列之矛盾与反对")`；
                - 如果当前是「逻辑判断」题，且考查“真假推理矛盾关系”或“翻译推理公式连锁推理”，调用 `get_typical_special_rule(term = "真假矛盾公式表" 或 "翻译推理公式秘籍")`；
                - 如果当前是「逻辑填空」题，且考查“公文时政搭配特点”或“双刃剑/紧箍咒等比喻意境”，调用 `get_typical_special_rule(term = "黄金公文热词搭配" 或 "常考比喻与本体隐喻")`。
            第四步：在工具返回你的专属技巧后，仔细阅读，将这些策略严密运用到题目解析中，对题目和每个选项进行极其专业的剖析。
            Fifth步：根据题目的最终判定题型，输出对应的结构化 JSON，严禁掺杂 Markdown 代码块包裹以外的任何闲聊文字。

            【输出 JSON 格式规范】
            请根据你识别出的题目类别，严格输出以下 7 种 JSON 结构之一：

            1. [片段阅读] 格式：
            {
              "question_type": "片段阅读",
              "passage_type": "中心理解" | "细节判断" | "下文推断" | "标题拟定",
              "structure_type": ["转折结构", "因果结构", "递进结构", "并列结构", "总分/分总结构"],
              "question": "清洗后的干净题干",
              "correct_answer": "A",
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "选项详细理由，指出如何对应原文的对策句或主题词"},
                {"option": "B", "correct": false, "reason": "选项详细错误原因，标明是属于「细节非主旨/无中生有/偷换概念/偏离核心」中的哪种"}
              ],
              "logical_labels": [
                {"label": "关联词/逻辑词", "reason": "该逻辑词在文段行文脉络中的具体承接/转折作用说明"}
              ],
              "analysis": "一句话核心秒杀解析",
              "keywords": ["关键词1", "关键词2"]
            }

            2. [逻辑填空] 格式（用 " ________ " 作为空格占位符）：
            {
              "question_type": "逻辑填空",
              "skill_tags": ["词义辨析", "搭配用法", "成语填空"],
              "question": "含有 ________ 的完整题干",
              "core_meaning": "文段的核心主旨意思",
              "breakthrough": "核心破题点（如解释性关系或反对关系线索）",
              "correct_answer": "A",
              "blanks_analysis": [
                {
                  "position": "空格1",
                  "context_hint": "该空的语境线索或修饰关系",
                  "answer": "正确填入的词",
                  "summary": "该空的选择小结",
                  "candidates": [
                    {"word": "词语A", "correct": true, "dimension": "词义轻重/搭配对象", "reason": "正确理由"},
                    {"word": "词语B", "correct": false, "dimension": "词义轻重/褒贬色彩", "reason": "排除理由"}
                  ]
                }
              ],
              "verification": "带回验证句意是否顺畅",
              "accumulation": "本题相关的成语或核心实词积累",
              "keywords": ["关键词1", "关键词2"]
            }

            3. [语句表达] 格式：
            {
              "question_type": "语句表达",
              "question_subtype": "语句填入题" | "语句排序题",
              "question": "干净题干",
              "correct_answer": "A",
              "structure_analysis": "文段整体行文脉络与逻辑层级分析",
              "key_clues": ["排序或填入的绝对捆绑线索1", "绝对捆绑线索2"],
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "正确理由"},
                {"option": "B", "correct": false, "reason": "排除理由"}
              ],
              "pitfall": "容易被语感误导的经典易错点",
              "keywords": ["关键词1", "关键词2"]
            }

            4. [定义判断] 格式：
            {
              "question_type": "定义判断",
              "ask_type": "属于" | "不属于",
              "definition_text": "定义原文",
              "key_elements": ["核心要素1(如主体)", "核心要素2(如手段)", "核心要素3(如结果)"],
              "question": "题干设问和选项前情",
              "correct_answer": "A",
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "完全契合所有关键定义要素的详细剖析"},
                {"option": "B", "correct": false, "reason": "缺少哪项核心要素的详细说明"}
              ],
              "analysis": "一句话判定精要",
              "keywords": ["关键词1", "关键词2"]
            }

            5. [类比推理] 格式：
            {
              "question_type": "类比推理",
              "word_pair": "题干词组(如：微风：狂风)",
              "relationship_type": "核心关系(如：语义关系-程度递进)",
              "relationship_analysis": "题干两个词的深层逻辑关系分析",
              "sentence": "题干词组造句验证",
              "technique": {
                "level1": "一级逻辑关系",
                "level2": "二级辨析关系",
                "key_point": "区分干扰项 of 核判断点"
              },
              "options_table": [
                {"option": "A. 词1：词2", "relationship": "选项关系", "match": true, "reason": "比对说明"},
                {"option": "B. 词1：词2", "relationship": "选项关系", "match": false, "reason": "比对说明"}
              ],
              "secondary_analysis": "详细的一二级二级辨析对比过程",
              "correct_answer": "A",
              "pitfall": "经典易错方向陷阱",
              "summary": "解此类题的核心总结",
              "analysis": "秒杀解析",
              "keywords": ["关键词1", "关键词2"]
            }

            6. [逻辑判断] 格式：
            {
              "question_type": "逻辑判断",
              "reasoning_type": "论证削弱" | "论证加强" | "翻译推理" | "真假推理" | "分析推理",
              "argument_structure": "论点与论据的推出式结构梳理",
              "diagram_type": "逻辑图描述(可选)",
              "logical_chain": [
                {"step": 1, "premise": "前提/论据", "deduction": "具体推导/强化/削弱逻辑"}
              ],
              "question": "干净题干",
              "correct_answer": "A",
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "如何起到最强加强/削弱/翻译契合作用"},
                {"option": "B", "correct": false, "reason": "无关项/加强程度过弱/诉诸无知等排除原因"}
              ],
              "analysis": "核心推导小结",
              "keywords": ["关键词1", "keywords2"]
            }

            7. [图形推理] 格式：
            {
              "question_type": "图形推理",
              "pattern_type": "位置变化" | "样式变化" | "数量规律" | "属性规律" | "空间重构",
              "rule_description": "图形整体核心变化规律精要描述",
              "visual_analysis": "视觉细节剖析",
              "correct_answer": "A",
              "options_analysis": [
                {"option": "A", "correct": true, "reason": "规律完全契合"},
                {"option": "B", "correct": false, "reason": "不符合什么移动步长或翻转规律"}
              ],
              "analysis": "秒杀解析",
              "keywords": ["关键词1", "关键词2"]
            }
        """.trimIndent()
    }

    private fun renderAiResult(result: String, questionType: QuestionType) {
        layoutAiResult.removeAllViews()
        layoutAiResult.visibility = View.VISIBLE

        try {
            val json = JSONObject(result)

            // 正确答案
            val correctAnswer = json.optString("correct_answer", "")
            if (correctAnswer.isNotEmpty()) {
                addAnswerCard(correctAnswer)
            }

            // 根据题型渲染内容
            when (questionType) {
                QuestionType.PIAN_DUAN_YUE_DU -> renderPianDuan(json)
                QuestionType.LUO_JI_TIAN_KONG -> renderTianKong(json)
                QuestionType.YU_JU_BIAO_DA -> renderYuJu(json)
                QuestionType.TU_XING_TUI_LI -> renderTuXing(json)
                QuestionType.DING_YI_PAN_DUAN -> renderDingYi(json)
                QuestionType.LEI_BI_TUI_LI -> renderLeiBi(json)
                QuestionType.LUO_JI_PAN_DUAN -> renderLuoJi(json)
            }
        } catch (e: Exception) {
            // JSON解析失败，显示原始文本
            addTextContent(result)
        }
    }

    private fun addAnswerCard(answer: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_correct_answer_card)
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(12) }
        }

        val checkmark = TextView(this).apply {
            text = "✓"
            setTextColor(getColor(R.color.correct_green))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        card.addView(checkmark)

        val label = TextView(this).apply {
            text = "正确答案"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
        }
        card.addView(label)

        val answerTv = TextView(this).apply {
            this.text = answer
            setTextColor(getColor(R.color.correct_green))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        card.addView(answerTv)

        layoutAiResult.addView(card)
    }

    private fun renderPianDuan(json: JSONObject) {
        json.optString("question_type").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("题型", it)
        }
        json.optString("passage_type").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("文段类型", it)
        }
        json.optJSONArray("structure_type")?.let { arr ->
            if (arr.length() > 0) {
                val types = (0 until arr.length()).map { arr.getString(it) }
                addInfoRow("行文结构", types.joinToString("、"))
            }
        }
        renderOptionsAnalysis(json)
        renderLogicalLabels(json)
    }

    private fun renderTianKong(json: JSONObject) {
        json.optString("core_meaning").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("核心意思", it)
        }
        json.optString("breakthrough").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("突破口", it)
        }
        json.optString("verification").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("代回验证", it)
        }
        json.optString("accumulation").takeIf { it.isNotEmpty() }?.let {
            addSectionTitle("本题积累")
            addTextContent(it)
        }
    }

    private fun renderYuJu(json: JSONObject) {
        json.optString("question_subtype").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("子题型", it)
        }
        json.optString("structure_analysis").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("结构分析", it)
        }
        json.optJSONArray("key_clues")?.let { arr ->
            if (arr.length() > 0) {
                addSectionTitle("关键线索")
                for (i in 0 until arr.length()) {
                    addBulletPoint(arr.getString(i))
                }
            }
        }
        renderOptionsAnalysis(json)
        json.optString("pitfall").takeIf { it.isNotEmpty() }?.let {
            addSectionTitle("易错点")
            addTextContent(it)
        }
    }

    private fun renderTuXing(json: JSONObject) {
        json.optString("pattern_type").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("规律类型", it)
        }
        json.optString("rule_description").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("规律描述", it)
        }
        json.optString("visual_analysis").takeIf { it.isNotEmpty() }?.let {
            addSectionTitle("推理过程")
            addTextContent(it)
        }
        renderOptionsAnalysis(json)
    }

    private fun renderDingYi(json: JSONObject) {
        json.optString("ask_type").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("问法", it)
        }
        json.optString("definition_text").takeIf { it.isNotEmpty() }?.let {
            addSectionTitle("定义原文")
            addTextContent(it)
        }
        json.optJSONArray("key_elements")?.let { arr ->
            if (arr.length() > 0) {
                addSectionTitle("关键要素")
                for (i in 0 until arr.length()) {
                    addBulletPoint(arr.getString(i))
                }
            }
        }
        renderOptionsAnalysis(json)
    }

    private fun renderLeiBi(json: JSONObject) {
        json.optString("word_pair").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("题干词对", it)
        }
        json.optString("relationship_type").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("关系类型", it)
        }
        json.optString("sentence").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("造句验证", it)
        }
        renderOptionsAnalysis(json)
        json.optString("pitfall").takeIf { it.isNotEmpty() }?.let {
            addSectionTitle("易错点")
            addTextContent(it)
        }
        json.optString("summary").takeIf { it.isNotEmpty() }?.let {
            addSectionTitle("总结")
            addTextContent(it)
        }
    }

    private fun renderLuoJi(json: JSONObject) {
        json.optString("reasoning_type").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("推理类型", it)
        }
        json.optString("argument_structure").takeIf { it.isNotEmpty() }?.let {
            addInfoRow("论证结构", it)
        }
        renderOptionsAnalysis(json)
        json.optString("analysis").takeIf { it.isNotEmpty() }?.let {
            addSectionTitle("整体解析")
            addTextContent(it)
        }
    }

    private fun renderOptionsAnalysis(json: JSONObject) {
        val optionsArr = json.optJSONArray("options_analysis") ?: return
        if (optionsArr.length() == 0) return

        addSectionTitle("选项解析")

        for (i in 0 until optionsArr.length()) {
            val opt = optionsArr.getJSONObject(i)
            val isCorrect = opt.optBoolean("correct", false)
            val optionText = opt.optString("option", "")
            val reason = opt.optString("reason", "")

            val itemView = layoutInflater.inflate(R.layout.item_option_analysis, layoutAiResult, false)
            val tvOption = itemView.findViewById<TextView>(R.id.tv_option)
            val tvReason = itemView.findViewById<TextView>(R.id.tv_reason)
            val indicator = itemView.findViewById<View>(R.id.correct_indicator)

            tvOption.text = optionText
            tvOption.setTextColor(getColor(if (isCorrect) R.color.correct_green else R.color.text_primary))
            if (isCorrect) tvOption.setTypeface(null, Typeface.BOLD)

            tvReason.text = reason
            indicator.setBackgroundColor(getColor(if (isCorrect) R.color.correct_green else R.color.border_light))

            layoutAiResult.addView(itemView)
        }
    }

    private fun renderLogicalLabels(json: JSONObject) {
        val labelsArr = json.optJSONArray("logical_labels") ?: return
        if (labelsArr.length() == 0) return

        addSectionTitle("逻辑结构")

        for (i in 0 until labelsArr.length()) {
            val item = labelsArr.getJSONObject(i)
            val unit = item.optString("unit", "")
            val label = item.optString("label", "")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(4) }
            }

            val unitTv = TextView(this).apply {
                this.text = unit
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
            }
            row.addView(unitTv)

            val labelTv = TextView(this).apply {
                this.text = label
                textSize = 12f
                setTextColor(getColor(R.color.primary))
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_tag_blue)
                setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(labelTv)

            layoutAiResult.addView(row)
        }
    }

    private fun addInfoRow(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val labelTv = TextView(this).apply {
            this.text = label
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(12) }
        }
        row.addView(labelTv)

        val valueTv = TextView(this).apply {
            this.text = value
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(valueTv)

        layoutAiResult.addView(row)
    }

    private fun addSectionTitle(title: String) {
        val tv = TextView(this).apply {
            this.text = title
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dpToPx(16), 0, dpToPx(8))
        }
        layoutAiResult.addView(tv)
    }

    private fun addTextContent(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            setLineSpacing(0f, 1.4f)
        }
        layoutAiResult.addView(tv)
    }

    private fun addBulletPoint(text: String) {
        val tv = TextView(this).apply {
            this.text = "• $text"
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dpToPx(4), 0, dpToPx(4))
        }
        layoutAiResult.addView(tv)
    }

    private fun mapModuleToQuestionType(moduleName: String?): QuestionType? {
        if (moduleName == null) return null
        return when (moduleName) {
            "片段阅读" -> QuestionType.PIAN_DUAN_YUE_DU
            "逻辑填空" -> QuestionType.LUO_JI_TIAN_KONG
            "语句表达" -> QuestionType.YU_JU_BIAO_DA
            "图形推理" -> QuestionType.TU_XING_TUI_LI
            "定义判断" -> QuestionType.DING_YI_PAN_DUAN
            "类比推理" -> QuestionType.LEI_BI_TUI_LI
            "逻辑判断" -> QuestionType.LUO_JI_PAN_DUAN
            else -> null
        }
    }

    private fun buildUserMessage(item: WrongQuestion): String {
        val sb = StringBuilder()
        sb.appendLine("题目：")
        sb.appendLine(if (item.isFromBank) item.bankStem else item.questionText)
        sb.appendLine()
        if (item.bankOptions.isNotEmpty()) {
            sb.appendLine("选项：")
            for ((i, opt) in item.bankOptions.withIndex()) {
                sb.appendLine("${'A' + i}. $opt")
            }
            sb.appendLine()
        }
        if (item.bankAnswer.isNotEmpty()) {
            sb.appendLine("正确答案：${item.bankAnswer}")
        }
        return sb.toString()
    }

    // ==================== 导出长图功能 ====================

    private fun exportAsImage() {
        val item = getCurrentItem() ?: return

        Toast.makeText(this, "正在生成长图...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val bitmap = generateExportBitmap(item)
                val uri = saveBitmapToGallery(bitmap)
                runOnUiThread {
                    if (uri != null) {
                        shareImage(uri)
                    } else {
                        Toast.makeText(this, "保存图片失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun generateExportBitmap(item: WrongQuestion): Bitmap {
        val density = resources.displayMetrics.density
        val widthPx = (360 * density).toInt()
        val padding = (20 * density).toInt()
        val cardPadding = (16 * density).toInt()
        val cardMargin = (12 * density).toInt()
        val cornerRadius = 12 * density

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14 * density
            color = Color.parseColor("#1A1A1A")
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * density
            color = Color.parseColor("#666666")
            typeface = Typeface.DEFAULT_BOLD
        }
        val answerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18 * density
            color = Color.parseColor("#2E7D32")
            typeface = Typeface.DEFAULT_BOLD
        }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18 * density
            color = Color.parseColor("#1A1A1A")
            typeface = Typeface.DEFAULT_BOLD
        }

        val lineHeight = 20 * density
        val maxWidth = (widthPx - padding * 2 - cardPadding * 2).toFloat()

        // 计算总高度
        var totalHeight = padding.toFloat()

        // 标题
        totalHeight += 30 * density
        totalHeight += cardMargin

        // 题干
        totalHeight += cardPadding + 20 * density
        val stemText = if (item.isFromBank) item.bankStem else item.questionText
        val stemLines = wrapText(stemText, textPaint, maxWidth)
        totalHeight += stemLines.size * lineHeight + cardPadding
        totalHeight += cardMargin

        // 选项
        if (item.isFromBank && item.bankOptions.isNotEmpty()) {
            totalHeight += cardPadding + 20 * density
            for (opt in item.bankOptions) {
                val optLines = wrapText(opt, textPaint, maxWidth - 20 * density)
                totalHeight += optLines.size * lineHeight + 10 * density
            }
            totalHeight += cardPadding
            totalHeight += cardMargin
        }

        // 正确答案
        if (item.isFromBank && item.bankAnswer.isNotEmpty()) {
            totalHeight += cardPadding + 20 * density + cardPadding
            totalHeight += cardMargin
        }

        // 解析
        if (item.isFromBank && item.bankAnalysis.isNotEmpty()) {
            totalHeight += cardPadding + 20 * density
            val analysisLines = wrapText(item.bankAnalysis, textPaint, maxWidth)
            totalHeight += analysisLines.size * lineHeight + cardPadding
            totalHeight += cardMargin
        }

        // 水印
        totalHeight += 40 * density
        totalHeight += padding

        // 创建 Bitmap
        val bitmap = Bitmap.createBitmap(widthPx, totalHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = padding.toFloat()

        // 绘制标题
        canvas.drawText("错题本", padding.toFloat(), y + 20 * density, headerPaint)
        y += 30 * density + cardMargin

        // 绘制题干卡片
        val stemCardHeight = cardPadding + 20 * density + stemLines.size * lineHeight + cardPadding
        drawRoundRect(canvas, padding.toFloat(), y, (widthPx - padding).toFloat(),
            y + stemCardHeight, cornerRadius, Color.parseColor("#F5F5F5"))
        y += cardPadding
        canvas.drawText("题目", (padding + cardPadding).toFloat(), y + 16 * density, titlePaint)
        y += 24 * density
        for (line in stemLines) {
            canvas.drawText(line, (padding + cardPadding).toFloat(), y + textPaint.textSize, textPaint)
            y += lineHeight
        }
        y += cardPadding + cardMargin

        // 绘制选项
        if (item.isFromBank && item.bankOptions.isNotEmpty()) {
            var optionsHeight = cardPadding + 20 * density
            for (opt in item.bankOptions) {
                val lines = wrapText(opt, textPaint, maxWidth - 20 * density)
                optionsHeight += lines.size * lineHeight + 10 * density
            }
            optionsHeight += cardPadding
            drawRoundRect(canvas, padding.toFloat(), y, (widthPx - padding).toFloat(),
                y + optionsHeight, cornerRadius, Color.parseColor("#F5F5F5"))
            y += cardPadding
            canvas.drawText("选项", (padding + cardPadding).toFloat(), y + 16 * density, titlePaint)
            y += 24 * density
            for ((i, opt) in item.bankOptions.withIndex()) {
                val label = "${'A' + i}. $opt"
                val isCorrect = item.bankAnswer.isNotEmpty() && item.bankAnswer.first() == ('A' + i)
                val optPaint = Paint(textPaint).apply {
                    if (isCorrect) {
                        color = Color.parseColor("#2E7D32")
                        typeface = Typeface.DEFAULT_BOLD
                    }
                }
                val optLines = wrapText(label, optPaint, maxWidth - 20 * density)
                for (line in optLines) {
                    canvas.drawText(line, (padding + cardPadding + 10 * density),
                        y + optPaint.textSize, optPaint)
                    y += lineHeight
                }
                y += 10 * density
            }
            y += cardPadding + cardMargin
        }

        // 绘制正确答案
        if (item.isFromBank && item.bankAnswer.isNotEmpty()) {
            val answerHeight = cardPadding + 20 * density + cardPadding + 10 * density
            drawRoundRect(canvas, padding.toFloat(), y, (widthPx - padding).toFloat(),
                y + answerHeight, cornerRadius, Color.parseColor("#E8F5E9"))
            y += cardPadding
            canvas.drawText("正确答案", (padding + cardPadding).toFloat(), y + 16 * density, titlePaint)
            canvas.drawText(item.bankAnswer, (padding + cardPadding + 80 * density),
                y + 20 * density, answerPaint)
            y += cardPadding + 10 * density + cardMargin
        }

        // 绘制解析
        if (item.isFromBank && item.bankAnalysis.isNotEmpty()) {
            val analysisLines = wrapText(item.bankAnalysis, textPaint, maxWidth)
            val analysisHeight = cardPadding + 20 * density + analysisLines.size * lineHeight + cardPadding
            drawRoundRect(canvas, padding.toFloat(), y, (widthPx - padding).toFloat(),
                y + analysisHeight, cornerRadius, Color.parseColor("#FFF3E0"))
            y += cardPadding
            canvas.drawText("题库解析", (padding + cardPadding).toFloat(), y + 16 * density, titlePaint)
            y += 24 * density
            for (line in analysisLines) {
                canvas.drawText(line, (padding + cardPadding).toFloat(), y + textPaint.textSize, textPaint)
                y += lineHeight
            }
            y += cardPadding + cardMargin
        }

        // 水印
        val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12 * density
            color = Color.parseColor("#CCCCCC")
        }
        canvas.drawText("AI伴学 · 错题本", padding.toFloat(), y + 16 * density, watermarkPaint)

        return bitmap
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val paragraphs = text.split("\n")
        for (paragraph in paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                val breakPos = paint.breakText(remaining, true, maxWidth, null)
                if (breakPos >= remaining.length) {
                    lines.add(remaining)
                    break
                }
                var cutPos = breakPos
                for (i in breakPos - 1 downTo breakPos / 2) {
                    if (remaining[i] in "，。、；：！？,.;:!?" || remaining[i] == ' ') {
                        cutPos = i + 1
                        break
                    }
                }
                lines.add(remaining.substring(0, cutPos))
                remaining = remaining.substring(cutPos)
            }
        }
        return lines
    }

    private fun drawRoundRect(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
                               radius: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val fileName = "wrong_question_${System.currentTimeMillis()}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AI伴学")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
                }
            }
            uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AI伴学")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            Uri.fromFile(file)
        }
    }

    private fun shareImage(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享错题"))
    }
}
