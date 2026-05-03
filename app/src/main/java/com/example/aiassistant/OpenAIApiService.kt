package com.example.aiassistant

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 封装 OpenAI 格式的 API 调用
 * 兼容：OpenAI / 任意 OpenAI Compatible 服务
 * 专供纯文本流式请求（接收 OCR 提取的内容）
 *
 */
object OpenAIApiService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 流式需要更长的读取超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

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
                // 预热失败不影响正常使用
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 纯文本流式 API（OCR 识别后专用）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 将 OCR 提取的文本发送给 AI，使用 SSE 流式响应
     *
     * @param ocrText     OCR 识别出的文本
     * @param baseUrl     API BaseUrl
     * @param apiKey      API Key
     * @param model       模型名称
     * @param prompt      用户自定义提示词
     * @param onChunk     每收到一段文字时回调
     * @param onComplete  全部完成后回调
     * @param onError     失败回调
     */
    fun analyzeTextStream(
        ocrText: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        onChunk: (deltaText: String, fullTextSoFar: String) -> Unit,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        // 构造纯文本请求体
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", "$prompt\n\n以下是从图片中识别出的文字内容：\n$ocrText")
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { put(userMessage) })
            put("max_tokens", 2048)
            put("stream", true)
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "无响应体"
                    onError("API响应错误 ${response.code}：$bodyStr")
                    return
                }

                val body = response.body
                if (body == null) {
                    onError("API响应为空")
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(body.byteStream()))
                    val fullText = StringBuilder()

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (!trimmed.startsWith("data:")) continue

                        val data = trimmed.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices") ?: continue
                            if (choices.length() == 0) continue
                            val delta = choices.getJSONObject(0)
                                .optJSONObject("delta") ?: continue
                                
                            var appendStr = ""
                            
                            // 安全提取 reasoning_content
                            val r = delta.opt("reasoning_content")
                            if (r != null && r !== org.json.JSONObject.NULL) {
                                val rStr = r.toString()
                                if (rStr != "null") appendStr += rStr
                            }
                            
                            // 安全提取 content
                            val c = delta.opt("content")
                            if (c != null && c !== org.json.JSONObject.NULL) {
                                val cStr = c.toString()
                                if (cStr != "null") appendStr += cStr
                            }
                            
                            if (appendStr.isNotEmpty()) {
                                fullText.append(appendStr)
                                onChunk(appendStr, fullText.toString())
                            }
                        } catch (_: Exception) {
                            // 跳过无法解析的行
                        }
                    }

                    reader.close()
                    onComplete(fullText.toString())
                } catch (e: Exception) {
                    onError("读取流式响应失败：${e.message}")
                }
            }
        })
    }
}
