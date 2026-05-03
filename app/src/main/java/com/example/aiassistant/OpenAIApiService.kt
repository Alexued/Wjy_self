package com.example.aiassistant

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 封装 OpenAI 格式的 Vision API 调用
 * 兼容：OpenAI / Azure / 硅基流动 / 任意 OpenAI Compatible 服务
 *
 * 优化：
 *  ① 图片自动缩放到 MAX_IMAGE_SIDE（减少 80%+ 传输体积）
 *  ② JPEG 质量降低到 60（视觉无损，体积减半）
 *  ③ 支持 SSE 流式响应（首字 1-2s 内展示）
 */
object OpenAIApiService {

    /** 图片最大边长（像素）。Vision 模型对超大图无额外收益，1024 是最佳平衡点。 */
    private const val MAX_IMAGE_SIDE = 1024

    /** JPEG 压缩质量。60 对 Vision 模型几乎无影响，但体积比 80 减少约 40%。 */
    private const val JPEG_QUALITY = 60

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 流式需要更长的读取超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 将 Bitmap 缩放 + 压缩为 base64 字符串
     * - 长边超过 MAX_IMAGE_SIDE 时等比缩放
     * - 使用 JPEG_QUALITY 压缩
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = scaleBitmap(bitmap, MAX_IMAGE_SIDE)
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        if (scaled !== bitmap) {
            scaled.recycle()   // 只回收新创建的缩放图
        }
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * 等比缩放 Bitmap，使最大边不超过 maxSide
     * 如果已经小于 maxSide，直接返回原图（不创建新对象）
     */
    private fun scaleBitmap(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap

        val scale = maxSide.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 流式 API（推荐）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 发送截图到 OpenAI Vision API，使用 SSE 流式响应
     *
     * @param bitmap      截图
     * @param baseUrl     API BaseUrl，例如 https://api.openai.com/v1
     * @param apiKey      API Key
     * @param model       模型名称，例如 gpt-4o
     * @param prompt      用户自定义提示词
     * @param onChunk     每收到一段文字时回调（增量文本），在 OkHttp 线程调用
     * @param onComplete  全部完成后回调（完整文本）
     * @param onError     失败回调
     */
    fun analyzeImageStream(
        bitmap: Bitmap,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        onChunk: (deltaText: String, fullTextSoFar: String) -> Unit,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val base64Image = bitmapToBase64(bitmap)

        // 构造标准化 OpenAI Vision 请求体
        val imageContent = JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
            })
        }
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        }
        val contentArray = JSONArray().apply {
            put(imageContent)
            put(textContent)
        }
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { put(userMessage) })
            put("max_tokens", 1024)
            put("stream", true)   // 🔑 开启流式
        }

        // 构造 HTTP 请求
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
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                fullText.append(content)
                                onChunk(content, fullText.toString())
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

    // ─────────────────────────────────────────────────────────────────────
    // 非流式 API（保留作为 fallback）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 发送截图到 OpenAI Vision API 并异步回调结果
     *
     * @param bitmap      截图
     * @param baseUrl     API BaseUrl，例如 https://api.openai.com/v1
     * @param apiKey      API Key
     * @param model       模型名称，例如 gpt-4o
     * @param prompt      用户自定义提示词
     * @param onSuccess   成功回调（在子线程，需要 post 到主线程）
     * @param onError     失败回调
     */
    fun analyzeImage(
        bitmap: Bitmap,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val base64Image = bitmapToBase64(bitmap)

        // 构造标准化 OpenAI Vision 请求体 (被火山引擎完全兼容)
        val imageContent = JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
            })
        }
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        }
        val contentArray = JSONArray().apply {
            put(imageContent)
            put(textContent)
        }
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { put(userMessage) })
            put("max_tokens", 1024)
        }

        // 构造 HTTP 请求
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string()
                if (!response.isSuccessful || bodyStr == null) {
                    onError("API响应错误 ${response.code}：${bodyStr ?: "无响应体"}")
                    return
                }
                try {
                    val json = JSONObject(bodyStr)
                    val content = json
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    onSuccess(content)
                } catch (e: Exception) {
                    onError("解析响应失败：${e.message}\n原始响应：$bodyStr")
                }
            }
        })
    }
}
