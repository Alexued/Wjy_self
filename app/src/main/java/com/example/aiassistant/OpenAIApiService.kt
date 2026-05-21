package com.example.aiassistant

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
                            put("budget_tokens", thinkingBudget)
                        })
                        // 兼容 OpenAI
                        put("reasoning_effort", "high")
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
                            put("budget_tokens", thinkingBudget)
                        })
                        put("reasoning_effort", "high")
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
                val candidates = json.optJSONArray("candidates") ?: return ""
                val parts = candidates.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: return ""
                parts.optJSONObject(0)?.optString("text") ?: ""
            }
            else -> { // "openai"
                val choices = json.optJSONArray("choices") ?: return ""
                val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
                val content = message.opt("content")
                if (content != null && content !== JSONObject.NULL) {
                    val cStr = content.toString()
                    if (cStr != "null") cStr else ""
                } else ""
            }
        }
    }
}
