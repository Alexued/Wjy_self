package com.example.aiassistant.questionbank

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

object QuestionBankManager {

    private const val TAG = "QuestionBankManager"

    private var db: QuestionBankDb? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var ready = false
    @Volatile private var importing = false

    private val onReadyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnReadyListener(listener: () -> Unit) {
        if (ready) {
            listener()
        } else {
            onReadyListeners.add(listener)
        }
    }

    fun removeOnReadyListener(listener: () -> Unit) {
        onReadyListeners.remove(listener)
    }

    fun init(context: Context) {
        if (ready || importing) return
        val appCtx = context.applicationContext

        executor.execute {
            try {
                val t0 = System.currentTimeMillis()

                // 删除旧库（如果版本不匹配）
                val oldDb = appCtx.getDatabasePath("question_bank_v2.db")
                if (oldDb.exists()) {
                    try {
                        val testDb = QuestionBankDb(appCtx)
                        if (!testDb.isImported()) {
                            testDb.close()
                            appCtx.deleteDatabase("question_bank_v2.db")
                            Log.d(TAG, "删除未完成的旧数据库")
                        } else {
                            testDb.close()
                        }
                    } catch (_: Exception) {
                        appCtx.deleteDatabase("question_bank_v2.db")
                    }
                }

                val dbHelper = QuestionBankDb(appCtx)
                db = dbHelper

                // 完整性自愈检查：检测大模块总数和题目总数
                var needReimport = !dbHelper.isImported()
                if (!needReimport) {
                    val modules = dbHelper.getModules()
                    val totalQuestions = modules.sumOf { it.questionCount + it.children.sumOf { child -> child.questionCount } }
                    if (totalQuestions < 20000 || modules.size < 5) {
                        Log.w(TAG, "检测到题库数据残缺（共 $totalQuestions 题，${modules.size} 模块），触发自动修复重新导入...")
                        needReimport = true
                    }
                }

                if (needReimport) {
                    importing = true
                    Log.d(TAG, "开始导入题库...")
                    dbHelper.importFromAssets(appCtx) { msg ->
                        Log.d(TAG, msg)
                    }
                    importing = false
                }

                ready = true
                val modules = getModules()
                val totalQuestions = modules.sumOf { it.questionCount + it.children.sumOf { child -> child.questionCount } }
                Log.d(TAG, "题库就绪: ${modules.size} 大模块, $totalQuestions 题, 耗时 ${System.currentTimeMillis() - t0}ms")

                // 通知所有监听器
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onReadyListeners.forEach { it() }
                    onReadyListeners.clear()
                }
            } catch (e: Exception) {
                importing = false
                Log.e(TAG, "题库初始化异常: ${e.message}")
            }
        }
    }

    fun isLoaded(): Boolean = ready

    fun getModules(): List<QuestionModule> {
        return db?.getModules() ?: emptyList()
    }

    fun getModulesAsync(onResult: (List<QuestionModule>) -> Unit) {
        executor.execute {
            val modules = getModules()
            onResult(modules)
        }
    }

    fun getQuestionsByModule(moduleId: String): List<Question> {
        return db?.getQuestionsByModule(moduleId) ?: emptyList()
    }

    fun getQuestionsByModuleAndDifficulty(moduleId: String, difficulty: String, limit: Int): List<Question> {
        return db?.getQuestionsByModuleAndDifficulty(moduleId, difficulty, limit) ?: emptyList()
    }

    fun getMaterialQuestions(materialId: String): List<Question> {
        return db?.getMaterialQuestions(materialId) ?: emptyList()
    }

    fun getQuestionById(id: String): Question? {
        return db?.getQuestionById(id)
    }

    fun getQuestionCountByModule(moduleId: String): Int {
        return db?.getQuestionCountByModule(moduleId) ?: 0
    }

    fun getQuestionCountByModuleAndDifficulty(moduleId: String, difficulty: String): Int {
        return db?.getQuestionCountByModuleAndDifficulty(moduleId, difficulty) ?: 0
    }

    fun getQuestionsByRateRange(moduleId: String, rateMin: Int, rateMax: Int, limit: Int): List<Question> {
        return db?.getQuestionsByRateRange(moduleId, rateMin, rateMax, limit) ?: emptyList()
    }

    fun getQuestionCountByRateRange(moduleId: String, rateMin: Int, rateMax: Int): Int {
        return db?.getQuestionCountByRateRange(moduleId, rateMin, rateMax) ?: 0
    }

    fun search(ocrText: String): Question? {
        return db?.search(ocrText)
    }

    fun searchAsync(ocrText: String, onResult: (Question?) -> Unit) {
        executor.execute {
            val result = search(ocrText)
            onResult(result)
        }
    }

    /** 获取题目所属模块名称 */
    fun getQuestionModuleName(questionId: String): String? {
        val moduleId = db?.getQuestionModuleId(questionId) ?: return null
        return db?.getModuleName(moduleId)
    }
}
