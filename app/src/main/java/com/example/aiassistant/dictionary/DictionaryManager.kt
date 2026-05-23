package com.example.aiassistant.dictionary

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object DictionaryManager {

    private const val TAG = "Dictionary"
    private const val MAX_RESULTS = 10

    @Volatile private var db: DictionaryDb? = null

    @Volatile var isLoaded = false; private set
    @Volatile var isLoading = false; private set

    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val readyListeners = mutableListOf<() -> Unit>()

    /** 搜索请求 ID，用于丢弃过期结果 */
    private val searchId = AtomicLong(0)

    fun addOnReadyListener(listener: () -> Unit) {
        synchronized(readyListeners) { readyListeners.add(listener) }
    }

    fun removeOnReadyListener(listener: () -> Unit) {
        synchronized(readyListeners) { readyListeners.remove(listener) }
    }

    fun init(context: Context) {
        if (isLoaded || isLoading) {
            if (isLoaded) {
                synchronized(readyListeners) { readyListeners.forEach { it() } }
            }
            return
        }
        isLoading = true
        val appCtx = context.applicationContext
        loadExecutor.execute {
            try {
                val dbHelper = DictionaryDb(appCtx)
                db = dbHelper
                if (!dbHelper.isImported()) {
                    Log.i(TAG, "开始首次导入词典数据...")
                    dbHelper.importFromAssets(appCtx)
                }
                isLoaded = true
                Log.i(TAG, "词典初始化完成")
                synchronized(readyListeners) { readyListeners.forEach { it() } }
            } catch (e: Throwable) {
                Log.e(TAG, "词典加载失败", e)
            } finally {
                isLoading = false
            }
        }
    }

    // ── 异步搜索（后台线程执行，结果回调到主线程） ────────────────

    /**
     * 异步搜索词典。
     * @param query 查询字符串
     * @param onResult 结果回调（在主线程），如果被更新的查询覆盖则不会回调
     */
    fun searchAsync(query: String, onResult: (DictionaryResult) -> Unit) {
        val q = query.trim()
        val requestId = searchId.incrementAndGet()

        if (q.isBlank()) {
            mainHandler.post { onResult(DictionaryResult.EMPTY) }
            return
        }

        searchExecutor.execute {
            // 如果已经有更新的查询，跳过
            if (searchId.get() != requestId) return@execute

            val result = searchInternal(q)

            // 只有请求仍然最新才回调
            if (searchId.get() == requestId) {
                mainHandler.post { onResult(result) }
            }
        }
    }

    /**
     * 同步搜索（用于少量数据或已在后台线程时）。
     */
    fun search(query: String): DictionaryResult {
        val q = query.trim()
        if (q.isBlank()) return DictionaryResult.EMPTY
        return searchInternal(q)
    }

    /**
     * 核心搜索逻辑：基于 SQLite 数据库进行模糊匹配查询。
     */
    private fun searchInternal(q: String): DictionaryResult {
        val dbHelper = db ?: return DictionaryResult.EMPTY
        val database = dbHelper.readableDatabase

        val isChinese = q.first().code > 127
        val resultItems = mutableListOf<DictItem>()

        try {
            if (isChinese) {
                // 1. 查汉字
                database.rawQuery(
                    "SELECT word, old_word, strokes, pinyin, radicals, explanation, more FROM ${DictionaryDb.T_WORDS} WHERE word LIKE ? LIMIT ?",
                    arrayOf("$q%", MAX_RESULTS.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        resultItems.add(DictItem.WordItem(WordEntry(
                            word = cursor.getString(0),
                            oldWord = cursor.getString(1),
                            strokes = cursor.getInt(2),
                            pinyin = cursor.getString(3),
                            radicals = cursor.getString(4),
                            explanation = cursor.getString(5),
                            more = cursor.getString(6)
                        )))
                    }
                }

                // 2. 查词语
                database.rawQuery(
                    "SELECT ci, explanation FROM ${DictionaryDb.T_CI} WHERE ci LIKE ? LIMIT ?",
                    arrayOf("$q%", MAX_RESULTS.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        resultItems.add(DictItem.CiItem(CiEntry(
                            ci = cursor.getString(0),
                            explanation = cursor.getString(1)
                        )))
                    }
                }

                // 3. 查成语
                database.rawQuery(
                    "SELECT word, pinyin, abbreviation, explanation, derivation, example FROM ${DictionaryDb.T_IDIOMS} WHERE word LIKE ? LIMIT ?",
                    arrayOf("$q%", MAX_RESULTS.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        resultItems.add(DictItem.IdiomItem(IdiomEntry(
                            word = cursor.getString(0),
                            pinyin = cursor.getString(1),
                            abbreviation = cursor.getString(2),
                            explanation = cursor.getString(3),
                            derivation = cursor.getString(4),
                            example = cursor.getString(5)
                        )))
                    }
                }

                // 4. 查歇后语
                database.rawQuery(
                    "SELECT riddle, answer FROM ${DictionaryDb.T_XIEHOUYU} WHERE riddle LIKE ? LIMIT ?",
                    arrayOf("$q%", MAX_RESULTS.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        resultItems.add(DictItem.XiehouyuItem(XiehouyuEntry(
                            riddle = cursor.getString(0),
                            answer = cursor.getString(1)
                        )))
                    }
                }
            } else {
                // 拼音/缩写搜索
                val qLower = q.lowercase()

                // 1. 查成语（缩写或拼音前缀）
                database.rawQuery(
                    "SELECT word, pinyin, abbreviation, explanation, derivation, example FROM ${DictionaryDb.T_IDIOMS} WHERE abbreviation LIKE ? OR replace(pinyin, ' ', '') LIKE ? LIMIT ?",
                    arrayOf("$qLower%", "$qLower%", MAX_RESULTS.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        resultItems.add(DictItem.IdiomItem(IdiomEntry(
                            word = cursor.getString(0),
                            pinyin = cursor.getString(1),
                            abbreviation = cursor.getString(2),
                            explanation = cursor.getString(3),
                            derivation = cursor.getString(4),
                            example = cursor.getString(5)
                        )))
                    }
                }

                // 2. 查汉字（拼音前缀）
                database.rawQuery(
                    "SELECT word, old_word, strokes, pinyin, radicals, explanation, more FROM ${DictionaryDb.T_WORDS} WHERE replace(pinyin, ' ', '') LIKE ? LIMIT ?",
                    arrayOf("$qLower%", MAX_RESULTS.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        resultItems.add(DictItem.WordItem(WordEntry(
                            word = cursor.getString(0),
                            oldWord = cursor.getString(1),
                            strokes = cursor.getInt(2),
                            pinyin = cursor.getString(3),
                            radicals = cursor.getString(4),
                            explanation = cursor.getString(5),
                            more = cursor.getString(6)
                        )))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
        }

        // 根据长度和类型进行排序和权重调整
        val sorted = sortResultWithWeight(q.length, resultItems)
        return DictionaryResult(query = q, items = sorted)
    }

    private fun sortResultWithWeight(len: Int, items: List<DictItem>): List<DictItem> {
        val words = items.filterIsInstance<DictItem.WordItem>()
        val ci = items.filterIsInstance<DictItem.CiItem>()
        val idioms = items.filterIsInstance<DictItem.IdiomItem>()
        val xiehouyu = items.filterIsInstance<DictItem.XiehouyuItem>()

        val result = mutableListOf<DictItem>()
        if (len == 1) {
            result.addAll(words)
            result.addAll(ci)
            result.addAll(idioms)
            result.addAll(xiehouyu)
        } else if (len <= 3) {
            result.addAll(ci)
            result.addAll(idioms)
            result.addAll(xiehouyu)
            result.addAll(words)
        } else {
            result.addAll(idioms)
            result.addAll(xiehouyu)
            result.addAll(ci)
            result.addAll(words)
        }

        return result.distinctBy {
            when (it) {
                is DictItem.IdiomItem -> "idiom_${it.data.word}"
                is DictItem.WordItem -> "word_${it.data.word}"
                is DictItem.CiItem -> "ci_${it.data.ci}"
                is DictItem.XiehouyuItem -> "xie_${it.data.riddle}"
            }
        }.take(MAX_RESULTS)
    }
}

// ── 搜索结果数据模型 ──────────────────────────────────────────────────

sealed class DictItem {
    class IdiomItem(val data: IdiomEntry) : DictItem()
    class WordItem(val data: WordEntry) : DictItem()
    class CiItem(val data: CiEntry) : DictItem()
    class XiehouyuItem(val data: XiehouyuEntry) : DictItem()
}

data class DictionaryResult(
    val query: String,
    val items: List<DictItem>
) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val EMPTY = DictionaryResult("", emptyList())
    }
}

// ── 词条模型 ──────────────────────────────────────────────────────────

data class IdiomEntry(
    val word: String,
    val pinyin: String,
    val abbreviation: String,
    val explanation: String,
    val derivation: String,
    val example: String
)

data class WordEntry(
    val word: String,
    val oldWord: String,
    val strokes: Int,
    val pinyin: String,
    val radicals: String,
    val explanation: String,
    val more: String
)

data class CiEntry(
    val ci: String,
    val explanation: String
)

data class XiehouyuEntry(
    val riddle: String,
    val answer: String
)
