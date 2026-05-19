package com.example.aiassistant.plan

data class PlanTask(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val date: String,           // YYYY-MM-DD
    val isCompleted: Boolean = false,
    val priority: Int = 0,      // 0=普通, 1=重要, 2=紧急
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    companion object {
        const val PRIORITY_NORMAL = 0
        const val PRIORITY_IMPORTANT = 1
        const val PRIORITY_URGENT = 2
    }
}
