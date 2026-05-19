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
        private const val DB_VERSION = 3

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            // 添加 stem_html 列
            db.execSQL("ALTER TABLE $T_QUESTIONS ADD COLUMN stem_html TEXT DEFAULT ''")
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

        for (module in modules) {
            module.questionCount = countMap[module.id] ?: 0
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
        readableDatabase.rawQuery(
            "SELECT q.id, q.stem, q.stem_html, q.options, q.answer, q.analysis, q.knowledge_point, q.source, q.rate, q.title_images, q.material_id, COALESCE(m.content, ''), q.difficulty " +
            "FROM $T_QUESTIONS q LEFT JOIN $T_MATERIALS m ON q.material_id = m.id " +
            "WHERE q.module_id = ? AND q.rate >= ? AND q.rate <= ? ORDER BY RANDOM() LIMIT ?",
            arrayOf(moduleId, rateMin.toString(), rateMax.toString(), limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                questions.add(cursorToQuestion(cursor))
            }
        }
        return questions
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
}
