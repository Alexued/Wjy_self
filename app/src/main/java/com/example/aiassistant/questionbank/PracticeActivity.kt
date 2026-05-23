package com.example.aiassistant.questionbank

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.example.aiassistant.R
import com.google.android.material.button.MaterialButton
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class PracticeActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvProgress: TextView
    private lateinit var cardMaterial: CardView
    private lateinit var wvMaterial: WebView
    private lateinit var tvKnowledgePoint: TextView
    private lateinit var wvStem: WebView
    private lateinit var layoutStemImages: android.widget.LinearLayout
    private lateinit var layoutOptions: LinearLayout
    private lateinit var cardAnswer: CardView
    private lateinit var tvAnswer: TextView
    private lateinit var tvRate: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvAnalysis: TextView
    private lateinit var btnShowAnswer: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton

    private var moduleId: String = ""
    private var moduleName: String = ""
    private var questionCount: Int = 15
    private var rateMin: Int = 0
    private var rateMax: Int = 100
    private var questions: List<Question> = emptyList()
    private var currentIndex: Int = 0
    private var selectedOption: Int = -1
    private var isAnswerShown: Boolean = false
    private var correctCount: Int = 0
    private var wrongCount: Int = 0

    // 自定义浮动工具栏 (PopupWindow)
    private var selectionPopup: PopupWindow? = null
    private var lastSelectedText = ""
    private var suppressPoll = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private val trustAllCerts = arrayOf<javax.net.ssl.X509TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        private val imageClient: OkHttpClient by lazy {
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0])
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }

    private var destroyed = false
    private val readyListener: () -> Unit = { loadData() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_practice)

        moduleId = intent.getStringExtra("module_id") ?: ""
        moduleName = intent.getStringExtra("module_name") ?: ""
        questionCount = intent.getIntExtra("question_count", 15)
        rateMin = intent.getIntExtra("rate_min", 0)
        rateMax = intent.getIntExtra("rate_max", 100)

        initViews()
        loadData()
        setupListeners()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initViews() {
        tvTitle = findViewById(R.id.tv_title)
        tvProgress = findViewById(R.id.tv_progress)
        cardMaterial = findViewById(R.id.card_material)
        wvMaterial = findViewById(R.id.wv_material)
        tvKnowledgePoint = findViewById(R.id.tv_knowledge_point)
        wvStem = findViewById(R.id.wv_stem)
        layoutStemImages = findViewById(R.id.layout_stem_images)
        layoutOptions = findViewById(R.id.layout_options)
        cardAnswer = findViewById(R.id.card_answer)
        tvAnswer = findViewById(R.id.tv_answer)
        tvRate = findViewById(R.id.tv_rate)
        tvSource = findViewById(R.id.tv_source)
        tvAnalysis = findViewById(R.id.tv_analysis)
        btnShowAnswer = findViewById(R.id.btn_show_answer)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)

        tvTitle.text = moduleName

        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }

        // 配置 WebView
        setupWebView(wvStem)
        setupWebView(wvMaterial)

        setupTextSelection()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            defaultTextEncodingName = "UTF-8"
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = true
        }
        webView.setBackgroundColor(0)
        webView.isNestedScrollingEnabled = false
    }

    /**
     * 在 WebView 中渲染 HTML，自动将 <img> 标签中的 LaTeX 替换为 KaTeX 渲染
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun renderInWebView(webView: WebView, html: String) {
        val katexCss = assets.open("katex/katex.min.css").bufferedReader().readText()
        val katexJs = assets.open("katex/katex.min.js").bufferedReader().readText()
        val autoRenderJs = assets.open("katex/auto-render.min.js").bufferedReader().readText()

        // 将协议相对 URL //xxx 转换为 https://xxx，因为基础 URL 是 file:/// 会导致解析错误
        val fixedHtml = html.replace("src=\"//", "src=\"https://").replace("src='//", "src='https://")

        val fullHtml = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
$katexCss
body {
    font-family: -apple-system, "Microsoft YaHei", sans-serif;
    font-size: 16px;
    color: #212121;
    line-height: 1.6;
    margin: 0;
    padding: 8px;
    word-wrap: break-word;
    overflow-wrap: break-word;
}
img { max-width: 100%; height: auto; display: inline; vertical-align: middle; }
.katex { font-size: 1.05em; }
</style>
</head>
<body>
$fixedHtml
<script>
$katexJs
$autoRenderJs
try {
    renderMathInElement(document.body, {
        delimiters: [{left: '$$', right: '$$', display: true}, {left: '$', right: '$', display: false}],
        throwOnError: false
    });
} catch(e) {}
</script>
</body>
</html>""".trimIndent()

        webView.loadDataWithBaseURL("file:///android_asset/", fullHtml, "text/html", "UTF-8", null)
    }

    /**
     * 静态方法：检查 HTML 是否包含公式图片
     */
    private fun hasFormulas(html: String): Boolean {
        return html.contains("formulas") || html.contains("latex=") || html.contains("$")
    }

    private fun setupTextSelection() {
        // WebView 内的文字选择由 WebView 自身处理
        // 这里只设置非 WebView 的文字选择
    }

    private fun loadData() {
        if (!QuestionBankManager.isLoaded()) {
            Toast.makeText(this, "题库加载中，请稍候...", Toast.LENGTH_SHORT).show()
            QuestionBankManager.addOnReadyListener(readyListener)
            return
        }

        questions = QuestionBankManager.getQuestionsByRateRange(moduleId, rateMin, rateMax, questionCount)

        if (questions.isEmpty()) {
            Toast.makeText(this, "没有符合条件的题目", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showQuestion(0)
    }

    private fun setupListeners() {
        btnPrev.setOnClickListener {
            if (currentIndex > 0) showQuestion(currentIndex - 1)
        }
        btnNext.setOnClickListener {
            if (currentIndex < questions.size - 1) {
                showQuestion(currentIndex + 1)
            } else {
                showEndingDialog()
            }
        }
    }

    private fun showQuestion(index: Int) {
        currentIndex = index
        selectedOption = -1
        isAnswerShown = false

        selectionPopup?.dismiss()
        selectionPopup = null
        lastSelectedText = ""

        val question = questions[index]

        // 材料区域
        if (question.materialContent.isNotEmpty()) {
            cardMaterial.visibility = View.VISIBLE
            if (hasFormulas(question.materialContent)) {
                wvMaterial.visibility = View.VISIBLE
                renderInWebView(wvMaterial, question.materialContent)
            } else {
                wvMaterial.visibility = View.GONE
                // 纯文本材料用隐藏的 WebView 渲染（保持一致性）
                wvMaterial.visibility = View.VISIBLE
                renderInWebView(wvMaterial, question.materialContent)
            }
        } else {
            cardMaterial.visibility = View.GONE
        }

        if (question.knowledgePoint.isNotEmpty()) {
            tvKnowledgePoint.visibility = View.VISIBLE
            tvKnowledgePoint.text = question.knowledgePoint
        } else {
            tvKnowledgePoint.visibility = View.GONE
        }

        // 题干 - 有 HTML 时用 WebView 渲染，纯文本 + 图片也用 WebView
        layoutStemImages.removeAllViews()
        layoutStemImages.visibility = View.GONE
        if (question.stemHtml.isNotEmpty()) {
            wvStem.visibility = View.VISIBLE
            renderInWebView(wvStem, question.stemHtml)
        } else {
            // 纯文本 + 图片：构建简单 HTML，不用 KaTeX
            val stemText = formatBlanks(question.stem)
            val imageHtml = question.titleImages
                .filter { !it.contains("formulas") && !it.contains("latex=") }
                .joinToString("") { """<img src="$it" style="max-width:100%;height:auto;margin:8px 0;">""" }
            val simpleHtml = """
                <html><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>body{font-family:sans-serif;font-size:16px;color:#212121;line-height:1.6;margin:0;padding:8px;}
                img{max-width:100%;height:auto;display:block;margin:8px 0;}</style>
                </head><body><p>$stemText</p>$imageHtml</body></html>
            """.trimIndent()
            wvStem.visibility = View.VISIBLE
            wvStem.loadDataWithBaseURL("https://fb.fenbike.cn/", simpleHtml, "text/html", "UTF-8", null)
        }

        showOptions(question.options)

        cardAnswer.visibility = View.GONE
        btnShowAnswer.visibility = View.VISIBLE
        btnShowAnswer.text = "显示答案"
        btnShowAnswer.isEnabled = true

        updateProgress()

        btnPrev.isEnabled = index > 0
        if (index == questions.size - 1) {
            btnNext.text = "完成训练"
        } else {
            btnNext.text = "下一题"
        }
    }

    private fun formatBlanks(text: String): String {
        return text.replace(Regex("[\\s\\xa0　]{3,}")) { match ->
            "_".repeat(match.value.length)
        }
    }

    private fun showOptions(options: List<QuestionOption>) {
        layoutOptions.removeAllViews()
        val labels = listOf("A", "B", "C", "D", "E", "F", "G", "H")

        for ((i, option) in options.withIndex()) {
            val optionView = LayoutInflater.from(this)
                .inflate(R.layout.item_option, layoutOptions, false)

            val tvLabel = optionView.findViewById<TextView>(R.id.tv_option_label)
            val tvText = optionView.findViewById<TextView>(R.id.tv_option_text)
            val ivImage = optionView.findViewById<ImageView>(R.id.iv_option_image)

            tvLabel.text = labels[i]

            // 优先使用HTML格式
            if (option.html.isNotEmpty()) {
                if (hasFormulas(option.html)) {
                    // 选项有公式：用 WebView 渲染
                    tvText.visibility = View.GONE
                    val optionWv = WebView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        setBackgroundColor(0)
                    }
                    setupWebView(optionWv)
                    // 插入到 tvText 之后
                    val parent = tvText.parent as android.view.ViewGroup
                    val idx = parent.indexOfChild(tvText)
                    parent.addView(optionWv, idx)
                    renderInWebView(optionWv, "<p>${option.html}</p>")
                } else {
                    tvText.visibility = View.VISIBLE
                    tvText.text = android.text.Html.fromHtml(option.html, android.text.Html.FROM_HTML_MODE_COMPACT)
                }
            } else if (option.text.isNotEmpty()) {
                tvText.visibility = View.VISIBLE
                tvText.text = option.text
            } else {
                tvText.visibility = View.GONE
            }

            if (option.images.isNotEmpty()) {
                ivImage.visibility = View.VISIBLE
                ivImage.setImageResource(android.R.drawable.ic_menu_gallery)
                loadImage(option.images[0], ivImage)
            } else {
                ivImage.visibility = View.GONE
            }

            if (option.html.isEmpty() && option.text.isEmpty() && option.images.isEmpty()) {
                tvText.visibility = View.VISIBLE
                tvText.text = "(图片加载中...)"
                tvText.setTextColor(resources.getColor(R.color.text_secondary, null))
            }

            optionView.setOnClickListener {
                if (!isAnswerShown) selectOption(i)
            }

            layoutOptions.addView(optionView)
        }
    }

    private fun loadImage(url: String, imageView: ImageView, isStemImage: Boolean = false, retryCount: Int = 0) {
        if (destroyed) return
        val finalUrl = if (url.contains("fontSize=") && url.contains("formulas")) {
            url.replace(Regex("fontSize=\\d+"), "fontSize=40")
        } else url
        val request = Request.Builder().url(finalUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://www.fenbike.cn/")
            .build()
        imageClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (destroyed) return
                if (retryCount < 2) {
                    handler.postDelayed({ loadImage(url, imageView, isStemImage, retryCount + 1) }, 1000L * (retryCount + 1))
                } else {
                    runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        if (isStemImage) {
                            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                            imageView.visibility = View.VISIBLE
                        } else {
                            imageView.visibility = View.GONE
                        }
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (destroyed) { response.close(); return }
                if (!response.isSuccessful) {
                    if (retryCount < 2) {
                        handler.postDelayed({ loadImage(url, imageView, isStemImage, retryCount + 1) }, 1000L * (retryCount + 1))
                    } else {
                        runOnUiThread {
                            if (isStemImage) {
                                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                                imageView.visibility = View.VISIBLE
                            } else {
                                imageView.visibility = View.GONE
                            }
                        }
                    }
                    return
                }

                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

                    val maxWidth = if (isStemImage) resources.displayMetrics.widthPixels
                                   else resources.displayMetrics.widthPixels * 3 / 4
                    val maxHeight = if (isStemImage) (200 * resources.displayMetrics.density).toInt()
                                    else (100 * resources.displayMetrics.density).toInt()
                    var sampleSize = 1
                    while (opts.outWidth / sampleSize > maxWidth || opts.outHeight / sampleSize > maxHeight) {
                        sampleSize *= 2
                    }

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize })

                    runOnUiThread {
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                            imageView.visibility = View.VISIBLE
                        } else if (isStemImage) {
                            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                            imageView.visibility = View.VISIBLE
                        } else {
                            imageView.visibility = View.GONE
                        }
                    }
                } else {
                    runOnUiThread {
                        if (isStemImage) {
                            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                            imageView.visibility = View.VISIBLE
                        } else {
                            imageView.visibility = View.GONE
                        }
                    }
                }
            }
        })
    }

    private fun selectOption(index: Int) {
        selectedOption = index

        for (i in 0 until layoutOptions.childCount) {
            val optionView = layoutOptions.getChildAt(i)
            val tvLabel = optionView.findViewById<TextView>(R.id.tv_option_label)

            if (i == index) {
                optionView.setBackgroundResource(R.drawable.bg_option_selected)
                tvLabel.setBackgroundResource(R.drawable.bg_option_label)
            } else {
                optionView.setBackgroundResource(R.drawable.bg_option_normal)
                tvLabel.setBackgroundResource(R.drawable.bg_option_label)
            }
        }

        showAnswer()
    }

    private fun showAnswer() {
        if (isAnswerShown) return
        isAnswerShown = true

        val question = questions[currentIndex]
        QuestionBankManager.markQuestionCompleted(question.id)

        val correctIndex = question.answer.firstOrNull()?.minus('A') ?: return

        if (selectedOption == correctIndex) {
            correctCount++
        } else {
            wrongCount++
        }

        for (i in 0 until layoutOptions.childCount) {
            val optionView = layoutOptions.getChildAt(i)
            val tvLabel = optionView.findViewById<TextView>(R.id.tv_option_label)

            when {
                i == correctIndex -> {
                    optionView.setBackgroundResource(R.drawable.bg_option_correct)
                    tvLabel.setBackgroundResource(R.drawable.bg_option_label)
                }
                i == selectedOption && selectedOption != correctIndex -> {
                    optionView.setBackgroundResource(R.drawable.bg_option_wrong)
                    tvLabel.setBackgroundResource(R.drawable.bg_option_label)
                }
                else -> {
                    optionView.setBackgroundResource(R.drawable.bg_option_normal)
                    tvLabel.setBackgroundResource(R.drawable.bg_option_label)
                }
            }
        }

        cardAnswer.visibility = View.VISIBLE
        tvAnswer.text = question.answer
        tvRate.text = "正确率: ${question.rate}%"
        tvSource.text = question.source
        tvAnalysis.text = question.analysis

        btnShowAnswer.text = "已显示答案"
        btnShowAnswer.isEnabled = false

        updateProgress()
    }

    private fun updateProgress() {
        tvProgress.text = "${currentIndex + 1}/${questions.size} | ✓$correctCount ✗$wrongCount"
    }

    private fun showSearchResults(query: String) {
        Toast.makeText(this, "搜索中...", Toast.LENGTH_SHORT).show()

        QuestionBankManager.searchAsync(query) { result ->
            runOnUiThread {
                if (result == null) {
                    Toast.makeText(this, "未找到相关题目", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val message = buildString {
                    append("题干: ${result.stem.take(100)}...\n\n")
                    append("答案: ${result.answer}\n")
                    append("正确率: ${result.rate}%\n")
                    append("来源: ${result.source}")
                    if (result.analysis.isNotEmpty()) {
                        append("\n\n解析: ${result.analysis.take(200)}...")
                    }
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("搜索结果")
                    .setMessage(message)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("查看原题") { _, _ ->
                        val intent = android.content.Intent(this, PracticeActivity::class.java)
                        intent.putExtra("module_id", moduleId)
                        intent.putExtra("module_name", moduleName)
                        intent.putExtra("question_count", 1)
                        intent.putExtra("rate_min", 0)
                        intent.putExtra("rate_max", 100)
                        startActivity(intent)
                    }
                    .show()
            }
        }
    }

    private fun showQaDialog(selectedText: String) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("问答")
            .setMessage("正在思考...")
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()

        val baseUrl = com.example.aiassistant.AppPreferences.getApiBaseUrl(this)
        val apiKey = com.example.aiassistant.AppPreferences.getApiKey(this)
        val model = try {
            com.example.aiassistant.ModelManager.allModels.firstOrNull()?.model ?: "deepseek-chat"
        } catch (_: Exception) { "deepseek-chat" }

        com.example.aiassistant.OpenAIApiService.analyzeWithSystemPrompt(
            ocrText = "",
            systemPrompt = "你是一个公务员考试辅导助手。请用简洁的中文回答用户的问题。如果用户粘贴了一道题目，请分析题目并给出答案和解析。",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            userMessage = "请回答以下问题：\n$selectedText",
            onComplete = { response ->
                runOnUiThread {
                    try { dialog.setMessage(response) } catch (_: Exception) {}
                }
            },
            onError = { error ->
                runOnUiThread {
                    try { dialog.setMessage("请求失败: $error") } catch (_: Exception) {}
                }
            }
        )
    }

    private fun showEndingDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_practice_ending, null)
        
        val tvAccuracy = dialogView.findViewById<TextView>(R.id.tv_ending_accuracy)
        val tvTotal = dialogView.findViewById<TextView>(R.id.tv_ending_total)
        val tvCorrect = dialogView.findViewById<TextView>(R.id.tv_ending_correct)
        val tvWrong = dialogView.findViewById<TextView>(R.id.tv_ending_wrong)
        val tvQuote = dialogView.findViewById<TextView>(R.id.tv_ending_quote)
        val tvTeacherName = dialogView.findViewById<TextView>(R.id.tv_ending_teacher_name)
        val btnRestart = dialogView.findViewById<View>(R.id.btn_ending_restart)
        val btnClose = dialogView.findViewById<View>(R.id.btn_ending_close)
        
        val answeredCount = correctCount + wrongCount
        val accuracy = if (answeredCount > 0) (correctCount * 100 / answeredCount) else 0
        
        tvAccuracy.text = "$accuracy%"
        tvTotal.text = "${questions.size}"
        tvCorrect.text = "$correctCount"
        tvWrong.text = "$wrongCount"
        
        // 老师鼓励寄语
        val teacherName = com.example.aiassistant.TeacherManager.activeTeacher.name
        tvTeacherName.text = "—— $teacherName"
        
        val quotes = listOf(
            "读书百遍，其义自见。继续加油，胜利就在前方！",
            "精诚所至，金石为开。每一次答题都是一次成长！",
            "积土成山，风雨兴焉；积水成渊，蛟龙生焉。",
            "不积跬步，无以至千里；不积小流，无以成江海。",
            "温故而知新，可以为师矣。坚持就是胜利！",
            "知之者不如好之者，好之者不如乐之者。",
            "纸上得来终觉浅，绝知此事要躬行。加油！"
        )
        tvQuote.text = "“${quotes.random()}”"
        
        val alertDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            
        // 设置对话框圆角和透明背景以显示自定义布局的圆角
        alertDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        btnRestart.setOnClickListener {
            alertDialog.dismiss()
            restartTraining()
        }
        
        btnClose.setOnClickListener {
            alertDialog.dismiss()
            finish()
        }
        
        alertDialog.show()
    }

    private fun restartTraining() {
        currentIndex = 0
        selectedOption = -1
        isAnswerShown = false
        correctCount = 0
        wrongCount = 0
        
        Toast.makeText(this, "正在为您加载下一组题目...", Toast.LENGTH_SHORT).show()
        
        // 重新查询新的一组题目！
        questions = com.example.aiassistant.questionbank.QuestionBankManager.getQuestionsByRateRange(
            moduleId, rateMin, rateMax, questionCount
        )
        
        if (questions.isEmpty()) {
            Toast.makeText(this, "没有符合条件的题目", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        showQuestion(0)
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        imageClient.dispatcher.cancelAll()
        wvStem.destroy()
        wvMaterial.destroy()
        QuestionBankManager.removeOnReadyListener(readyListener)
    }
}
