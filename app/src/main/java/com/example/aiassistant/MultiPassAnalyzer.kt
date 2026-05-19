package com.example.aiassistant

import java.util.concurrent.atomic.AtomicInteger

/**
 * 多轮 AI 推理编排器，每轮可独立选择 AI 模型和是否开启思考模式。
 */
object MultiPassAnalyzer {

    private const val TAG = "MultiPassAnalyzer"

    fun analyzeWithImage(
        imageBase64: String,
        systemPrompt: String,
        round: RoundConfig,
        onProgress: (phase: String, detail: String?) -> Unit,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress("R1", "AI 视觉分析中...")
        OpenAIApiService.analyzeWithImage(
            imageBase64 = imageBase64,
            systemPrompt = systemPrompt,
            baseUrl = round.model.baseUrl,
            apiKey = round.model.apiKey,
            model = round.model.model,
            thinking = round.thinking,
            onComplete = onComplete,
            onError = { onError("视觉分析失败：$it") }
        )
    }

    fun analyze(
        ocrText: String,
        rounds: List<RoundConfig>,
        defaultPrompt: String,
        customR2Prompt: String,
        selfCheckInstruction: String,
        strategyType: String, // "single" / "standard" / "self_check" / "custom_r2"
        r3Prompt: String = "",
        userMessage: String? = null,  // 自定义用户消息，null则使用默认的OCR文本
        onProgress: (phase: String, detail: String?) -> Unit,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (rounds.isEmpty()) { onError("没有配置AI模型"); return }

        // 单轮
        if (rounds.size == 1) {
            val r = rounds[0]
            onProgress("R1", "AI 思考中...")
            OpenAIApiService.analyzeText(
                ocrText = ocrText, baseUrl = r.model.baseUrl, apiKey = r.model.apiKey,
                model = r.model.model, prompt = defaultPrompt, thinking = r.thinking,
                userMessage = userMessage,
                onComplete = onComplete,
                onError = { onError("AI 请求失败：$it") }
            )
            return
        }

        val r1 = rounds[0]
        val r2 = rounds.getOrNull(1) ?: rounds[0]
        val r3 = rounds.getOrNull(2)

        val r1Prompt = defaultPrompt
        val r2Prompt = when (strategyType) {
            "self_check" -> defaultPrompt + "\n\n" + selfCheckInstruction
            "custom_r2" -> customR2Prompt.ifBlank { defaultPrompt }
            else -> defaultPrompt
        }

        onProgress("R1", "第1/2轮思考中...")

        val completedCount = AtomicInteger(0)
        var r1Result: String? = null
        var r2Result: String? = null
        var hasError = false
        val lock = Any()

        fun onRoundComplete() {
            if (completedCount.incrementAndGet() == 2) {
                val res1 = synchronized(lock) { r1Result }
                val res2 = synchronized(lock) { r2Result }
                if (res1 != null && res2 != null) {
                    if (r3 != null) {
                        onProgress("R3", "两轮思考完成，正在验证...")
                        runR3(ocrText, res1, res2, r3Prompt, r3, onComplete, onError)
                    } else {
                        onComplete(res1) // 无R3，返回R1结果
                    }
                }
            }
        }

        // R1
        OpenAIApiService.analyzeWithSystemPrompt(
            ocrText = ocrText, systemPrompt = r1Prompt,
            baseUrl = r1.model.baseUrl, apiKey = r1.model.apiKey,
            model = r1.model.model, thinking = r1.thinking,
            userMessage = userMessage,
            onComplete = { result ->
                synchronized(lock) { if (hasError) return@analyzeWithSystemPrompt; r1Result = result }
                onRoundComplete()
            },
            onError = { error ->
                synchronized(lock) { if (hasError) return@analyzeWithSystemPrompt; hasError = true }
                onError("第一轮失败：$error")
            }
        )

        // R2（与 R1 并行）
        OpenAIApiService.analyzeWithSystemPrompt(
            ocrText = ocrText, systemPrompt = r2Prompt,
            baseUrl = r2.model.baseUrl, apiKey = r2.model.apiKey,
            model = r2.model.model, thinking = r2.thinking,
            userMessage = userMessage,
            onComplete = { result ->
                synchronized(lock) { if (hasError) return@analyzeWithSystemPrompt; r2Result = result }
                onRoundComplete()
            },
            onError = { error ->
                synchronized(lock) { if (hasError) return@analyzeWithSystemPrompt; hasError = true }
                onError("第二轮失败：$error")
            }
        )
    }

    private fun runR3(
        ocrText: String, r1Result: String, r2Result: String,
        r3PromptTemplate: String, r3: RoundConfig,
        onComplete: (String) -> Unit, onError: (String) -> Unit
    ) {
        val r3Prompt = if (r3PromptTemplate.isNotBlank()) {
            r3PromptTemplate.replace("{ocrText}", ocrText)
                .replace("{r1Result}", r1Result).replace("{r2Result}", r2Result)
        } else {
            """你是公务员考试行测题目的资深审核专家。

你的任务是审核两道独立AI解析结果：
1. 对比第一轮和第二轮给出的最终答案（选项字母）
2. 如果选项一致 → 确认该答案，从两轮中选择解析更完整、校对更准确的一份，按原格式输出
3. 如果选项不一致 → 仔细评判哪一轮的推理逻辑更严谨、选项排除更有说服力，采纳该轮的答案，按原格式输出
4. 在输出的question字段末尾标注【✓ 双轮一致】或【⚡ 分歧已裁决：采纳第X轮】

=== 原始题目文字 ===
$ocrText

=== 第一轮思考 ===
$r1Result

=== 第二轮思考 ===
$r2Result

请严格按照第一轮/第二轮所使用的JSON格式输出最终结果（不要Markdown代码块包裹），保持所有字段与输入格式一致。
        """.trimIndent()
        }
        OpenAIApiService.analyzeWithSystemPrompt(
            ocrText = "", systemPrompt = r3Prompt,
            baseUrl = r3.model.baseUrl, apiKey = r3.model.apiKey,
            model = r3.model.model, thinking = r3.thinking,
            onComplete = onComplete,
            onError = { onError("第三轮验证失败：$it") }
        )
    }
}
