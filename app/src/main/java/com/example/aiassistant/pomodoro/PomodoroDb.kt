package com.example.aiassistant.pomodoro

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PomodoroDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "pomodoro.db"
        private const val DB_VERSION = 1

        private const val TABLE_SESSIONS = "focus_sessions"
        const val COL_ID = "id"
        const val COL_TASK_TITLE = "task_title"
        const val COL_TAG = "tag"
        const val COL_DURATION = "duration_minutes"
        const val COL_TARGET = "target_minutes"
        const val COL_COMPLETED = "is_completed"
        const val COL_PLAN_TASK_ID = "plan_task_id"
        const val COL_STARTED_AT = "started_at"
        const val COL_FINISHED_AT = "finished_at"
        const val COL_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_SESSIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TASK_TITLE TEXT NOT NULL DEFAULT '',
                $COL_TAG TEXT NOT NULL DEFAULT '',
                $COL_DURATION INTEGER NOT NULL DEFAULT 0,
                $COL_TARGET INTEGER NOT NULL DEFAULT 25,
                $COL_COMPLETED INTEGER NOT NULL DEFAULT 0,
                $COL_PLAN_TASK_ID INTEGER NOT NULL DEFAULT -1,
                $COL_STARTED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_FINISHED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_sessions_created ON $TABLE_SESSIONS($COL_CREATED_AT)")
        db.execSQL("CREATE INDEX idx_sessions_tag ON $TABLE_SESSIONS($COL_TAG)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ── 插入 ──

    fun insertSession(session: FocusSession): Long {
        val cv = ContentValues().apply {
            put(COL_TASK_TITLE, session.taskTitle)
            put(COL_TAG, session.tag)
            put(COL_DURATION, session.durationMinutes)
            put(COL_TARGET, session.targetMinutes)
            put(COL_COMPLETED, if (session.isCompleted) 1 else 0)
            put(COL_PLAN_TASK_ID, session.planTaskId)
            put(COL_STARTED_AT, session.startedAt)
            put(COL_FINISHED_AT, session.finishedAt)
            put(COL_CREATED_AT, session.createdAt)
        }
        return writableDatabase.insert(TABLE_SESSIONS, null, cv)
    }

    // ── 更新 ──

    fun updateSession(session: FocusSession): Int {
        val cv = ContentValues().apply {
            put(COL_TASK_TITLE, session.taskTitle)
            put(COL_TAG, session.tag)
            put(COL_DURATION, session.durationMinutes)
            put(COL_TARGET, session.targetMinutes)
            put(COL_COMPLETED, if (session.isCompleted) 1 else 0)
            put(COL_FINISHED_AT, session.finishedAt)
        }
        return writableDatabase.update(TABLE_SESSIONS, cv, "$COL_ID=?", arrayOf(session.id.toString()))
    }

    fun deleteSession(id: Long): Int {
        return writableDatabase.delete(TABLE_SESSIONS, "$COL_ID=?", arrayOf(id.toString()))
    }

    // ── 查询 ──

    fun getSession(id: Long): FocusSession? {
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, null, "$COL_ID=?", arrayOf(id.toString()),
            null, null, null
        )
        return cursor.use { if (it.moveToFirst()) cursorToSession(it) else null }
    }

    fun getSessionsByDate(dateStartMs: Long, dateEndMs: Long): List<FocusSession> {
        val sessions = mutableListOf<FocusSession>()
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, null,
            "$COL_STARTED_AT >= ? AND $COL_STARTED_AT < ?",
            arrayOf(dateStartMs.toString(), dateEndMs.toString()),
            null, null, "$COL_STARTED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                sessions.add(cursorToSession(it))
            }
        }
        return sessions
    }

    fun getRecentSessions(limit: Int = 50): List<FocusSession> {
        val sessions = mutableListOf<FocusSession>()
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, null, null, null,
            null, null, "$COL_STARTED_AT DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                sessions.add(cursorToSession(it))
            }
        }
        return sessions
    }

    fun getTodayStats(): DailyStats {
        val (startMs, endMs) = getTodayRange()
        val cursor = readableDatabase.rawQuery("""
            SELECT
                COUNT(*) as total,
                SUM(CASE WHEN $COL_COMPLETED = 1 THEN 1 ELSE 0 END) as completed,
                SUM(CASE WHEN $COL_COMPLETED = 1 THEN $COL_DURATION ELSE 0 END) as focus_minutes
            FROM $TABLE_SESSIONS
            WHERE $COL_STARTED_AT >= ? AND $COL_STARTED_AT < ?
        """.trimIndent(), arrayOf(startMs.toString(), endMs.toString()))

        cursor.use {
            if (it.moveToFirst()) {
                return DailyStats(
                    date = getTodayDateStr(),
                    totalFocusMinutes = it.getInt(it.getColumnIndexOrThrow("focus_minutes")),
                    tomatoCount = it.getInt(it.getColumnIndexOrThrow("total")),
                    completedCount = it.getInt(it.getColumnIndexOrThrow("completed")),
                    targetCount = 0
                )
            }
        }
        return DailyStats(getTodayDateStr(), 0, 0, 0, 0)
    }

    fun getStatsByDateRange(startMs: Long, endMs: Long): DailyStats {
        val cursor = readableDatabase.rawQuery("""
            SELECT
                COUNT(*) as total,
                SUM(CASE WHEN $COL_COMPLETED = 1 THEN 1 ELSE 0 END) as completed,
                SUM(CASE WHEN $COL_COMPLETED = 1 THEN $COL_DURATION ELSE 0 END) as focus_minutes
            FROM $TABLE_SESSIONS
            WHERE $COL_STARTED_AT >= ? AND $COL_STARTED_AT < ?
        """.trimIndent(), arrayOf(startMs.toString(), endMs.toString()))

        cursor.use {
            if (it.moveToFirst()) {
                return DailyStats(
                    date = "",
                    totalFocusMinutes = it.getInt(it.getColumnIndexOrThrow("focus_minutes")),
                    tomatoCount = it.getInt(it.getColumnIndexOrThrow("total")),
                    completedCount = it.getInt(it.getColumnIndexOrThrow("completed")),
                    targetCount = 0
                )
            }
        }
        return DailyStats("", 0, 0, 0, 0)
    }

    fun getDailyStatsForWeek(): List<DailyStats> {
        val stats = mutableListOf<DailyStats>()
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        // 回到本周一
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val offset = if (dow == java.util.Calendar.SUNDAY) -6 else -(dow - java.util.Calendar.MONDAY)
        cal.add(java.util.Calendar.DAY_OF_MONTH, offset)

        for (i in 0 until 7) {
            val startMs = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            val endMs = cal.timeInMillis

            val cursor = readableDatabase.rawQuery("""
                SELECT
                    COUNT(*) as total,
                    SUM(CASE WHEN $COL_COMPLETED = 1 THEN 1 ELSE 0 END) as completed,
                    SUM(CASE WHEN $COL_COMPLETED = 1 THEN $COL_DURATION ELSE 0 END) as focus_minutes
                FROM $TABLE_SESSIONS
                WHERE $COL_STARTED_AT >= ? AND $COL_STARTED_AT < ?
            """.trimIndent(), arrayOf(startMs.toString(), endMs.toString()))

            cursor.use {
                if (it.moveToFirst()) {
                    stats.add(DailyStats(
                        date = formatDate(startMs),
                        totalFocusMinutes = it.getInt(it.getColumnIndexOrThrow("focus_minutes")),
                        tomatoCount = it.getInt(it.getColumnIndexOrThrow("total")),
                        completedCount = it.getInt(it.getColumnIndexOrThrow("completed")),
                        targetCount = 0
                    ))
                } else {
                    stats.add(DailyStats(formatDate(startMs), 0, 0, 0, 0))
                }
            }
        }
        return stats
    }

    fun getTagDistribution(startMs: Long, endMs: Long): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        val cursor = readableDatabase.rawQuery("""
            SELECT $COL_TAG, COUNT(*) as cnt
            FROM $TABLE_SESSIONS
            WHERE $COL_STARTED_AT >= ? AND $COL_STARTED_AT < ? AND $COL_COMPLETED = 1 AND $COL_TAG != ''
            GROUP BY $COL_TAG
            ORDER BY cnt DESC
        """.trimIndent(), arrayOf(startMs.toString(), endMs.toString()))

        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    it.getString(it.getColumnIndexOrThrow(COL_TAG)) to
                    it.getInt(it.getColumnIndexOrThrow("cnt"))
                )
            }
        }
        return result
    }

    // ── 辅助 ──

    private fun cursorToSession(c: Cursor) = FocusSession(
        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
        taskTitle = c.getString(c.getColumnIndexOrThrow(COL_TASK_TITLE)) ?: "",
        tag = c.getString(c.getColumnIndexOrThrow(COL_TAG)) ?: "",
        durationMinutes = c.getInt(c.getColumnIndexOrThrow(COL_DURATION)),
        targetMinutes = c.getInt(c.getColumnIndexOrThrow(COL_TARGET)),
        isCompleted = c.getInt(c.getColumnIndexOrThrow(COL_COMPLETED)) == 1,
        planTaskId = c.getLong(c.getColumnIndexOrThrow(COL_PLAN_TASK_ID)),
        startedAt = c.getLong(c.getColumnIndexOrThrow(COL_STARTED_AT)),
        finishedAt = c.getLong(c.getColumnIndexOrThrow(COL_FINISHED_AT)),
        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT))
    )

    private fun getTodayRange(): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return start to cal.timeInMillis
    }

    private fun getTodayDateStr(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    private fun formatDate(ms: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }
}
