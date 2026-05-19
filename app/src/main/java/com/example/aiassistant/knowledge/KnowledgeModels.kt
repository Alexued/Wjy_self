package com.example.aiassistant.knowledge

data class KnowledgeCategory(
    val id: String,
    val name: String,
    val icon: String,
    val isVisible: Boolean = true,
    val sortOrder: Int = 0
)

data class KnowledgeCard(
    val id: Long = 0,
    val category: String,
    val title: String,
    val content: String,
    val tags: String = "[]",
    val isCustom: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)
