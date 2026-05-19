package com.example.aiassistant

import org.json.JSONObject
import java.util.UUID

data class StrategyScheme(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val r1ModelId: String,      // "none" = 不启用
    val r2ModelId: String,
    val r3ModelId: String,
    val r1Thinking: Boolean? = null,  // null=模型默认
    val r2Thinking: Boolean? = null,
    val r3Thinking: Boolean? = null,
    val isBuiltIn: Boolean = false,
    val customR2Prompt: String = ""   // 仅自定义R2策略使用
) {
    fun isSingleRound() = r2ModelId == "none" && r3ModelId == "none"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("r1ModelId", r1ModelId)
        put("r2ModelId", r2ModelId)
        put("r3ModelId", r3ModelId)
        if (r1Thinking != null) put("r1Thinking", r1Thinking)
        if (r2Thinking != null) put("r2Thinking", r2Thinking)
        if (r3Thinking != null) put("r3Thinking", r3Thinking)
        put("isBuiltIn", isBuiltIn)
        if (customR2Prompt.isNotEmpty()) put("customR2Prompt", customR2Prompt)
    }

    companion object {
        fun fromJson(json: JSONObject): StrategyScheme = StrategyScheme(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            r1ModelId = json.optString("r1ModelId", ""),
            r2ModelId = json.optString("r2ModelId", ""),
            r3ModelId = json.optString("r3ModelId", ""),
            r1Thinking = if (json.has("r1Thinking")) json.optBoolean("r1Thinking") else null,
            r2Thinking = if (json.has("r2Thinking")) json.optBoolean("r2Thinking") else null,
            r3Thinking = if (json.has("r3Thinking")) json.optBoolean("r3Thinking") else null,
            isBuiltIn = json.optBoolean("isBuiltIn", false),
            customR2Prompt = json.optString("customR2Prompt", "")
        )
    }
}

/** 单轮配置（传给 MultiPassAnalyzer） */
data class RoundConfig(
    val model: AiModelConfig,
    val thinking: Boolean       // 该轮是否开启思考
)
