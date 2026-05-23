package com.example.aiassistant.questionbank

/**
 * 题库模块树状结构
 */
data class QuestionModule(
    val id: String,
    val name: String,
    val parentId: String? = null,
    var questionCount: Int = 0,
    var completedCount: Int = 0,
    val children: MutableList<QuestionModule> = mutableListOf()
)

/**
 * 题目数据
 */
data class Question(
    val id: String,
    val stem: String,
    val stemHtml: String = "",
    val options: List<QuestionOption>,
    val answer: String,
    val analysis: String,
    val knowledgePoint: String,
    val source: String,
    val rate: Int,
    val titleImages: List<String>,
    val materialId: String = "",
    val materialContent: String = "",
    val difficulty: String = "medium"
)

data class QuestionOption(
    val text: String,
    val html: String = "",
    val images: List<String> = emptyList()
)

/**
 * 材料题（一拖多）
 */
data class QuestionMaterial(
    val id: String,
    val content: String,
    val questions: List<Question>
)

/**
 * 做题进度
 */
data class PracticeProgress(
    val currentIndex: Int,
    val totalCount: Int,
    val correctCount: Int,
    val wrongCount: Int
)
