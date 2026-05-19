package com.example.aiassistant.plan

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PlanDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "plan.db"
        private const val DB_VERSION = 1

        const val T_TASKS = "tasks"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_DESCRIPTION = "description"
        const val COL_DATE = "date"           // YYYY-MM-DD
        const val COL_IS_COMPLETED = "is_completed"
        const val COL_PRIORITY = "priority"   // 0=普通, 1=重要, 2=紧急
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T_TASKS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL,
                $COL_DESCRIPTION TEXT DEFAULT '',
                $COL_DATE TEXT NOT NULL,
                $COL_IS_COMPLETED INTEGER NOT NULL DEFAULT 0,
                $COL_PRIORITY INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_tasks_date ON $T_TASKS($COL_DATE)")
        db.execSQL("CREATE INDEX idx_tasks_completed ON $T_TASKS($COL_IS_COMPLETED)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T_TASKS")
        onCreate(db)
    }

    // ── 基础 CRUD ─────────────────────────────────────────────────────

    fun insertTask(task: PlanTask): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_TITLE, task.title)
            put(COL_DESCRIPTION, task.description)
            put(COL_DATE, task.date)
            put(COL_IS_COMPLETED, if (task.isCompleted) 1 else 0)
            put(COL_PRIORITY, task.priority)
            put(COL_CREATED_AT, now)
            put(COL_UPDATED_AT, now)
        }
        return writableDatabase.insert(T_TASKS, null, values)
    }

    fun updateTask(task: PlanTask): Int {
        val values = ContentValues().apply {
            put(COL_TITLE, task.title)
            put(COL_DESCRIPTION, task.description)
            put(COL_DATE, task.date)
            put(COL_IS_COMPLETED, if (task.isCompleted) 1 else 0)
            put(COL_PRIORITY, task.priority)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.update(T_TASKS, values, "$COL_ID = ?", arrayOf(task.id.toString()))
    }

    fun deleteTask(id: Long): Int {
        return writableDatabase.delete(T_TASKS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun toggleComplete(id: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var result = 0
            db.rawQuery("SELECT $COL_IS_COMPLETED FROM $T_TASKS WHERE $COL_ID = ?", arrayOf(id.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    val current = cursor.getInt(0)
                    val values = ContentValues().apply {
                        put(COL_IS_COMPLETED, if (current == 1) 0 else 1)
                        put(COL_UPDATED_AT, System.currentTimeMillis())
                    }
                    result = db.update(T_TASKS, values, "$COL_ID = ?", arrayOf(id.toString()))
                }
            }
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    // ── 查询 ──────────────────────────────────────────────────────────

    fun getTask(id: Long): PlanTask? {
        readableDatabase.query(
            T_TASKS, null, "$COL_ID = ?", arrayOf(id.toString()),
            null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToTask(cursor) else null
        }
    }

    fun getTasksByDate(date: String): List<PlanTask> {
        val list = mutableListOf<PlanTask>()
        readableDatabase.query(
            T_TASKS, null, "$COL_DATE = ?", arrayOf(date),
            null, null, "$COL_IS_COMPLETED ASC, $COL_PRIORITY DESC, $COL_CREATED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToTask(cursor))
            }
        }
        return list
    }

    /** 获取指定月份有任务的日期集合（用于日历标记） */
    fun getDatesWithTasks(year: Int, month: Int): Set<String> {
        val dates = mutableSetOf<String>()
        val prefix = String.format("%04d-%02d", year, month)
        readableDatabase.rawQuery(
            "SELECT DISTINCT $COL_DATE FROM $T_TASKS WHERE $COL_DATE LIKE ?",
            arrayOf("$prefix%")
        ).use { cursor ->
            while (cursor.moveToNext()) {
                dates.add(cursor.getString(0))
            }
        }
        return dates
    }

    /** 获取指定月份每天的任务统计（总数,已完成数） */
    fun getDailyStats(year: Int, month: Int): Map<String, Pair<Int, Int>> {
        val stats = mutableMapOf<String, Pair<Int, Int>>()
        val prefix = String.format("%04d-%02d", year, month)
        readableDatabase.rawQuery(
            """SELECT $COL_DATE,
                      COUNT(*) as total,
                      SUM(CASE WHEN $COL_IS_COMPLETED = 1 THEN 1 ELSE 0 END) as done
               FROM $T_TASKS WHERE $COL_DATE LIKE ?
               GROUP BY $COL_DATE""",
            arrayOf("$prefix%")
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val date = cursor.getString(0)
                val total = cursor.getInt(1)
                val done = cursor.getInt(2)
                stats[date] = Pair(total, done)
            }
        }
        return stats
    }

    /** 获取今天的待办任务（未完成） */
    fun getTodayPending(): List<PlanTask> {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val list = mutableListOf<PlanTask>()
        readableDatabase.query(
            T_TASKS, null, "$COL_DATE = ? AND $COL_IS_COMPLETED = 0", arrayOf(today),
            null, null, "$COL_PRIORITY DESC, $COL_CREATED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToTask(cursor))
            }
        }
        return list
    }

    /** 获取所有未完成任务（按日期排序） */
    fun getAllPending(): List<PlanTask> {
        val list = mutableListOf<PlanTask>()
        readableDatabase.query(
            T_TASKS, null, "$COL_IS_COMPLETED = 0", null,
            null, null, "$COL_DATE ASC, $COL_PRIORITY DESC, $COL_CREATED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToTask(cursor))
            }
        }
        return list
    }

    /**
     * 获取按日期分组的任务列表
     * @param filter 0=全部, 1=未完成, 2=已完成
     */
    fun getGroupedTasks(filter: Int = 0): List<Pair<String, List<PlanTask>>> {
        val where = when (filter) {
            1 -> "AND $COL_IS_COMPLETED = 0"
            2 -> "AND $COL_IS_COMPLETED = 1"
            else -> ""
        }
        val dateMap = linkedMapOf<String, MutableList<PlanTask>>()
        readableDatabase.rawQuery(
            "SELECT * FROM $T_TASKS WHERE 1=1 $where ORDER BY $COL_DATE DESC, $COL_IS_COMPLETED ASC, $COL_PRIORITY DESC, $COL_CREATED_AT ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val task = cursorToTask(cursor)
                dateMap.getOrPut(task.date) { mutableListOf() }.add(task)
            }
        }
        return dateMap.map { (date, tasks) -> Pair(date, tasks) }
    }

    // ── 导入导出 ──────────────────────────────────────────────────────

    fun exportAll(): List<PlanTask> {
        val list = mutableListOf<PlanTask>()
        readableDatabase.query(
            T_TASKS, null, null, null, null, null, "$COL_DATE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToTask(cursor))
            }
        }
        return list
    }

    fun importTasks(tasks: List<PlanTask>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (task in tasks) {
                val values = ContentValues().apply {
                    put(COL_TITLE, task.title)
                    put(COL_DESCRIPTION, task.description)
                    put(COL_DATE, task.date)
                    put(COL_IS_COMPLETED, if (task.isCompleted) 1 else 0)
                    put(COL_PRIORITY, task.priority)
                    put(COL_CREATED_AT, now)
                    put(COL_UPDATED_AT, now)
                }
                db.insert(T_TASKS, null, values)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private fun cursorToTask(cursor: android.database.Cursor): PlanTask {
        return PlanTask(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
            description = cursor.getString(cursor.getColumnIndexOrThrow(COL_DESCRIPTION)),
            date = cursor.getString(cursor.getColumnIndexOrThrow(COL_DATE)),
            isCompleted = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_COMPLETED)) == 1,
            priority = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PRIORITY)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT))
        )
    }
}
