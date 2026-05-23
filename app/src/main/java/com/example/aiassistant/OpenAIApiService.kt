package com.example.aiassistant

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通用 AI 接口引擎：支持 OpenAI、Anthropic 和 Google Gemini 协议
 * 兼容：OpenAI / Anthropic Claude / Google Gemini REST API 及自定义中转
 * 使用非流式（一次性）请求，支持自动重试与高可用模型链式容错
 */
object OpenAIApiService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var currentCall: Call? = null
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun cancelCurrentRequest() {
        retryHandler.removeCallbacksAndMessages(null)
        val call = synchronized(this) {
            val c = currentCall
            currentCall = null
            c
        }
        call?.let { if (!it.isCanceled()) it.cancel() }
    }

    fun warmUpConnection(baseUrl: String) {
        Thread {
            try {
                val url = baseUrl.trimEnd('/') + "/chat/completions"
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .build()
                client.newCall(request).execute().close()
            } catch (_: Exception) {}
        }.start()
    }

    /** 统一文本请求核心：完美路由至 OpenAI / Anthropic / Gemini */
    fun analyzeText(
        ocrText: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        thinking: Boolean = false,
        userMessage: String? = null,
        apiType: String = "openai",
        thinkingBudget: Int = 4096,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val systemPrompt = prompt
        val userContent = userMessage ?: "以下是从图片中识别出的文字内容：\n$ocrText"

        val request = try {
            buildTextRequest(baseUrl, apiKey, model, systemPrompt, userContent, thinking, apiType, thinkingBudget)
        } catch (e: Exception) {
            onError("构建请求失败：${e.message}")
            return
        }

        executeRequest(request, apiType, 0, onComplete, onError)
    }

    /** 统一 System Prompt 模式接口（向下兼容） */
    fun analyzeWithSystemPrompt(
        ocrText: String,
        systemPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        thinking: Boolean = false,
        userMessage: String? = null,
        apiType: String = "openai",
        thinkingBudget: Int = 4096,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        analyzeText(ocrText, baseUrl, apiKey, model, systemPrompt, thinking, userMessage, apiType, thinkingBudget, onComplete, onError)
    }

    /** 统一视觉/多模态请求核心：完美路由至 OpenAI / Anthropic / Gemini */
    fun analyzeWithImage(
        imageBase64: String,
        systemPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        thinking: Boolean = false,
        apiType: String = "openai",
        thinkingBudget: Int = 4096,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = try {
            buildImageRequest(baseUrl, apiKey, model, systemPrompt, imageBase64, thinking, apiType, thinkingBudget)
        } catch (e: Exception) {
            onError("构建视觉请求失败：${e.message}")
            return
        }

        executeRequest(request, apiType, 0, onComplete, onError)
    }

    // ── 内部请求构造引擎 ───────────────────────────────────────────────

    private fun buildTextRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userContent: String,
        thinking: Boolean,
        apiType: String,
        thinkingBudget: Int
    ): Request {
        val mediaType = "application/json".toMediaType()

        return when (apiType.lowercase()) {
            "anthropic" -> {
                val url = baseUrl.trimEnd('/') + "/v1/messages"
                val body = JSONObject().apply {
                    put("model", model)
                    put("system", systemPrompt)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                    })
                    if (thinking) {
                        // Anthropic: max_tokens 必须 >= budget_tokens，取两者中较大值 + 输出空间
                        put("max_tokens", maxOf(8192, thinkingBudget + 4096))
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                            put("budget_tokens", thinkingBudget)
                        })
                    } else {
                        put("max_tokens", 8192)
                    }
                }
                Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            "gemini" -> {
                val cleanModel = if (model.startsWith("models/")) model.substringAfter("models/") else model
                val url = if (baseUrl.contains("googleapis.com") || baseUrl.isBlank()) {
                    "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
                } else {
                    baseUrl.trimEnd('/') + "/v1beta/models/$cleanModel:generateContent?key=$apiKey"
                }
                val body = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", userContent) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 8192)
                        if (thinking) {
                            put("thinkingConfig", JSONObject().apply {
                                put("thinkingBudget", thinkingBudget)
                            })
                        }
                    })
                }
                Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            else -> { // "openai"
                val cleanBaseUrl = if (!baseUrl.contains("/v1") && !baseUrl.endsWith("/v1")) {
                    baseUrl.trimEnd('/') + "/v1"
                } else {
                    baseUrl
                }
                val url = cleanBaseUrl.trimEnd('/') + "/chat/completions"
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                    })
                    put("max_tokens", 8192)
                    put("stream", false)
                    if (thinking) {
                        // 兼容 DeepSeek
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                        })
                        // 兼容 OpenAI 与 DeepSeek-V4
                        val effort = if (thinkingBudget >= 4096) "max" else "high"
                        put("reasoning_effort", effort)
                    }
                }
                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
        }
    }

    private fun buildImageRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        imageBase64: String,
        thinking: Boolean,
        apiType: String,
        thinkingBudget: Int
    ): Request {
        val mediaType = "application/json".toMediaType()

        return when (apiType.lowercase()) {
            "anthropic" -> {
                val url = baseUrl.trimEnd('/') + "/v1/messages"
                val body = JSONObject().apply {
                    put("model", model)
                    put("system", systemPrompt)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", imageBase64)
                                    })
                                })
                                put(JSONObject().apply { put("type", "text"); put("text", "请分析这张图片中的题目") })
                            })
                        })
                    })
                    if (thinking) {
                        put("max_tokens", maxOf(8192, thinkingBudget + 4096))
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                            put("budget_tokens", thinkingBudget)
                        })
                    } else {
                        put("max_tokens", 8192)
                    }
                }
                Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            "gemini" -> {
                val cleanModel = if (model.startsWith("models/")) model.substringAfter("models/") else model
                val url = if (baseUrl.contains("googleapis.com") || baseUrl.isBlank()) {
                    "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
                } else {
                    baseUrl.trimEnd('/') + "/v1beta/models/$cleanModel:generateContent?key=$apiKey"
                }
                val body = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("inlineData", JSONObject().apply {
                                        put("mimeType", "image/jpeg")
                                        put("data", imageBase64)
                                    })
                                })
                                put(JSONObject().apply { put("text", "请分析这张图片中的题目") })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 8192)
                        if (thinking) {
                            put("thinkingConfig", JSONObject().apply {
                                put("thinkingBudget", thinkingBudget)
                            })
                        }
                    })
                }
                Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            else -> { // "openai"
                val cleanBaseUrl = if (!baseUrl.contains("/v1") && !baseUrl.endsWith("/v1")) {
                    baseUrl.trimEnd('/') + "/v1"
                } else {
                    baseUrl
                }
                val url = cleanBaseUrl.trimEnd('/') + "/chat/completions"
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply { put("type", "text"); put("text", "请分析这张图片中的题目") })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$imageBase64") })
                                })
                            })
                        })
                    })
                    put("max_tokens", 8192)
                    put("stream", false)
                    if (thinking) {
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                        })
                        val effort = if (thinkingBudget >= 4096) "max" else "high"
                        put("reasoning_effort", effort)
                    }
                }
                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
        }
    }

    // ── 通用 HTTP 执行引擎 ───────────────────────────────────────────────

    private fun executeRequest(
        request: Request,
        apiType: String,
        retryCount: Int,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        cancelCurrentRequest()
        android.util.Log.d("AIAssistantAPI", "executeRequest: Launching request. Type: $apiType, URL: ${request.url}, Method: ${request.method}")
        val call = client.newCall(request)
        synchronized(this) { currentCall = call }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    android.util.Log.d("AIAssistantAPI", "onFailure: Request was canceled.")
                    return
                }
                android.util.Log.e("AIAssistantAPI", "onFailure: Network request failed!", e)
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) {
                    android.util.Log.d("AIAssistantAPI", "onResponse: Request was canceled after response received.")
                    response.close()
                    return
                }

                if (!response.isSuccessful) {
                    val statusCode = response.code
                    val bodyStr = try { response.body?.string() } catch (_: Exception) { null } ?: "无响应体"
                    response.close()

                    android.util.Log.e("AIAssistantAPI", "onResponse: API Error. HTTP Status: $statusCode, ResponseBody: $bodyStr")

                    // 对 429 进行延迟重试
                    if (statusCode == 429 && retryCount < 2) {
                        val delayMs = (retryCount + 1) * 2000L
                        android.util.Log.w("AIAssistantAPI", "onResponse: Too Many Requests (429). Retrying in ${delayMs}ms...")
                        retryHandler.postDelayed({
                            executeRequest(request, apiType, retryCount + 1, onComplete, onError)
                        }, delayMs)
                        return
                    }

                    onError("API 响应错误 ${statusCode}：$bodyStr")
                    return
                }

                val body = response.body
                if (body == null) {
                    android.util.Log.e("AIAssistantAPI", "onResponse: Response body is null!")
                    onError("API 响应为空")
                    return
                }

                try {
                    val responseStr = body.string()
                    android.util.Log.i("AIAssistantAPI", "onResponse: Raw API JSON Response: $responseStr")
                    
                    val parsedText = parseResponseStr(responseStr, apiType)
                    android.util.Log.d("AIAssistantAPI", "onResponse: Parsed output length: ${parsedText.length}, preview: ${parsedText.take(150)}")
                    
                    if (parsedText.isEmpty()) {
                        android.util.Log.e("AIAssistantAPI", "onResponse: Parsed text is empty!")
                        onError("解析响应内容为空")
                    } else {
                        onComplete(parsedText)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AIAssistantAPI", "onResponse: Parse exception!", e)
                    onError("解析响应失败：${e.message}")
                } finally {
                    try { body.close() } catch (_: Exception) {}
                }
            }
        })
    }

    private fun parseResponseStr(responseStr: String, apiType: String): String {
        val json = JSONObject(responseStr)
        return when (apiType.lowercase()) {
            "anthropic" -> {
                val contentArr = json.optJSONArray("content") ?: return ""
                val sb = StringBuilder()
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.getJSONObject(i)
                    if (item.optString("type") == "text") {
                        sb.append(item.optString("text"))
                    }
                }
                sb.toString()
            }
            "gemini" -> {
                // 检查是否有错误响应
                if (json.has("error")) {
                    val error = json.optJSONObject("error")
                    val code = error?.optInt("code", -1) ?: -1
                    val message = error?.optString("message", "未知错误") ?: "未知错误"
                    val status = error?.optString("status", "") ?: ""
                    android.util.Log.e("AIAssistantAPI", "Gemini API 错误: code=$code, status=$status, message=$message")
                    return ""
                }

                // 兼容 Gemini 原生格式和 OpenAI 兼容格式
                // 1. 先检查 Gemini 原生格式: {"candidates": [...]}
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.optJSONObject(0)
                    if (firstCandidate != null) {
                        val finishReason = firstCandidate.optString("finishReason", "")
                        if (finishReason.isNotEmpty() && finishReason != "STOP") {
                            android.util.Log.w("AIAssistantAPI", "Gemini finishReason: $finishReason")
                        }
                        val content = firstCandidate.optJSONObject("content")
                        if (content != null) {
                            val parts = content.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val text = parts.optJSONObject(0)?.optString("text") ?: ""
                                if (text.isNotEmpty()) return text
                            }
                        }
                    }
                }

                // 2. 再检查 OpenAI 兼容格式: {"choices": [...]}
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.optJSONObject(0)
                    if (firstChoice != null) {
                        val finishReason = firstChoice.optString("finish_reason", "")
                        val message = firstChoice.optJSONObject("message")
                        if (message != null) {
                            // 检查是否有普通文本内容
                            val content = message.opt("content")
                            if (content != null && content !== JSONObject.NULL) {
                                val cStr = content.toString()
                                if (cStr != "null" && cStr.isNotEmpty()) {
                                    android.util.Log.d("AIAssistantAPI", "Gemini 使用 OpenAI 兼容格式解析成功")
                                    return cStr
                                }
                            }
                            // 如果是 tool_calls 响应，尝试提取 tool_calls 中的文本
                            if (finishReason == "tool_calls") {
                                android.util.Log.w("AIAssistantAPI", "Gemini 返回 tool_calls 格式，finish_reason=tool_calls")
                                val toolCalls = message.optJSONArray("tool_calls")
                                if (toolCalls != null && toolCalls.length() > 0) {
                                    // 尝试从第一个 tool_call 的 arguments 中提取
                                    for (tci in 0 until toolCalls.length()) {
                                        val toolCall = toolCalls.optJSONObject(tci) ?: continue
                                        val function = toolCall.optJSONObject("function")
                                        if (function != null) {
                                            val arguments = function.optString("arguments", "")
                                            if (arguments.isNotEmpty()) {
                                                android.util.Log.d("AIAssistantAPI", "Gemini tool_call arguments: ${arguments.take(200)}")
                                                // 尝试解析 arguments 为 JSON
                                                try {
                                                    val argsJson = JSONObject(arguments)
                                                    // 如果 arguments 中有 text 字段，返回它
                                                    if (argsJson.has("text")) {
                                                        return argsJson.getString("text")
                                                    }
                                                    // 否则返回整个 arguments
                                                    return arguments
                                                } catch (_: Exception) {
                                                    // arguments 不是 JSON，直接返回
                                                    return arguments
                                                }
                                            }
                                        }
                                    }
                                }
                                android.util.Log.e("AIAssistantAPI", "Gemini tool_calls 响应中没有可提取的内容")
                            }
                        }
                    }
                }

                // 3. 都没有找到有效内容
                android.util.Log.e("AIAssistantAPI", "Gemini 响应格式无法识别. JSON: ${responseStr.take(500)}")
                return ""
            }
            else -> { // "openai"
                // 检查是否有错误响应
                if (json.has("error")) {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message", "未知错误") ?: "未知错误"
                    val type = error?.optString("type", "") ?: ""
                    val code = error?.optString("code", "") ?: ""
                    android.util.Log.e("AIAssistantAPI", "OpenAI API 错误: type=$type, code=$code, message=$message")
                    return ""
                }
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    android.util.Log.e("AIAssistantAPI", "OpenAI 响应缺少 choices 字段或为空. JSON: ${responseStr.take(500)}")
                    return ""
                }
                val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
                val content = message.opt("content")
                if (content != null && content !== JSONObject.NULL) {
                    val cStr = content.toString()
                    if (cStr != "null") cStr else ""
                } else ""
            }
        }
    }

    /**
     * 支持 Tool Calling 的分析入口。
     * 执行 ReAct 循环：发送请求 → 如果 AI 返回 tool_calls → 执行工具 → 将结果追加到 messages → 再次请求 → 直到 AI 返回最终文本。
     * 最多允许 maxToolRounds 轮工具调用（防止无限循环）。
     */
    fun analyzeWithTools(
        context: Context,
        ocrText: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        thinking: Boolean = false,
        userMessage: String? = null,
        apiType: String = "openai",
        thinkingBudget: Int = 4096,
        tools: JSONArray? = null,
        maxToolRounds: Int = 3,
        imageBase64: String? = null,  // 新增多模态识图图片数据
        onToolCall: ((String) -> Unit)? = null,  // 通知 UI 正在调用哪个工具
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", prompt) })
            put(JSONObject().apply {
                put("role", "user")
                if (imageBase64 != null) {
                    val defaultText = userMessage ?: "请分析这张图片中的题目。以下是OCR识别作为参考：\n$ocrText"
                    if (apiType.lowercase() == "anthropic") {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", imageBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", defaultText)
                            })
                        })
                    } else {
                        // OpenAI / Gemini 格式
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", defaultText)
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$imageBase64")
                                })
                            })
                        })
                    }
                } else {
                    put("role", "user")
                    put("content", userMessage ?: "以下是从图片中识别出的文字内容：\n$ocrText")
                }
            })
        }
        
        executeToolLoop(
            context = context,
            baseUrl = baseUrl, apiKey = apiKey, model = model,
            apiType = apiType, thinking = thinking, thinkingBudget = thinkingBudget,
            messages = messages, tools = tools,
            round = 0, maxRounds = maxToolRounds,
            onToolCall = onToolCall, onComplete = onComplete, onError = onError
        )
    }

    private fun executeToolLoop(
        context: Context,
        baseUrl: String, apiKey: String, model: String,
        apiType: String, thinking: Boolean, thinkingBudget: Int,
        messages: JSONArray,
        tools: JSONArray?,
        round: Int, maxRounds: Int,
        onToolCall: ((String) -> Unit)?,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (round >= maxRounds) {
            onError("工具调用轮次超限（最多 $maxRounds 轮），已终止")
            return
        }
        
        val request = try {
            buildToolRequest(baseUrl, apiKey, model, apiType, thinking, thinkingBudget, messages, tools)
        } catch (e: Exception) {
            onError("构建带有工具的请求失败：${e.message}")
            return
        }
        
        if (round == 0) {
            cancelCurrentRequest()
        }
        val call = client.newCall(request)
        synchronized(this) { currentCall = call }
        
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (call.isCanceled()) return
                onError("网络请求失败：${e.message}")
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (call.isCanceled()) { response.close(); return }
                if (!response.isSuccessful) {
                    val body = try { response.body?.string() } catch (_: Exception) { null } ?: ""
                    response.close()
                    onError("API 响应错误 ${response.code}：$body")
                    return
                }
                
                try {
                    val responseStr = response.body!!.string()
                    response.close()
                    val json = JSONObject(responseStr)
                    
                    // 检查是否有 tool_calls
                    val toolCalls = parseToolCalls(json, apiType)
                    
                    if (toolCalls.isNotEmpty()) {
                        // AI 请求调用工具 → 执行工具 → 追加结果到 messages → 重新请求
                        android.util.Log.d("AIAssistantAPI", "AI 请求调用 ${toolCalls.size} 个工具")
                        
                        // 先把 assistant 的 tool_calls message 加入 messages
                        val assistantMsg = extractAssistantMessage(json, apiType)
                        messages.put(assistantMsg)
                        
                        // 在后台线程执行工具
                        Thread {
                            val toolResults = mutableListOf<Pair<String, String>>()
                            for (tc in toolCalls) {
                                onToolCall?.let {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        it(tc.name)
                                    }
                                }
                                val result = com.example.aiassistant.skills.ToolRegistry.execute(context, tc)
                                toolResults.add(result.toolCallId to result.content)
                            }

                            // Anthropic 格式要求所有 tool_result 在同一个 user 消息中
                            if (apiType.lowercase() == "anthropic") {
                                val toolResultContent = JSONArray()
                                for ((toolCallId, content) in toolResults) {
                                    toolResultContent.put(JSONObject().apply {
                                        put("type", "tool_result")
                                        put("tool_use_id", toolCallId)
                                        put("content", content)
                                    })
                                }
                                val toolResultMsg = JSONObject().apply {
                                    put("role", "user")
                                    put("content", toolResultContent)
                                }
                                messages.put(toolResultMsg)
                                android.util.Log.d("AIAssistantAPI", "Anthropic tool_result 消息: $toolResultMsg")
                            } else {
                                // OpenAI/Gemini 格式：每个 tool_result 是单独的消息
                                for ((toolCallId, content) in toolResults) {
                                    messages.put(JSONObject().apply {
                                        put("role", "tool")
                                        put("tool_call_id", toolCallId)
                                        put("content", content)
                                    })
                                }
                            }

                            // 递归下一轮
                            executeToolLoop(
                                context, baseUrl, apiKey, model, apiType,
                                thinking, thinkingBudget, messages, tools,
                                round + 1, maxRounds, onToolCall, onComplete, onError
                            )
                        }.start()
                    } else {
                        // 最终文本回答
                        val text = parseResponseStr(responseStr, apiType)
                        if (text.isEmpty()) onError("解析响应内容为空")
                        else onComplete(text)
                    }
                } catch (e: Exception) {
                    onError("解析响应失败：${e.message}")
                }
            }
        })
    }

    /** 构建带 tools 参数的请求（仅适用于支持 OpenAI/DeepSeek 协议的模型） */
    private fun buildToolRequest(
        baseUrl: String, apiKey: String, model: String,
        apiType: String, thinking: Boolean, thinkingBudget: Int,
        messages: JSONArray, tools: JSONArray?
    ): Request {
        val mediaType = "application/json".toMediaType()

        return when (apiType.lowercase()) {
            "anthropic" -> {
                // Anthropic 格式
                val url = baseUrl.trimEnd('/') + "/v1/messages"

                // 转换 OpenAI tools 格式到 Anthropic 格式
                val anthropicTools = if (tools != null && tools.length() > 0) {
                    JSONArray().apply {
                        for (i in 0 until tools.length()) {
                            val openaiTool = tools.getJSONObject(i)
                            val function = openaiTool.getJSONObject("function")
                            put(JSONObject().apply {
                                put("name", function.getString("name"))
                                put("description", function.optString("description", ""))
                                put("input_schema", function.optJSONObject("parameters") ?: JSONObject())
                            })
                        }
                    }
                } else null

                // 转换 messages 格式（Anthropic 的 system 是单独的字段）
                var systemPrompt = ""
                val anthropicMessages = JSONArray()
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    val role = msg.getString("role")
                    if (role == "system") {
                        systemPrompt = msg.optString("content", "")
                    } else {
                        // user 和 assistant 消息直接保留
                        // tool_result 已经在 executeToolLoop 中转换为 user 消息
                        anthropicMessages.put(msg)
                    }
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("system", systemPrompt)
                    put("messages", anthropicMessages)
                    put("max_tokens", 8192)
                    if (anthropicTools != null && anthropicTools.length() > 0) {
                        put("tools", anthropicTools)
                    }
                }

                Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
            else -> {
                // OpenAI 和 Gemini 格式
                val isGemini = apiType.lowercase() == "gemini" || baseUrl.contains("generativelanguage.googleapis.com")

                val url = if (isGemini) {
                    val keyParam = if (apiKey.isNotBlank()) "?key=$apiKey" else ""
                    "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions$keyParam"
                } else {
                    val cleanBaseUrl = if (!baseUrl.contains("/v1") && !baseUrl.endsWith("/v1")) {
                        baseUrl.trimEnd('/') + "/v1"
                    } else {
                        baseUrl
                    }
                    cleanBaseUrl.trimEnd('/') + "/chat/completions"
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 8192)
                    put("stream", false)
                    if (tools != null && tools.length() > 0) {
                        put("tools", tools)
                        put("tool_choice", "auto")
                    }
                    if (thinking) {
                        if (isGemini) {
                            val effort = if (thinkingBudget >= 4096) "max" else "high"
                            put("reasoning_effort", effort)
                        } else {
                            put("thinking", JSONObject().apply {
                                put("type", "enabled")
                            })
                            val effort = if (thinkingBudget >= 4096) "max" else "high"
                            put("reasoning_effort", effort)
                        }
                    }
                }

                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()
            }
        }
    }

    /** 从 AI 响应中提取 tool_calls 列表 */
    private fun parseToolCalls(json: JSONObject, apiType: String): List<com.example.aiassistant.skills.ToolCall> {
        return when (apiType.lowercase()) {
            "anthropic" -> {
                // Anthropic 格式: {"content": [{"type": "tool_use", "id": "...", "name": "...", "input": {...}}]}
                val contentArr = json.optJSONArray("content") ?: return emptyList()
                val toolCalls = mutableListOf<com.example.aiassistant.skills.ToolCall>()
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.optJSONObject(i) ?: continue
                    if (item.optString("type") == "tool_use") {
                        try {
                            toolCalls.add(com.example.aiassistant.skills.ToolCall(
                                id = item.optString("id", "toolu_$i"),
                                name = item.getString("name"),
                                arguments = item.optJSONObject("input")?.toString() ?: "{}"
                            ))
                        } catch (e: Exception) {
                            android.util.Log.e("AIAssistantAPI", "解析 Anthropic tool_use 失败: ${e.message}")
                        }
                    }
                }
                toolCalls
            }
            else -> {
                // OpenAI 和 Gemini（OpenAI 兼容格式）
                val choices = json.optJSONArray("choices") ?: return emptyList()
                val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return emptyList()
                val toolCallsArr = message.optJSONArray("tool_calls") ?: return emptyList()

                (0 until toolCallsArr.length()).mapNotNull { i ->
                    try {
                        val tc = toolCallsArr.getJSONObject(i)
                        val fn = tc.getJSONObject("function")
                        com.example.aiassistant.skills.ToolCall(
                            id = tc.optString("id", "call_$i"),
                            name = fn.getString("name"),
                            arguments = fn.getString("arguments")
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("AIAssistantAPI", "解析 tool_call 失败: ${e.message}")
                        null
                    }
                }
            }
        }
    }

    /** 提取 assistant 消息（含 tool_calls）用于追加到 messages，并清洗不安全字段如 content = null */
    private fun extractAssistantMessage(json: JSONObject, apiType: String): JSONObject {
        return when (apiType.lowercase()) {
            "anthropic" -> {
                // Anthropic 格式 - 需要保留 tool_use 块
                JSONObject().apply {
                    put("role", "assistant")
                    val contentArr = json.optJSONArray("content")
                    if (contentArr != null) {
                        // 保留所有 content 块（包括 text 和 tool_use）
                        put("content", contentArr)
                        android.util.Log.d("AIAssistantAPI", "Anthropic assistant 消息 content: $contentArr")
                    } else {
                        put("content", "")
                    }
                }
            }
            else -> {
                // OpenAI 和 Gemini 格式
                val choices = json.optJSONArray("choices") ?: return JSONObject()
                val original = choices.optJSONObject(0)?.optJSONObject("message") ?: return JSONObject()

                JSONObject().apply {
                    put("role", "assistant")
                    val rawContent = original.optString("content")
                    put("content", if (rawContent == "null" || original.isNull("content")) "" else rawContent)

                    if (original.has("tool_calls")) {
                        put("tool_calls", original.optJSONArray("tool_calls"))
                    }
                    if (original.has("reasoning_content")) {
                        put("reasoning_content", original.optString("reasoning_content"))
                    }
                }
            }
        }
    }
}
