package com.example.aiassistant.pomodoro

import android.content.Context

object PomodoroManager {

    private lateinit var db: PomodoroDb

    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = PomodoroDb(context.applicationContext)
        }
    }

    private fun ensureDb(): PomodoroDb {
        check(::db.isInitialized) { "PomodoroManager.init() not called" }
        return db
    }

    // ── 会话 CRUD ──

    fun startSession(taskTitle: String, tag: String, targetMinutes: Int, planTaskId: Long = -1): Long {
        val session = FocusSession(
            taskTitle = taskTitle,
            tag = tag,
            targetMinutes = targetMinutes,
            startedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        return ensureDb().insertSession(session)
    }

    fun completeSession(id: Long, durationMinutes: Int): Int {
        if (id < 0) return 0
        val session = ensureDb().getSession(id) ?: return 0
        return ensureDb().updateSession(
            session.copy(
                durationMinutes = durationMinutes,
                isCompleted = true,
                finishedAt = System.currentTimeMillis()
            )
        )
    }

    fun cancelSession(id: Long, durationMinutes: Int): Int {
        if (id < 0) return 0
        val session = ensureDb().getSession(id) ?: return 0
        return ensureDb().updateSession(
            session.copy(
                durationMinutes = durationMinutes,
                isCompleted = false,
                finishedAt = System.currentTimeMillis()
            )
        )
    }

    fun deleteSession(id: Long): Int = ensureDb().deleteSession(id)

    // ── 查询 ──

    fun getRecentSessions(limit: Int = 50): List<FocusSession> = ensureDb().getRecentSessions(limit)

    fun getSessionsByDate(dateStartMs: Long, dateEndMs: Long): List<FocusSession> =
        ensureDb().getSessionsByDate(dateStartMs, dateEndMs)

    fun getTodayStats(): DailyStats = ensureDb().getTodayStats()

    fun getDailyStatsForWeek(): List<DailyStats> = ensureDb().getDailyStatsForWeek()

    fun getTagDistribution(startMs: Long, endMs: Long): List<Pair<String, Int>> =
        ensureDb().getTagDistribution(startMs, endMs)

    fun getStatsByDateRange(startMs: Long, endMs: Long): DailyStats =
        ensureDb().getStatsByDateRange(startMs, endMs)

    /**
     * 清理孤立会话（进程被杀后遗留的未完成记录）
     */
    fun cleanOrphanedSessions() {
        val db = ensureDb()
        val sessions = db.getRecentSessions(20)
        for (s in sessions) {
            if (!s.isCompleted && s.finishedAt == 0L && s.startedAt > 0) {
                val elapsed = ((System.currentTimeMillis() - s.startedAt) / 60000).toInt()
                if (elapsed > s.targetMinutes + 5) {
                    db.updateSession(s.copy(durationMinutes = elapsed, isCompleted = false, finishedAt = s.startedAt + elapsed * 60000L))
                }
            }
        }
    }
}
