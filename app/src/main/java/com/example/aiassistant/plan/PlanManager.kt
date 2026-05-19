package com.example.aiassistant.plan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PlanManager {

    private lateinit var db: PlanDb

    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = PlanDb(context.applicationContext)
        }
    }

    fun getDb(): PlanDb {
        check(::db.isInitialized) { "PlanManager not initialized" }
        return db
    }

    // ── 任务操作 ──────────────────────────────────────────────────────

    fun addTask(task: PlanTask): Long = db.insertTask(task)

    fun updateTask(task: PlanTask): Int = db.updateTask(task)

    fun deleteTask(id: Long): Int = db.deleteTask(id)

    fun toggleComplete(id: Long): Int = db.toggleComplete(id)

    fun getTask(id: Long): PlanTask? = db.getTask(id)

    fun getTasksByDate(date: String): List<PlanTask> = db.getTasksByDate(date)

    fun getTodayPending(): List<PlanTask> = db.getTodayPending()

    fun getAllPending(): List<PlanTask> = db.getAllPending()

    fun getGroupedTasks(filter: Int = 0): List<Pair<String, List<PlanTask>>> = db.getGroupedTasks(filter)

    // ── 日历统计 ──────────────────────────────────────────────────────

    fun getDatesWithTasks(year: Int, month: Int): Set<String> = db.getDatesWithTasks(year, month)

    fun getDailyStats(year: Int, month: Int): Map<String, Pair<Int, Int>> = db.getDailyStats(year, month)

    // ── 导入导出 ──────────────────────────────────────────────────────

    fun exportToJson(): String {
        val tasks = db.exportAll()
        val arr = JSONArray()
        for (task in tasks) {
            arr.put(JSONObject().apply {
                put("title", task.title)
                put("description", task.description)
                put("date", task.date)
                put("is_completed", task.isCompleted)
                put("priority", task.priority)
            })
        }
        return JSONObject().apply {
            put("tasks", arr)
            put("count", tasks.size)
            put("exported_at", System.currentTimeMillis())
        }.toString(2)
    }

    fun importFromJson(json: String): Int {
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("tasks")
        val tasks = mutableListOf<PlanTask>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            tasks.add(PlanTask(
                title = item.optString("title", ""),
                description = item.optString("description", ""),
                date = item.optString("date", ""),
                isCompleted = item.optBoolean("is_completed", false),
                priority = item.optInt("priority", 0)
            ))
        }
        return if (tasks.isEmpty()) 0 else db.importTasks(tasks)
    }
}
