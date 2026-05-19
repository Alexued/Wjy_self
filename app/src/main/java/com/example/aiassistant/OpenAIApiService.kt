package com.example.aiassistant

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 封装 OpenAI 格式的 API 调用
 * 兼容：OpenAI / 任意 OpenAI Compatible 服务
 * 使用非流式（一次性）请求，等待完整响应后返回
 */
object OpenAIApiService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 跟踪当前正在执行的请求，用于取消 */
    private var currentCall: Call? = null
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 取消当前正在执行的请求（原子交换，避免竞态覆盖新请求） */
    fun cancelCurrentRequest() {
        retryHandler.removeCallbacksAndMessages(null)
        val call = synchronized(this) {
            val c = currentCall
            currentCall = null
            c
        }
        call?.let { if (!it.isCanceled()) it.cancel() }
    }

    /**
     * 预热 HTTPS 连接（DNS + TCP + TLS 握手）
     * 在服务启动时调用，后续请求可复用连接池，节省 200-500ms
     */
    fun warmUpConnection(baseUrl: String) {
        Thread {
            try {
                val url = baseUrl.trimEnd('/') + "/chat/completions"
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .build()
                client.newCall(request).execute().close()
                android.util.Log.d("OpenAIApiService", "连接预热完成: $baseUrl")
            } catch (_: Exception) {
                android.util.Log.d("OpenAIApiService", "连接预热失败: $baseUrl")
            }
        }.start()
    }

    /**
     * 使用自定义 system prompt 进行分析（多轮推理使用）
     * 与 analyzeText 的唯一区别：直接传入 systemPrompt，不从 AppPreferences 读取
     */
    fun analyzeWithSystemPrompt(
        ocrText: String,
        systemPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        thinking: Boolean = false,
        userMessage: String? = null,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val systemMessage = JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        }
        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", userMessage ?: "以下是从图片中识别出的文字内容：\n$ocrText")
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(systemMessage)
                put(userMsg)
            })
            put("max_tokens", 8192)
            put("stream", false)
            if (thinking) {
                put("reasoning_effort", "high")
                put("thinking", JSONObject().apply { put("type", "enabled") })
            }
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // 不调用 cancelCurrentRequest()：多轮推理时 R1/R2 需并行，不能互相取消。
        // 外部可通过 OpenAIApiService.cancelCurrentRequest() 统一取消。
        fun enqueueWithRetry(retryCount: Int) {
            val call = client.newCall(request)
            synchronized(this) { currentCall = call }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    onError("网络请求失败：${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (call.isCanceled()) { response.close(); return }
                    if (!response.isSuccessful) {
                        val bodyStr = try { response.body?.string() } catch (_: Exception) { null } ?: "无响应体"
                        response.close()
                        if (response.code == 429 && retryCount < 3) {
                            val delayMs = (retryCount + 1) * 3000L
                            android.util.Log.w("OpenAIApiService", "429 速率限制，${delayMs}ms 后第${retryCount + 1}次重试")
                            retryHandler.postDelayed({ enqueueWithRetry(retryCount + 1) }, delayMs)
                            return
                        }
                        onError("API响应错误 ${response.code}：$bodyStr")
                        return
                    }

                val body = response.body
                if (body == null) {
                    onError("API响应为空")
                    return
                }

                try {
                    val responseStr = body.string()
                    val json = JSONObject(responseStr)
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        onError("API返回空choices")
                        return
                    }
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    if (message == null) {
                        onError("API返回空message")
                        return
                    }

                    val content = message.opt("content")
                    val finalContent = if (content != null && content !== JSONObject.NULL) {
                        val cStr = content.toString()
                        if (cStr != "null") cStr else ""
                    } else ""

                    onComplete(finalContent)
                } catch (e: Exception) {
                    onError("解析响应失败：${e.message}")
                } finally {
                    try { body.close() } catch (_: Exception) {}
                }
            }
        })
        }
        enqueueWithRetry(0)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 视觉 API（图像直接送多模态模型，跳过 OCR）
    // ─────────────────────────────────────────────────────────────────────

    fun analyzeWithImage(
        imageBase64: String,
        systemPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        thinking: Boolean = false,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val systemMessage = JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        }
        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "请分析这张图片中的题目")
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$imageBase64")
                })
            })
        }
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(systemMessage)
                put(userMessage)
            })
            put("max_tokens", 8192)
            put("stream", false)
            if (thinking) {
                put("reasoning_effort", "high")
                put("thinking", JSONObject().apply { put("type", "enabled") })
            }
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        cancelCurrentRequest()
        val call = client.newCall(request)
        synchronized(this) { currentCall = call }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) { response.close(); return }
                if (!response.isSuccessful) {
                    val bodyStr = try { response.body?.string() } catch (_: Exception) { null } ?: "无响应体"
                    response.close()
                    onError("API响应错误 ${response.code}：$bodyStr")
                    return
                }
                val body = response.body
                if (body == null) {
                    onError("API响应为空")
                    return
                }
                try {
                    val responseStr = body.string()
                    val json = JSONObject(responseStr)
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        onError("API返回空choices")
                        return
                    }
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    if (message == null) {
                        onError("API返回空message")
                        return
                    }
                    // 仅提取 content，忽略 reasoning_content（思考过程不展示）
                    val content = message.opt("content")
                    val finalContent = if (content != null && content !== JSONObject.NULL) {
                        val cStr = content.toString()
                        if (cStr != "null") cStr else ""
                    } else ""
                    onComplete(finalContent)
                } catch (e: Exception) {
                    onError("解析响应失败：${e.message}")
                } finally {
                    try { body.close() } catch (_: Exception) {}
                }
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────
    // 非流式 API（一次性返回完整结果）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 将 OCR 提取的文本发送给 AI，使用非流式（一次性）请求
     *
     * @param ocrText     OCR 识别出的文本
     * @param baseUrl     API BaseUrl
     * @param apiKey      API Key
     * @param model       模型名称
     * @param prompt      用户自定义提示词
     * @param onComplete  完整响应返回后回调
     * @param onError     失败回调
     */
    fun analyzeText(
        ocrText: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        thinking: Boolean = false,
        userMessage: String? = null,  // 自定义用户消息，null则使用默认
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        // 构造请求体：system 角色承载指令，user 角色承载题目内容
        val systemMessage = JSONObject().apply {
            put("role", "system")
            put("content", prompt)
        }
        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", userMessage ?: "以下是从图片中识别出的文字内容：\n$ocrText")
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(systemMessage)
                put(userMsg)
            })
            put("max_tokens", 8192)
            put("stream", false)
            if (thinking) {
                put("reasoning_effort", "high")
                put("thinking", JSONObject().apply { put("type", "enabled") })
            }
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        cancelCurrentRequest() // 取消上一次未完成的请求
        fun enqueueWithRetry(retryCount: Int) {
            val call = client.newCall(request)
            synchronized(this) { currentCall = call }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    onError("网络请求失败：${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (call.isCanceled()) { response.close(); return }
                    if (!response.isSuccessful) {
                        val bodyStr = try { response.body?.string() } catch (_: Exception) { null } ?: "无响应体"
                        response.close()
                        // 429 速率限制：自动重试（最多3次，间隔递增）
                        if (response.code == 429 && retryCount < 3) {
                            val delayMs = (retryCount + 1) * 3000L
                            android.util.Log.w("OpenAIApiService", "429 速率限制，${delayMs}ms 后第${retryCount + 1}次重试")
                            retryHandler.postDelayed({ enqueueWithRetry(retryCount + 1) }, delayMs)
                            return
                        }
                        onError("API响应错误 ${response.code}：$bodyStr")
                        return
                    }

                val body = response.body
                if (body == null) {
                    onError("API响应为空")
                    return
                }

                try {
                    val responseStr = body.string()
                    val json = JSONObject(responseStr)
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        onError("API返回空choices")
                        return
                    }
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    if (message == null) {
                        onError("API返回空message")
                        return
                    }

                    // 仅提取 content，忽略 reasoning_content（不在前端展示，也避免破坏 JSON 解析）
                    val content = message.opt("content")
                    val finalContent = if (content != null && content !== JSONObject.NULL) {
                        val cStr = content.toString()
                        if (cStr != "null") cStr else ""
                    } else ""

                    onComplete(finalContent)
                } catch (e: Exception) {
                    onError("解析响应失败：${e.message}")
                } finally {
                    try { body.close() } catch (_: Exception) {}
                }
            }
        })
        }
        enqueueWithRetry(0)
    }
}
