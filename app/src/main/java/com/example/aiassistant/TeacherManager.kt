package com.example.aiassistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 老师系统管理器：加载、切换、导入、导出老师配置。
 * 每位老师包含 7 种题型 prompt + R3 审核 + 自检指令 + 多轮策略。
 */
object TeacherManager {

    private const val TAG = "TeacherManager"
    private const val TEACHERS_DIR = "teachers"
    private const val IMPORTED_DIR = "imported_teachers"

    private val builtIn = mutableListOf<TeacherConfig>()
    private val imported = mutableListOf<TeacherConfig>()

    /** 当前生效的老师（fallback：花生） */
    @Volatile var activeTeacher: TeacherConfig = createFallbackTeacher()
        private set

    val allTeachers: List<TeacherConfig> get() = builtIn + imported
    val builtInTeachers: List<TeacherConfig> get() = builtIn
    val importedTeachers: List<TeacherConfig> get() = imported

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        loadBuiltIn(context)
        loadImported(context)
        val activeId = AppPreferences.getActiveTeacherId(context)
        activeTeacher = allTeachers.find { it.id == activeId } ?: builtIn.firstOrNull() ?: createFallbackTeacher()
        Log.d(TAG, "TeacherManager 初始化完成，当前老师：${activeTeacher.name} (${activeTeacher.id})，共 ${allTeachers.size} 位")
    }

    fun switchTeacher(context: Context, teacherId: String) {
        val teacher = allTeachers.find { it.id == teacherId } ?: return
        activeTeacher = teacher
        AppPreferences.setActiveTeacherId(context, teacherId)
        // 同步老师的默认策略到全局偏好
        AppPreferences.setMultiPassStrategy(context, teacher.defaultStrategy)
        Log.d(TAG, "切换到老师：${teacher.name}，策略：${teacher.defaultStrategy}")
    }

    /** 获取当前老师有效的多轮策略（用户可在UI覆盖，覆盖后以AppPreferences为准，切换老师时重置） */
    fun getEffectiveStrategy(context: Context): Int =
        AppPreferences.getMultiPassStrategy(context)

    // ── Prompt 获取（含覆盖层） ──────────────────────────────────────

    fun getPrompt(context: Context, type: QuestionType): String {
        val overlay = getOverlay(context, activeTeacher.id, type)
        if (overlay != null) return overlay
        return activeTeacher.getPrompt(type)
    }

    fun getR3Prompt(): String = activeTeacher.r3Prompt
    fun getSelfCheckInstruction(): String = activeTeacher.selfCheckInstruction
    fun getDefaultStrategy(): Int = activeTeacher.defaultStrategy

    fun getCustomR2Prompt(context: Context, type: QuestionType): String? {
        val overlay = getOverlay(context, activeTeacher.id, type)
        if (overlay != null) return null // 覆盖后的题型不适用自定义R2
        return activeTeacher.getCustomR2Prompt(type)
    }

    // ── 覆盖层（用户编辑内置老师 prompt） ────────────────────────────

    private fun overlayKey(teacherId: String, type: QuestionType): String =
        "teacher_overlay_${teacherId}_${type.ordinal}"

    fun setOverlay(context: Context, teacherId: String, type: QuestionType, prompt: String) {
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(overlayKey(teacherId, type), prompt).apply()
    }

    fun removeOverlay(context: Context, teacherId: String, type: QuestionType) {
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(overlayKey(teacherId, type)).apply()
    }

    fun removeAllOverlays(context: Context, teacherId: String) {
        val editor = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
        for (type in QuestionType.entries) {
            editor.remove(overlayKey(teacherId, type))
        }
        editor.apply()
    }

    private fun getOverlay(context: Context, teacherId: String, type: QuestionType): String? {
        val overlay = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(overlayKey(teacherId, type), null)
        return overlay?.takeIf { it.isNotBlank() }
    }

    // ── 导入/导出 ────────────────────────────────────────────────────

    fun importTeacher(context: Context, jsonString: String): Result<TeacherConfig> {
        return try {
            val json = JSONObject(jsonString.trim())
            val teacher = TeacherConfig.fromJson(json)
            if (teacher.id.isBlank() || teacher.name.isBlank()) {
                return Result.failure(IllegalArgumentException("老师ID和名称不能为空"))
            }
            if (allTeachers.any { it.id == teacher.id }) {
                return Result.failure(IllegalStateException("老师ID已存在：${teacher.id}"))
            }
            // 保存到内部存储（消毒文件名，防止路径穿越）
            val safeId = teacher.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val dir = File(context.filesDir, IMPORTED_DIR)
            dir.mkdirs()
            File(dir, "${safeId}.json").writeText(jsonString)
            imported.add(teacher)
            Log.i(TAG, "导入老师成功：${teacher.name} (${teacher.id})")
            Result.success(teacher)
        } catch (e: Exception) {
            Log.e(TAG, "导入老师失败", e)
            Result.failure(e)
        }
    }

    fun exportTeacher(teacherId: String): String? {
        val teacher = allTeachers.find { it.id == teacherId } ?: return null
        return teacher.toJson().toString(2)
    }

    fun deleteTeacher(context: Context, teacherId: String): Boolean {
        if (builtIn.any { it.id == teacherId }) return false // 内置不可删
        val removed = imported.removeAll { it.id == teacherId }
        if (removed) {
            // 如果删除的是当前老师，切回第一个内置
            if (activeTeacher.id == teacherId) {
                activeTeacher = builtIn.firstOrNull() ?: createFallbackTeacher()
                AppPreferences.setActiveTeacherId(context, activeTeacher.id)
            }
            // 删除文件
            val file = File(context.filesDir, "$IMPORTED_DIR/${teacherId}.json")
            file.delete()
            removeAllOverlays(context, teacherId)
            Log.i(TAG, "删除老师：$teacherId")
        }
        return removed
    }

    // ── 加载逻辑 ──────────────────────────────────────────────────────

    private fun loadBuiltIn(context: Context) {
        builtIn.clear()
        try {
            val files = context.assets.list("$TEACHERS_DIR") ?: return
            for (fileName in files) {
                if (!fileName.endsWith(".json")) continue
                val jsonStr = context.assets.open("$TEACHERS_DIR/$fileName")
                    .bufferedReader().use { it.readText() }
                val teacher = TeacherConfig.fromJson(JSONObject(jsonStr))
                builtIn.add(teacher)
                Log.d(TAG, "加载内置老师：${teacher.name} (${teacher.id})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载内置老师失败", e)
        }
    }

    private fun loadImported(context: Context) {
        imported.clear()
        try {
            val dir = File(context.filesDir, IMPORTED_DIR)
            if (!dir.exists()) return
            val files = dir.listFiles { f -> f.extension == "json" } ?: return
            for (file in files) {
                val jsonStr = file.readText()
                val teacher = TeacherConfig.fromJson(JSONObject(jsonStr))
                imported.add(teacher)
                Log.d(TAG, "加载导入老师：${teacher.name} (${teacher.id})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载导入老师失败", e)
        }
    }

    private fun createFallbackTeacher(): TeacherConfig = TeacherConfig(
        id = "fallback",
        name = "默认",
        prompts = emptyMap(),
        r3Prompt = "",
        selfCheckInstruction = ""
    )
}
