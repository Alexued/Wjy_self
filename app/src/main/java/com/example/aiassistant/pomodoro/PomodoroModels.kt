package com.example.aiassistant.pomodoro

/**
 * 番茄专注记录
 */
data class FocusSession(
    val id: Long = 0,
    val taskTitle: String = "",
    val tag: String = "",
    val durationMinutes: Int = 25,
    val targetMinutes: Int = 25,
    val isCompleted: Boolean = false,
    val planTaskId: Long = -1,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TAG_ALL = ""
        const val TAG_CET = "行测"
        const val TAG_ESSAY = "申论"
        const val TAG_INTERVIEW = "面试"
        const val TAG_COMMON = "常识"
        const val TAG_MATH = "数量关系"
        const val TAG_OTHER = "其他"

        val DEFAULT_TAGS = listOf(TAG_CET, TAG_ESSAY, TAG_INTERVIEW, TAG_COMMON, TAG_MATH, TAG_OTHER)
    }
}

/**
 * 番茄钟状态
 */
enum class TimerState {
    IDLE,       // 空闲
    FOCUS,      // 专注中
    SHORT_BREAK,// 短休息
    LONG_BREAK, // 长休息
    PAUSED      // 暂停
}

/**
 * 番茄钟配置
 */
data class PomodoroConfig(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val longBreakInterval: Int = 4,
    val autoStartBreak: Boolean = true,
    val autoStartFocus: Boolean = false,
    val breakEndReminder: Boolean = true,
    val keepScreenOn: Boolean = false,
    val dailyTarget: Int = 8,
    val vibrationEnabled: Boolean = true,
    val whiteNoiseVolume: Float = 0.6f,
    val selectedWhiteNoise: String = ""
)

/**
 * 每日统计
 */
data class DailyStats(
    val date: String,
    val totalFocusMinutes: Int,
    val tomatoCount: Int,
    val completedCount: Int,
    val targetCount: Int
)
