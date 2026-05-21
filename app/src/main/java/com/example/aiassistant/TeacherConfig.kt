package com.example.aiassistant

import org.json.JSONObject

/**
 * 一位"老师"的完整配置：7 种题型 prompt + 多轮策略 + R3 审核 + 自检指令。
 * 支持 JSON 序列化/反序列化，用于 assets 加载和文件导入导出。
 */
data class TeacherConfig(
    val id: String,
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val prompts: Map<QuestionType, String>,
    val r3Prompt: String,
    val selfCheckInstruction: String,
    val defaultStrategy: Int = 0,
    val customR2Prompts: Map<QuestionType, String> = emptyMap()
) {
    companion object {
        fun fromJson(json: JSONObject): TeacherConfig {
            val promptsMap = mutableMapOf<QuestionType, String>()
            val promptsJson = json.optJSONObject("prompts")
            if (promptsJson != null) {
                for (type in QuestionType.entries) {
                    promptsMap[type] = promptsJson.optString(type.name, "")
                }
            }
            val customR2Map = mutableMapOf<QuestionType, String>()
            val customR2Json = json.optJSONObject("custom_r2_prompts")
            if (customR2Json != null) {
                for (type in QuestionType.entries) {
                    val v = customR2Json.optString(type.name, "")
                    if (v.isNotEmpty()) customR2Map[type] = v
                }
            }
            return TeacherConfig(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                description = json.optString("description", ""),
                version = json.optInt("version", 1),
                prompts = promptsMap,
                r3Prompt = json.optString("r3_prompt", ""),
                selfCheckInstruction = json.optString("self_check_instruction", ""),
                defaultStrategy = json.optInt("default_strategy", 0),
                customR2Prompts = customR2Map
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("version", version)
        put("prompts", JSONObject().apply {
            for ((type, prompt) in prompts) {
                put(type.name, prompt)
            }
        })
        put("r3_prompt", r3Prompt)
        put("self_check_instruction", selfCheckInstruction)
        put("default_strategy", defaultStrategy)
        if (customR2Prompts.isNotEmpty()) {
            put("custom_r2_prompts", JSONObject().apply {
                for ((type, prompt) in customR2Prompts) {
                    put(type.name, prompt)
                }
            })
        }
    }

    fun getPrompt(type: QuestionType): String =
        prompts[type]?.takeIf { it.isNotBlank() } ?: ""

    fun getCustomR2Prompt(type: QuestionType): String? =
        customR2Prompts[type]?.takeIf { it.isNotBlank() }
}
