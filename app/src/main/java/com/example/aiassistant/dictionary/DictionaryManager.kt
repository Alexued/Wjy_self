package com.example.aiassistant.dictionary

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object DictionaryManager {

    private const val TAG = "Dictionary"
    private const val MAX_RESULTS = 10

    // ── 原始数据 ──────────────────────────────────────────────────
    @Volatile private var idioms = mutableListOf<IdiomEntry>()
    @Volatile private var words = mutableListOf<WordEntry>()
    @Volatile private var ciList = mutableListOf<CiEntry>()
    @Volatile private var xiehouyu = mutableListOf<XiehouyuEntry>()

    // ── 索引 ──────────────────────────────────────────────────────────
    private lateinit var ciCharIndex: HashMap<String, List<CiEntry>>
    private lateinit var idiomCharIndex: HashMap<String, List<IdiomEntry>>
    private lateinit var idiomPinyinIndex: HashMap<String, List<IdiomEntry>>
    private lateinit var idiomAbbrIndex: HashMap<String, List<IdiomEntry>>
    private lateinit var wordCharIndex: HashMap<String, List<WordEntry>>
    private lateinit var wordPinyinIndex: HashMap<String, List<WordEntry>>
    private lateinit var xiehouyuCharIndex: HashMap<String, List<XiehouyuEntry>>

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
        if (isLoaded || isLoading) return
        isLoading = true
        loadExecutor.execute {
            try {
                loadIdioms(context)
                loadWords(context)
                loadCi(context)
                loadXiehouyu(context)
                buildIndexes()
                isLoaded = true
                Log.i(TAG, "词典加载完成: 成语${idioms.size}, 汉字${words.size}, 词语${ciList.size}, 歇后语${xiehouyu.size}")
                synchronized(readyListeners) { readyListeners.forEach { it() } }
            } catch (e: Exception) {
                Log.e(TAG, "词典加载失败", e)
            } finally {
                isLoading = false
            }
        }
    }

    // ── 数据加载 ──────────────────────────────────────────────────

    private fun loadIdioms(context: Context) {
        context.assets.open("dictionary/idiom.json").bufferedReader().use { reader ->
            val arr = JSONArray(reader.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                idioms.add(IdiomEntry(
                    word = obj.optString("word", ""),
                    pinyin = obj.optString("pinyin", ""),
                    abbreviation = obj.optString("abbreviation", ""),
                    explanation = obj.optString("explanation", ""),
                    derivation = obj.optString("derivation", ""),
                    example = obj.optString("example", "")
                ))
            }
        }
        System.gc()
    }

    private fun loadWords(context: Context) {
        context.assets.open("dictionary/word.json").bufferedReader().use { reader ->
            val arr = JSONArray(reader.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                words.add(WordEntry(
                    word = obj.optString("word", ""),
                    oldWord = obj.optString("oldword", ""),
                    strokes = obj.optInt("strokes", 0),
                    pinyin = obj.optString("pinyin", ""),
                    radicals = obj.optString("radicals", ""),
                    explanation = obj.optString("explanation", ""),
                    more = obj.optString("more", "")
                ))
            }
        }
        System.gc()
    }

    private fun loadCi(context: Context) {
        context.assets.open("dictionary/ci.json").bufferedReader().use { reader ->
            val arr = JSONArray(reader.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                ciList.add(CiEntry(
                    ci = obj.optString("ci", ""),
                    explanation = obj.optString("explanation", "")
                ))
            }
        }
        System.gc()
    }

    private fun loadXiehouyu(context: Context) {
        context.assets.open("dictionary/xiehouyu.json").bufferedReader().use { reader ->
            val arr = JSONArray(reader.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                xiehouyu.add(XiehouyuEntry(
                    riddle = obj.optString("riddle", ""),
                    answer = obj.optString("answer", "")
                ))
            }
        }
    }

    // ── 构建索引 ──────────────────────────────────────────────────

    private fun buildIndexes() {
        // 词语索引：按首字分组
        ciCharIndex = ciList.groupBy { it.ci.first().toString() }
            .mapValues { (_, v) -> v.sortedBy { it.ci.length } }
            .toHashMap()

        // 成语索引：按首字分组
        idiomCharIndex = idioms.groupBy { it.word.first().toString() }
            .mapValues { (_, v) -> v.sortedBy { it.word.length } }
            .toHashMap()

        // 成语拼音索引：key = 去空格小写拼音（如 "ābídìyù"），用于前缀匹配
        idiomPinyinIndex = idioms.filter { it.pinyin.isNotBlank() }
            .groupBy { it.pinyin.replace(" ", "").lowercase() }
            .toHashMap()

        // 成语缩写索引：key = 小写缩写（如 "abdy"），用于前缀匹配
        idiomAbbrIndex = idioms.filter { it.abbreviation.isNotBlank() }
            .groupBy { it.abbreviation.lowercase() }
            .toHashMap()

        // 汉字索引：按字本身分组
        wordCharIndex = words.groupBy { it.word }
            .toHashMap()

        // 汉字拼音索引：key = 去空格小写拼音
        wordPinyinIndex = words.filter { it.pinyin.isNotBlank() }
            .groupBy { it.pinyin.replace(" ", "").lowercase() }
            .toHashMap()

        // 歇后语索引：按首字分组
        xiehouyuCharIndex = xiehouyu.groupBy { it.riddle.first().toString() }
            .mapValues { (_, v) -> v.sortedBy { it.riddle.length } }
            .toHashMap()

        Log.i(TAG, "索引构建完成")
    }

    private fun <K, V> Map<K, V>.toHashMap(): HashMap<K, V> = HashMap(this)

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
     * 核心搜索逻辑：基于索引的 O(1) 查找。
     */
    private fun searchInternal(q: String): DictionaryResult {
        val isChinese = q.first().code > 127

        if (isChinese) {
            return searchChinese(q)
        } else {
            return searchPinyin(q)
        }
    }

    /** 中文查询：按首字索引查找，再过滤前缀 */
    private fun searchChinese(q: String): DictionaryResult {
        val firstChar = q.first().toString()

        val ciResults = ciCharIndex[firstChar]
            ?.filter { it.ci.startsWith(q) }
            ?.take(MAX_RESULTS)
            ?: emptyList()

        val idiomResults = idiomCharIndex[firstChar]
            ?.filter { it.word.startsWith(q) }
            ?.take(MAX_RESULTS)
            ?: emptyList()

        val wordResults = wordCharIndex[firstChar]
            ?.filter { it.word.startsWith(q) }
            ?.take(MAX_RESULTS)
            ?: emptyList()

        val xieResults = xiehouyuCharIndex[firstChar]
            ?.filter { it.riddle.startsWith(q) }
            ?.take(MAX_RESULTS)
            ?: emptyList()

        val sorted = sortWithWeight(q.length, idiomResults, wordResults, ciResults, xieResults)
        return DictionaryResult(query = q, items = sorted)
    }

    /** 拼音查询：前缀扫描拼音/缩写索引 */
    private fun searchPinyin(q: String): DictionaryResult {
        val qLower = q.lowercase()

        // 成语：拼音前缀匹配（索引 key = 去空格小写拼音）
        val idiomByPinyin = idiomPinyinIndex.entries
            .filter { it.key.startsWith(qLower) }
            .flatMap { it.value }
            .take(MAX_RESULTS)

        // 成语：缩写前缀匹配（索引 key = 小写缩写）
        val idiomByAbbr = idiomAbbrIndex.entries
            .filter { it.key.startsWith(qLower) }
            .flatMap { it.value }
            .take(MAX_RESULTS)

        // 合并去重
        val allIdioms = (idiomByPinyin + idiomByAbbr).distinctBy { it.word }.take(MAX_RESULTS)

        // 汉字：拼音前缀匹配
        val wordByPinyin = wordPinyinIndex.entries
            .filter { it.key.startsWith(qLower) }
            .flatMap { it.value }
            .take(MAX_RESULTS)

        // 词语和歇后语没有拼音字段，拼音查询不参与
        val sorted = sortWithWeight(q.length, allIdioms, wordByPinyin, emptyList(), emptyList())
        return DictionaryResult(query = q, items = sorted)
    }

    private fun sortWithWeight(
        len: Int,
        idioms: List<IdiomEntry>,
        words: List<WordEntry>,
        ci: List<CiEntry>,
        xiehouyu: List<XiehouyuEntry>
    ): List<DictItem> {
        val result = mutableListOf<DictItem>()

        if (len == 1) {
            words.forEach { result.add(DictItem.WordItem(it)) }
            ci.forEach { result.add(DictItem.CiItem(it)) }
            idioms.forEach { result.add(DictItem.IdiomItem(it)) }
            xiehouyu.forEach { result.add(DictItem.XiehouyuItem(it)) }
        } else if (len <= 3) {
            ci.forEach { result.add(DictItem.CiItem(it)) }
            idioms.forEach { result.add(DictItem.IdiomItem(it)) }
            xiehouyu.forEach { result.add(DictItem.XiehouyuItem(it)) }
            words.forEach { result.add(DictItem.WordItem(it)) }
        } else {
            idioms.forEach { result.add(DictItem.IdiomItem(it)) }
            xiehouyu.forEach { result.add(DictItem.XiehouyuItem(it)) }
            ci.forEach { result.add(DictItem.CiItem(it)) }
            words.forEach { result.add(DictItem.WordItem(it)) }
        }

        return result.take(MAX_RESULTS)
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
