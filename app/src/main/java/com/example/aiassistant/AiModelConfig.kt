package com.example.aiassistant

import org.json.JSONObject
import java.util.UUID

data class AiModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String = AppPreferences.DEFAULT_BASE_URL,
    val apiKey: String = AppPreferences.DEFAULT_API_KEY,
    val model: String = "deepseek-chat",
    val thinkingDefault: Boolean = false,
    val isVision: Boolean = false,
    val apiType: String = "openai",       // 支持 "openai", "anthropic", "gemini"
    val thinkingBudget: Int = 4096        // 思考强度设置
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("thinkingDefault", thinkingDefault)
        put("isVision", isVision)
        put("apiType", apiType)
        put("thinkingBudget", thinkingBudget)
    }

    companion object {
        fun fromJson(json: JSONObject): AiModelConfig = AiModelConfig(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            baseUrl = json.optString("baseUrl", AppPreferences.DEFAULT_BASE_URL),
            apiKey = json.optString("apiKey", AppPreferences.DEFAULT_API_KEY),
            model = json.optString("model", "deepseek-chat"),
            thinkingDefault = json.optBoolean("thinkingDefault", false),
            isVision = json.optBoolean("isVision", false),
            apiType = json.optString("apiType", "openai"),
            thinkingBudget = json.optInt("thinkingBudget", 4096)
        )
    }
}
