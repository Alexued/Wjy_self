package com.example.aiassistant

/**
 * 公务员考试7种题型定义。
 * ordinal 用作持久化 ID（SharedPreferences 存储）。
 */
enum class QuestionType(
    val displayName: String,
    val usesVision: Boolean,
    val outputSchemaDesc: String
) {
    PIAN_DUAN_YUE_DU(
        displayName = "片段阅读",
        usesVision = false,
        outputSchemaDesc = "question_type, passage_type, options_analysis[], logical_labels[]"
    ),
    LUO_JI_TIAN_KONG(
        displayName = "逻辑填空",
        usesVision = false,
        outputSchemaDesc = "context, blanks[{position, candidates[], answer, reason}], options_analysis[]"
    ),
    YU_JU_BIAO_DA(
        displayName = "语句表达",
        usesVision = false,
        outputSchemaDesc = "sentence, error_type, correction, analysis"
    ),
    TU_XING_TUI_LI(
        displayName = "图形推理",
        usesVision = true,
        outputSchemaDesc = "pattern_type, rule_description, visual_analysis, options_analysis[]"
    ),
    DING_YI_PAN_DUAN(
        displayName = "定义判断",
        usesVision = false,
        outputSchemaDesc = "definition_text, key_elements[], options_analysis[{option, matches, reason}]"
    ),
    LEI_BI_TUI_LI(
        displayName = "类比推理",
        usesVision = false,
        outputSchemaDesc = "word_pair, relationship_type, relationship_analysis, options_analysis[]"
    ),
    LUO_JI_PAN_DUAN(
        displayName = "逻辑判断",
        usesVision = false,
        outputSchemaDesc = "argument_structure, reasoning_type, logical_chain[], options_analysis[]"
    );

    companion object {
        fun fromOrdinal(ordinal: Int): QuestionType =
            entries.getOrElse(ordinal) { PIAN_DUAN_YUE_DU }

        /** SharedPreferences key 前缀，用于按题型存储自定义 prompt */
        const val PROMPT_KEY_PREFIX = "prompt_type_"
    }
}
