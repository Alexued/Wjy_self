package com.example.aiassistant.questionbank

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.tencent.wcdb.database.SQLiteDatabase
import com.tencent.wcdb.database.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class QuestionBankDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "QuestionBankDb"
        private const val DB_NAME = "question_bank_v2.db"
        private const val DB_VERSION = 4

        const val T_MODULES = "modules"
        const val T_QUESTIONS = "questions"
        const val T_MATERIALS = "materials"
        const val T_FTS = "questions_fts"

        // 模块定义：大模块 → 小模块列表
        val MODULE_TREE = mapOf(
            "常识判断" to listOf("法律常识", "人文常识", "科技常识", "经济常识", "地理国情"),
            "言语理解与表达" to listOf("片段阅读", "逻辑填空", "语句表达"),
            "数量关系" to listOf("数学运算"),
            "判断推理" to listOf("图形推理", "定义判断", "类比推理", "逻辑判断"),
            "资料分析" to listOf("文字资料", "统计表", "统计图", "综合资料", "基期与现期", "增长率", "增长量", "倍数与比值相关", "比重问题", "平均数问题", "简单计算", "综合分析"),
            "政治常识" to listOf("新思想", "时事政治", "马克思主义", "毛中特")
        )

        // 文件名到模块的映射
        val FILE_TO_MODULE = mapOf(
            "法律常识.json" to ("常识判断" to "法律常识"),
            "人文常识.json" to ("常识判断" to "人文常识"),
            "科技常识.json" to ("常识判断" to "科技常识"),
            "经济常识.json" to ("常识判断" to "经济常识"),
            "地理国情.json" to ("常识判断" to "地理国情"),
            "片段阅读.json" to ("言语理解与表达" to "片段阅读"),
            "逻辑填空.json" to ("言语理解与表达" to "逻辑填空"),
            "语句表达.json" to ("言语理解与表达" to "语句表达"),
            "数学运算.json" to ("数量关系" to "数学运算"),
            "图形推理.json" to ("判断推理" to "图形推理"),
            "定义判断.json" to ("判断推理" to "定义判断"),
            "类比推理.json" to ("判断推理" to "类比推理"),
            "逻辑判断.json" to ("判断推理" to "逻辑判断"),
            "文字资料.json" to ("资料分析" to "文字资料"),
            "统计表.json" to ("资料分析" to "统计表"),
            "统计图.json" to ("资料分析" to "统计图"),
            "综合资料.json" to ("资料分析" to "综合资料"),
            "基期与现期.json" to ("资料分析" to "基期与现期"),
            "增长率.json" to ("资料分析" to "增长率"),
            "增长量.json" to ("资料分析" to "增长量"),
            "倍数与比值相关.json" to ("资料分析" to "倍数与比值相关"),
            "比重问题.json" to ("资料分析" to "比重问题"),
            "平均数问题.json" to ("资料分析" to "平均数问题"),
            "简单计算.json" to ("资料分析" to "简单计算"),
            "综合分析.json" to ("资料分析" to "综合分析"),
            "新思想.json" to ("政治常识" to "新思想"),
            "时事政治.json" to ("政治常识" to "时事政治"),
            "马克思主义.json" to ("政治常识" to "马克思主义"),
            "毛中特.json" to ("政治常识" to "毛中特")
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 模块表
        db.execSQL("""
            CREATE TABLE $T_MODULES (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                parent_id TEXT,
                sort_order INTEGER DEFAULT 0
            )
        """)

        // 材料表
        db.execSQL("""
            CREATE TABLE $T_MATERIALS (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL
            )
        """)

        // 题目表
        db.execSQL("""
            CREATE TABLE $T_QUESTIONS (
                id TEXT PRIMARY KEY,
                module_id TEXT NOT NULL,
                stem TEXT NOT NULL,
                stem_html TEXT DEFAULT '',
                material_id TEXT DEFAULT '',
                options TEXT DEFAULT '[]',
                answer TEXT NOT NULL,
                analysis TEXT DEFAULT '',
                knowledge_point TEXT DEFAULT '',
                source TEXT DEFAULT '',
                rate INTEGER DEFAULT 0,
                difficulty TEXT DEFAULT 'medium',
                title_images TEXT DEFAULT '[]',
                FOREIGN KEY (module_id) REFERENCES $T_MODULES(id)
            )
        """)

        // FTS 虚拟表
        db.execSQL("""
            CREATE VIRTUAL TABLE $T_FTS USING fts5(
                id, stem, tags,
                tokenize='unicode61'
            )
        """)

        // 元信息表
        db.execSQL("""
            CREATE TABLE meta (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """)

        // 已做题记录表
        db.execSQL("CREATE TABLE IF NOT EXISTS completed_questions (question_id TEXT PRIMARY KEY, completed_at INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            // 添加 stem_html 列
            db.execSQL("ALTER TABLE $T_QUESTIONS ADD COLUMN stem_html TEXT DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS completed_questions (question_id TEXT PRIMARY KEY, completed_at INTEGER)")
        }
    }

    fun isImported(): Boolean {
        return readableDatabase.rawQuery(
            "SELECT value FROM meta WHERE key='imported'", null
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == "true"
        }
    }

    fun importFromAssets(context: Context, onProgress: ((String) -> Unit)? = null) {
        val t0 = System.currentTimeMillis()
        val db = writableDatabase

        db.beginTransaction()
        try {
            db.delete(T_QUESTIONS, null, null)
            db.delete(T_MATERIALS, null, null)
            db.delete(T_MODULES, null, null)
            db.delete(T_FTS, null, null)

            // 插入模块
            var sortOrder = 0
            for ((parentName, children) in MODULE_TREE) {
                val parentId = "mod_${parentName.hashCode().toString().replace("-", "n")}"
                val parentValues = ContentValues().apply {
                    put("id", parentId)
                    put("name", parentName)
                    putNull("parent_id")
                    put("sort_order", sortOrder++)
                }
                db.insert(T_MODULES, null, parentValues)

                for (childName in children) {
                    val childId = "mod_${childName.hashCode().toString().replace("-", "n")}"
                    val childValues = ContentValues().apply {
                        put("id", childId)
                        put("name", childName)
                        put("parent_id", parentId)
                        put("sort_order", sortOrder++)
                    }
                    db.insert(T_MODULES, null, childValues)
                }
            }

            // 导入题目
            val assetManager = context.assets
            val bankFiles = assetManager.list("bank") ?: emptyArray()

            var total = 0
            var materialCount = 0

            for (fileName in bankFiles) {
                if (!fileName.endsWith(".json")) continue
                val mapping = FILE_TO_MODULE[fileName] ?: continue
                val (parentName, childName) = mapping
                val moduleId = "mod_${childName.hashCode().toString().replace("-", "n")}"

                onProgress?.invoke("导入 $childName...")
                try {
                    val json = assetManager.open("bank/$fileName").bufferedReader().use { it.readText() }
                    val questions = JSONArray(json)

                    for (i in 0 until questions.length()) {
                        val q = questions.getJSONObject(i)
                        val id = q.getString("key")
                        val stem = q.optString("title", "")
                        if (stem.length < 5) continue

                        val stemHtml = q.optString("title_html", "")
                        val options = q.optJSONArray("options")?.toString() ?: "[]"
                        val answer = q.optString("answer", "")
                        val analysis = q.optString("analysis", "")
                        val knowledgePoint = q.optString("knowledge_point", "")
                        val source = q.optString("source", "")
                        val rate = q.optInt("rate", 0)
                        val titleImages = q.optJSONArray("title_images")?.toString() ?: "[]"

                        // 处理材料题
                        var materialId = ""
                        val materialContent = q.optString("material", "")
                        if (materialContent.isNotEmpty()) {
                            materialId = generateMaterialId(materialContent)
                            val materialValues = ContentValues().apply {
                                put("id", materialId)
                                put("content", materialContent)
                            }
                            db.insertWithOnConflict(T_MATERIALS, null, materialValues, SQLiteDatabase.CONFLICT_IGNORE)
                            materialCount++
                        }

                        // 插入题目
                        val questionValues = ContentValues().apply {
                            put("id", id)
                            put("module_id", moduleId)
                            put("stem", stem)
                            put("stem_html", stemHtml)
                            put("material_id", materialId)
                            put("options", options)
                            put("answer", answer)
                            put("analysis", analysis)
                            put("knowledge_point", knowledgePoint)
                            put("source", source)
                            put("rate", rate)
                            put("difficulty", calculateDifficulty(rate))
                            put("title_images", titleImages)
                        }
                        db.insertWithOnConflict(T_QUESTIONS, null, questionValues, SQLiteDatabase.CONFLICT_REPLACE)

                        // 插入 FTS
                        val ftsValues = ContentValues().apply {
                            put("id", id)
                            put("stem", toBigrams(stem))
                            put("tags", toBigrams(knowledgePoint))
                        }
                        db.insert(T_FTS, null, ftsValues)

                        total++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "导入 $fileName 失败: ${e.message}")
                }
            }

            val meta = ContentValues().apply {
                put("key", "imported")
                put("value", "true")
            }
            db.insert("meta", null, meta)

            db.setTransactionSuccessful()
            Log.d(TAG, "题库导入完成: $total 题, $materialCount 材料, 耗时 ${System.currentTimeMillis() - t0}ms")
        } finally {
            db.endTransaction()
        }
    }

    private fun generateMaterialId(content: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(content.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun calculateDifficulty(rate: Int): String {
        return when {
            rate > 70 -> "easy"
            rate >= 40 -> "medium"
            else -> "hard"
        }
    }

    // ── 查询方法 ──────────────────────────────────────────────────────

    fun getModules(): List<QuestionModule> {
        val modules = mutableListOf<QuestionModule>()
        readableDatabase.rawQuery(
            "SELECT id, name, parent_id FROM $T_MODULES ORDER BY sort_order", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                modules.add(QuestionModule(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    parentId = if (cursor.isNull(2)) null else cursor.getString(2)
                ))
            }
        }

        // 一次性查询所有模块的题目数
        val countMap = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT module_id, COUNT(*) FROM $T_QUESTIONS GROUP BY module_id", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                countMap[cursor.getString(0)] = cursor.getInt(1)
            }
        }

        // 一次性查询所有模块已做题目的数量
        val completedCountMap = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT q.module_id, COUNT(*) FROM $T_QUESTIONS q " +
            "JOIN completed_questions c ON q.id = c.question_id GROUP BY q.module_id", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                completedCountMap[cursor.getString(0)] = cursor.getInt(1)
            }
        }

        for (module in modules) {
            module.questionCount = countMap[module.id] ?: 0
            module.completedCount = completedCountMap[module.id] ?: 0
        }

        // 构建树状结构
        val rootModules = modules.filter { it.parentId == null }
        for (root in rootModules) {
            root.children.addAll(modules.filter { it.parentId == root.id })
        }

        return rootModules
    }

    fun getQuestionsByModule(moduleId: String): List<Question> {
        val questions = mutableListOf<Question>()
        readableDatabase.rawQuery(
            "SELECT q.id, q.stem, q.stem_html, q.options, q.answer, q.analysis, q.knowledge_point, q.source, q.rate, q.title_images, q.material_id, COALESCE(m.content, ''), q.difficulty " +
            "FROM $T_QUESTIONS q LEFT JOIN $T_MATERIALS m ON q.material_id = m.id " +
            "WHERE q.module_id = ? ORDER BY q.id",
            arrayOf(moduleId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                questions.add(cursorToQuestion(cursor))
            }
        }
        return questions
    }

    fun getQuestionsByModuleAndDifficulty(moduleId: String, difficulty: String, limit: Int): List<Question> {
        val questions = mutableListOf<Question>()
        readableDatabase.rawQuery(
            "SELECT q.id, q.stem, q.stem_html, q.options, q.answer, q.analysis, q.knowledge_point, q.source, q.rate, q.title_images, q.material_id, COALESCE(m.content, ''), q.difficulty " +
            "FROM $T_QUESTIONS q LEFT JOIN $T_MATERIALS m ON q.material_id = m.id " +
            "WHERE q.module_id = ? AND q.difficulty = ? ORDER BY RANDOM() LIMIT ?",
            arrayOf(moduleId, difficulty, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                questions.add(cursorToQuestion(cursor))
            }
        }
        return questions
    }

    fun getMaterialQuestions(materialId: String): List<Question> {
        val questions = mutableListOf<Question>()
        readableDatabase.rawQuery(
            "SELECT q.id, q.stem, q.stem_html, q.options, q.answer, q.analysis, q.knowledge_point, q.source, q.rate, q.title_images, q.material_id, COALESCE(m.content, ''), q.difficulty " +
            "FROM $T_QUESTIONS q LEFT JOIN $T_MATERIALS m ON q.material_id = m.id " +
            "WHERE q.material_id = ? ORDER BY q.id",
            arrayOf(materialId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                questions.add(cursorToQuestion(cursor))
            }
        }
        return questions
    }

    fun getQuestionById(id: String): Question? {
        return readableDatabase.rawQuery(
            "SELECT q.id, q.stem, q.stem_html, q.options, q.answer, q.analysis, q.knowledge_point, q.source, q.rate, q.title_images, q.material_id, COALESCE(m.content, ''), q.difficulty " +
            "FROM $T_QUESTIONS q LEFT JOIN $T_MATERIALS m ON q.material_id = m.id " +
            "WHERE q.id = ?",
            arrayOf(id)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursorToQuestion(cursor) else null
        }
    }

    /** 获取题目所属模块ID */
    fun getQuestionModuleId(questionId: String): String? {
        return readableDatabase.rawQuery(
            "SELECT module_id FROM $T_QUESTIONS WHERE id = ?",
            arrayOf(questionId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /** 获取模块名称 */
    fun getModuleName(moduleId: String): String? {
        return readableDatabase.rawQuery(
            "SELECT name FROM $T_MODULES WHERE id = ?",
            arrayOf(moduleId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun getQuestionCountByModule(moduleId: String): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_QUESTIONS WHERE module_id = ?", arrayOf(moduleId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getQuestionCountByModuleAndDifficulty(moduleId: String, difficulty: String): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_QUESTIONS WHERE module_id = ? AND difficulty = ?",
            arrayOf(moduleId, difficulty)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getQuestionsByRateRange(moduleId: String, rateMin: Int, rateMax: Int, limit: Int): List<Question> {
        val questions = mutableListOf<Question>()
        
        // 1. 尝试先获取未做过的题目
        readableDatabase.rawQuery(
            "SELECT q.id, q.stem, q.stem_html, q.options, q.answer, q.analysis, q.knowledge_point, q.source, q.rate, q.title_images, q.material_id, COALESCE(m.content, ''), q.difficulty " +
            "FROM $T_QUESTIONS q LEFT JOIN $T_MATERIALS m ON q.material_id = m.id " +
            "WHERE q.module_id = ? AND q.rate >= ? AND q.rate <= ? " +
            "AND q.id NOT IN (SELECT question_id FROM completed_questions) " +
            "ORDER BY RANDOM() LIMIT ?",
            arrayOf(moduleId, rateMin.toString(), rateMax.toString(), limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                questions.add(cursorToQuestion(cursor))
            }
        }
        
        // 2. 如果未做过的题目不足，且该范围内所有题目均已做完，则自动重置已做记录并重新派送
        if (questions.size < limit) {
            val totalInModuleRange = getQuestionCountByRateRange(moduleId, rateMin, rateMax)
            val completedInModuleRange = getCompletedQuestionCountByRateRange(moduleId, rateMin, rateMax)
            
            if (completedInModuleRange >= totalInModuleRange && totalInModuleRange > 0) {
                // 该范围的所有题都做完了，重置它们以允许重新派送
                resetCompletedQuestionsByRange(moduleId, rateMin, rateMax)
                
                // 再次尝试获取
                val remainingLimit = limit - questions.size
                readableDatabase.rawQuery(
                    "SELECT q.id, q.stem, q.stem_html, q.options, q.answer, q.analysis, q.knowledge_point, q.source, q.rate, q.title_images, q.material_id, COALESCE(m.content, ''), q.difficulty " +
                    "FROM $T_QUESTIONS q LEFT JOIN $T_MATERIALS m ON q.material_id = m.id " +
                    "WHERE q.module_id = ? AND q.rate >= ? AND q.rate <= ? " +
                    "ORDER BY RANDOM() LIMIT ?",
                    arrayOf(moduleId, rateMin.toString(), rateMax.toString(), remainingLimit.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        questions.add(cursorToQuestion(cursor))
                    }
                }
            }
        }
        return questions
    }

    fun getCompletedQuestionCountByRateRange(moduleId: String, rateMin: Int, rateMax: Int): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_QUESTIONS q " +
            "WHERE q.module_id = ? AND q.rate >= ? AND q.rate <= ? " +
            "AND q.id IN (SELECT question_id FROM completed_questions)",
            arrayOf(moduleId, rateMin.toString(), rateMax.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun resetCompletedQuestionsByRange(moduleId: String, rateMin: Int, rateMax: Int) {
        writableDatabase.execSQL(
            "DELETE FROM completed_questions WHERE question_id IN (" +
            "SELECT id FROM $T_QUESTIONS WHERE module_id = ? AND rate >= ? AND rate <= ?)",
            arrayOf(moduleId, rateMin.toString(), rateMax.toString())
        )
    }

    fun markQuestionCompleted(questionId: String) {
        val values = ContentValues().apply {
            put("question_id", questionId)
            put("completed_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("completed_questions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getQuestionCountByRateRange(moduleId: String, rateMin: Int, rateMax: Int): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_QUESTIONS WHERE module_id = ? AND rate >= ? AND rate <= ?",
            arrayOf(moduleId, rateMin.toString(), rateMax.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun cursorToQuestion(cursor: android.database.Cursor): Question {
        val optionsStr = cursor.getString(3)
        val options = try {
            val arr = JSONArray(optionsStr)
            List(arr.length()) {
                val obj = arr.getJSONObject(it)
                val images = obj.optJSONArray("images")?.let { imgArr ->
                    List(imgArr.length()) { idx -> imgArr.getString(idx) }
                } ?: emptyList()
                QuestionOption(
                    text = obj.optString("text", ""),
                    html = obj.optString("html", ""),
                    images = images
                )
            }
        } catch (_: Exception) { emptyList() }

        val titleImagesStr = cursor.getString(9)
        val titleImages = try {
            val arr = JSONArray(titleImagesStr)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) { emptyList() }

        return Question(
            id = cursor.getString(0),
            stem = cursor.getString(1),
            stemHtml = cursor.getString(2),
            options = options,
            answer = cursor.getString(4),
            analysis = cursor.getString(5),
            knowledgePoint = cursor.getString(6),
            source = cursor.getString(7),
            rate = cursor.getInt(8),
            titleImages = titleImages,
            materialId = cursor.getString(10),
            materialContent = cursor.getString(11),
            difficulty = cursor.getString(12)
        )
    }

    // ── FTS 搜索 ──────────────────────────────────────────────────────

    fun search(ocrText: String): Question? {
        if (ocrText.length < 10) return null

        val queryBigrams = toBigrams(ocrText).split(" ").filter { it.length >= 2 }.distinct()
        if (queryBigrams.isEmpty()) return null

        val query = queryBigrams.take(30).joinToString(" OR ")

        val candidates = mutableListOf<Question>()
        readableDatabase.rawQuery("""
            SELECT q.id, q.stem, q.stem_html, q.options, q.answer, q.analysis, q.knowledge_point, q.source, q.rate, q.title_images, q.material_id, COALESCE(m.content, ''), q.difficulty,
                   bm25($T_FTS, 10.0, 5.0, 2.0) AS score
            FROM $T_FTS
            JOIN $T_QUESTIONS q ON $T_FTS.id = q.id
            LEFT JOIN $T_MATERIALS m ON q.material_id = m.id
            WHERE $T_FTS MATCH ?
            ORDER BY score
            LIMIT 5
        """.trimIndent(), arrayOf(query)).use { cursor ->
            while (cursor.moveToNext()) {
                candidates.add(cursorToQuestion(cursor))
            }
        }

        if (candidates.isEmpty()) return null

        // LCS 验证
        var best: Question? = null
        var bestSim = 0.0
        for (c in candidates) {
            val sim = lcsSimilarity(ocrText, c.stem)
            if (sim > bestSim) {
                bestSim = sim
                best = c
            }
        }

        return if (bestSim >= 0.4) best else null
    }

    private fun lcsSimilarity(a: String, b: String): Double {
        val cleanA = a.replace(Regex("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]"), "").take(80)
        val cleanB = b.replace(Regex("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]"), "").take(80)
        if (cleanA.isEmpty() || cleanB.isEmpty()) return 0.0
        val m = cleanA.length; val n = cleanB.length
        var prev = IntArray(n + 1); var curr = IntArray(n + 1)
        for (i in 1..m) {
            for (j in 1..n) {
                curr[j] = if (cleanA[i-1] == cleanB[j-1]) prev[j-1] + 1 else maxOf(prev[j], curr[j-1])
            }
            val tmp = prev; prev = curr; curr = tmp; curr.fill(0)
        }
        return prev[n].toDouble() / minOf(m, n)
    }

    fun toBigrams(text: String): String {
        val clean = text.replace(Regex("[\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF\\s]"), " ").trim()
        val sb = StringBuilder()
        val chars = clean.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (c == ' ') {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                i++
                continue
            }
            if (isChinese(c)) {
                if (i + 1 < chars.size && isChinese(chars[i + 1])) {
                    sb.append(c).append(chars[i + 1])
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    i++
                } else {
                    sb.append(c).append(' ')
                    i++
                }
            } else {
                val start = i
                while (i < chars.size && !isChinese(chars[i]) && chars[i] != ' ') i++
                sb.append(chars, start, i - start).append(' ')
            }
        }
        return sb.toString().trim()
    }

    private fun isChinese(c: Char): Boolean {
        val ub = Character.UnicodeBlock.of(c)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    /** 导出所有的题库数据（含分类、材料、题目）为 JSON */
    fun exportQuestionsJson(): String {
        return try {
            val root = org.json.JSONObject()
            root.put("backup_type", "question_bank")
            root.put("version", 1)
            
            // 1. 导出 modules
            val modulesArr = org.json.JSONArray()
            readableDatabase.rawQuery("SELECT id, name, parent_id, sort_order FROM $T_MODULES", null).use { cursor ->
                while (cursor.moveToNext()) {
                    modulesArr.put(org.json.JSONObject().apply {
                        put("id", cursor.getString(0))
                        put("name", cursor.getString(1))
                        put("parent_id", cursor.getString(2) ?: "")
                        put("sort_order", cursor.getInt(3))
                    })
                }
            }
            root.put("modules", modulesArr)

            // 2. 导出 materials
            val materialsArr = org.json.JSONArray()
            readableDatabase.rawQuery("SELECT id, content FROM $T_MATERIALS", null).use { cursor ->
                while (cursor.moveToNext()) {
                    materialsArr.put(org.json.JSONObject().apply {
                        put("id", cursor.getString(0))
                        put("content", cursor.getString(1))
                    })
                }
            }
            root.put("materials", materialsArr)

            // 3. 导出 questions
            val questionsArr = org.json.JSONArray()
            readableDatabase.rawQuery("SELECT id, module_id, stem, stem_html, material_id, options, answer, analysis, knowledge_point, source, rate, difficulty, title_images FROM $T_QUESTIONS", null).use { cursor ->
                while (cursor.moveToNext()) {
                    questionsArr.put(org.json.JSONObject().apply {
                        put("id", cursor.getString(0))
                        put("module_id", cursor.getString(1))
                        put("stem", cursor.getString(2))
                        put("stem_html", cursor.getString(3))
                        put("material_id", cursor.getString(4) ?: "")
                        put("options", cursor.getString(5))
                        put("answer", cursor.getString(6))
                        put("analysis", cursor.getString(7))
                        put("knowledge_point", cursor.getString(8))
                        put("source", cursor.getString(9))
                        put("rate", cursor.getInt(10))
                        put("difficulty", cursor.getString(11))
                        put("title_images", cursor.getString(12))
                    })
                }
            }
            root.put("questions", questionsArr)
            root.toString(2)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /** 导入题库数据。支持全库备份还原，或纯外部自定义 JSONArray 模块热导入。 */
    fun importQuestionsFromJson(
        jsonStr: String, 
        parentModule: String? = null, 
        childModule: String? = null
    ): Int {
        val db = writableDatabase
        var result = 0
        db.beginTransaction()
        try {
            var count = 0
            if (jsonStr.trim().startsWith("{")) {
                // 1. 全量备份恢复模式
                val root = org.json.JSONObject(jsonStr)
                if (root.optString("backup_type") != "question_bank") return -1
                
                db.delete(T_QUESTIONS, null, null)
                db.delete(T_MATERIALS, null, null)
                db.delete(T_MODULES, null, null)
                db.delete(T_FTS, null, null)

                val modules = root.getJSONArray("modules")
                for (i in 0 until modules.length()) {
                    val m = modules.getJSONObject(i)
                    db.insert(T_MODULES, null, ContentValues().apply {
                        put("id", m.getString("id"))
                        put("name", m.getString("name"))
                        val parentId = m.optString("parent_id")
                        if (parentId.isNotEmpty()) put("parent_id", parentId) else putNull("parent_id")
                        put("sort_order", m.getInt("sort_order"))
                    })
                }

                val materials = root.getJSONArray("materials")
                for (i in 0 until materials.length()) {
                    val mat = materials.getJSONObject(i)
                    db.insert(T_MATERIALS, null, ContentValues().apply {
                        put("id", mat.getString("id"))
                        put("content", mat.getString("content"))
                    })
                }

                val questions = root.getJSONArray("questions")
                for (i in 0 until questions.length()) {
                    val q = questions.getJSONObject(i)
                    val id = q.getString("id")
                    val stem = q.getString("stem")
                    val kp = q.optString("knowledge_point")
                    db.insert(T_QUESTIONS, null, ContentValues().apply {
                        put("id", id)
                        put("module_id", q.getString("module_id"))
                        put("stem", stem)
                        put("stem_html", q.optString("stem_html"))
                        put("material_id", q.optString("material_id"))
                        put("options", q.optString("options", "[]"))
                        put("answer", q.getString("answer"))
                        put("analysis", q.optString("analysis"))
                        put("knowledge_point", kp)
                        put("source", q.optString("source"))
                        put("rate", q.optInt("rate", 0))
                        put("difficulty", q.optString("difficulty", "medium"))
                        put("title_images", q.optString("title_images", "[]"))
                    })

                    db.insert(T_FTS, null, ContentValues().apply {
                        put("id", id)
                        put("stem", toBigrams(stem))
                        put("tags", toBigrams(kp))
                    })
                    count++
                }
            } else {
                // 2. 自定义题库单分类导入模式
                if (parentModule.isNullOrEmpty() || childModule.isNullOrEmpty()) return -2
                val arr = org.json.JSONArray(jsonStr)

                val parentId = "mod_${parentModule.hashCode().toString().replace("-", "n")}"
                val childId = "mod_${childModule.hashCode().toString().replace("-", "n")}"

                db.insertWithOnConflict(T_MODULES, null, ContentValues().apply {
                    put("id", parentId)
                    put("name", parentModule)
                    putNull("parent_id")
                    put("sort_order", 999)
                }, SQLiteDatabase.CONFLICT_IGNORE)

                db.insertWithOnConflict(T_MODULES, null, ContentValues().apply {
                    put("id", childId)
                    put("name", childModule)
                    put("parent_id", parentId)
                    put("sort_order", 999)
                }, SQLiteDatabase.CONFLICT_IGNORE)

                for (i in 0 until arr.length()) {
                    val q = arr.getJSONObject(i)
                    val id = q.optString("key").ifBlank { q.optString("id").ifBlank { System.currentTimeMillis().toString() + "_" + i } }
                    val stem = q.optString("title").ifBlank { q.optString("stem", "") }
                    if (stem.length < 5) continue

                    val kp = q.optString("knowledge_point", "")
                    val optionsArr = q.optJSONArray("options")
                    val optionsStr = if (optionsArr != null) {
                        val targetArr = org.json.JSONArray()
                        for (j in 0 until optionsArr.length()) {
                            val item = optionsArr.get(j)
                            if (item is org.json.JSONObject) {
                                targetArr.put(item)
                            } else {
                                targetArr.put(org.json.JSONObject().apply {
                                    put("text", item.toString())
                                    put("html", "")
                                    put("images", org.json.JSONArray())
                                })
                            }
                        }
                        targetArr.toString()
                    } else "[]"

                    val answer = q.optString("answer", "")
                    val analysis = q.optString("analysis", "")
                    val source = q.optString("source", "自定义导入")
                    val rate = q.optInt("rate", 60)
                    val stemHtml = q.optString("title_html").ifBlank { q.optString("stem_html", "") }
                    val titleImages = q.optJSONArray("title_images")?.toString() ?: "[]"

                    var materialId = ""
                    val materialContent = q.optString("material", "")
                    if (materialContent.isNotEmpty()) {
                        materialId = generateMaterialId(materialContent)
                        db.insertWithOnConflict(T_MATERIALS, null, ContentValues().apply {
                            put("id", materialId)
                            put("content", materialContent)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }

                    db.insertWithOnConflict(T_QUESTIONS, null, ContentValues().apply {
                        put("id", id)
                        put("module_id", childId)
                        put("stem", stem)
                        put("stem_html", stemHtml)
                        put("material_id", materialId)
                        put("options", optionsStr)
                        put("answer", answer)
                        put("analysis", analysis)
                        put("knowledge_point", kp)
                        put("source", source)
                        put("rate", rate)
                        put("difficulty", calculateDifficulty(rate))
                        put("title_images", titleImages)
                    }, SQLiteDatabase.CONFLICT_REPLACE)

                    db.delete(T_FTS, "id = ?", arrayOf(id))
                    db.insert(T_FTS, null, ContentValues().apply {
                        put("id", id)
                        put("stem", toBigrams(stem))
                        put("tags", toBigrams(kp))
                    })
                    count++
                }
            }
            val meta = ContentValues().apply {
                put("key", "imported")
                put("value", "true")
            }
            db.insertWithOnConflict("meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
            result = count
        } catch (e: Exception) {
            e.printStackTrace()
            result = -3
        } finally {
            db.endTransaction()
        }
        return result
    }

    /**
     * 终极流式导入 JSON 数据库。
     * 支持大备份还原（无 parentModule 与 childModule），或大文件热导入（指定分类）。
     * 使用 android.util.JsonReader 流式解析，内存分配极小，完全杜绝 85MB 大 JSON 的 OOM 闪退。
     */
    fun importQuestionsFromStream(
        ins: java.io.InputStream,
        parentModule: String? = null,
        childModule: String? = null
    ): Int {
        val reader = android.util.JsonReader(ins.bufferedReader())
        
        // 🌟 强力探测首令牌 Token，防止在单分类热导入模式里误选整库文件（或反之）造成类型异常
        val nextToken = reader.peek()
        if (nextToken == android.util.JsonToken.BEGIN_OBJECT) {
            if (!parentModule.isNullOrEmpty() || !childModule.isNullOrEmpty()) {
                return -1 // 优雅返回 -1，告知 UI 此为全量整库备份文件
            }
        } else if (nextToken == android.util.JsonToken.BEGIN_ARRAY) {
            if (parentModule.isNullOrEmpty() || childModule.isNullOrEmpty()) {
                return -4 // 告知 UI 此为专项分类题库，请使用专项导入
            }
        }

        val db = writableDatabase
        var resultCount = 0
        db.beginTransaction()
        try {
            if (parentModule.isNullOrEmpty() || childModule.isNullOrEmpty()) {
                // ================== 1. 全量整库备份还原流模式 ==================
                db.delete(T_QUESTIONS, null, null)
                db.delete(T_MATERIALS, null, null)
                db.delete(T_MODULES, null, null)
                db.delete(T_FTS, null, null)

                reader.beginObject()
                while (reader.hasNext()) {
                    val rootKey = reader.nextName()
                    when (rootKey) {
                        "backup_type" -> {
                            val type = reader.nextString()
                            if (type != "question_bank") {
                                throw Exception("备份文件类型不匹配：$type")
                            }
                        }
                        "modules" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var id = ""
                                var name = ""
                                var parentId: String? = null
                                var sortOrder = 0
                                while (reader.hasNext()) {
                                    val k = reader.nextName()
                                    when (k) {
                                        "id" -> id = reader.nextString()
                                        "name" -> name = reader.nextString()
                                        "parent_id" -> {
                                            val p = reader.nextString()
                                            if (p.isNotEmpty()) parentId = p
                                        }
                                        "sort_order" -> sortOrder = reader.nextInt()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                db.insert(T_MODULES, null, ContentValues().apply {
                                    put("id", id)
                                    put("name", name)
                                    if (parentId != null) put("parent_id", parentId) else putNull("parent_id")
                                    put("sort_order", sortOrder)
                                })
                            }
                            reader.endArray()
                        }
                        "materials" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var id = ""
                                var content = ""
                                while (reader.hasNext()) {
                                    val k = reader.nextName()
                                    when (k) {
                                        "id" -> id = reader.nextString()
                                        "content" -> content = reader.nextString()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                db.insert(T_MATERIALS, null, ContentValues().apply {
                                    put("id", id)
                                    put("content", content)
                                })
                            }
                            reader.endArray()
                        }
                        "questions" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var id = ""
                                var moduleId = ""
                                var stem = ""
                                var stemHtml = ""
                                var materialId = ""
                                var options = "[]"
                                var answer = ""
                                var analysis = ""
                                var kp = ""
                                var source = ""
                                var rate = 0
                                var difficulty = "medium"
                                var titleImages = "[]"
                                while (reader.hasNext()) {
                                    val k = reader.nextName()
                                    when (k) {
                                        "id" -> id = reader.nextString()
                                        "module_id" -> moduleId = reader.nextString()
                                        "stem" -> stem = reader.nextString()
                                        "stem_html" -> stemHtml = reader.nextString()
                                        "material_id" -> materialId = reader.nextString()
                                        "options" -> options = reader.nextString()
                                        "answer" -> answer = reader.nextString()
                                        "analysis" -> analysis = reader.nextString()
                                        "knowledge_point" -> kp = reader.nextString()
                                        "source" -> source = reader.nextString()
                                        "rate" -> rate = reader.nextInt()
                                        "difficulty" -> difficulty = reader.nextString()
                                        "title_images" -> titleImages = reader.nextString()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                
                                db.insert(T_QUESTIONS, null, ContentValues().apply {
                                    put("id", id)
                                    put("module_id", moduleId)
                                    put("stem", stem)
                                    put("stem_html", stemHtml)
                                    put("material_id", materialId)
                                    put("options", options)
                                    put("answer", answer)
                                    put("analysis", analysis)
                                    put("knowledge_point", kp)
                                    put("source", source)
                                    put("rate", rate)
                                    put("difficulty", difficulty)
                                    put("title_images", titleImages)
                                })

                                db.insert(T_FTS, null, ContentValues().apply {
                                    put("id", id)
                                    put("stem", toBigrams(stem))
                                    put("tags", toBigrams(kp))
                                })
                                resultCount++
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

            } else {
                // ================== 2. 自定义单分类热导入流模式 ==================
                val parentId = "mod_${parentModule.hashCode().toString().replace("-", "n")}"
                val childId = "mod_${childModule.hashCode().toString().replace("-", "n")}"

                db.insertWithOnConflict(T_MODULES, null, ContentValues().apply {
                    put("id", parentId)
                    put("name", parentModule)
                    putNull("parent_id")
                    put("sort_order", 999)
                }, SQLiteDatabase.CONFLICT_IGNORE)

                db.insertWithOnConflict(T_MODULES, null, ContentValues().apply {
                    put("id", childId)
                    put("name", childModule)
                    put("parent_id", parentId)
                    put("sort_order", 999)
                }, SQLiteDatabase.CONFLICT_IGNORE)

                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginObject()
                    var keyName = ""
                    var stemName = ""
                    var stemHtml = ""
                    var optionsStr = "[]"
                    var answer = ""
                    var analysis = ""
                    var source = "自定义导入"
                    var rate = 60
                    var kpName = ""
                    var titleImages = "[]"
                    var materialContent = ""
                    
                    while (reader.hasNext()) {
                        val k = reader.nextName()
                        when (k) {
                            "key", "id" -> keyName = reader.nextString()
                            "title", "stem" -> stemName = reader.nextString()
                            "title_html", "stem_html" -> stemHtml = reader.nextString()
                            "answer" -> answer = reader.nextString()
                            "analysis" -> analysis = reader.nextString()
                            "source" -> source = reader.nextString()
                            "rate" -> rate = reader.nextInt()
                            "knowledge_point" -> kpName = reader.nextString()
                            "material" -> materialContent = reader.nextString()
                            "options" -> {
                                val optList = mutableListOf<String>()
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val optType = reader.peek()
                                    if (optType == android.util.JsonToken.BEGIN_OBJECT) {
                                        reader.beginObject()
                                        var optText = ""
                                        while (reader.hasNext()) {
                                            val ok = reader.nextName()
                                            if (ok == "text") optText = reader.nextString() else reader.skipValue()
                                        }
                                        reader.endObject()
                                        optList.add(optText)
                                    } else {
                                        optList.add(reader.nextString())
                                    }
                                }
                                reader.endArray()
                                val optJsonArr = org.json.JSONArray()
                                for (o in optList) {
                                    optJsonArr.put(org.json.JSONObject().apply {
                                        put("text", o)
                                        put("html", "")
                                        put("images", org.json.JSONArray())
                                    })
                                }
                                optionsStr = optJsonArr.toString()
                            }
                            "title_images" -> {
                                val imgList = mutableListOf<String>()
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    imgList.add(reader.nextString())
                                }
                                reader.endArray()
                                val imgJsonArr = org.json.JSONArray()
                                for (img in imgList) imgJsonArr.put(img)
                                titleImages = imgJsonArr.toString()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    
                    if (stemName.length < 5) continue
                    if (keyName.isBlank()) {
                        keyName = System.currentTimeMillis().toString() + "_" + resultCount
                    }

                    var materialId = ""
                    if (materialContent.isNotEmpty()) {
                        materialId = "mat_${materialContent.hashCode().toString().replace("-", "n")}"
                        db.insertWithOnConflict(T_MATERIALS, null, ContentValues().apply {
                            put("id", materialId)
                            put("content", materialContent)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }

                    db.insert(T_QUESTIONS, null, ContentValues().apply {
                        put("id", keyName)
                        put("module_id", childId)
                        put("stem", stemName)
                        put("stem_html", stemHtml)
                        put("material_id", materialId)
                        put("options", optionsStr)
                        put("answer", answer)
                        put("analysis", analysis)
                        put("knowledge_point", kpName)
                        put("source", source)
                        put("rate", rate)
                        put("difficulty", "medium")
                        put("title_images", titleImages)
                    })

                    db.insert(T_FTS, null, ContentValues().apply {
                        put("id", keyName)
                        put("stem", toBigrams(stemName))
                        put("tags", toBigrams(kpName))
                    })
                    resultCount++
                }
                reader.endArray()
            }
            
            val meta = ContentValues().apply {
                put("key", "imported")
                put("value", "true")
            }
            db.insertWithOnConflict("meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return resultCount
    }
}
