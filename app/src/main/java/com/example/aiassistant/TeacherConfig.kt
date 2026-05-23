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
    val prompts: Map<QuestionType, String>
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
            return TeacherConfig(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                description = json.optString("description", ""),
                version = json.optInt("version", 1),
                prompts = promptsMap
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
    }

    fun getPrompt(type: QuestionType): String =
        prompts[type]?.takeIf { it.isNotBlank() } ?: ""
}
