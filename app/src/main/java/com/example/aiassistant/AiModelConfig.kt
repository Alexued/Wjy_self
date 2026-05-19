package com.example.aiassistant

import org.json.JSONObject
import java.util.UUID

data class AiModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String = AppPreferences.DEFAULT_BASE_URL,
    val apiKey: String = AppPreferences.DEFAULT_API_KEY,
    val model: String = "deepseek-chat",
    val thinkingDefault: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("thinkingDefault", thinkingDefault)
    }

    companion object {
        fun fromJson(json: JSONObject): AiModelConfig = AiModelConfig(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            baseUrl = json.optString("baseUrl", AppPreferences.DEFAULT_BASE_URL),
            apiKey = json.optString("apiKey", AppPreferences.DEFAULT_API_KEY),
            model = json.optString("model", "deepseek-chat"),
            thinkingDefault = json.optBoolean("thinkingDefault", false)
        )
    }
}
