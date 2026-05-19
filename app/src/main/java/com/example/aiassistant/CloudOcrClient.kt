package com.example.aiassistant

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object CloudOcrClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentCall: Call? = null

    fun cancelCurrentRequest() {
        val call = synchronized(this) {
            val c = currentCall
            currentCall = null
            c
        }
        call?.let { if (!it.isCanceled()) it.cancel() }
    }

    fun parseLayout(
        bitmap: Bitmap,
        url: String,
        token: String,
        onSuccess: (markdownText: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val requestJson = buildBaseRequest(bitmap).apply {
            put("useLayoutDetection", true)
            put("useChartRecognition", false)
            put("useSealRecognition", false)
            put("useOcrForImageBlock", false)
            put("mergeTables", false)
            put("relevelTitles", false)
            put("layoutShapeMode", "auto")
            put("promptLabel", "ocr")
        }
        executeRequest(url, token, requestJson, onSuccess = { json ->
            val result = json.optJSONObject("result")
                ?: run { onError("云端 OCR 响应缺少 result 字段"); return@executeRequest }
            val arr = result.optJSONArray("layoutParsingResults")
            if (arr == null || arr.length() == 0) {
                onError("云端 OCR 响应缺少 layoutParsingResults"); return@executeRequest
            }
            val page = arr.getJSONObject(0)
            val blocks = page.optJSONObject("prunedResult")
                ?.optJSONArray("parsing_res_list")
            if (blocks == null || blocks.length() == 0) {
                onError("云端 OCR 返回空文本"); return@executeRequest
            }
            // 只取 text 和 paragraph_title 类型的块（包含下划线识别）
            val targetLabels = setOf("text", "paragraph_title")
            val filtered = mutableListOf<JSONObject>()
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                val label = b.optString("block_label", "")
                val content = b.optString("block_content", "").trim()
                if (label in targetLabels && content.isNotEmpty()) {
                    filtered.add(b)
                }
            }
            // 按 block_order 排序（null 放最后）
            filtered.sortBy {
                if (it.has("block_order") && !it.isNull("block_order")) it.optInt("block_order") else Int.MAX_VALUE
            }
            val text = filtered.joinToString("\n\n") { it.optString("block_content", "") }
            if (text.isBlank()) { onError("云端 OCR 返回空文本"); return@executeRequest }
            onSuccess(text)
        }, onError)
    }

    fun parseText(
        bitmap: Bitmap,
        url: String,
        token: String,
        onSuccess: (text: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val requestJson = buildBaseRequest(bitmap)
        executeRequest(url, token, requestJson, onSuccess = { json ->
            val result = json.optJSONObject("result")
                ?: run { onError("云端 OCR 响应缺少 result 字段"); return@executeRequest }
            val arr = result.optJSONArray("ocrResults")
            if (arr == null || arr.length() == 0) {
                onError("云端 OCR 响应缺少 ocrResults"); return@executeRequest
            }
            val text = arr.getJSONObject(0).optString("prunedResult", "")
            if (text.isBlank()) { onError("云端 OCR 返回空文本"); return@executeRequest }
            onSuccess(text)
        }, onError)
    }

    private fun buildBaseRequest(bitmap: Bitmap): JSONObject {
        val jpegBytes: ByteArray
        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            jpegBytes = baos.toByteArray()
        } catch (e: Exception) {
            throw IllegalStateException("图片编码失败：${e.message}")
        }
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        return JSONObject().apply {
            put("file", base64Image)
            put("fileType", 1)
            put("useDocOrientationClassify", false)
            put("useDocUnwarping", false)
            put("useTextlineOrientation", false)
            put("visualize", false)  // 不返回可视化图，节省处理时间和响应体积
        }
    }

    private fun executeRequest(
        url: String,
        token: String,
        requestJson: JSONObject,
        onSuccess: (json: JSONObject) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = Request.Builder()
            .url(url.trimEnd('/'))
            .addHeader("Authorization", "token $token")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        cancelCurrentRequest()
        val call = client.newCall(request)
        synchronized(this) { currentCall = call }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("云端 OCR 请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "无响应体"
                    onError("云端 OCR 响应错误 ${response.code}：$bodyStr")
                    return
                }
                val body = response.body
                if (body == null) { onError("云端 OCR 响应为空"); return }
                try {
                    onSuccess(JSONObject(body.string()))
                } catch (e: Exception) {
                    onError("解析云端 OCR 响应失败：${e.message}")
                } finally {
                    try { body.close() } catch (_: Exception) {}
                }
            }
        })
    }
}
