package com.example.aiassistant

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ModelTester {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun test(config: AiModelConfig, onComplete: (String) -> Unit, onError: (String) -> Unit) {
        val request = try { buildRequest(config) } catch (e: Exception) {
            onError("构建测试请求失败：${e.message}")
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("网络请求失败：${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = try { resp.body?.string().orEmpty() } catch (_: Exception) { "" }
                    if (!resp.isSuccessful) {
                        onError("HTTP ${resp.code}：${body.take(500)}")
                        return
                    }
                    val text = parseText(body, config.apiType)
                    if (text.isBlank()) onError("接口可连接，但未返回有效文本：${body.take(500)}")
                    else onComplete(text.take(200))
                }
            }
        })
    }


    fun fetchModels(baseUrl: String, apiKey: String, apiType: String, onComplete: (List<String>) -> Unit, onError: (String) -> Unit) {
        val url = try { buildModelsUrl(baseUrl, apiKey, apiType) } catch (e: Exception) {
            onError("构建模型列表请求失败：${e.message}")
            return
        }
        val builder = Request.Builder().url(url).get()
            .addHeader("Content-Type", "application/json")
        when (apiType.lowercase()) {
            "anthropic" -> builder.addHeader("x-api-key", apiKey).addHeader("anthropic-version", "2023-06-01")
            "gemini" -> Unit
            else -> builder.addHeader("Authorization", "Bearer $apiKey")
        }
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("模型列表请求失败：${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = try { resp.body?.string().orEmpty() } catch (_: Exception) { "" }
                    if (!resp.isSuccessful) {
                        onError("HTTP ${resp.code}：${body.take(500)}")
                        return
                    }
                    val models = parseModels(body, apiType)
                    if (models.isEmpty()) onError("接口可连接，但没有识别到模型列表：${body.take(500)}")
                    else onComplete(models)
                }
            }
        })
    }

    private fun buildModelsUrl(baseUrl: String, apiKey: String, apiType: String): String {
        val base = baseUrl.trimEnd('/')
        return when (apiType.lowercase()) {
            "gemini" -> "$base/v1beta/models?key=$apiKey"
            "anthropic" -> "$base/v1/models"
            else -> base.let { if (it.endsWith("/v1")) it else "$it/v1" } + "/models"
        }
    }

    private fun parseModels(body: String, apiType: String): List<String> = try {
        val json = JSONObject(body)
        val arr = when (apiType.lowercase()) {
            "gemini" -> json.optJSONArray("models") ?: JSONArray()
            else -> json.optJSONArray("data") ?: JSONArray()
        }
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = when (apiType.lowercase()) {
                "gemini" -> obj.optString("name").removePrefix("models/")
                else -> obj.optString("id")
            }
            if (id.isNotBlank()) result.add(id)
        }
        result.distinct().sorted()
    } catch (e: Exception) { emptyList() }


    private fun buildRequest(config: AiModelConfig): Request {
        val mediaType = "application/json".toMediaType()
        val apiType = config.apiType.lowercase()
        return when (apiType) {
            "anthropic" -> {
                val url = config.baseUrl.trimEnd('/') + "/v1/messages"
                val body = JSONObject().apply {
                    put("model", config.model)
                    put("max_tokens", 64)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user"); put("content", "请只回复 OK")
                    }))
                }
                Request.Builder().url(url)
                    .addHeader("x-api-key", config.apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType)).build()
            }
            "gemini" -> {
                val base = config.baseUrl.trimEnd('/')
                val url = "$base/v1beta/models/${config.model}:generateContent?key=${config.apiKey}"
                val body = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", "请只回复 OK")))
                    }))
                    put("generationConfig", JSONObject().put("maxOutputTokens", 64))
                }
                Request.Builder().url(url).addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType)).build()
            }
            else -> {
                val base = config.baseUrl.trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
                val body = JSONObject().apply {
                    put("model", config.model)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user"); put("content", "请只回复 OK")
                    }))
                    put("max_tokens", 128)
                }
                Request.Builder().url("$base/chat/completions")
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType)).build()
            }
        }
    }

    private fun parseText(body: String, apiType: String): String = try {
        val json = JSONObject(body)
        when (apiType.lowercase()) {
            "anthropic" -> {
                val arr = json.optJSONArray("content") ?: JSONArray()
                buildString { for (i in 0 until arr.length()) append(arr.optJSONObject(i)?.optString("text").orEmpty()) }
            }
            "gemini" -> json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
            else -> json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
        }
    } catch (e: Exception) { "" }
}
